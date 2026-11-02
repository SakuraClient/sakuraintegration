package com.sakuraclient.integration.paper;

import com.sakuraclient.integration.api.SakuraPlayer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

/** {@code /sakura status | players | verify <address> | reload}. */
final class SakuraCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = Arrays.asList("status", "players", "verify", "reload");

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
            default:
                say(sender, "/" + label + " status | players | verify <address> | reload");
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

    private static void say(CommandSender sender, String line) {
        sender.sendMessage(ChatColor.LIGHT_PURPLE + "Sakura " + ChatColor.DARK_GRAY + "· " + ChatColor.GRAY + line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String s : SUBCOMMANDS) {
            if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }
}
