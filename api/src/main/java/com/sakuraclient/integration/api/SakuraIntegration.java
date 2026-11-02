package com.sakuraclient.integration.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * The Sakura players on this server, and the mods it turns off for them.
 *
 * <p>Get it with {@link #get()} once the plugin is enabled (on Paper it is
 * also in Bukkit's {@code ServicesManager}). Call it from the server's main
 * thread on Paper; on Velocity any thread will do.
 *
 * <p>A player counts as a Sakura player once their client has answered the
 * plugin's hello, a second or so after they join; listen for
 * {@code SakuraPlayerJoinEvent} to hear the moment it does. That answer is a
 * claim the client makes, not a proof: fine for showing a mark, not enough to
 * pay anyone for.
 */
public interface SakuraIntegration {

    /** The running plugin's API. Throws if Sakura Integration isn't enabled. */
    static SakuraIntegration get() {
        SakuraIntegration api = Provider.api;
        if (api == null) throw new IllegalStateException("Sakura Integration isn't enabled");
        return api;
    }

    /** True if this player is online and playing on Sakura. */
    boolean isSakura(UUID player);

    /** The player, if they're online and playing on Sakura. */
    Optional<SakuraPlayer> player(UUID player);

    /** Every Sakura player online, in no particular order. */
    Collection<SakuraPlayer> players();

    /** Sets the running API; for the plugin itself, not for callers. */
    final class Provider {
        private static volatile SakuraIntegration api;

        private Provider() {}

        public static void set(SakuraIntegration value) {
            api = value;
        }
    }
}
