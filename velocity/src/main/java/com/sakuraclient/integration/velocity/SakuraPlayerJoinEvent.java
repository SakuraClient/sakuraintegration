package com.sakuraclient.integration.velocity;

import com.sakuraclient.integration.api.SakuraPlayer;
import com.velocitypowered.api.proxy.Player;

/**
 * A player's Sakura client answered the proxy: from now on they count as a
 * Sakura player on this network. Fires once per connection, a second or so
 * after they join.
 */
public final class SakuraPlayerJoinEvent {

    private final Player player;
    private final SakuraPlayer sakura;

    public SakuraPlayerJoinEvent(Player player, SakuraPlayer sakura) {
        this.player = player;
        this.sakura = sakura;
    }

    public Player getPlayer() {
        return player;
    }

    public SakuraPlayer getSakuraPlayer() {
        return sakura;
    }
}
