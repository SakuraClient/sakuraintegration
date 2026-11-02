package com.sakuraclient.integration.core;

import com.sakuraclient.integration.wire.Wire;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Mod ids as servers write them. */
public final class Mods {

    /**
     * Mods no server turns off, and the client ignores if one tries:
     * streamer mode keeps a streamer's private details off screen, wellbeing
     * looks after the player, and subtitles are accessibility.
     */
    public static final Set<String> NEVER = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("streamer-mode", "wellbeing", "subtitles")));

    private Mods() {}

    /** The ids as a sorted set: lower-cased, valid, and none of {@link #NEVER}. */
    public static Set<String> clean(Collection<String> ids) {
        Set<String> out = new TreeSet<>();
        if (ids == null) return out;
        for (String raw : ids) {
            if (raw == null) continue;
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if (Wire.isModId(id) && !NEVER.contains(id)) out.add(id);
        }
        return out;
    }
}
