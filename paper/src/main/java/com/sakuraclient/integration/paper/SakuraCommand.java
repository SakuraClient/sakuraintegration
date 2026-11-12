package com.sakuraclient.integration.paper;

import com.sakuraclient.integration.api.SakuraPlayer;
import com.sakuraclient.integration.paper.record.Recordings;
import com.sakuraclient.integration.record.RecordingStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code /sakura status | players | verify <address> | reload | record start|stop|mark|list}. */
final class SakuraCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = Arrays.asList("status", "players", "verify", "reload", "record");
    private static final List<String> RECORD = Arrays.asList("start", "stop", "mark", "list");

    private final SakuraIntegrationPlugin plugin;

    SakuraCommand(SakuraIntegrationPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status":
                status(sender);
                return true;
            case "players":
                players(sender);
                return true;
            case "verify":
                verify(sender, args.length > 1 ? args[1] : "");
                return true;
            case "reload":
                plugin.readConfig();
                plugin.players().refreshAll();
                say(sender, "Reloaded. Every Sakura player has the new list.");
                return true;
            case "record":
                record(sender, label, Arrays.copyOfRange(args, 1, args.length));
                return true;
            default:
                say(sender, "/" + label + " status | players | verify <address> | reload | record");
                return true;
        }
    }

    private void status(CommandSender sender) {
        say(sender, "Sakura Integration " + plugin.getDescription().getVersion());
        say(sender, "Sakura players online: " + plugin.players().players().size());
        Set<String> global = plugin.globalMods();
        say(sender, "Mods off: " + (global.isEmpty() ? "none" : String.join(", ", global)));
        for (Map.Entry<String, Set<String>> world : plugin.worldMods().entrySet()) {
            Set<String> mods = world.getValue();
            say(sender, "  in " + world.getKey() + ": " + (mods.isEmpty() ? "none" : String.join(", ", mods)));
        }
        Set<String> unknown = plugin.players().unknown(plugin.configuredIds());
        if (!unknown.isEmpty()) {
            say(sender, ChatColor.GOLD + "Not Sakura mods (a typo?): " + String.join(", ", unknown));
        }
        List<String> addresses = plugin.verifier().addresses();
        say(sender, addresses.isEmpty()
                ? "No verified address yet: /sakura verify <address>"
                : "Verified: " + String.join(", ", addresses));
    }

    private void players(CommandSender sender) {
        Collection<SakuraPlayer> all = plugin.players().players();
        if (all.isEmpty()) {
            say(sender, "Nobody online is playing on Sakura.");
            return;
        }
        List<String> names = new ArrayList<>();
        for (SakuraPlayer sp : all) {
            Player p = Bukkit.getPlayer(sp.uniqueId());
            if (p != null) names.add(p.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        say(sender, names.size() + " on Sakura: " + String.join(", ", names));
    }

    private void verify(CommandSender sender, String address) {
        if (!plugin.canVerify()) {
            say(sender, "Verifying needs Paper or a fork of it (Purpur, Pufferfish, ...): plain Spigot can't show the code.");
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> plugin.verifier().verify(address,
                line -> Bukkit.getScheduler().runTask(plugin, () -> say(sender, line))));
    }

    // ── record ───────────────────────────────────────────────────────────

    private void record(CommandSender sender, String label, String[] args) {
        Recordings rec = plugin.recordings();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        switch (sub) {
            case "start":
                start(sender, rec, rest);
                return;
            case "stop":
                stop(sender, rec, rest);
                return;
            case "mark":
                if (rest.length == 0) {
                    say(sender, "/" + label + " record mark <words>");
                    return;
                }
                Location at = sender instanceof Player ? ((Player) sender).getLocation() : Bukkit.getWorlds().get(0).getSpawnLocation();
                rec.mark(String.join(" ", rest), at);
                say(sender, "Marked.");
                return;
            case "list":
                list(sender, rec);
                return;
            default:
                say(sender, "/" + label + " record start [radius=<chunks> | box=<x1>,<z1>,<x2>,<z2>] [world=<name>] [label] | stop [id] | mark <words> | list");
        }
    }

    /** {@code start [radius=8 | box=x1,z1,x2,z2] [world=name] [label…]}: round you (or the world's spawn), or a box of blocks. */
    private void start(CommandSender sender, Recordings rec, String[] args) {
        World world = sender instanceof Player ? ((Player) sender).getWorld() : Bukkit.getWorlds().get(0);
        Location centre = sender instanceof Player ? ((Player) sender).getLocation() : null;
        int radius = rec.radius();
        int[] box = null;
        List<String> words = new ArrayList<>();
        for (String a : args) {
            String lower = a.toLowerCase(Locale.ROOT);
            try {
                if (lower.startsWith("world=")) {
                    world = Bukkit.getWorld(a.substring(6));
                    if (world == null) {
                        say(sender, "There's no world called " + a.substring(6) + ".");
                        return;
                    }
                    centre = null;
                } else if (lower.startsWith("radius=")) {
                    radius = Math.max(0, Math.min(32, Integer.parseInt(a.substring(7))));
                } else if (lower.startsWith("box=")) {
                    String[] c = a.substring(4).split(",");
                    if (c.length != 4) throw new NumberFormatException();
                    box = new int[4];
                    for (int i = 0; i < 4; i++) box[i] = Integer.parseInt(c[i].trim());
                } else {
                    words.add(a);
                }
            } catch (NumberFormatException e) {
                say(sender, "I can't read " + a + ": radius=<chunks>, box=<x1>,<z1>,<x2>,<z2> (blocks).");
                return;
            }
        }
        int minCX, minCZ, maxCX, maxCZ;
        if (box != null) {
            minCX = Math.min(box[0], box[2]) >> 4;
            maxCX = Math.max(box[0], box[2]) >> 4;
            minCZ = Math.min(box[1], box[3]) >> 4;
            maxCZ = Math.max(box[1], box[3]) >> 4;
        } else {
            if (centre == null) centre = world.getSpawnLocation();
            int cx = centre.getBlockX() >> 4, cz = centre.getBlockZ() >> 4;
            minCX = cx - radius;
            maxCX = cx + radius;
            minCZ = cz - radius;
            maxCZ = cz + radius;
        }
        String name = String.join(" ", words);
        try {
            String id = rec.start(world, minCX, minCZ, maxCX, maxCZ, name);
            int chunks = (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
            say(sender, "Recording " + (name.isEmpty() ? world.getName() : name) + " (" + id + "): " + chunks + " chunks of "
                    + world.getName() + ", every player and block. /sakura record stop ends it.");
        } catch (IllegalArgumentException e) {
            say(sender, "Can't record that: " + e.getMessage() + ".");
        } catch (java.io.IOException e) {
            say(sender, "Can't record: " + e.getMessage());
        }
    }

    private void stop(CommandSender sender, Recordings rec, String[] args) {
        List<String> ids = args.length > 0 ? Collections.singletonList(args[0]) : new ArrayList<>(rec.running());
        if (ids.isEmpty()) {
            say(sender, "Nothing is recording.");
            return;
        }
        for (String id : ids) {
            boolean stopped = rec.stop(id, info -> say(sender, "Saved " + info.label + " (" + info.id + RecordingStore.EXTENSION + ", "
                    + size(info.size) + ", " + info.players.size() + " players)."));
            say(sender, stopped ? "Stopped " + id + ". Saving it…" : "No recording " + id + " is running.");
        }
    }

    private void list(CommandSender sender, Recordings rec) {
        List<String> running = rec.describeRunning();
        if (running.isEmpty()) say(sender, "Nothing is recording.");
        for (String line : running) say(sender, "Recording: " + line);
        List<RecordingStore.Info> kept = rec.kept();
        if (kept.isEmpty()) {
            say(sender, "No recordings kept yet.");
            return;
        }
        long total = 0;
        for (RecordingStore.Info i : kept) total += i.size;
        say(sender, kept.size() + " kept (" + size(total) + "), newest first:");
        for (int n = 0; n < Math.min(10, kept.size()); n++) {
            RecordingStore.Info i = kept.get(n);
            long s = i.durationMs / 1000;
            say(sender, "  " + i.id + " · " + i.label + " · " + (s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s") + " · "
                    + size(i.size) + " · " + i.players.size() + " players");
        }
    }

    private static String size(long bytes) {
        return bytes < 1024 * 1024 ? ((bytes + 1023) / 1024) + " KB" : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private static void say(CommandSender sender, String line) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Sakura " + ChatColor.DARK_GRAY + "· " + ChatColor.GRAY + line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> from;
        if (args.length == 1) from = SUBCOMMANDS;
        else if (args.length == 2 && "record".equalsIgnoreCase(args[0])) from = RECORD;
        else return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        for (String s : from) {
            if (s.startsWith(typed)) out.add(s);
        }
        return out;
    }
}
