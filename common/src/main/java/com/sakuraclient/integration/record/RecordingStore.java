package com.sakuraclient.integration.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Where a server keeps its recordings (docs/integration.md §9.4): a folder of sealed files, {@code <id>.recording},
 * each a zip of {@code meta.json}, {@code map/<sha256>.bin} and {@code timeline.bin}. A recording is written as a
 * folder, {@code <id>.part/}, and sealed into its file when it stops; a folder a crash left behind is sealed with what
 * it holds the next time the plugin starts.
 */
public final class RecordingStore {
    public static final String EXTENSION = ".recording";
    public static final String PART = ".part";
    public static final String TIMELINE = "timeline.bin";
    public static final String MAP = "map.bin";
    public static final String META = "meta.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A sealed recording, as the plugin offers it. */
    public static final class Info {
        public final String id;
        public final Path file;
        public final long size;
        public final long modified;
        public final String sha256;
        public final String label;
        public final long startedAt;
        public final long durationMs;
        /** Everyone in it, uuid to name, in the order they came. */
        public final Map<UUID, String> players;

        Info(String id, Path file, long size, long modified, String sha256, String label, long startedAt, long durationMs, Map<UUID, String> players) {
            this.id = id;
            this.file = file;
            this.size = size;
            this.modified = modified;
            this.sha256 = sha256;
            this.label = label;
            this.startedAt = startedAt;
            this.durationMs = durationMs;
            this.players = Collections.unmodifiableMap(players);
        }
    }

    private final Path folder;
    private final Map<Path, Info> cache = new HashMap<>();

    public RecordingStore(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    /** The folder a recording is written into while it runs. */
    public Path part(String id) {
        return folder.resolve(id + PART);
    }

    /** A new id from when it starts ({@code 2026-11-12_14-03-22}), made unique among what's kept. */
    public synchronized String newId(long epochMs) {
        String base = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(epochMs));
        String id = base;
        for (int n = 2; Files.exists(folder.resolve(id + EXTENSION)) || Files.exists(part(id)); n++) id = base + "_" + n;
        return id;
    }

