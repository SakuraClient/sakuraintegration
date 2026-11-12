package com.sakuraclient.integration.paper.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sakuraclient.integration.record.RecordingStore;
import com.sakuraclient.integration.record.Records;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * One recording of an arena (docs/integration.md §9.1): a box of chunks in one world, every player and entity in it
 * and every block. Once a tick, at the start of the server's tick, it reads where everything is ({@link #sample}) and
 * writes what changed; the events between ticks write what only events say (a swing, a hurt, a death, a block a
 * player broke, chat) and mark the blocks that may have changed. Changes no event tells of (commands, other plugins)
 * are found by comparing each chunk with what was written, a few chunks a tick ({@link #resync}). The bytes go to the
 * recording's folder on its I/O thread. The server's thread only, apart from {@link #chat}.
 */
final class ArenaRecorder {
    /** Chunks are taken for the map until this much of a tick is spent (one at least), as a recording starts. */
    private static final long TAKE_NANOS = 1_500_000;
    /** An item's exact bytes beyond this aren't kept (a book, a full shulker box): its kind, count and glint are. */
    private static final int MAX_EXACT = 4096;

    final Recordings owner;
    final String id;
    final String label;
    final World world;
    final int minCX, minCZ, maxCX, maxCZ;
    final long startedAt;
    private final long startNanos;
    final Path dir;
    final int minY, maxY, sections;

    private final Records.Buffer out = new Records.Buffer();
    private final Records.Palette strings = new Records.Palette();
    private final Map<BlockData, Integer> stateIds = new HashMap<>();
    private OutputStream timeline;
    private MapEncoder map;

    private int tick = -1;
    private long lastMs;
    private long gameLast = -1, dayLast;
    private long bytes;
    private long nanosTotal, nanosMax;
    /** The tick that took longest, and the longest after the first second (the map's first chunks and a cold JVM). */
    private int maxTick;
    private long nanosMaxSettled;
    private int samples;
    /** Where the time goes: taking chunks, the tab list, finding entities, moves, states, equipment, marked blocks, the comparison. */
    private final long[] phases = new long[8];
    private boolean stopped;

    private final Location centre;
    private final double hx, hy, hz;
    private final Location tmp;

    // ── players and entities ────────────────────────────────────────────

    private static final class Listed {
        int mode;

        Listed(int mode) {
            this.mode = mode;
        }
    }

    private static final class Tracked {
        final int id;
        long x, y, z;
        int yaw, pitch, head;
        boolean ground;
        int flags = -1, pose = -1, living = -1;
        float health = Float.NaN;
        final ItemStack[] equip = new ItemStack[Records.SLOTS];
        boolean dead;
        /** The tick it died, for a mob's death to play out before its body goes. */
        int deadAt = -1;
        int seen;
        final int since;

        Tracked(int id, int since) {
            this.id = id;
            this.since = since;
        }
    }

    private final Map<UUID, Listed> listed = new LinkedHashMap<>();
    /** Everyone who was in the recording: uuid to name. */
    final Map<UUID, String> participants = new LinkedHashMap<>();
    final Set<UUID> sakuraParticipants = new HashSet<>();
    private final Map<Integer, Tracked> tracked = new HashMap<>();
    /**
     * The entities that may be in the box: its players, what spawned in it ({@link #added}), and what a sweep found
     * there (once a second, for whatever walked in). Each is read every tick; one that left the world goes.
     */
    private final Map<Integer, Entity> watched = new LinkedHashMap<>();
    /** How often the box is swept for entities no event announced, in ticks. */
    private static final int SWEEP_TICKS = 20;
    /** How long a mob's body stays after it died: the game's death animation (LivingEntity.tickDeath). */
    private static final int DEATH_TICKS = 20;

    // a tick's moves, written as one record
    private int moves;
    private int[] moveIds = new int[64], moveMask = new int[64], moveYaw = new int[64], movePitch = new int[64], moveHead = new int[64];
    private long[] moveDx = new long[64], moveDy = new long[64], moveDz = new long[64];

    // ── blocks ──────────────────────────────────────────────────────────

    private static final class Override {
        final BlockData data;
        final int tick;

        Override(BlockData data, int tick) {
            this.data = data;
            this.tick = tick;
        }
    }

    private static final class Resync {
        final long chunk;
        final ChunkSnapshot now;
        final int tick;
        final List<MapEncoder.Diff> diffs;

        Resync(long chunk, ChunkSnapshot now, int tick, List<MapEncoder.Diff> diffs) {
            this.chunk = chunk;
            this.now = now;
            this.tick = tick;
            this.diffs = diffs;
        }
    }

    /** Each chunk as last taken: with {@link #overrides}, what the recording says its blocks are. */
    private final Map<Long, ChunkSnapshot> snaps = new HashMap<>();
    /** Blocks written since their chunk was taken, by chunk. */
    private final Map<Long, Map<Long, Override>> overrides = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();
    private final ArrayDeque<Long> toTake = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final List<Long> resyncOrder = new ArrayList<>();
    private int resyncCursor;
    private final Set<Long> resyncing = new HashSet<>();
    private final ConcurrentLinkedQueue<Resync> resynced = new ConcurrentLinkedQueue<>();

    // ── from other threads ──────────────────────────────────────────────

    private static final class Chat {
        final String text;
        final UUID sender;
        final int kind;

        Chat(String text, UUID sender, int kind) {
            this.text = text;
            this.sender = sender;
            this.kind = kind;
        }
    }

    private final ConcurrentLinkedQueue<Chat> chats = new ConcurrentLinkedQueue<>();

    ArenaRecorder(Recordings owner, String id, String label, World world, int minCX, int minCZ, int maxCX, int maxCZ, Path dir) {
        this.owner = owner;
        this.id = id;
        this.label = label;
        this.world = world;
        this.minCX = minCX;
        this.minCZ = minCZ;
        this.maxCX = maxCX;
        this.maxCZ = maxCZ;
        this.dir = dir;
        this.startedAt = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
        this.minY = Versions.minHeight(world);
        this.maxY = world.getMaxHeight();
        this.sections = Math.max(1, (maxY - minY) >> 4);
        double x0 = minCX << 4, x1 = (maxCX + 1) << 4, z0 = minCZ << 4, z1 = (maxCZ + 1) << 4;
        this.centre = new Location(world, (x0 + x1) / 2, (minY + maxY) / 2.0, (z0 + z1) / 2);
        this.hx = (x1 - x0) / 2;
        this.hz = (z1 - z0) / 2;
        this.hy = (maxY - minY) / 2.0 + 64;
        this.tmp = new Location(world, 0, 0, 0);
    }

    /** Opens the folder and queues the box's loaded chunks, nearest the middle first. Then {@link #sample} each tick. */
    void open() throws IOException {
        Files.createDirectories(dir);
        RecordingStore.writeMeta(dir, meta(false));
        timeline = RecordingStore.append(dir.resolve(RecordingStore.TIMELINE));
        map = new MapEncoder(RecordingStore.append(dir.resolve(RecordingStore.MAP)), minY, sections, world.getEnvironment() == World.Environment.NORMAL);
        List<long[]> loaded = new ArrayList<>();
        int mx = (minCX + maxCX) / 2, mz = (minCZ + maxCZ) / 2;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (world.isChunkLoaded(cx, cz)) loaded.add(new long[]{cx, cz, (long) (cx - mx) * (cx - mx) + (long) (cz - mz) * (cz - mz)});
            }
        }
        loaded.sort((a, b) -> Long.compare(a[2], b[2]));
        for (long[] c : loaded) take((int) c[0], (int) c[1]);
    }

    boolean inBox(double x, double z) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        return cx >= minCX && cx <= maxCX && cz >= minCZ && cz <= maxCZ;
    }

    boolean inBox(World w, int x, int z) {
        return w == world && (x >> 4) >= minCX && (x >> 4) <= maxCX && (z >> 4) >= minCZ && (z >> 4) <= maxCZ;
    }

    int chunks() {
        return snaps.size();
    }

    long bytes() {
        return bytes + out.size();
    }

    long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    double meanMs() {
        return samples == 0 ? 0 : nanosTotal / 1e6 / samples;
    }

    double maxMs() {
        return nanosMax / 1e6;
    }

    int maxTick() {
        return maxTick;
    }

    /** The longest tick after the first second. */
    double maxSettledMs() {
        return nanosMaxSettled / 1e6;
    }

    int ticks() {
        return tick + 1;
    }

    /** The mean time a tick of each part, for {@code /sakura record list} and the log. */
    String phases() {
        if (samples == 0) return "";
        String[] names = {"map", "players", "finding", "moves", "states", "equipment", "blocks", "resync"};
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (b.length() > 0) b.append(", ");
            b.append(names[i]).append(' ').append(String.format(java.util.Locale.ROOT, "%.3f", phases[i] / 1e6 / samples));
        }
        return b.toString();
    }

    // ── each tick ───────────────────────────────────────────────────────

    void sample() {
        if (stopped) return;
        long t0 = System.nanoTime();
        tick++;
        long ms = (t0 - startNanos) / 1_000_000L;
        long game = Versions.gameTime(world), day = world.getFullTime();
        long gameDelta = game >= 0 && gameLast >= 0 ? Math.max(0, game - gameLast) : 0;
        out.begin(Records.TICK).varInt((int) Math.max(0, ms - lastMs)).varInt((int) Math.min(Integer.MAX_VALUE, gameDelta)).zigzag(tick == 0 ? 0 : day - dayLast).end();
        lastMs = ms;
        gameLast = game;
        dayLast = day;
        if (tick % 20 == 0) out.begin(Records.CLOCK).varLong(game).varLong(day).u8(world.isThundering() ? 2 : world.hasStorm() ? 1 : 0).end();
        for (Chat c; (c = chats.poll()) != null; ) out.begin(Records.CHAT).string(c.text).uuid(c.sender).u8(c.kind).end();
        long t1 = System.nanoTime();
        takeSome();
        long t2 = System.nanoTime();
        players();
        long t3 = System.nanoTime();
        entities();
        long t4 = System.nanoTime();
        blocks();
        long t5 = System.nanoTime();
        resync();
        long t6 = System.nanoTime();
        phases[0] += t2 - t1;
        phases[1] += t3 - t2;
        phases[6] += t5 - t4;
        phases[7] += t6 - t5;
        flush();
        long dt = System.nanoTime() - t0;
        nanosTotal += dt;
        if (dt > nanosMax) {
            nanosMax = dt;
            maxTick = tick;
        }
        if (tick >= 20) nanosMaxSettled = Math.max(nanosMaxSettled, dt);
        samples++;
    }

    private void flush() {
        if (out.size() == 0) return;
        byte[] b = out.take();
        bytes += b.length;
        OutputStream o = timeline;
        owner.io(() -> o.write(b));
    }

    // ── the tab list ────────────────────────────────────────────────────

    private void players() {
        Set<UUID> here = new HashSet<>();
        for (Player p : world.getPlayers()) {
            UUID u = p.getUniqueId();
            here.add(u);
            int mode = mode(p.getGameMode());
            Listed l = listed.get(u);
            if (l == null) {
                String[] textures = Versions.textures(p);
                boolean sakura = owner.isSakura(u);
                out.begin(Records.PLAYER).uuid(u).string(p.getName()).string(textures[0]).string(textures[1]).u8(mode).u8(sakura ? 1 : 0).end();
                listed.put(u, new Listed(mode));
                participants.put(u, p.getName());
                if (sakura) sakuraParticipants.add(u);
            } else if (l.mode != mode) {
                out.begin(Records.GAME_MODE).uuid(u).u8(mode).end();
                l.mode = mode;
            }
        }
        for (Iterator<Map.Entry<UUID, Listed>> it = listed.entrySet().iterator(); it.hasNext(); ) {
            UUID u = it.next().getKey();
            if (here.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) {
                Tracked t = tracked.remove(p.getEntityId());
                if (t != null) out.begin(Records.REMOVE).varInt(t.id).end();
            }
            out.begin(Records.PLAYER_GONE).uuid(u).end();
            it.remove();
        }
    }

    private static int mode(GameMode m) {
        switch (m.name()) {
            case "CREATIVE": return 1;
            case "ADVENTURE": return 2;
            case "SPECTATOR": return 3;
            default: return 0;
        }
    }

    // ── entities ────────────────────────────────────────────────────────

    /** An entity that spawned or came into the world: watched from this tick if it's in the box. */
    void added(Entity e) {
        if (e.getWorld() != world) return;
        Location l = e.getLocation(tmp);
        if (inBox(l.getX(), l.getZ())) watched.put(e.getEntityId(), e);
    }

    private void entities() {
        long t0 = System.nanoTime();
        for (Player p : world.getPlayers()) watched.putIfAbsent(p.getEntityId(), p);
        if (tick % SWEEP_TICKS == 0) {
            Collection<Entity> near = world.getNearbyEntities(centre, hx, hy, hz);
            for (Entity e : near) watched.putIfAbsent(e.getEntityId(), e);
        }
        phases[2] += System.nanoTime() - t0;
        long tState = 0, tEquip = 0;
        long t1 = System.nanoTime();
        moves = 0;
        for (Iterator<Entity> it = watched.values().iterator(); it.hasNext(); ) {
            Entity e = it.next();
            boolean player = e instanceof Player;
            // gone from the world: a player who left or changed worlds, a mob that despawned, an item picked up
            if (player ? !((Player) e).isOnline() || e.getWorld() != world : !e.isValid() || e.getWorld() != world) {
                Tracked dying = player ? null : tracked.get(e.getEntityId());
                if (dying != null && dying.dead && e.getWorld() == world && tick - dying.deadAt < DEATH_TICKS) {
                    dying.seen = tick; // a mob that died stays for its fall, as a server keeps it
                    continue;
                }
                it.remove();
                continue;
            }
            Location l = e.getLocation(tmp);
            if (!inBox(l.getX(), l.getZ())) {
                if (!player) it.remove(); // a sweep finds it again if it comes back
                continue;
            }
            int id = e.getEntityId();
            Tracked t = tracked.get(id);
            if (e instanceof Player) {
                Player p = (Player) e;
                if (!listed.containsKey(p.getUniqueId())) continue; // the tab list first, as a server pairs a player
                if (t != null && t.dead && !p.isDead()) {
                    // back from the dead: a new body, as a server shows a player who respawned
                    out.begin(Records.REMOVE).varInt(t.id).end();
                    tracked.remove(id);
                    t = null;
                }
            }
            if (t == null) {
                String type = Versions.typeKey(e);
                if (type == null) continue;
                t = spawn(e, l, type);
                tracked.put(id, t);
            } else if (!e.isDead()) {
                move(e, l, t);
            }
            t.seen = tick;
            long s0 = System.nanoTime();
            if (!t.dead) state(e, t, false);
            long s1 = System.nanoTime();
            if (e instanceof LivingEntity && (tick - t.since) % 4 == 0) equipment((LivingEntity) e, t, false);
            tState += s1 - s0;
            tEquip += System.nanoTime() - s1;
        }
        if (moves > 0) {
            out.begin(Records.MOVE).varInt(moves);
            for (int i = 0; i < moves; i++) {
                int m = moveMask[i];
                out.varInt(moveIds[i]).u8(m);
                if ((m & 1) != 0) out.zigzag(moveDx[i]).zigzag(moveDy[i]).zigzag(moveDz[i]);
                if ((m & 2) != 0) out.u8(moveYaw[i]).u8(movePitch[i]);
                if ((m & 4) != 0) out.u8(moveHead[i]);
            }
            out.end();
        }
        for (Iterator<Tracked> it = tracked.values().iterator(); it.hasNext(); ) {
            Tracked t = it.next();
            if (t.seen == tick) continue;
            out.begin(Records.REMOVE).varInt(t.id).end();
            it.remove();
        }
        phases[3] += System.nanoTime() - t1 - tState - tEquip;
        phases[4] += tState;
        phases[5] += tEquip;
    }

    private static long fix(double v) {
        return Math.round(v * 4096.0);
    }

    private static int pack(float degrees) {
        return (int) Math.floor(degrees * 256.0F / 360.0F) & 0xFF;
    }

    private static int velocity(double v) {
        return (int) Math.max(-32768, Math.min(32767, Math.round(v * 8000.0)));
    }

    private Tracked spawn(Entity e, Location l, String type) {
        int typeIdx = strings.of(type, out);
        int data = 0, block = -1;
        Records.Item item = null;
        if (e instanceof Item) {
            item = item(((Item) e).getItemStack());
            data = 1;
        } else if (e instanceof FallingBlock) {
            block = stateId(((FallingBlock) e).getBlockData());
            data = 2;
        }
        Tracked t = new Tracked(e.getEntityId(), tick);
        t.x = fix(l.getX());
        t.y = fix(l.getY());
        t.z = fix(l.getZ());
        t.yaw = pack(l.getYaw());
        t.pitch = pack(l.getPitch());
        t.head = t.yaw;
        t.ground = e.isOnGround();
        Vector v = e.getVelocity();
        out.begin(Records.SPAWN).varInt(t.id).uuid(e.getUniqueId()).varInt(typeIdx).i64(t.x).i64(t.y).i64(t.z)
                .u8(t.yaw).u8(t.pitch).u8(t.head).i16(velocity(v.getX())).i16(velocity(v.getY())).i16(velocity(v.getZ())).u8(data);
        if (data == 1) Records.writeItem(out, item);
        else if (data == 2) out.varInt(block);
        out.end();
        state(e, t, true);
        if (e instanceof LivingEntity) equipment((LivingEntity) e, t, true);
        if (e.isDead()) t.dead = true;
        return t;
    }

    private void move(Entity e, Location l, Tracked t) {
        long x = fix(l.getX()), y = fix(l.getY()), z = fix(l.getZ());
        int yaw = pack(l.getYaw()), pitch = pack(l.getPitch()), head = yaw;
        boolean ground = e.isOnGround();
        int mask = 0;
        if (x != t.x || y != t.y || z != t.z) mask |= 1;
        if (yaw != t.yaw || pitch != t.pitch) mask |= 2;
        if (head != t.head) mask |= 4;
        if (mask == 0 && ground == t.ground) return;
        if (moves == moveIds.length) {
            int n = moves * 2;
            moveIds = java.util.Arrays.copyOf(moveIds, n);
            moveMask = java.util.Arrays.copyOf(moveMask, n);
            moveYaw = java.util.Arrays.copyOf(moveYaw, n);
            movePitch = java.util.Arrays.copyOf(movePitch, n);
            moveHead = java.util.Arrays.copyOf(moveHead, n);
            moveDx = java.util.Arrays.copyOf(moveDx, n);
            moveDy = java.util.Arrays.copyOf(moveDy, n);
            moveDz = java.util.Arrays.copyOf(moveDz, n);
        }
        int i = moves++;
        moveIds[i] = t.id;
        moveMask[i] = mask | (ground ? 8 : 0);
        moveDx[i] = x - t.x;
        moveDy[i] = y - t.y;
        moveDz[i] = z - t.z;
        moveYaw[i] = yaw;
        movePitch[i] = pitch;
        moveHead[i] = head;
        t.x = x;
        t.y = y;
        t.z = z;
        t.yaw = yaw;
        t.pitch = pitch;
        t.head = head;
        t.ground = ground;
    }

    private void state(Entity e, Tracked t, boolean force) {
        int flags = 0;
        if (e.getFireTicks() > 0) flags |= 1;
        LivingEntity le = e instanceof LivingEntity ? (LivingEntity) e : null;
        if (e instanceof Player) {
            Player p = (Player) e;
            if (p.isSneaking()) flags |= 2;
            if (p.isSprinting()) flags |= 8;
        }
        if (le != null) {
            if (le.isSwimming()) flags |= 16;
            if (Versions.invisible(le)) flags |= 32;
            if (le.isGliding()) flags |= 128;
        }
        if (e.isGlowing()) flags |= 64;
        int pose = strings.of(Versions.pose(e), out);
        int living = 0;
        if (e instanceof HumanEntity && ((HumanEntity) e).isHandRaised()) living |= 1;
        if (le != null && le.isRiptiding()) living |= 4;
        float health = le != null ? (float) le.getHealth() : Float.NaN;
        boolean sameHealth = Float.isNaN(health) ? Float.isNaN(t.health) : health == t.health;
        if (!force && flags == t.flags && pose == t.pose && living == t.living && sameHealth) return;
        t.flags = flags;
        t.pose = pose;
        t.living = living;
        t.health = health;
        out.begin(Records.STATE).varInt(t.id).u8(flags).varInt(pose).u8(living).f32(health).end();
    }

    private void equipment(LivingEntity e, Tracked t, boolean force) {
        EntityEquipment eq = e.getEquipment();
        if (eq == null) return;
        for (int slot = 0; slot < Records.SLOTS; slot++) {
            ItemStack now;
            try {
                now = slot(eq, slot);
            } catch (RuntimeException ex) {
                continue;
            }
            if (empty(now)) now = null;
            ItemStack was = t.equip[slot];
            if (!force && (now == null ? was == null : now.equals(was))) continue;
            if (force && now == null && was == null) continue;
            Records.Item item = now == null ? Records.Item.EMPTY : item(now);
            out.begin(Records.EQUIP).varInt(t.id).u8(slot);
            Records.writeItem(out, item);
            out.end();
            t.equip[slot] = now == null ? null : now.clone();
        }
    }

    private static ItemStack slot(EntityEquipment eq, int slot) {
        switch (slot) {
            case 0: return eq.getItemInMainHand();
            case 1: return eq.getItemInOffHand();
            case 2: return eq.getBoots();
            case 3: return eq.getLeggings();
            case 4: return eq.getChestplate();
            default: return eq.getHelmet();
        }
    }

    private static boolean empty(ItemStack s) {
        return s == null || s.getType() == Material.AIR || s.getAmount() <= 0;
    }

    /** An item's strings resolved and its exact bytes read, ready to go inside a record. */
    private Records.Item item(ItemStack s) {
        if (empty(s)) return Records.Item.EMPTY;
        int key = strings.of(s.getType().getKey().toString(), out);
        boolean glint = false;
        int colour = -1;
        if (s.hasItemMeta()) {
            ItemMeta m = s.getItemMeta();
            if (m != null) {
                glint = m.hasEnchants() || (m instanceof EnchantmentStorageMeta && ((EnchantmentStorageMeta) m).hasStoredEnchants());
                if (m instanceof LeatherArmorMeta) colour = ((LeatherArmorMeta) m).getColor().asRGB();
            }
        }
        byte[] exact = Versions.exact(s);
        if (exact != null && exact.length > MAX_EXACT) exact = null;
        return new Records.Item(key, s.getAmount(), glint, colour, exact);
    }

    // ── what events say ─────────────────────────────────────────────────

    void swing(Entity e, int hand) {
        Tracked t = tracked.get(e.getEntityId());
        if (t != null) out.begin(Records.SWING).varInt(t.id).u8(hand).end();
    }

    void hurt(Entity e, String cause, Entity attacker) {
        Tracked t = tracked.get(e.getEntityId());
        if (t == null) return;
        int c = strings.of(cause, out);
        out.begin(Records.HURT).varInt(t.id).varInt(c).varInt(attacker != null && tracked.containsKey(attacker.getEntityId()) ? attacker.getEntityId() + 1 : 0).end();
    }

    void death(Entity e, Entity killer, String message) {
        Tracked t = tracked.get(e.getEntityId());
        if (t == null) return;
        out.begin(Records.DEATH).varInt(t.id).varInt(killer != null && tracked.containsKey(killer.getEntityId()) ? killer.getEntityId() + 1 : 0)
                .string(message == null ? "" : message).end();
        t.dead = true;
        t.deadAt = tick;
        if (e instanceof LivingEntity) {
            t.health = 0;
        }
    }

    /** A player broke a block (before it changed): its particles and sound, and the block marked. */
    void broke(int x, int y, int z, BlockData was, Entity breaker) {
        if (!taken(x, z)) return;
        int s = stateId(was);
        out.begin(Records.BREAK).zigzag(x).zigzag(y).zigzag(z).varInt(s)
                .varInt(breaker != null && tracked.containsKey(breaker.getEntityId()) ? breaker.getEntityId() + 1 : 0).end();
        mark(x, y, z);
    }

    void explode(double x, double y, double z, float power) {
        if (!inBox(x, z)) return;
        out.begin(Records.EXPLODE).f64(x).f64(y).f64(z).f32(power).end();
    }

    void marker(String text, Location at) {
        out.begin(Records.MARKER).string(text).f64(at.getX()).f64(at.getY()).f64(at.getZ()).end();
    }

    /** Any thread: a line of chat, written at the next tick. */
    void chat(String text, UUID sender, int kind) {
        chats.add(new Chat(text, sender, kind));
    }

    /** A block that may have changed: read at the next tick and written if it did. */
    void mark(int x, int y, int z) {
        if (y < minY || y >= maxY || !taken(x, z)) return;
        dirty.add(blockKey(x, y, z));
    }

    /** A chunk that loaded inside the box: taken for the map if it hasn't been. */
    void chunkLoaded(int cx, int cz) {
        if (cx < minCX || cx > maxCX || cz < minCZ || cz > maxCZ) return;
        take(cx, cz);
    }

    private boolean taken(int x, int z) {
        return snaps.containsKey(chunkKey(x >> 4, z >> 4));
    }

    // ── blocks ──────────────────────────────────────────────────────────

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static long blockKey(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private static int bx(long k) {
        return (int) (k >> 38);
    }

    private static int bz(long k) {
        return (int) (k << 26 >> 38);
    }

    private static int by(long k) {
        return (int) (k << 52 >> 52);
    }

    private void take(int cx, int cz) {
        long ck = chunkKey(cx, cz);
        if (snaps.containsKey(ck) || !queued.add(ck)) return;
        toTake.add(ck);
    }

    /** A few of the chunks waiting to be taken for the map. */
    private void takeSome() {
        long until = System.nanoTime() + TAKE_NANOS;
        for (int n = 0; !toTake.isEmpty() && (n == 0 || System.nanoTime() < until); ) {
            long ck = toTake.poll();
            queued.remove(ck);
            int cx = (int) (ck >> 32), cz = (int) ck;
            if (snaps.containsKey(ck) || !world.isChunkLoaded(cx, cz)) continue;
            ChunkSnapshot s = world.getChunkAt(cx, cz).getChunkSnapshot(false, true, false);
            snaps.put(ck, s);
            resyncOrder.add(ck);
            int at = tick;
            MapEncoder m = map;
            owner.io(() -> m.chunk(s, at));
            n++;
        }
    }

    private int stateId(BlockData d) {
        Integer i = stateIds.get(d);
        if (i != null) return i;
        int n = strings.of(d.getAsString(), out);
        stateIds.put(d, n);
        return n;
    }

    private BlockData known(long ck, long key, ChunkSnapshot snap, int x, int y, int z) {
        Map<Long, Override> ov = overrides.get(ck);
        Override o = ov == null ? null : ov.get(key);
        return o != null ? o.data : snap.getBlockData(x & 15, y, z & 15);
    }

    private void writeBlock(int x, int y, int z, BlockData now) {
        int s = stateId(now);
        out.begin(Records.BLOCK).zigzag(x).zigzag(y).zigzag(z).varInt(s).end();
    }

    /** The blocks events marked: each read now and written if it isn't what the recording says. */
    private void blocks() {
        if (dirty.isEmpty()) return;
        for (long key : dirty) {
            int x = bx(key), y = by(key), z = bz(key);
            long ck = chunkKey(x >> 4, z >> 4);
            ChunkSnapshot snap = snaps.get(ck);
            if (snap == null || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            BlockData now = world.getBlockAt(x, y, z).getBlockData();
            if (now.equals(known(ck, key, snap, x, y, z))) continue;
            writeBlock(x, y, z, now);
            Map<Long, Override> ov = overrides.get(ck);
            if (ov == null) overrides.put(ck, ov = new HashMap<>());
            ov.put(key, new Override(now, tick));
        }
        dirty.clear();
    }

    /**
     * The rolling comparison: a few chunks a tick are taken again and compared off the server's thread with how they
     * were last taken, so a change no event told of (a command, another plugin) is written when it's found.
     */
    private void resync() {
        for (Resync r; (r = resynced.poll()) != null; ) apply(r);
        if (resyncOrder.isEmpty()) return;
        int per = (int) Math.max(1, Math.min(4, Math.ceil(resyncOrder.size() / (owner.resyncSeconds() * 20.0))));
        for (int i = 0; i < per; i++) {
            long ck = resyncOrder.get(resyncCursor++ % resyncOrder.size());
            int cx = (int) (ck >> 32), cz = (int) ck;
            if (resyncing.contains(ck) || !world.isChunkLoaded(cx, cz)) continue;
            ChunkSnapshot before = snaps.get(ck);
            ChunkSnapshot now = Versions.blocksOnly(world.getChunkAt(cx, cz));
            int at = tick;
            int lo = minY, n = sections;
            resyncing.add(ck);
            owner.io(() -> resynced.add(new Resync(ck, now, at, MapEncoder.diff(before, now, lo, n))));
        }
    }

    private void apply(Resync r) {
        resyncing.remove(r.chunk);
        if (stopped) return;
        Map<Long, Override> ov = overrides.get(r.chunk);
        Set<Long> seen = new HashSet<>();
        for (MapEncoder.Diff d : r.diffs) {
            long key = blockKey(d.x, d.y, d.z);
            seen.add(key);
            Override o = ov == null ? null : ov.get(key);
            if (o != null && (o.tick > r.tick || o.data.equals(d.now))) continue; // written already, or newer than the snapshot
            writeBlock(d.x, d.y, d.z, d.now);
        }
        if (ov != null) {
            for (Iterator<Map.Entry<Long, Override>> it = ov.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Long, Override> e = it.next();
                Override o = e.getValue();
                if (o.tick > r.tick) continue; // newer than the snapshot: still the truth
                long key = e.getKey();
                if (!seen.contains(key)) {
                    // changed back without an event: the block is as the chunk was taken
                    int x = bx(key), y = by(key), z = bz(key);
                    BlockData now = r.now.getBlockData(x & 15, y, z & 15);
                    if (!now.equals(o.data)) writeBlock(x, y, z, now);
                }
                it.remove();
            }
            if (ov.isEmpty()) overrides.remove(r.chunk);
        }
        snaps.put(r.chunk, r.now);
    }

    // ── the end ─────────────────────────────────────────────────────────

    /** Stops sampling and hands the rest to the I/O thread, which closes the files. Then {@link Recordings} seals it. */
    JsonObject stop() {
        stopped = true;
        flush();
        OutputStream t = timeline;
        MapEncoder m = map;
        owner.io(() -> {
            t.close();
            m.close();
        });
        return meta(true);
    }

    JsonObject meta(boolean done) {
        JsonObject meta = new JsonObject();
        meta.addProperty("format", Records.FORMAT);
        meta.addProperty("id", id);
        meta.addProperty("label", label);
        meta.addProperty("plugin", owner.pluginVersion());
        meta.addProperty("server", Bukkit.getName() + " " + Bukkit.getVersion());
        meta.addProperty("minecraft", Versions.minecraft());
        meta.addProperty("dataVersion", Versions.dataVersion());
        int protocol = Versions.protocol();
        if (protocol > 0) meta.addProperty("protocol", protocol);
        JsonObject w = new JsonObject();
        w.addProperty("name", world.getName());
        w.addProperty("environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
        w.addProperty("minY", minY);
        w.addProperty("maxY", maxY);
        w.addProperty("seaLevel", world.getSeaLevel());
        w.addProperty("flat", Versions.flat(world));
        meta.add("world", w);
        JsonObject box = new JsonObject();
        box.addProperty("minChunkX", minCX);
        box.addProperty("minChunkZ", minCZ);
        box.addProperty("maxChunkX", maxCX);
        box.addProperty("maxChunkZ", maxCZ);
        meta.add("box", box);
        JsonObject start = new JsonObject();
        start.addProperty("epochMs", startedAt);
        meta.add("start", start);
        if (done) {
            meta.addProperty("durationMs", lastMs);
            meta.addProperty("ticks", tick + 1);
            meta.addProperty("chunks", snaps.size());
            JsonArray players = new JsonArray();
            for (Map.Entry<UUID, String> e : participants.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", e.getKey().toString());
                o.addProperty("name", e.getValue());
                if (sakuraParticipants.contains(e.getKey())) o.addProperty("sakura", true);
                players.add(o);
            }
            meta.add("players", players);
        }
        return meta;
    }
}
