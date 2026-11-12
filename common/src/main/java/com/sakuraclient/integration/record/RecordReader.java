package com.sakuraclient.integration.record;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Reads the records {@link Records.Buffer} writes, one at a time: {@link #next} gives the next kind (−1 at the end,
 * or where the stream was cut short: a recording a crash ended keeps every whole record), then the payload's
 * fields in order. Whatever of a payload isn't read is skipped by the next {@link #next}.
 */
public final class RecordReader {
    private final DataInputStream in;
    private byte[] payload = new byte[256];
    private int len;
    private int pos;

    public RecordReader(InputStream in) {
        this.in = in instanceof DataInputStream ? (DataInputStream) in : new DataInputStream(in);
    }

    /** The next record's kind, or −1 when there are no more whole records. */
    public int next() throws IOException {
        int kind = in.read();
        if (kind < 0) return -1;
        int length;
        try {
            length = streamVarInt();
        } catch (EOFException e) {
            return -1;
        }
        if (length < 0 || length > (64 << 20)) throw new IOException("a record of " + length + " bytes");
        if (payload.length < length) payload = new byte[Math.max(length, payload.length * 2)];
        int read = 0;
        while (read < length) {
            int n = in.read(payload, read, length - read);
            if (n < 0) return -1; // cut short: keep what was whole
            read += n;
        }
        len = length;
        pos = 0;
        return kind;
    }

    private int streamVarInt() throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.read();
            if (b < 0) throw new EOFException();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("VarInt too long");
    }

    public int remaining() {
        return len - pos;
    }

    private void need(int n) throws IOException {
        if (len - pos < n) throw new IOException("a record ends early");
    }

    public int u8() throws IOException {
        need(1);
        return payload[pos++] & 0xFF;
    }

    public int i16() throws IOException {
        need(2);
        int v = (short) (((payload[pos] & 0xFF) << 8) | (payload[pos + 1] & 0xFF));
        pos += 2;
        return v;
    }

    public int i32() throws IOException {
        need(4);
        int v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | (payload[pos++] & 0xFF);
        return v;
    }

    public long i64() throws IOException {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (payload[pos++] & 0xFF);
        return v;
    }

    public float f32() throws IOException {
        return Float.intBitsToFloat(i32());
    }

    public double f64() throws IOException {
        return Double.longBitsToDouble(i64());
    }

    public int varInt() throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = u8();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("VarInt too long");
    }

    public long varLong() throws IOException {
        long v = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            int b = u8();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("VarLong too long");
    }

    public long zigzag() throws IOException {
        long v = varLong();
        return (v >>> 1) ^ -(v & 1);
    }

    public String string() throws IOException {
        int n = varInt();
        need(n);
        String s = new String(payload, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public UUID uuid() throws IOException {
        return new UUID(i64(), i64());
    }

    public byte[] bytes(int n) throws IOException {
        need(n);
        byte[] b = new byte[n];
        System.arraycopy(payload, pos, b, 0, n);
        pos += n;
        return b;
    }
}
