package com.sakuraclient.integration.wire;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireTest {

    @Test
    void helloRoundTrips() throws Exception {
        Wire.Hello h = (Wire.Hello) Wire.decode(Wire.encode(new Wire.Hello(1, Wire.SCOPE_NETWORK, "velocity", "1.0.0")));
        assertEquals(1, h.protocol);
        assertEquals(Wire.SCOPE_NETWORK, h.scope);
        assertEquals("velocity", h.platform);
        assertEquals("1.0.0", h.version);
    }

    @Test
    void clientHelloRoundTrips() throws Exception {
        Wire.ClientHello h = (Wire.ClientHello) Wire.decode(Wire.encode(
                new Wire.ClientHello(1, Wire.SCOPE_SERVER, "2026.1102", Arrays.asList("freelook", "minimap"))));
        assertEquals(Wire.SCOPE_SERVER, h.scope);
        assertEquals("2026.1102", h.client);
        assertEquals(Arrays.asList("freelook", "minimap"), h.mods);
    }

    @Test
    void disableModsRoundTrips() throws Exception {
        Wire.DisableMods d = (Wire.DisableMods) Wire.decode(Wire.encode(
                new Wire.DisableMods(Wire.SCOPE_SERVER, Arrays.asList("camera-zoom", "light-lens"))));
        assertEquals(Arrays.asList("camera-zoom", "light-lens"), d.ids);
        Wire.DisableMods none = (Wire.DisableMods) Wire.decode(Wire.encode(
                new Wire.DisableMods(Wire.SCOPE_NETWORK, Collections.<String>emptyList())));
        assertTrue(none.ids.isEmpty());
    }

    /** The exact bytes, so the client's copy and this one can't drift unnoticed. */
    @Test
    void bytesAreFixed() {
        byte[] b = Wire.encode(new Wire.DisableMods(Wire.SCOPE_SERVER, Collections.singletonList("fps")));
        assertArrayEquals(new byte[] {2, 0, 1, 3, 'f', 'p', 's'}, b);
    }

    @Test
    void unknownIdIsSkipped() throws Exception {
        assertNull(Wire.decode(new byte[] {42, 1, 2, 3}));
    }

    @Test
    void trailingBytesAreIgnored() throws Exception {
        byte[] b = Wire.encode(new Wire.DisableMods(Wire.SCOPE_SERVER, Collections.singletonList("fps")));
        byte[] longer = Arrays.copyOf(b, b.length + 5);
        assertEquals(Collections.singletonList("fps"), ((Wire.DisableMods) Wire.decode(longer)).ids);
    }

    @Test
    void malformedIsRefused() {
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[0]));
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[] {2, 0, 1, 9, 'a'}));
        // A string longer than the limit.
        byte[] tooLong = new byte[] {2, 0, 1, (byte) 0x81, 0x02};
        assertThrows(Wire.WireException.class, () -> Wire.decode(tooLong));
        // A list longer than the limit.
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[] {2, 0, (byte) 0x81, 0x02}));
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[Wire.MAX_MESSAGE + 1]));
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(new Wire.Hello(1, 0,
                new String(new char[300]).replace('\0', 'x'), "1")));
    }

    @Test
    void utf8Strings() throws Exception {
        String v = "1.0 ✿";
        Wire.Hello h = (Wire.Hello) Wire.decode(Wire.encode(new Wire.Hello(1, 0, "paper", v)));
        assertEquals(v, h.version);
        assertEquals(7, v.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void clientHelloFeatures() throws Exception {
        Wire.ClientHello h = (Wire.ClientHello) Wire.decode(Wire.encode(new Wire.ClientHello(1, Wire.SCOPE_SERVER, "2026.1112",
                Arrays.asList("freelook"), Collections.singletonList(Wire.FEATURE_RECORDINGS))));
        assertEquals(Collections.singletonList("recordings"), h.features);
        // A client from before features: its hello ends after the mods, and that's no features.
        byte[] old = new byte[] {1, 1, 0, 1, 'c', 1, 3, 'f', 'p', 's'};
        Wire.ClientHello before = (Wire.ClientHello) Wire.decode(old);
        assertEquals(Collections.singletonList("fps"), before.mods);
        assertTrue(before.features.isEmpty());
    }

    @Test
    void recordingMessagesRoundTrip() throws Exception {
        Wire.RecordingOffer o = (Wire.RecordingOffer) Wire.decode(Wire.encode(new Wire.RecordingOffer("2026-11-12_14-03-22", "Bed Wars 4",
                3_456_789_012L, "ab12", 612_000L, 1_794_152_780_682L, Arrays.asList("Pink", "Azerima"))));
        assertEquals("2026-11-12_14-03-22", o.recording);
        assertEquals("Bed Wars 4", o.label);
        assertEquals(3_456_789_012L, o.size);
        assertEquals("ab12", o.sha256);
        assertEquals(612_000L, o.durationMs);
        assertEquals(1_794_152_780_682L, o.startedAt);
        assertEquals(Arrays.asList("Pink", "Azerima"), o.players);

        Wire.RecordingGet g = (Wire.RecordingGet) Wire.decode(Wire.encode(new Wire.RecordingGet("r1", 2_400_000L, 262_144L)));
        assertEquals("r1", g.recording);
        assertEquals(2_400_000L, g.offset);
        assertEquals(262_144L, g.length);

        byte[] data = new byte[Wire.MAX_BYTES];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        byte[] part = Wire.encode(new Wire.RecordingPart("2026-11-12_14-03-22_arena-long-label", 9_000_000_000L, data));
        assertTrue(part.length <= Wire.MAX_MESSAGE);
        Wire.RecordingPart p = (Wire.RecordingPart) Wire.decode(part);
        assertEquals(9_000_000_000L, p.offset);
        assertArrayEquals(data, p.data);

        Wire.RecordingRefused r = (Wire.RecordingRefused) Wire.decode(Wire.encode(new Wire.RecordingRefused("r1", Wire.REFUSED_NOT_OFFERED)));
        assertEquals("not_offered", r.reason);
    }

    /** The exact bytes of a get, so the client's copy and this one can't drift unnoticed. */
    @Test
    void recordingBytesAreFixed() {
        assertArrayEquals(new byte[] {4, 1, 'r', (byte) 0x80, 0x02, (byte) 0x80, (byte) 0x80, 0x10},
                Wire.encode(new Wire.RecordingGet("r", 256, 262_144)));
    }

    @Test
    void partsOverTheLimitAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Wire.encode(new Wire.RecordingPart("r", 0, new byte[Wire.MAX_BYTES + 1])));
        // A part that says it carries more than the limit.
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[] {5, 1, 'r', 0, (byte) 0xB1, (byte) 0xEA, 0x01}));
        // One that says more than it has.
        assertThrows(Wire.WireException.class, () -> Wire.decode(new byte[] {5, 1, 'r', 0, 9, 1, 2}));
    }

    @Test
    void recordingIds() {
        assertTrue(Wire.isRecordingId("2026-11-12_14-03-22"));
        assertTrue(Wire.isRecordingId("BedWars_4"));
        assertFalse(Wire.isRecordingId(""));
        assertFalse(Wire.isRecordingId("../x"));
        assertFalse(Wire.isRecordingId("a.b"));
        assertFalse(Wire.isRecordingId("a b"));
        assertFalse(Wire.isRecordingId(new String(new char[65]).replace('\0', 'x')));
    }

    @Test
    void modIds() {
        assertTrue(Wire.isModId("camera-zoom"));
        assertTrue(Wire.isModId("fps"));
        assertFalse(Wire.isModId(""));
        assertFalse(Wire.isModId("Freelook"));
        assertFalse(Wire.isModId("free look"));
        assertFalse(Wire.isModId("minimap:players"));
    }
}
