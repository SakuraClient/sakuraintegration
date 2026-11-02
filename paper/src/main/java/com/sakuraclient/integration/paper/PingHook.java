package com.sakuraclient.integration.paper;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.sakuraclient.integration.core.Verifier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * While {@code /sakura verify} runs, adds its code to the version name in
 * status replies. Paper only: loaded only when Paper's ping event exists.
 */
final class PingHook implements Listener {

    static final String EVENT = "com.destroystokyo.paper.event.server.PaperServerListPingEvent";

    private final Verifier verifier;

    PingHook(Verifier verifier) {
        this.verifier = verifier;
    }

    static boolean available() {
        try {
            Class.forName(EVENT);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // After every other plugin has set its version name.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPing(PaperServerListPingEvent event) {
        String suffix = verifier.versionSuffix();
        if (suffix != null) event.setVersion(event.getVersion() + suffix);
    }
}
