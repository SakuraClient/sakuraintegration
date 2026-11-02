package com.sakuraclient.integration.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * {@code /sakura verify <address>}: proves to Sakura that the address leads
 * to this server.
 *
 * <p>Sakura's API hands out a short code and a token; for up to two minutes
 * this server adds {@code sakura:<code>} to the version name in its status replies
 * ({@link #versionSuffix()}, which the platform's ping listener asks); the
 * API then pings the address the way a player's game would and looks for the
 * code. Players whose game version matches the server never see the version
 * name, so nobody notices.
 *
 * <p>The first address verified makes this server known to Sakura: the API
 * answers with an id and a key, kept in {@code credentials.json} in the
 * plugin's folder, so later addresses join the same server. The key never
 * leaves that file except to Sakura's API.
 *
 * <p>This is the only time the plugin talks to Sakura. {@link #verify}
 * blocks for a few seconds: call it off the server's main thread.
 */
public final class Verifier {

    private static final String API = System.getProperty("sakura.integration.api", "https://api.sakuraclient.com");
    private static final int TIMEOUT_MS = 30_000;
    private static final Gson GSON = new Gson();

    private final Path credentialsFile;
    private final String platform;
    private final String version;
    private final Consumer<String> log;

    private volatile String challenge;
    private volatile long challengeUntil;
    private volatile boolean running;

    public Verifier(Path dataFolder, String platform, String version, Consumer<String> log) {
        this.credentialsFile = dataFolder.resolve("credentials.json");
        this.platform = platform;
        this.version = version;
        this.log = log;
    }

    /** What to add to the status reply's version name right now, or null. Any thread. */
    public String versionSuffix() {
        String c = challenge;
        if (c == null || System.currentTimeMillis() > challengeUntil) return null;
        return " sakura:" + c;
    }

    /** The addresses Sakura has verified for this server, as last heard. */
    public List<String> addresses() {
        Credentials c = Credentials.load(credentialsFile);
        return c == null ? Collections.<String>emptyList() : c.addresses;
    }

    /** Verifies {@code address}, telling {@code out} what happens. Blocks: call it off the main thread. */
    public void verify(String address, Consumer<String> out) {
        String target = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (target.isEmpty()) {
            out.accept("Give the address players type, like /sakura verify play.example.com");
            return;
        }
        synchronized (this) {
            if (running) {
                out.accept("A verification is already running. Wait for it to finish.");
                return;
            }
            running = true;
        }
        try {
            out.accept("Verifying " + target + "…");
            Credentials creds = Credentials.load(credentialsFile);

            JsonObject start = new JsonObject();
            start.addProperty("address", target);
            start.addProperty("platform", platform);
            start.addProperty("version", version);
            if (creds != null) {
                start.addProperty("server_id", creds.serverId);
                start.addProperty("key", creds.key);
            }
            Response started = post("/api/integration/verify/start", start);
            if (!started.ok()) {
                out.accept(explain(started, target));
                return;
            }
            String code = started.body.get("challenge").getAsString();
            // Anyone can read the code in our status replies; only we have the token that finishes it.
            String token = started.body.get("token").getAsString();
            long ttlMs = started.body.has("expires_in") ? started.body.get("expires_in").getAsLong() * 1000L : 120_000L;
            challengeUntil = System.currentTimeMillis() + ttlMs;
            challenge = code;

            JsonObject finish = new JsonObject();
            finish.addProperty("challenge", code);
            finish.addProperty("token", token);
            Response done = post("/api/integration/verify/finish", finish);
            challenge = null;
            if (!done.ok()) {
                out.accept(explain(done, target));
                return;
            }

            Credentials saved = creds;
            if (done.body.has("key") && !done.body.get("key").isJsonNull()) {
                saved = new Credentials(done.body.get("server_id").getAsString(), done.body.get("key").getAsString(),
                        Collections.<String>emptyList());
            }
            if (saved == null) {
                out.accept("Sakura verified " + target + " but sent no credentials. Try again.");
                return;
            }
            List<String> addresses = new ArrayList<>();
            JsonElement list = done.body.get("addresses");
            if (list != null && list.isJsonArray()) {
                for (JsonElement e : (JsonArray) list) {
                    if (!e.isJsonObject()) continue;
                    JsonObject a = e.getAsJsonObject();
                    int port = a.has("port") && !a.get("port").isJsonNull() ? a.get("port").getAsInt() : 25565;
                    // As a player types it: the port only when it isn't the default.
                    addresses.add(a.get("host").getAsString() + (port == 25565 ? "" : ":" + port));
                }
            }
            saved = new Credentials(saved.serverId, saved.key, addresses);
            saved.save(credentialsFile);
            log.accept("Verified " + target + "; this server's Sakura credentials are in " + credentialsFile.getFileName());
            out.accept(target + " is verified for this server.");
            if (addresses.size() > 1) out.accept("Verified addresses: " + String.join(", ", addresses) + ".");
        } catch (IOException e) {
            out.accept("Sakura's API didn't answer (" + e.getMessage() + "). Try again in a minute.");
        } catch (RuntimeException e) {
            out.accept("Sakura's API sent something unexpected. Try again in a minute.");
            log.accept("Verification failed: " + e);
        } finally {
            challenge = null;
            running = false;
        }
    }

    private static String explain(Response r, String target) {
        String detail = r.body != null && r.body.has("detail") && r.body.get("detail").isJsonPrimitive()
                ? r.body.get("detail").getAsString() : "";
        if (r.status == 429) return "Too many tries from this server. Try again in an hour.";
        if (r.status == 401) {
            return "Sakura refused this server's credentials.json. Delete it to verify as a new server.";
        }
        switch (detail) {
            case "bad_address":
                return target + " can't be verified: use a public domain name, like play.example.com.";
            case "unreachable":
                return "Sakura couldn't reach " + target + ". Check that it works from outside your network.";
            case "code_missing":
                return target + " answered, but not from this server. Run this where the address points: "
                        + "behind Velocity, that's the proxy.";
            case "expired":
                return "That took too long. Try again.";
            default:
                return "Sakura's API refused (" + r.status + (detail.isEmpty() ? "" : ", " + detail) + "). Try again later.";
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private static final class Response {
        final int status;
        final JsonObject body;

        Response(int status, JsonObject body) {
            this.status = status;
            this.body = body;
        }

        boolean ok() {
            return status >= 200 && status < 300 && body != null;
        }
    }

    private Response post(String path, JsonObject json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "SakuraIntegration/" + version + " (" + platform + ")");
        byte[] payload = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        JsonObject body = null;
        try {
            JsonElement e = new JsonParser().parse(text);
            if (e.isJsonObject()) body = e.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // Not JSON (a proxy's error page): the status says enough.
        }
        return new Response(status, body);
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ── credentials.json ─────────────────────────────────────────────────

    private static final class Credentials {
        final String serverId;
        final String key;
        final List<String> addresses;

        Credentials(String serverId, String key, List<String> addresses) {
            this.serverId = serverId;
            this.key = key;
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
        }

        static Credentials load(Path file) {
            if (!Files.isRegularFile(file)) return null;
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject o = new JsonParser().parse(r).getAsJsonObject();
                List<String> addresses = new ArrayList<>();
                if (o.has("addresses")) {
                    for (JsonElement e : o.getAsJsonArray("addresses")) addresses.add(e.getAsString());
                }
                return new Credentials(o.get("server_id").getAsString(), o.get("key").getAsString(), addresses);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        void save(Path file) throws IOException {
            JsonObject o = new JsonObject();
            o.addProperty("note", "This server's identity with Sakura. Keep it private: anyone with the key "
                    + "can change which addresses Sakura has verified for this server.");
            o.addProperty("server_id", serverId);
            o.addProperty("key", key);
            JsonArray list = new JsonArray();
            for (String a : addresses) list.add(a);
            o.add("addresses", list);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
