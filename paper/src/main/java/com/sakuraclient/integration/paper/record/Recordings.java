package com.sakuraclient.integration.paper.record;

import com.google.gson.JsonObject;
import com.sakuraclient.integration.record.Downloads;
import com.sakuraclient.integration.record.RecordingStore;
import com.sakuraclient.integration.wire.Wire;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Recording arenas on this server (docs/integration.md §9): the recordings running now, the I/O thread they write on,
 * where they're kept, and who they're offered to once they stop. A recording is offered only after it stops, never
 * while a match runs, and only to players whose game said it takes recordings.
 */
public final class Recordings {
    /** Offers sent to a player as they join, newest first. */
    private static final int OFFERS_ON_JOIN = 20;
    /** A recording stops by itself past this many bytes. */
    private static final long MAX_BYTES = 512L << 20;

    public static final String RECEIVE_PERMISSION = "sakura.recordings.receive";

    private final JavaPlugin plugin;
    private final Predicate<UUID> sakura;
    private final Map<String, ArenaRecorder> active = new ConcurrentHashMap<>();
    private final Map<UUID, Player> takers = new ConcurrentHashMap<>();
    private ExecutorService io;
    private RecordingStore store;
    private Downloads downloads;
    private BukkitTask sampler, sender;

    private volatile int keep = 50, keepDays = 14, maxMinutes = 60, radius = 8, resyncSeconds = 15, kibPerSecond = 512;
    private volatile String offerTo = "participants";

    /** Something to run on the recordings' I/O thread. */
    interface Io {
        void run() throws IOException;
    }

    public Recordings(JavaPlugin plugin, Predicate<UUID> sakura) {
        this.plugin = plugin;
        this.sakura = sakura;
    }

    // ── life ────────────────────────────────────────────────────────────

