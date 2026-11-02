package com.sakuraclient.integration.core;

import com.sakuraclient.integration.api.SakuraPlayer;
import com.sakuraclient.integration.wire.Wire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayersTest {

    private static final UUID ARI = UUID.randomUUID();

    private final Set<String> configured = new TreeSet<>();
    private final List<Wire.DisableMods> sent = new ArrayList<>();
    private final Players players = new Players(new Players.Platform() {
        @Override public Set<String> configuredMods(UUID player) { return configured; }

        @Override public void send(UUID player, byte[] message) {
            try {
                sent.add((Wire.DisableMods) Wire.decode(message));
            } catch (Wire.WireException e) {
                throw new AssertionError(e);
            }
        }
    }, Wire.SCOPE_SERVER, "paper", "test");

    private static Wire.ClientHello hello() {
        return new Wire.ClientHello(1, Wire.SCOPE_SERVER, "2026.1102", Arrays.asList("freelook", "minimap", "fps"));
    }

    @Test
    void answerMakesASakuraPlayerAndSendsTheSet() {
        configured.add("freelook");
        assertFalse(players.isSakura(ARI));
        SakuraPlayer p = players.answered(ARI, hello());
        assertNotNull(p);
        assertTrue(players.isSakura(ARI));
        assertEquals(1, sent.size());
        assertEquals(Collections.singletonList("freelook"), sent.get(0).ids);
        assertEquals(Wire.SCOPE_SERVER, sent.get(0).scope);
    }

    @Test
    void aSecondAnswerIsNotASecondJoin() {
        assertNotNull(players.answered(ARI, hello()));
        assertNull(players.answered(ARI, hello()));
        assertEquals(1, players.players().size());
    }

    @Test
    void theSameSetIsNeverSentTwice() {
        players.answered(ARI, hello());
        players.refresh(ARI);
        players.refreshAll();
        assertEquals(1, sent.size());
        configured.add("minimap");
        players.refreshAll();
        assertEquals(2, sent.size());
        assertEquals(Collections.singletonList("minimap"), sent.get(1).ids);
    }

    @Test
    void ownSetAddsToTheConfiguration() {
        configured.add("freelook");
        SakuraPlayer p = players.answered(ARI, hello());
        p.disableMods(Arrays.asList("Minimap", "streamer-mode", "not valid"));
        assertEquals(new TreeSet<>(Arrays.asList("freelook", "minimap")), p.disabledMods());
        p.enableMods(Collections.singletonList("freelook"));
        assertTrue(p.disabledMods().contains("freelook"), "the configuration's stays off");
        p.clearMods();
        assertEquals(Collections.singleton("freelook"), p.disabledMods());
        assertEquals(Arrays.asList("freelook", "minimap"), sent.get(1).ids);
        assertEquals(Collections.singletonList("freelook"), sent.get(2).ids);
    }

    @Test
    void neverListIsDropped() {
        configured.addAll(Arrays.asList("wellbeing", "subtitles", "streamer-mode", "fps"));
        players.answered(ARI, hello());
        assertEquals(Collections.singletonList("fps"), sent.get(0).ids);
    }

    @Test
    void unknownIdsAreTypos() {
        players.answered(ARI, hello());
        assertEquals(Collections.singleton("freelok"), players.unknown(Arrays.asList("freelook", "freelok")));
    }

    @Test
    void leavingForgets() {
        players.answered(ARI, hello());
        players.left(ARI);
        assertFalse(players.isSakura(ARI));
        players.refresh(ARI);
        assertEquals(1, sent.size());
    }
}
