package com.sakuraclient.integration.wire;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The messages Sakura Integration and the Sakura client send each other on
 * one plugin channel.
 *
 * <p>A message is a VarInt id, then its fields. A string is a VarInt byte
 * length and UTF-8 (at most {@link #MAX_STRING} bytes); a list is a VarInt
 * count (at most {@link #MAX_LIST}) and its items. Fields are only ever
 * appended, and a reader ignores bytes after the ones it knows, so either
 * side can be newer.
 *
 * <p>Plain Java 8 with no dependencies. The Sakura client keeps a copy of
 * this file, the same byte for byte apart from the package line.
 */
public final class Wire {

    public static final String CHANNEL = "sakura:integration";
    public static final int PROTOCOL = 1;

    /** The largest message either side reads. */
    public static final int MAX_MESSAGE = 32 * 1024;
    public static final int MAX_STRING = 256;
    public static final int MAX_LIST = 256;

    /** The server the player is on. */
    public static final int SCOPE_SERVER = 0;
    /** The network: the proxy in front of the servers. */
    public static final int SCOPE_NETWORK = 1;

    public static final int HELLO = 0;
    public static final int CLIENT_HELLO = 1;
    public static final int DISABLE_MODS = 2;

    private Wire() {}

    public interface Message {
        int id();
    }

    /** Server → client: the plugin is here. The client answers with a {@link ClientHello}. */
    public static final class Hello implements Message {
        public final int protocol;
        public final int scope;
        /** {@code paper} or {@code velocity}. */
        public final String platform;
        /** The plugin's version. */
        public final String version;

        public Hello(int protocol, int scope, String platform, String version) {
            this.protocol = protocol;
            this.scope = scope;
            this.platform = platform;
            this.version = version;
        }

        @Override public int id() { return HELLO; }
    }

    /** Client → server: a Sakura client, answering the hello of the same scope. */
    public static final class ClientHello implements Message {
        public final int protocol;
        public final int scope;
        /** The client's version. */
        public final String client;
        /** Every mod id the client can turn off. */
        public final List<String> mods;

        public ClientHello(int protocol, int scope, String client, Collection<String> mods) {
            this.protocol = protocol;
            this.scope = scope;
            this.client = client;
            this.mods = Collections.unmodifiableList(new ArrayList<>(mods));
        }

        @Override public int id() { return CLIENT_HELLO; }
    }

    /** Server → client: the whole set of mods this scope holds off (empty: none). */
    public static final class DisableMods implements Message {
        public final int scope;
        public final List<String> ids;

        public DisableMods(int scope, Collection<String> ids) {
            this.scope = scope;
            this.ids = Collections.unmodifiableList(new ArrayList<>(ids));
        }

        @Override public int id() { return DISABLE_MODS; }
    }

    /** A message that breaks the rules above. */
    public static final class WireException extends Exception {
        public WireException(String message) {
            super(message);
        }
    }

    /** True if {@code id} could be a mod id: 1–64 of {@code a-z}, {@code 0-9} and {@code -}. */
    public static boolean isModId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-')) return false;
        }
        return true;
    }

    // ── Writing ──────────────────────────────────────────────────────────

    public static byte[] encode(Message m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeVarInt(out, m.id());
        if (m instanceof Hello) {
            Hello h = (Hello) m;
            writeVarInt(out, h.protocol);
            out.write(h.scope);
            writeString(out, h.platform);
            writeString(out, h.version);
        } else if (m instanceof ClientHello) {
            ClientHello h = (ClientHello) m;
            writeVarInt(out, h.protocol);
            out.write(h.scope);
            writeString(out, h.client);
            writeList(out, h.mods);
        } else if (m instanceof DisableMods) {
            DisableMods d = (DisableMods) m;
            out.write(d.scope);
            writeList(out, d.ids);
        } else {
            throw new IllegalArgumentException("not a message: " + m);
        }
        if (out.size() > MAX_MESSAGE) throw new IllegalArgumentException("message over " + MAX_MESSAGE + " bytes");
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_STRING) throw new IllegalArgumentException("string over " + MAX_STRING + " bytes");
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    private static void writeList(ByteArrayOutputStream out, List<String> items) {
        if (items.size() > MAX_LIST) throw new IllegalArgumentException("list over " + MAX_LIST + " items");
        writeVarInt(out, items.size());
        for (String s : items) writeString(out, s);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /**
     * Reads one message, or returns null for an id this side doesn't know
     * (a newer peer's). Throws for anything malformed or too large.
     */
    public static Message decode(byte[] data) throws WireException {
        if (data == null || data.length == 0) throw new WireException("empty message");
        if (data.length > MAX_MESSAGE) throw new WireException("message over " + MAX_MESSAGE + " bytes");
        Reader r = new Reader(data);
        int id = r.varInt();
        switch (id) {
            case HELLO:
                return new Hello(r.varInt(), r.u8(), r.string(), r.string());
            case CLIENT_HELLO:
                return new ClientHello(r.varInt(), r.u8(), r.string(), r.list());
            case DISABLE_MODS:
                return new DisableMods(r.u8(), r.list());
            default:
                return null;
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int u8() throws WireException {
            if (pos >= data.length) throw new WireException("message ends early");
            return data[pos++] & 0xFF;
        }

        int varInt() throws WireException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new WireException("VarInt too long");
        }

        String string() throws WireException {
            int length = varInt();
            if (length < 0 || length > MAX_STRING) throw new WireException("string of " + length + " bytes");
            if (data.length - pos < length) throw new WireException("message ends early");
            String s = new String(data, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return s;
        }

        List<String> list() throws WireException {
            int count = varInt();
            if (count < 0 || count > MAX_LIST) throw new WireException("list of " + count + " items");
            List<String> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) out.add(string());
            return out;
        }
    }
}