    /** The recordings kept, newest first. */
    public synchronized List<Info> list() {
        List<Info> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        Map<Path, Info> seen = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "*" + EXTENSION)) {
            for (Path p : ds) {
                try {
                    long size = Files.size(p), modified = Files.getLastModifiedTime(p).toMillis();
                    Info i = cache.get(p);
                    if (i == null || i.size != size || i.modified != modified) i = read(p, size, modified);
                    seen.put(p, i);
                    out.add(i);
                } catch (IOException | RuntimeException e) {
                    // a file that isn't a recording (or is being replaced) isn't offered
                }
            }
        } catch (IOException e) {
            return out;
        }
        cache.clear();
        cache.putAll(seen);
        Collections.sort(out, new Comparator<Info>() {
            @Override public int compare(Info a, Info b) {
                return Long.compare(b.startedAt, a.startedAt);
            }
        });
        return out;
    }

    /** A kept recording by its id, or null. */
    public synchronized Info find(String id) {
        for (Info i : list()) if (i.id.equals(id)) return i;
        return null;
    }

    private static Info read(Path p, long size, long modified) throws IOException {
        String name = p.getFileName().toString();
        String id = name.substring(0, name.length() - EXTENSION.length());
        JsonObject meta;
        try (ZipFile zip = new ZipFile(p.toFile())) {
            ZipEntry e = zip.getEntry(META);
            if (e == null) throw new IOException("no " + META);
            try (InputStream in = zip.getInputStream(e)) {
                meta = new JsonParser().parse(new String(readAll(in), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
        Map<UUID, String> players = new LinkedHashMap<>();
        if (meta.has("players") && meta.get("players").isJsonArray()) {
            for (JsonElement el : meta.getAsJsonArray("players")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                try {
                    players.put(UUID.fromString(o.get("uuid").getAsString()), o.has("name") ? o.get("name").getAsString() : "");
                } catch (RuntimeException ignored) {
                }
            }
        }
        long started = meta.has("start") && meta.getAsJsonObject("start").has("epochMs") ? meta.getAsJsonObject("start").get("epochMs").getAsLong() : modified;
        return new Info(id, p, size, modified, sha256(p), meta.has("label") ? meta.get("label").getAsString() : id, started,
                meta.has("durationMs") ? meta.get("durationMs").getAsLong() : 0, players);
    }

    /**
     * Seals a recording's folder into {@code <id>.recording}: the meta (given; its {@code map} filled in here), the map
     * under its own SHA-256 and the timeline. The folder goes once the file is whole.
     */
    public Info seal(String id, JsonObject meta) throws IOException {
        Path dir = part(id);
        Path map = dir.resolve(MAP), timeline = dir.resolve(TIMELINE);
        if (!Files.exists(map)) Files.createFile(map);
        if (!Files.exists(timeline)) Files.createFile(timeline);
        String mapEntry = "map/" + sha256(map) + ".bin";
        meta.addProperty("map", mapEntry);
        Path out = folder.resolve(id + EXTENSION);
        Path tmp = folder.resolve(id + EXTENSION + ".tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            zip.putNextEntry(new ZipEntry(META));
            zip.write(GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(mapEntry));
            Files.copy(map, zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(TIMELINE));
            Files.copy(timeline, zip);
            zip.closeEntry();
        }
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteTree(dir);
        synchronized (this) {
            Info info = read(out, Files.size(out), Files.getLastModifiedTime(out).toMillis());
            cache.put(out, info); // hashed once, here, off the server's thread
            return info;
        }
    }

    /** Folders a crash left behind: recordings that never sealed. */
    public List<String> leftovers() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "*" + PART)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - PART.length()));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * A leftover folder's meta: what was written as it started, with its length and its players read back from the
     * timeline's whole records.
     */
    public JsonObject recover(String id) throws IOException {
        Path dir = part(id);
        JsonObject meta = new JsonObject();
        Path m = dir.resolve(META);
        if (Files.exists(m)) {
            try {
                meta = new JsonParser().parse(new String(Files.readAllBytes(m), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException ignored) {
            }
        }
        long ms = 0;
        int ticks = 0;
        Map<UUID, String> players = new LinkedHashMap<>();
        Path t = dir.resolve(TIMELINE);
        if (Files.exists(t)) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(t), 1 << 16)) {
                RecordReader r = new RecordReader(in);
                for (int kind; (kind = r.next()) >= 0; ) {
                    if (kind == Records.TICK) {
                        ms += r.varInt();
                        ticks++;
                    } else if (kind == Records.PLAYER) {
                        UUID u = r.uuid();
                        players.put(u, r.string());
                    }
                }
            } catch (IOException ignored) {
                // what was read is what there is
            }
        }
        meta.addProperty("durationMs", ms);
        meta.addProperty("ticks", ticks);
        meta.addProperty("recovered", true);
        JsonArray list = new JsonArray();
        for (Map.Entry<UUID, String> e : players.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.getKey().toString());
            o.addProperty("name", e.getValue());
            list.add(o);
        }
        meta.add("players", list);
        return meta;
    }

    /** Keeps the newest {@code keep} recordings and none older than {@code keepDays}; answers how many went. */
    public int prune(int keep, int keepDays, long now) {
        List<Info> all = list();
        int gone = 0;
        long oldest = keepDays > 0 ? now - keepDays * 86_400_000L : Long.MIN_VALUE;
        for (int i = 0; i < all.size(); i++) {
            Info info = all.get(i);
            if ((keep > 0 && i >= keep) || info.startedAt < oldest) {
                try {
                    Files.deleteIfExists(info.file);
                    gone++;
                } catch (IOException ignored) {
                }
            }
        }
        if (gone > 0) {
            synchronized (this) {
                for (Iterator<Path> it = cache.keySet().iterator(); it.hasNext(); ) if (!Files.exists(it.next())) it.remove();
            }
        }
        return gone;
    }

    /** Writes the meta a recording starts with, so a crash can still seal it. */
    public static void writeMeta(Path dir, JsonObject meta) throws IOException {
        Path tmp = dir.resolve(META + ".tmp");
        Files.write(tmp, GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, dir.resolve(META), StandardCopyOption.REPLACE_EXISTING);
    }

    public static String sha256(Path p) throws IOException {
        MessageDigest d;
        try {
            d = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(p)) {
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d.digest()) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return sb.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) deleteTree(p);
                else Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** For writing a stream into the part folder as it comes. */
    public static OutputStream append(Path file) throws IOException {
        return new BufferedOutputStream(Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), 1 << 16);
    }
}
