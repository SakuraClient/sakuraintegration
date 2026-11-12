package com.sakuraclient.integration.record;

import com.sakuraclient.integration.wire.Wire;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recordings going to players (docs/integration.md §9.5): the server offers a recording to a player
 * ({@link Wire.RecordingOffer}), and the player's game pulls it in windows ({@link Wire.RecordingGet}), which go out
 * as parts of at most {@link Wire#MAX_BYTES} ({@link Wire.RecordingPart}), each player at most
 * {@code kibPerSecond}. Only what was offered to that player is sent; anything else is refused. A window asked again
 * replaces the one waiting, so a game that resumes from where it is never gets a part twice.
 *
 * <p>{@link #offer} and {@link #get} come from the server's thread; {@link #tick} from one timer thread of its own,
 * which reads the files.
 */
public final class Downloads {
    /** How a platform sends a message to a player; false if they're gone. */
    public interface Sender {
        boolean send(UUID player, byte[] message);
    }

    /** The most a get asks for at once. */
    public static final long MAX_WINDOW = 4L << 20;

    private final RecordingStore store;
    private final Sender sender;
    private volatile int bytesPerTick;
    private final Map<UUID, Peer> peers = new ConcurrentHashMap<>();

    private static final class Peer {
        /** What this player was offered, by id: a request is answered from it, never by looking through the folder. */
        final Map<String, RecordingStore.Info> offered = new ConcurrentHashMap<>();
        final ArrayDeque<Request> queue = new ArrayDeque<>();
        double tokens;
    }

    private static final class Request {
        final String id;
        final Path file;
        long offset;
        final long end;

        Request(String id, Path file, long offset, long end) {
            this.id = id;
            this.file = file;
            this.offset = offset;
            this.end = end;
        }
    }

    public Downloads(RecordingStore store, Sender sender, int kibPerSecond) {
        this.store = store;
        this.sender = sender;
        rate(kibPerSecond);
    }

    public void rate(int kibPerSecond) {
        this.bytesPerTick = Math.max(256, kibPerSecond * 1024 / 20);
    }

    /** Offers a recording to a player (whose game said it takes recordings). */
    public void offer(UUID player, RecordingStore.Info info) {
        Peer p = peers.computeIfAbsent(player, k -> new Peer());
        p.offered.put(info.id, info);
        List<String> names = new ArrayList<>();
        for (String n : info.players.values()) {
            if (names.size() >= Wire.MAX_LIST) break;
            if (n != null && !n.isEmpty() && n.length() <= 64) names.add(n);
        }
        String label = info.label.length() > 64 ? info.label.substring(0, 64) : info.label;
        sender.send(player, Wire.encode(new Wire.RecordingOffer(info.id, label, info.size, info.sha256, info.durationMs, info.startedAt, names)));
    }

    /** Whether this player was offered the recording. */
    public boolean offered(UUID player, String id) {
        Peer p = peers.get(player);
        return p != null && p.offered.containsKey(id);
    }

    /** A player's game asks for part of a recording. */
    public void get(UUID player, Wire.RecordingGet g) {
        Peer p = peers.get(player);
        RecordingStore.Info info = p == null || !Wire.isRecordingId(g.recording) ? null : p.offered.get(g.recording);
        if (info == null) {
            sender.send(player, Wire.encode(new Wire.RecordingRefused(g.recording, Wire.REFUSED_NOT_OFFERED)));
            return;
        }
        if (!Files.isRegularFile(info.file)) {
            p.offered.remove(g.recording);
            sender.send(player, Wire.encode(new Wire.RecordingRefused(g.recording, Wire.REFUSED_GONE)));
            return;
        }
        long offset = Math.max(0, Math.min(g.offset, info.size));
        long end = Math.min(info.size, offset + Math.max(0, Math.min(g.length, MAX_WINDOW)));
        synchronized (p) {
            for (Iterator<Request> it = p.queue.iterator(); it.hasNext(); ) if (it.next().id.equals(g.recording)) it.remove();
            if (end > offset) p.queue.add(new Request(g.recording, info.file, offset, end));
        }
    }

    /** The player left: what they were offered goes with them (their game resumes when it's offered again). */
    public void left(UUID player) {
        peers.remove(player);
    }

    /** One tick of sending: each player's parts, at most their share of the rate. */
    public void tick() {
        int perTick = bytesPerTick;
        for (Map.Entry<UUID, Peer> e : peers.entrySet()) {
            Peer p = e.getValue();
            synchronized (p) {
                if (p.queue.isEmpty()) {
                    p.tokens = 0;
                    continue;
                }
                p.tokens = Math.min(p.tokens + perTick, Math.max(2.0 * perTick, Wire.MAX_BYTES));
                while (!p.queue.isEmpty()) {
                    Request r = p.queue.peek();
                    int n = (int) Math.min(Wire.MAX_BYTES, r.end - r.offset);
                    if (n <= 0) {
                        p.queue.poll();
                        continue;
                    }
                    if (p.tokens < n) break;
                    byte[] data;
                    try {
                        data = read(r.file, r.offset, n);
                    } catch (IOException ex) {
                        p.queue.poll();
                        sender.send(e.getKey(), Wire.encode(new Wire.RecordingRefused(r.id, Wire.REFUSED_GONE)));
                        continue;
                    }
                    if (!sender.send(e.getKey(), Wire.encode(new Wire.RecordingPart(r.id, r.offset, data)))) {
                        p.queue.clear();
                        break;
                    }
                    r.offset += data.length;
                    p.tokens -= data.length;
                    if (r.offset >= r.end) p.queue.poll();
                }
            }
        }
    }

    private static byte[] read(Path file, long offset, int n) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(n);
            while (buf.hasRemaining()) {
                int r = ch.read(buf, offset + buf.position());
                if (r < 0) break;
            }
            byte[] out = new byte[buf.position()];
            buf.flip();
            buf.get(out);
            if (out.length == 0) throw new IOException("past the end");
            return out;
        }
    }
}
