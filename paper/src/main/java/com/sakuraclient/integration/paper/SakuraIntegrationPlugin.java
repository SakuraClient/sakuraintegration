package com.sakuraclient.integration.paper;

import com.sakuraclient.integration.api.SakuraIntegration;
import com.sakuraclient.integration.core.Mods;
import com.sakuraclient.integration.core.Players;
import com.sakuraclient.integration.core.Verifier;
import com.sakuraclient.integration.paper.record.Recordings;
import com.sakuraclient.integration.wire.Wire;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Sakura Integration on Paper and Spigot, 1.13 and later.
 *
 * <p>Players whose client registers {@link Wire#CHANNEL} get a hello; those
 * that answer are Sakura players ({@link Players}) and get the set of mods
 * this server turns off: the configuration's (their world's own list, if it
 * has one) and whatever another plugin set for them. It's sent again when
 * they change world or the configuration reloads.
 *
 * <p>It also records arenas ({@link Recordings}): every player and block of a box of chunks, offered to the players'
 * games once the recording stops.
 */
public final class SakuraIntegrationPlugin extends JavaPlugin implements Players.Platform {

    private Players players;
    private Verifier verifier;
    private Recordings recordings;
    private boolean pingHook;

    private volatile Set<String> disabledMods = Collections.emptySet();
    private volatile Map<String, Set<String>> worldMods = Collections.emptyMap();
    private volatile String badge = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readConfig();

        String version = getDescription().getVersion();
        players = new Players(this, Wire.SCOPE_SERVER, "paper", version);
        verifier = new Verifier(getDataFolder().toPath(), "paper", version, getLogger()::info);

        getServer().getMessenger().registerOutgoingPluginChannel(this, Wire.CHANNEL);
        PaperListener listener = new PaperListener(this);
        getServer().getMessenger().registerIncomingPluginChannel(this, Wire.CHANNEL, listener);
        getServer().getPluginManager().registerEvents(listener, this);

        recordings = new Recordings(this, players::isSakura);
        recordings.enable();

        pingHook = PingHook.available();
        if (pingHook) getServer().getPluginManager().registerEvents(new PingHook(verifier), this);

        PluginCommand command = getCommand("sakura");
        if (command != null) {
            SakuraCommand handler = new SakuraCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        SakuraIntegration.Provider.set(players);
        getServer().getServicesManager().register(SakuraIntegration.class, players, this, ServicePriority.Normal);

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SakuraExpansion(this).register();
        }

        // A reload (/reload, a plugin manager) leaves players online: greet them again.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getListeningPluginChannels().contains(Wire.CHANNEL)) sendHello(p);
        }
    }

    @Override
    public void onDisable() {
        if (recordings != null) recordings.disable();
        SakuraIntegration.Provider.set(null);
        getServer().getServicesManager().unregisterAll(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    /** Reads config.yml again; callers send the new sets. */
    void readConfig() {
        reloadConfig();
        disabledMods = Mods.clean(getConfig().getStringList("disabled-mods"));
        Map<String, Set<String>> worlds = new HashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection("worlds");
        if (section != null) {
            for (String world : section.getKeys(false)) {
                worlds.put(world, Mods.clean(section.getStringList(world + ".disabled-mods")));
            }
        }
        worldMods = Collections.unmodifiableMap(worlds);
        badge = getConfig().getString("placeholders.badge", "");
        if (recordings != null) recordings.readConfig(getConfig());
    }

    void sendHello(Player p) {
        p.sendPluginMessage(this, Wire.CHANNEL, players.hello());
    }

    Players players() {
        return players;
    }

    Verifier verifier() {
        return verifier;
    }

    Recordings recordings() {
        return recordings;
    }

    boolean canVerify() {
        return pingHook;
    }

    String badge() {
        return badge;
    }

    Set<String> configuredIds() {
        Set<String> all = new TreeSet<>(disabledMods);
        for (Set<String> s : worldMods.values()) all.addAll(s);
        return all;
    }

    Set<String> globalMods() {
        return disabledMods;
    }

    Map<String, Set<String>> worldMods() {
        return worldMods;
    }

    // ── Players.Platform ─────────────────────────────────────────────────

    @Override
    public Set<String> configuredMods(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            Set<String> world = worldMods.get(p.getWorld().getName());
            if (world != null) return world;
        }
        return disabledMods;
    }

    @Override
    public void send(UUID id, byte[] message) {
        Player p = Bukkit.getPlayer(id);
        if (p != null && p.isOnline()) p.sendPluginMessage(this, Wire.CHANNEL, message);
    }
}
