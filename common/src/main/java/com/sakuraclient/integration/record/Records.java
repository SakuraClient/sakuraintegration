package com.sakuraclient.integration.record;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A recording's two streams, {@code timeline.bin} and {@code map/<sha256>.bin}: a run of records, each
 * {@code u8 kind | VarInt length | payload}. A reader skips a kind it doesn't know by its length, so kinds are only
 * ever added. Numbers are big-endian; a string is a VarInt byte length and UTF-8; a uuid is two longs; a zigzag is
 * a signed VarLong. Every name in a recording (a block state, an entity type, an item, a biome, a damage cause) is
 * the recording server's own string, written once as a {@link #STRING} and then used by its index, so a client of
 * another version upgrades each string once.
 *
 * <p>The timeline (docs/integration.md §9.2): a {@link #TICK} opens each server tick, and every record after it
 * belongs to that tick, until the next. The map: a {@link #MAP_HEADER}, then a {@link #MAP_CHUNK} for each chunk
 * of the box as it was when it was taken.
 */
public final class Records {
    private Records() {}

    public static final int FORMAT = 1;

    // ── the timeline ────────────────────────────────────────────────────

    /** {@code VarInt msDelta} (ms since the last tick), {@code VarInt gameDelta} (game ticks since; 0 unknown), {@code zigzag dayDelta}. */
    public static final int TICK = 0;
    /** {@code VarLong gameTime} (−1 unknown), {@code VarLong dayTime}, {@code u8 weather} (0 clear, 1 rain, 2 thunder). */
    public static final int CLOCK = 1;
    /** {@code VarInt index}, {@code string}: the next string. */
    public static final int STRING = 2;
    /** {@code uuid}, {@code string name}, {@code string textures}, {@code string signature}, {@code u8 gameMode}, {@code u8 flags} (1 on Sakura). */
    public static final int PLAYER = 3;
    /** {@code uuid}: off the tab list. */
    public static final int PLAYER_GONE = 4;
    /** {@code uuid}, {@code u8 gameMode} (0 survival, 1 creative, 2 adventure, 3 spectator). */
    public static final int GAME_MODE = 5;
    /**
     * {@code VarInt id}, {@code uuid}, {@code VarInt type}, {@code i64 x y z} (1/4096 of a block), {@code u8 yaw pitch head}
     * (256ths of a turn), {@code i16 vx vy vz} (1/8000 of a block a tick), {@code u8 data}: 0 nothing, 1 an item
     * ({@link #writeItem}), 2 a block state ({@code VarInt state}).
     */
    public static final int SPAWN = 6;
    /**
     * {@code VarInt count}, then each {@code VarInt id}, {@code u8 mask} (1 position, 2 look, 4 head, 8 on the ground),
     * {@code zigzag dx dy dz} (1/4096) if 1, {@code u8 yaw pitch} if 2, {@code u8 head} if 4.
     */
    public static final int MOVE = 7;
    /** {@code VarInt id}. */
    public static final int REMOVE = 8;
    /**
     * {@code VarInt id}, {@code u8 flags} (the game's shared flags: 1 on fire, 2 crouching, 8 sprinting, 16 swimming,
     * 32 invisible, 64 glowing, 128 gliding), {@code VarInt pose} (the game's name: {@code standing}, {@code crouching},
     * …), {@code u8 living} (1 using an item, 2 the off hand, 4 a riptide), {@code f32 health} (NaN: not living).
     */
    public static final int STATE = 9;
    /** {@code VarInt id}, {@code u8 slot} (0 main hand, 1 off hand, 2 feet, 3 legs, 4 chest, 5 head), an item ({@link #writeItem}). */
    public static final int EQUIP = 10;
    /** {@code VarInt id}, {@code u8 hand} (0 main, 1 off). */
    public static final int SWING = 11;
    /** {@code VarInt id}, {@code VarInt cause} (the server's damage cause), {@code VarInt attacker + 1} (0 none). */
    public static final int HURT = 12;
    /** {@code VarInt id}, {@code VarInt killer + 1}, {@code string message} (the death message as players read it, legacy § text). */
    public static final int DEATH = 13;
    /** {@code zigzag x y z}, {@code VarInt state}: a block as it is now. */
    public static final int BLOCK = 14;
    /** {@code zigzag x y z}, {@code VarInt state} (the block that was), {@code VarInt breaker + 1}: broken by a player. */
    public static final int BREAK = 15;
    /** {@code f64 x y z}, {@code f32 power}. */
    public static final int EXPLODE = 16;
    /** {@code string text} (legacy § text), {@code uuid sender} (zero: the server), {@code u8 kind} (0 a player's chat, 1 a broadcast). */
    public static final int CHAT = 17;
    /** {@code string text}, {@code f64 x y z}. */
    public static final int MARKER = 18;

    /** Equipment slots of {@link #EQUIP}, by index. */
    public static final int SLOTS = 6;

    // ── the map ─────────────────────────────────────────────────────────

    /** {@code u8 format}, {@code i32 minY}, {@code i32 sections}, {@code u8 sky} (1: the world has sky light). */
    public static final int MAP_HEADER = 0;
    /**
     * {@code i32 cx cz}, {@code VarInt tick} (the timeline's tick it was taken at, 0 at the start), then each section from
     * the bottom: {@code VarInt n}, {@code n × VarInt state}, if {@code n > 1} 4096 indices into those ({@code u8} each
     * while {@code n ≤ 256}, else {@code u16}) in the game's order ({@code y·256 + z·16 + x}); sky light and block light,
     * each {@code u8 mode} (0 none, 1 one value: {@code u8}, 2 all: 2048 bytes, the game's nibbles); biomes,
     * {@code u8 mode} (1 one: {@code VarInt biome}, 2 all: 64 × {@code VarInt biome}, {@code y·16 + z·4 + x} by fours).
     */
    public static final int MAP_CHUNK = 1;
    // MAP strings are STRING (2), as in the timeline.

    // ── items ───────────────────────────────────────────────────────────

    /** A prepared item: its strings already have their indices, so it can be written inside a record. */
    public static final class Item {
        public static final Item EMPTY = new Item(-1, 0, false, -1, null);
        public final int key;
        public final int count;
        public final boolean glint;
        public final int colour;
        public final byte[] exact;

        public Item(int key, int count, boolean glint, int colour, byte[] exact) {
            this.key = key;
            this.count = count;
            this.glint = glint;
            this.colour = colour;
            this.exact = exact;
        }

        public boolean empty() {
            return key < 0;
        }
    }

    /**
     * {@code VarInt key + 1} (0 empty); then {@code u8 count}, {@code u8 flags} (1 glint), {@code i32 colour} (−1 none),
     * {@code VarInt n}, {@code n} bytes: the server's exact item where it gives one (Paper's {@code serializeAsBytes}).
     */
    public static void writeItem(Buffer out, Item item) {
        if (item.empty()) {
            out.varInt(0);
            return;
        }
        out.varInt(item.key + 1).u8(Math.max(0, Math.min(255, item.count))).u8(item.glint ? 1 : 0).i32(item.colour);
        byte[] b = item.exact == null ? new byte[0] : item.exact;
        out.varInt(b.length).bytes(b);
    }

    // ── writing ─────────────────────────────────────────────────────────

    /** Records as bytes: {@link #begin}, the payload, {@link #end}. One thread. */
    public static final class Buffer {
        private byte[] out = new byte[1 << 14];
        private int outLen;
        private byte[] rec = new byte[256];
        private int recLen;
        private int kind = -1;

        public Buffer begin(int kind) {
            if (this.kind >= 0) throw new IllegalStateException("record " + this.kind + " still open");
            this.kind = kind;
            this.recLen = 0;
            return this;
        }

        public void end() {
            if (kind < 0) throw new IllegalStateException("no record open");
            ensureOut(1 + 5 + recLen);
            out[outLen++] = (byte) kind;
            int v = recLen;
            while ((v & ~0x7F) != 0) {
                out[outLen++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out[outLen++] = (byte) v;
            System.arraycopy(rec, 0, out, outLen, recLen);
            outLen += recLen;
            kind = -1;
        }

        public int size() {
            return outLen;
        }

        /** The records so far, then empty. */
        public byte[] take() {
            byte[] b = Arrays.copyOf(out, outLen);
            outLen = 0;
            if (out.length > (1 << 20)) out = new byte[1 << 14];
            return b;
        }

        public void writeTo(OutputStream o) throws IOException {
            o.write(out, 0, outLen);
        }

        private void ensureOut(int more) {
            if (outLen + more > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, outLen + more));
        }

        private void ensure(int more) {
            if (recLen + more > rec.length) rec = Arrays.copyOf(rec, Math.max(rec.length * 2, recLen + more));
        }

        public Buffer u8(int v) {
            ensure(1);
            rec[recLen++] = (byte) v;
            return this;
        }

        public Buffer i16(int v) {
            ensure(2);
            rec[recLen++] = (byte) (v >>> 8);
            rec[recLen++] = (byte) v;
            return this;
        }

        public Buffer i32(int v) {
            ensure(4);
            for (int s = 24; s >= 0; s -= 8) rec[recLen++] = (byte) (v >>> s);
            return this;
        }

        public Buffer i64(long v) {
            ensure(8);
            for (int s = 56; s >= 0; s -= 8) rec[recLen++] = (byte) (v >>> s);
            return this;
        }

        public Buffer f32(float v) {
            return i32(Float.floatToIntBits(v));
        }

        public Buffer f64(double v) {
            return i64(Double.doubleToLongBits(v));
        }

        public Buffer varInt(int v) {
            ensure(5);
            while ((v & ~0x7F) != 0) {
                rec[recLen++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            rec[recLen++] = (byte) v;
            return this;
        }

        public Buffer varLong(long v) {
            ensure(10);
            while ((v & ~0x7FL) != 0) {
                rec[recLen++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            rec[recLen++] = (byte) v;
            return this;
        }

        public Buffer zigzag(long v) {
            return varLong((v << 1) ^ (v >> 63));
        }

        public Buffer string(String s) {
            byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            varInt(b.length);
            return bytes(b);
        }

        public Buffer uuid(UUID u) {
            return i64(u == null ? 0 : u.getMostSignificantBits()).i64(u == null ? 0 : u.getLeastSignificantBits());
        }

        public Buffer bytes(byte[] b) {
            ensure(b.length);
            System.arraycopy(b, 0, rec, recLen, b.length);
            recLen += b.length;
            return this;
        }

        public Buffer bytes(byte[] b, int off, int len) {
            ensure(len);
            System.arraycopy(b, off, rec, recLen, len);
            recLen += len;
            return this;
        }
    }

    /** A stream's strings: each written once, as a {@link #STRING} record, the first time it's used. One thread. */
    public static final class Palette {
        private final Map<String, Integer> index = new HashMap<>();

        /** The string's index, writing it into {@code out} first if it's new. Call between records, never inside one. */
        public int of(String s, Buffer out) {
            Integer i = index.get(s);
            if (i != null) return i;
            int n = index.size();
            index.put(s, n);
            out.begin(STRING).varInt(n).string(s).end();
            return n;
        }

        public int size() {
            return index.size();
        }
    }
}