    public void enable() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Sakura recordings");
            t.setDaemon(true);
            return t;
        });
        readConfig(plugin.getConfig());
        Bukkit.getPluginManager().registerEvents(new RecordListener(this), plugin);
        if (RecordListener.Paper.available()) Bukkit.getPluginManager().registerEvents(new RecordListener.Paper(this), plugin);
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        sender = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                downloads.tick();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Couldn't send a recording", e);
            }
        }, 1L, 1L);
        // A recording a crash or a kill left as a folder: sealed with what it holds.
        for (String id : store.leftovers()) {
            io(() -> {
                RecordingStore.Info info = store.seal(id, store.recover(id));
                plugin.getLogger().info("Saved recording " + info.id + " that the last run didn't finish (" + kb(info.size) + ")");
            });
        }
    }

    /** Every recording running is stopped and saved before the server goes. */
    public void disable() {
        if (sampler != null) sampler.cancel();
        if (sender != null) sender.cancel();
        for (ArenaRecorder r : new ArrayList<>(active.values())) stop(r, null);
        io.shutdown();
        try {
            if (!io.awaitTermination(60, TimeUnit.SECONDS)) plugin.getLogger().warning("Recordings were still being saved as the server stopped; they're sealed at the next start");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void readConfig(FileConfiguration c) {
        String folder = c.getString("recordings.folder", "recordings");
        Path p = Paths.get(folder);
        if (!p.isAbsolute()) p = plugin.getDataFolder().toPath().resolve(folder);
        Path dir = p.normalize();
        if (store == null || !store.folder().equals(dir)) {
            store = new RecordingStore(dir);
            downloads = new Downloads(store, this::send, kibPerSecond);
        }
        keep = Math.max(0, c.getInt("recordings.keep", 50));
        keepDays = Math.max(0, c.getInt("recordings.keep-days", 14));
        maxMinutes = Math.max(1, c.getInt("recordings.max-minutes", 60));
        radius = Math.max(0, Math.min(32, c.getInt("recordings.radius", 8)));
        resyncSeconds = Math.max(1, c.getInt("recordings.resync-seconds", 15));
        kibPerSecond = Math.max(16, c.getInt("recordings.download-kib-per-second", 512));
        String to = c.getString("recordings.offer-to", "participants").toLowerCase(Locale.ROOT);
        offerTo = to.equals("everyone") || to.equals("nobody") ? to : "participants";
        downloads.rate(kibPerSecond);
    }

    int resyncSeconds() {
        return resyncSeconds;
    }

    public int radius() {
        return radius;
    }

    String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    boolean isSakura(UUID u) {
        return sakura.test(u);
    }

    void io(Io task) {
        io.execute(() -> {
            try {
                task.run();
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "A recording couldn't be written", e);
            }
        });
    }

    // ── starting and stopping ───────────────────────────────────────────

    /** A recording of every chunk from {@code (minCX, minCZ)} to {@code (maxCX, maxCZ)} in {@code world}; its id. */
    public String start(World world, int minCX, int minCZ, int maxCX, int maxCZ, String label) throws IOException {
        if (maxCX < minCX || maxCZ < minCZ) throw new IllegalArgumentException("the box is empty");
        if ((long) (maxCX - minCX + 1) * (maxCZ - minCZ + 1) > 65L * 65) throw new IllegalArgumentException("the box is over 65 × 65 chunks");
        long now = System.currentTimeMillis();
        String id = store.newId(now);
        String name = label == null || label.trim().isEmpty() ? world.getName() : label.trim();
        if (name.length() > 64) name = name.substring(0, 64);
        java.nio.file.Files.createDirectories(store.folder());
        ArenaRecorder r = new ArenaRecorder(this, id, name, world, minCX, minCZ, maxCX, maxCZ, store.part(id));
        r.open();
        active.put(id, r);
        plugin.getLogger().info("Recording " + name + " (" + id + "): " + world.getName() + ", chunks " + minCX + "," + minCZ + " to " + maxCX + "," + maxCZ);
        return id;
    }

    public Collection<String> running() {
        return Collections.unmodifiableCollection(new ArrayList<>(active.keySet()));
    }

    /** Stops a recording and saves it; {@code saved} hears its file on the server's thread. False if no such one runs. */
    public boolean stop(String id, Consumer<RecordingStore.Info> saved) {
        ArenaRecorder r = active.get(id);
        if (r == null) return false;
        stop(r, saved);
        return true;
    }

    private void stop(ArenaRecorder r, Consumer<RecordingStore.Info> saved) {
        active.remove(r.id);
        JsonObject meta = r.stop();
        Map<UUID, String> players = new LinkedHashMap<>(r.participants);
        plugin.getLogger().info(String.format(Locale.ROOT, "Stopped recording %s (%s): %s, %d players, %s, %.2f ms a tick on average, %.2f at most (tick %d), %.2f at most after the first second (%s)",
                r.label, r.id, duration(r.elapsedMs()), players.size(), kb(r.bytes()), r.meanMs(), r.maxMs(), r.maxTick(), r.maxSettledMs(), r.phases()));
        io(() -> {
            RecordingStore.Info info = store.seal(r.id, meta);
            int pruned = store.prune(keep, keepDays, System.currentTimeMillis());
            plugin.getLogger().info("Saved recording " + info.id + " (" + kb(info.size) + ", sha256 " + info.sha256.substring(0, 12) + "…)"
                    + (pruned > 0 ? "; " + pruned + " old ones removed" : ""));
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int offered = offerAll(info);
                    if (saved != null) saved.accept(info);
                    if (offered > 0) plugin.getLogger().info("Offered recording " + info.id + " to " + offered + " players");
                });
            }
        });
    }

    public void mark(String text, Location at) {
        for (ArenaRecorder r : active.values()) if (r.world == at.getWorld()) r.marker(text, at);
    }

    private void tick() {
        if (active.isEmpty()) return;
        for (ArenaRecorder r : new ArrayList<>(active.values())) {
            try {
                r.sample();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Recording " + r.id + " failed and stops here", e);
                stop(r, null);
                continue;
            }
            if (r.elapsedMs() > maxMinutes * 60_000L) {
                plugin.getLogger().info("Recording " + r.id + " reached its " + maxMinutes + " minutes");
                stop(r, null);
            } else if (r.bytes() > MAX_BYTES) {
                plugin.getLogger().info("Recording " + r.id + " reached " + kb(MAX_BYTES));
                stop(r, null);
            }
        }
    }

    /** One line for each recording running now. */
    public List<String> describeRunning() {
        List<String> out = new ArrayList<>();
        for (ArenaRecorder r : active.values()) {
            out.add(String.format(Locale.ROOT, "%s (%s): %s, %d chunks, %d players, %s, %.2f ms a tick on average, %.2f at most (%s)",
                    r.label, r.id, duration(r.elapsedMs()), r.chunks(), r.participants.size(), kb(r.bytes()), r.meanMs(), r.maxMs(), r.phases()));
        }
        return out;
    }

    public List<RecordingStore.Info> kept() {
        return store.list();
    }

    // ── events, for each recording of that world ────────────────────────

    void each(World w, Consumer<ArenaRecorder> f) {
        if (active.isEmpty() || w == null) return;
        for (ArenaRecorder r : active.values()) if (r.world == w) f.accept(r);
    }

    void eachBlock(World w, int x, int y, int z, Consumer<ArenaRecorder> f) {
        if (active.isEmpty() || w == null) return;
        for (ArenaRecorder r : active.values()) if (r.inBox(w, x, z)) f.accept(r);
    }

    void eachEntity(Entity e, Consumer<ArenaRecorder> f) {
        if (active.isEmpty()) return;
        each(e.getWorld(), f);
    }

    /** Any thread: a line every player of {@code w} saw ({@code w} null: every world's). */
    void chat(World w, String text, UUID sender, int kind) {
        // a recording that has just stopped takes a line it never writes
        for (ArenaRecorder r : active.values()) if (w == null || r.world == w) r.chat(text, sender, kind);
    }

    // ── offering and sending ────────────────────────────────────────────

    /** A player's game said what it takes; one that takes recordings is offered the ones it may have. */
    public void hello(Player p, List<String> features) {
        if (!features.contains(Wire.FEATURE_RECORDINGS)) return;
        takers.put(p.getUniqueId(), p);
        if (!p.hasPermission(RECEIVE_PERMISSION) || "nobody".equals(offerTo)) return;
        UUID u = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int n = 0;
            for (RecordingStore.Info info : store.list()) {
                if (n >= OFFERS_ON_JOIN) break;
                if ("everyone".equals(offerTo) || info.players.containsKey(u)) {
                    downloads.offer(u, info);
                    n++;
                }
            }
        });
    }

    /** A sealed recording to everyone who may take it and is here. */
    private int offerAll(RecordingStore.Info info) {
        if ("nobody".equals(offerTo)) return 0;
        int n = 0;
        for (Player p : takers.values()) {
            if (!p.isOnline() || !p.hasPermission(RECEIVE_PERMISSION)) continue;
            if ("everyone".equals(offerTo) || info.players.containsKey(p.getUniqueId())) {
                downloads.offer(p.getUniqueId(), info);
                n++;
            }
        }
        return n;
    }

    public void get(Player p, Wire.RecordingGet g) {
        downloads.get(p.getUniqueId(), g);
    }

    public void quit(UUID u) {
        takers.remove(u);
        downloads.left(u);
    }

    private boolean send(UUID u, byte[] message) {
        Player p = takers.get(u);
        if (p == null && Bukkit.isPrimaryThread()) p = Bukkit.getPlayer(u);
        if (p == null || !p.isOnline()) return false;
        p.sendPluginMessage(plugin, Wire.CHANNEL, message);
        return true;
    }

    // ── words ───────────────────────────────────────────────────────────

    static String kb(long bytes) {
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%d KB", (bytes + 1023) / 1024);
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        return s < 60 ? s + " s" : String.format(Locale.ROOT, "%d min %d s", s / 60, s % 60);
    }
}
