package com.sakuraclient.integration.velocity;

import com.sakuraclient.integration.api.SakuraPlayer;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code /sakuravelocity status | players | verify <address> | reload}. */
final class VelocityCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("status", "players", "verify", "reload");

    private final SakuraIntegrationVelocity plugin;

    VelocityCommand(SakuraIntegrationVelocity plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("sakura.admin");
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(source);
            case "players" -> players(source);
            case "verify" -> {
                String address = args.length > 1 ? args[1] : "";
                plugin.proxy().getScheduler()
                        .buildTask(plugin, () -> plugin.verifier().verify(address, line -> say(source, line)))
                        .schedule();
            }
            case "reload" -> {
                plugin.readConfig();
                plugin.players().refreshAll();
                say(source, "Reloaded. Every Sakura player has the new list.");
            }
            default -> say(source, "/" + invocation.alias() + " status | players | verify <address> | reload");
        }
    }

    private void status(CommandSource source) {
        say(source, "Sakura Integration " + BuildInfo.VERSION + " (network)");
        say(source, "Sakura players online: " + plugin.players().players().size());
        Set<String> global = plugin.globalMods();
        say(source, "Mods off: " + (global.isEmpty() ? "none" : String.join(", ", global)));
        for (Map.Entry<String, Set<String>> server : plugin.serverMods().entrySet()) {
            Set<String> mods = server.getValue();
            say(source, "  also on " + server.getKey() + ": " + (mods.isEmpty() ? "none" : String.join(", ", mods)));
        }
        Set<String> unknown = plugin.players().unknown(plugin.configuredIds());
        if (!unknown.isEmpty()) say(source, "Not Sakura mods (a typo?): " + String.join(", ", unknown));
        List<String> addresses = plugin.verifier().addresses();
        say(source, addresses.isEmpty()
                ? "No verified address yet: /sakuravelocity verify <address>"
                : "Verified: " + String.join(", ", addresses));
    }

    private void players(CommandSource source) {
        Collection<SakuraPlayer> all = plugin.players().players();
        if (all.isEmpty()) {
            say(source, "Nobody online is playing on Sakura.");
            return;
        }
        List<String> names = new ArrayList<>();
        for (SakuraPlayer sp : all) {
            plugin.proxy().getPlayer(sp.uniqueId()).map(Player::getUsername).ifPresent(names::add);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        say(source, names.size() + " on Sakura: " + String.join(", ", names));
    }

    private static void say(CommandSource source, String line) {
        source.sendMessage(Component.text("Sakura ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("· ", NamedTextColor.DARK_GRAY))
                .append(Component.text(line, NamedTextColor.GRAY)));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : SUBCOMMANDS) {
            if (s.startsWith(prefix)) out.add(s);
        }
        return out;
    }
}
