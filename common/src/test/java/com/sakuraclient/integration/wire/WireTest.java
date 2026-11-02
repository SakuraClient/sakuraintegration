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
    void modIds() {
        assertTrue(Wire.isModId("camera-zoom"));
        assertTrue(Wire.isModId("fps"));
        assertFalse(Wire.isModId(""));
        assertFalse(Wire.isModId("Freelook"));
        assertFalse(Wire.isModId("free look"));
        assertFalse(Wire.isModId("minimap:players"));
    }
}
