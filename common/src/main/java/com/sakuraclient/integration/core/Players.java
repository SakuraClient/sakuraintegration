package com.sakuraclient.integration.core;

import com.sakuraclient.integration.api.SakuraIntegration;
import com.sakuraclient.integration.api.SakuraPlayer;
import com.sakuraclient.integration.wire.Wire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Sakura players one platform knows, and the mods it holds off for each:
 * the plugin's API, shared by Paper and Velocity.
 *
 * <p>The platform says what its configuration turns off for a player and
 * carries the bytes; this decides who is a Sakura player (their
 * {@link Wire.ClientHello} of our scope came in) and sends each one their
 * set whenever it changes, never the same set twice.
 */
public final class Players implements SakuraIntegration {

    /** What a platform does for {@link Players}. */
    public interface Platform {
        /** The mods the configuration turns off for this player right now. */
        Set<String> configuredMods(UUID player);

        /** Sends one message to the player on {@link Wire#CHANNEL}, if they're still online. */
        void send(UUID player, byte[] message);
    }

    private final Platform platform;
    private final int scope;
    private final String platformName;
    private final String version;
    private final Map<UUID, Member> members = new ConcurrentHashMap<>();
    /** Every mod id the clients that answered say they know (for warnings about typos). */
    private final Set<String> knownMods = ConcurrentHashMap.newKeySet();

    public Players(Platform platform, int scope, String platformName, String version) {
        this.platform = platform;
        this.scope = scope;
        this.platformName = platformName;
        this.version = version;
    }

    /** The hello this platform sends a player who registered the channel. */
    public byte[] hello() {
        return Wire.encode(new Wire.Hello(Wire.PROTOCOL, scope, platformName, version));
    }

    /**
     * A client answered our hello. Returns the player when this is the first
     * answer on this connection (fire the join event), null otherwise.
     */
    public SakuraPlayer answered(UUID player, Wire.ClientHello hello) {
        knownMods.addAll(Mods.clean(hello.mods));
        Member fresh = new Member(player, hello.client, Mods.clean(hello.mods));
        Member old = members.putIfAbsent(player, fresh);
        if (old != null) {
            // A second answer on one connection (a client that saw the hello twice): keep the player's own set.
            old.clientVersion = hello.client;
            old.availableMods = fresh.availableMods;
            synchronized (old) {
                old.sent = null;
            }
            refresh(player);
            return null;
        }
        refresh(player);
        return fresh;
    }

    public void left(UUID player) {
        members.remove(player);
    }

    /** Sends this player their set, if it changed since the last one sent. */
    public void refresh(UUID player) {
        Member m = members.get(player);
        if (m == null) return;
        Set<String> now = m.disabledMods();
        synchronized (m) {
            if (now.equals(m.sent)) return;
            m.sent = now;
        }
        platform.send(player, Wire.encode(new Wire.DisableMods(scope, now)));
    }

    public void refreshAll() {
        for (UUID player : members.keySet()) refresh(player);
    }

    /** Configured ids that no client has said it knows: probably typos. */
    public Set<String> unknown(Collection<String> configured) {
        Set<String> out = new TreeSet<>();
        if (knownMods.isEmpty()) return out;
        for (String id : Mods.clean(configured)) {
            if (!knownMods.contains(id)) out.add(id);
        }
        return out;
    }

    // ── SakuraIntegration ────────────────────────────────────────────────

    @Override
    public boolean isSakura(UUID player) {
        return player != null && members.containsKey(player);
    }

    @Override
    public Optional<SakuraPlayer> player(UUID player) {
        return player == null ? Optional.empty() : Optional.ofNullable(members.get(player));
    }

    @Override
    public Collection<SakuraPlayer> players() {
        return Collections.unmodifiableList(new ArrayList<SakuraPlayer>(members.values()));
    }

    private final class Member implements SakuraPlayer {
        private final UUID id;
        private volatile String clientVersion;
        private volatile Set<String> availableMods;
        /** Mods another plugin turned off for just this player. */
        private final Set<String> own = new TreeSet<>();
        /** The set last sent (guarded by this). */
        private Set<String> sent;

        Member(UUID id, String clientVersion, Set<String> availableMods) {
            this.id = id;
            this.clientVersion = clientVersion;
            this.availableMods = Collections.unmodifiableSet(availableMods);
        }

        @Override public UUID uniqueId() { return id; }

        @Override public String clientVersion() { return clientVersion; }

        @Override public Set<String> availableMods() { return availableMods; }

        @Override
        public Set<String> disabledMods() {
            Set<String> all = new TreeSet<>(Mods.clean(platform.configuredMods(id)));
            synchronized (this) {
                all.addAll(own);
            }
            return Collections.unmodifiableSet(all);
        }

        @Override
        public void disableMods(Collection<String> ids) {
            synchronized (this) {
                own.addAll(Mods.clean(ids));
            }
            refresh(id);
        }

        @Override
        public void enableMods(Collection<String> ids) {
            synchronized (this) {
                own.removeAll(Mods.clean(ids));
            }
            refresh(id);
        }

        @Override
        public void clearMods() {
            synchronized (this) {
                own.clear();
            }
            refresh(id);
        }
    }
}
