package com.sakuraclient.integration.paper;

import com.sakuraclient.integration.api.SakuraPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A player's Sakura client answered: from now on they count as a Sakura
 * player. Fires on the main thread, a second or so after they join, and
 * again after each server switch behind a proxy (each server hears them
 * anew).
 */
public final class SakuraPlayerJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

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

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
