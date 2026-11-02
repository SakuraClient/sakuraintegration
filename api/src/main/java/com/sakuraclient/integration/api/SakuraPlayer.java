package com.sakuraclient.integration.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * A player on Sakura, while they're online.
 *
 * <p>Mods are named by their Sakura ids ({@code freelook}, {@code minimap},
 * {@code camera-zoom}, …; {@link #availableMods()} lists every one this
 * player's client knows). A mod turned off is off outright while the player
 * is here, and comes back when they leave. The player's client never lets a
 * server turn off {@code streamer-mode}, {@code wellbeing} or
 * {@code subtitles}.
 */
public interface SakuraPlayer {

    UUID uniqueId();

    /** The Sakura version the player's client reported. */
    String clientVersion();

    /** Every mod id this player's client can turn off. */
    Set<String> availableMods();

    /**
     * Every mod this server holds off for the player right now: the
     * configuration's and this player's own (below).
     */
    Set<String> disabledMods();

    /**
     * Turns these mods off for this player, on top of the configuration's
     * (a minigame that turns freelook off for a match). Ids that aren't mods
     * are ignored. Sent to the player at once.
     */
    void disableMods(Collection<String> ids);

    /**
     * Takes these mods out of this player's own set. A mod the configuration
     * turns off stays off.
     */
    void enableMods(Collection<String> ids);

    /** Empties this player's own set. */
    void clearMods();
}
