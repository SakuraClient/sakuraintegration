package com.sakuraclient.integration.record;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordsTest {

    private static final UUID PINK = UUID.fromString("c6e2afda-a785-38ad-94ec-646f43a3e2e8");

    /** Every field type comes back as it went in, and a string is written once. */
    @Test
    void recordsRoundTrip() throws Exception {
        Records.Buffer out = new Records.Buffer();
        Records.Palette strings = new Records.Palette();
        out.begin(Records.TICK).varInt(50).varInt(1).zigzag(-3).end();
        int stone = strings.of("minecraft:stone", out);
        assertEquals(stone, strings.of("minecraft:stone", out));
        out.begin(Records.BLOCK).zigzag(-1234567).zigzag(-64).zigzag(89).varInt(stone).end();
        out.begin(Records.SPAWN).varInt(7).uuid(PINK).varInt(stone).i64(-40960).i64(17).i64(Long.MIN_VALUE)
                .u8(192).u8(0).u8(255).i16(-32768).i16(0).i16(32767).u8(0).end();
        out.begin(Records.EXPLODE).f64(0.5).f64(-60.25).f64(1e9).f32(4.0F).end();
        out.begin(Records.CHAT).string("<Pink> ✿ hello").uuid(PINK).u8(0).end();

        RecordReader r = new RecordReader(new ByteArrayInputStream(out.take()));
        assertEquals(Records.TICK, r.next());
        assertEquals(50, r.varInt());
        assertEquals(1, r.varInt());
        assertEquals(-3, r.zigzag());
        assertEquals(Records.STRING, r.next());
        assertEquals(0, r.varInt());
        assertEquals("minecraft:stone", r.string());
        assertEquals(Records.BLOCK, r.next());
        assertEquals(-1234567, r.zigzag());
        assertEquals(-64, r.zigzag());
        assertEquals(89, r.zigzag());
        assertEquals(stone, r.varInt());
        assertEquals(Records.SPAWN, r.next());
        assertEquals(7, r.varInt());
        assertEquals(PINK, r.uuid());
        r.varInt();
        assertEquals(-40960, r.i64());
        assertEquals(17, r.i64());
        assertEquals(Long.MIN_VALUE, r.i64());
        assertEquals(192, r.u8());
        r.u8();
        assertEquals(255, r.u8());
        assertEquals(-32768, r.i16());
        r.i16();
        assertEquals(32767, r.i16());
        assertEquals(Records.EXPLODE, r.next());
        assertEquals(0.5, r.f64());
        assertEquals(-60.25, r.f64());
        assertEquals(1e9, r.f64());
        assertEquals(4.0F, r.f32());
        assertEquals(Records.CHAT, r.next());
        assertEquals("<Pink> ✿ hello", r.string());
        assertEquals(-1, r.next());
    }

    /** A kind a reader doesn't know is skipped by its length; a record cut short ends the stream, keeping what was whole. */
    @Test
    void unknownKindsAndCutRecords() throws Exception {
        Records.Buffer out = new Records.Buffer();
        out.begin(99).bytes(new byte[300]).end();
        out.begin(Records.REMOVE).varInt(42).end();
        out.begin(Records.REMOVE).varInt(43).end();
        byte[] all = out.take();
        RecordReader r = new RecordReader(new ByteArrayInputStream(Arrays.copyOf(all, all.length - 1)));
        assertEquals(99, r.next());
        assertEquals(Records.REMOVE, r.next());
        assertEquals(42, r.varInt());
        assertEquals(-1, r.next());
    }

    /** A recording's folder sealed into one file, its map named by its own hash; a leftover read back from its timeline. */
    @Test
    void sealAndRecover(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore(dir);
        String id = store.newId(1_794_152_780_682L);
        Path part = store.part(id);
        Files.createDirectories(part);
        JsonObject meta = new JsonObject();
        meta.addProperty("format", Records.FORMAT);
        meta.addProperty("label", "Arena check");
        JsonObject start = new JsonObject();
        start.addProperty("epochMs", 1_794_152_780_682L);
        meta.add("start", start);
        RecordingStore.writeMeta(part, meta);
        try (OutputStream o = RecordingStore.append(part.resolve(RecordingStore.TIMELINE))) {
            Records.Buffer b = new Records.Buffer();
            for (int i = 0; i < 40; i++) b.begin(Records.TICK).varInt(50).varInt(1).zigzag(1).end();
            b.begin(Records.PLAYER).uuid(PINK).string("Pink").string("").string("").u8(1).u8(1).end();
            b.writeTo(o);
        }
        Files.write(part.resolve(RecordingStore.MAP), new byte[]{1, 2, 3});

        assertEquals(Arrays.asList(id), store.leftovers());
        JsonObject recovered = store.recover(id);
        assertEquals(2000, recovered.get("durationMs").getAsLong());
        assertEquals(40, recovered.get("ticks").getAsInt());
        assertEquals("Pink", recovered.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString());

        RecordingStore.Info info = store.seal(id, recovered);
        assertFalse(Files.exists(part));
        assertEquals("Arena check", info.label);
        assertEquals(2000, info.durationMs);
        assertEquals("Pink", info.players.get(PINK));
        assertEquals(RecordingStore.sha256(info.file), info.sha256);
        try (ZipFile z = new ZipFile(info.file.toFile())) {
            String mapName = "map/" + "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81" + ".bin";
            assertNotNull(z.getEntry(mapName));
            assertNotNull(z.getEntry("timeline.bin"));
            assertNotNull(z.getEntry("meta.json"));
        }
        List<RecordingStore.Info> kept = store.list();
        assertEquals(1, kept.size());
        assertEquals(id, store.find(id).id);
        assertNull(store.find("2001-01-01_00-00-00"));
    }

    /** The newest are kept, and none older than the days asked. */
    @Test
    void prune(@TempDir Path dir) throws Exception {
        RecordingStore store = new RecordingStore(dir);
        long day = 86_400_000L, now = 100 * day;
        for (int i = 0; i < 5; i++) seal(store, "r" + i, now - i * day);
        assertEquals(5, store.list().size());
        assertEquals(2, store.prune(3, 0, now));
        assertEquals(Arrays.asList("r0", "r1", "r2"), ids(store.list()));
        assertEquals(1, store.prune(0, 1, now));
        assertEquals(Arrays.asList("r0", "r1"), ids(store.list()));
    }

    private static void seal(RecordingStore store, String id, long at) throws IOException {
        Files.createDirectories(store.part(id));
        JsonObject meta = new JsonObject();
        JsonObject start = new JsonObject();
        start.addProperty("epochMs", at);
        meta.add("start", start);
        store.seal(id, meta);
    }

    private static List<String> ids(List<RecordingStore.Info> list) {
        String[] out = new String[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).id;
        return Arrays.asList(out);
    }

    @Test
    void itemsGoInsideRecords() throws Exception {
        Records.Buffer out = new Records.Buffer();
        out.begin(Records.EQUIP).varInt(3).u8(0);
        Records.writeItem(out, new Records.Item(5, 64, true, 0xA06540, new byte[]{9, 8}));
        out.end();
        out.begin(Records.EQUIP).varInt(3).u8(5);
        Records.writeItem(out, Records.Item.EMPTY);
        out.end();
        RecordReader r = new RecordReader(new ByteArrayInputStream(out.take()));
        assertEquals(Records.EQUIP, r.next());
        assertEquals(3, r.varInt());
        assertEquals(0, r.u8());
        assertEquals(6, r.varInt());
        assertEquals(64, r.u8());
        assertEquals(1, r.u8());
        assertEquals(0xA06540, r.i32());
        assertArrayEquals(new byte[]{9, 8}, r.bytes(r.varInt()));
        assertEquals(Records.EQUIP, r.next());
        r.varInt();
        r.u8();
        assertEquals(0, r.varInt());
        assertTrue(r.next() < 0);
    }
}
