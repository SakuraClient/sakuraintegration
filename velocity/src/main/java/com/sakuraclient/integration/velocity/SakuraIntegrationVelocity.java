package com.sakuraclient.integration.velocity;

import com.google.inject.Inject;
import com.sakuraclient.integration.api.SakuraIntegration;
import com.sakuraclient.integration.api.SakuraPlayer;
import com.sakuraclient.integration.core.Mods;
import com.sakuraclient.integration.core.Players;
import com.sakuraclient.integration.core.Verifier;
import com.sakuraclient.integration.wire.Wire;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyPingEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Sakura Integration on Velocity: the network's say.
 *
 * <p>The proxy greets every Sakura client with network scope and sends it the
 * network's set: {@code disabled-mods} plus the list of the backend the
 * player is on. Backends with the plugin greet the same client with server
 * scope; the client answers each, and the proxy keeps the answers meant for
 * it and passes the others on. A backend never speaks for the network: its
 * messages that claim network scope stop here.
 */
@Plugin(
        id = "sakuraintegration",
        name = "SakuraIntegration",
        version = BuildInfo.VERSION,
        description = "Talks to the Sakura players on your network. Turn Sakura mods off, see who plays on Sakura, and verify your address.",
        url = "https://sakuraclient.com",
        authors = {"Sakura"})
public final class SakuraIntegrationVelocity implements Players.Platform {

    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(Wire.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private Players players;
    private Verifier verifier;

    private volatile Set<String> disabledMods = Collections.emptySet();
    private volatile Map<String, Set<String>> serverMods = Collections.emptyMap();

    @Inject
    public SakuraIntegrationVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        readConfig();
        players = new Players(this, Wire.SCOPE_NETWORK, "velocity", BuildInfo.VERSION);
        verifier = new Verifier(dataDirectory, "velocity", BuildInfo.VERSION, logger::info);
        proxy.getChannelRegistrar().register(CHANNEL);

        CommandMeta meta = proxy.getCommandManager().metaBuilder("sakuravelocity").plugin(this).build();
        proxy.getCommandManager().register(meta, new VelocityCommand(this));

        SakuraIntegration.Provider.set(players);
    }

    /** Reads config.yml again (writing the default first); callers send the new sets. */
    void readConfig() {
        Path file = dataDirectory.resolve("config.yml");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) Files.copy(in, file);
                }
            }
            Map<String, Object> root;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Object loaded = new Yaml().load(r);
                root = loaded instanceof Map ? cast(loaded) : Collections.emptyMap();
            }
            disabledMods = Mods.clean(strings(root.get("disabled-mods")));
            Map<String, Set<String>> servers = new HashMap<>();
            Object section = root.get("servers");
            if (section instanceof Map) {
                for (Map.Entry<String, Object> e : this.<Map<String, Object>>cast(section).entrySet()) {
                    Object entry = e.getValue();
                    Object mods = entry instanceof Map ? ((Map<?, ?>) entry).get("disabled-mods") : null;
                    servers.put(e.getKey(), Mods.clean(strings(mods)));
                }
            }
            serverMods = Collections.unmodifiableMap(servers);
        } catch (IOException | RuntimeException e) {
            logger.warn("Couldn't read {}: {}. Keeping the lists as they were.", file, e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T cast(Object o) {
        return (T) o;
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof Collection)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Object item : (Collection<?>) o) {
            if (item != null) out.add(item.toString());
        }
        return out;
    }

    // ── Events ───────────────────────────────────────────────────────────

    @Subscribe
    public void onChannelRegister(PlayerChannelRegisterEvent event) {
        if (event.getChannels().contains(CHANNEL)) {
            event.getPlayer().sendPluginMessage(CHANNEL, players.hello());
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        Wire.Message m;
        try {
            m = Wire.decode(event.getData());
        } catch (Wire.WireException e) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            return;
        }
        if (event.getSource() instanceof Player) {
            Player player = (Player) event.getSource();
            if (m instanceof Wire.ClientHello && ((Wire.ClientHello) m).scope == Wire.SCOPE_NETWORK) {
                event.setResult(PluginMessageEvent.ForwardResult.handled());
                SakuraPlayer joined = players.answered(player.getUniqueId(), (Wire.ClientHello) m);
                if (joined != null) proxy.getEventManager().fireAndForget(new SakuraPlayerJoinEvent(player, joined));
            }
            // Answers to a backend's hello go on to it.
        } else if (event.getSource() instanceof ServerConnection) {
            if (scope(m) == Wire.SCOPE_NETWORK) event.setResult(PluginMessageEvent.ForwardResult.handled());
        }
    }

    private static int scope(Wire.Message m) {
        if (m instanceof Wire.Hello) return ((Wire.Hello) m).scope;
        if (m instanceof Wire.DisableMods) return ((Wire.DisableMods) m).scope;
        if (m instanceof Wire.ClientHello) return ((Wire.ClientHello) m).scope;
        return Wire.SCOPE_SERVER;
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        players.refresh(event.getPlayer().getUniqueId());
    }

    @Subscribe(order = PostOrder.LAST)
    public void onDisconnect(DisconnectEvent event) {
        players.left(event.getPlayer().getUniqueId());
    }

    @Subscribe(order = PostOrder.LAST)
    public void onPing(ProxyPingEvent event) {
        String suffix = verifier.versionSuffix();
        if (suffix == null) return;
        ServerPing ping = event.getPing();
        ServerPing.Version v = ping.getVersion();
        event.setPing(ping.asBuilder().version(new ServerPing.Version(v.getProtocol(), v.getName() + suffix)).build());
    }

    // ── Players.Platform ─────────────────────────────────────────────────

    @Override
    public Set<String> configuredMods(UUID id) {
        Set<String> backend = proxy.getPlayer(id)
                .flatMap(Player::getCurrentServer)
                .map(s -> serverMods.get(s.getServerInfo().getName()))
                .orElse(null);
        if (backend == null || backend.isEmpty()) return disabledMods;
        Set<String> all = new TreeSet<>(disabledMods);
        all.addAll(backend);
        return all;
    }

    @Override
    public void send(UUID id, byte[] message) {
        proxy.getPlayer(id).ifPresent(p -> p.sendPluginMessage(CHANNEL, message));
    }

    // ── For the command ──────────────────────────────────────────────────

    ProxyServer proxy() {
        return proxy;
    }

    Players players() {
        return players;
    }

    Verifier verifier() {
        return verifier;
    }

    Set<String> globalMods() {
        return disabledMods;
    }

    Map<String, Set<String>> serverMods() {
        return serverMods;
    }

    Set<String> configuredIds() {
        Set<String> all = new TreeSet<>(disabledMods);
        for (Set<String> s : serverMods.values()) all.addAll(s);
        return all;
    }
}
