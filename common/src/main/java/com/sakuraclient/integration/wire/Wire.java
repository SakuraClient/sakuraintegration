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
 * count (at most {@link #MAX_LIST}) and its items; a long is a VarLong; bytes
 * are a VarInt length (at most {@link #MAX_BYTES}) and the bytes. Fields are
 * only ever appended, and a reader ignores bytes after the ones it knows and
 * takes a trailing field that isn't there as empty, so either side can be
 * newer.
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
    /** The most bytes one {@link RecordingPart} carries. */
    public static final int MAX_BYTES = 30_000;

    /** The server the player is on. */
    public static final int SCOPE_SERVER = 0;
    /** The network: the proxy in front of the servers. */
    public static final int SCOPE_NETWORK = 1;

    /** A {@link ClientHello} feature: the client takes the server's recordings ({@link RecordingOffer}). */
    public static final String FEATURE_RECORDINGS = "recordings";

    /** {@link RecordingRefused}: the id isn't a recording offered to this player. */
    public static final String REFUSED_NOT_OFFERED = "not_offered";
    /** {@link RecordingRefused}: it was offered, but the server no longer keeps it. */
    public static final String REFUSED_GONE = "gone";

    public static final int HELLO = 0;
    public static final int CLIENT_HELLO = 1;
    public static final int DISABLE_MODS = 2;
    public static final int RECORDING_OFFER = 3;
    public static final int RECORDING_GET = 4;
    public static final int RECORDING_PART = 5;
    public static final int RECORDING_REFUSED = 6;

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
        /** What else the client takes ({@link #FEATURE_RECORDINGS}); empty from a client that doesn't say. */
        public final List<String> features;

        public ClientHello(int protocol, int scope, String client, Collection<String> mods) {
            this(protocol, scope, client, mods, Collections.<String>emptyList());
        }

        public ClientHello(int protocol, int scope, String client, Collection<String> mods, Collection<String> features) {
            this.protocol = protocol;
            this.scope = scope;
            this.client = client;
            this.mods = Collections.unmodifiableList(new ArrayList<>(mods));
            this.features = Collections.unmodifiableList(new ArrayList<>(features));
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

    /**
     * Server → client: a recording the server made that this player may take, once it has stopped
     * ({@link #isRecordingId its id}). The client pulls it with {@link RecordingGet}.
     */
    public static final class RecordingOffer implements Message {
        public final String recording;
        public final String label;
        /** The file's size in bytes. */
        public final long size;
        /** The file's SHA-256, lower-case hex. */
        public final String sha256;
        public final long durationMs;
        /** When it started, epoch ms (the server's clock). */
        public final long startedAt;
        /** The players in it, by name. */
        public final List<String> players;

        public RecordingOffer(String recording, String label, long size, String sha256, long durationMs, long startedAt, Collection<String> players) {
            this.recording = recording;
            this.label = label;
            this.size = size;
            this.sha256 = sha256;
            this.durationMs = durationMs;
            this.startedAt = startedAt;
            this.players = Collections.unmodifiableList(new ArrayList<>(players));
        }

        @Override public int id() { return RECORDING_OFFER; }
    }

    /** Client → server: the bytes of an offered recording from {@code offset}, at most {@code length} of them. */
    public static final class RecordingGet implements Message {
        public final String recording;
        public final long offset;
        public final long length;

        public RecordingGet(String recording, long offset, long length) {
            this.recording = recording;
            this.offset = offset;
            this.length = length;
        }

        @Override public int id() { return RECORDING_GET; }
    }

    /** Server → client: a recording's bytes at {@code offset} (at most {@link #MAX_BYTES}). */
    public static final class RecordingPart implements Message {
        public final String recording;
        public final long offset;
        public final byte[] data;

        public RecordingPart(String recording, long offset, byte[] data) {
            this.recording = recording;
            this.offset = offset;
            this.data = data;
        }

        @Override public int id() { return RECORDING_PART; }
    }

    /** Server → client: a {@link RecordingGet} the server won't answer ({@link #REFUSED_NOT_OFFERED}, {@link #REFUSED_GONE}). */
    public static final class RecordingRefused implements Message {
        public final String recording;
        public final String reason;

        public RecordingRefused(String recording, String reason) {
            this.recording = recording;
            this.reason = reason;
        }

        @Override public int id() { return RECORDING_REFUSED; }
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

    /** True if {@code id} could be a recording's id: 1–64 of {@code A-Z}, {@code a-z}, {@code 0-9}, {@code _} and {@code -}. */
    public static boolean isRecordingId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
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
            writeList(out, h.features);
        } else if (m instanceof DisableMods) {
            DisableMods d = (DisableMods) m;
            out.write(d.scope);
            writeList(out, d.ids);
        } else if (m instanceof RecordingOffer) {
            RecordingOffer o = (RecordingOffer) m;
            writeString(out, o.recording);
            writeString(out, o.label);
            writeVarLong(out, o.size);
            writeString(out, o.sha256);
            writeVarLong(out, o.durationMs);
            writeVarLong(out, o.startedAt);
            writeList(out, o.players);
        } else if (m instanceof RecordingGet) {
            RecordingGet g = (RecordingGet) m;
            writeString(out, g.recording);
            writeVarLong(out, g.offset);
            writeVarLong(out, g.length);
        } else if (m instanceof RecordingPart) {
            RecordingPart p = (RecordingPart) m;
            writeString(out, p.recording);
            writeVarLong(out, p.offset);
            writeBytes(out, p.data);
        } else if (m instanceof RecordingRefused) {
            RecordingRefused r = (RecordingRefused) m;
            writeString(out, r.recording);
            writeString(out, r.reason);
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

    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
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

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) {
        if (b.length > MAX_BYTES) throw new IllegalArgumentException("bytes over " + MAX_BYTES);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
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
            case CLIENT_HELLO: {
                int protocol = r.varInt();
                int scope = r.u8();
                String client = r.string();
                List<String> mods = r.list();
                // Clients before features say nothing more.
                List<String> features = r.more() ? r.list() : Collections.<String>emptyList();
                return new ClientHello(protocol, scope, client, mods, features);
            }
            case DISABLE_MODS:
                return new DisableMods(r.u8(), r.list());
            case RECORDING_OFFER:
                return new RecordingOffer(r.string(), r.string(), r.varLong(), r.string(), r.varLong(), r.varLong(), r.list());
            case RECORDING_GET:
                return new RecordingGet(r.string(), r.varLong(), r.varLong());
            case RECORDING_PART:
                return new RecordingPart(r.string(), r.varLong(), r.bytes());
            case RECORDING_REFUSED:
                return new RecordingRefused(r.string(), r.string());
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

        boolean more() {
            return pos < data.length;
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

        long varLong() throws WireException {
            long value = 0;
            for (int shift = 0; shift < 70; shift += 7) {
                int b = u8();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new WireException("VarLong too long");
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

        byte[] bytes() throws WireException {
            int length = varInt();
            if (length < 0 || length > MAX_BYTES) throw new WireException(length + " bytes");
            if (data.length - pos < length) throw new WireException("message ends early");
            byte[] b = new byte[length];
            System.arraycopy(data, pos, b, 0, length);
            pos += length;
            return b;
        }
    }
}
