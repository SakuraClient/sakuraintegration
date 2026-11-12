package com.sakuraclient.integration.paper.record;

import com.sakuraclient.integration.record.Records;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Keyed;
import org.bukkit.block.data.BlockData;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The arena's blocks, as {@code map.bin} (docs/integration.md §9.2): each chunk the recording takes, encoded off the
 * server's thread from its {@link ChunkSnapshot} (blocks as the server's own state strings, sky and block light,
 * biomes). Also compares two snapshots of a chunk, for the changes no event told of. The recording's I/O thread only.
 */
final class MapEncoder {
    private final Records.Buffer out = new Records.Buffer();
    private final Records.Palette strings = new Records.Palette();
    private final Map<BlockData, Integer> states = new HashMap<>();
    private final Map<Object, Integer> biomes = new HashMap<>();
    private final OutputStream file;
    private final int minY;
    private final int sections;
    private final boolean sky;
    int chunks;

    /** {@code getBiome(x, y, z)}, where the server has biomes in three dimensions (1.15 and later). */
    private static final Method BIOME_3D = method(ChunkSnapshot.class, "getBiome", int.class, int.class, int.class);

    MapEncoder(OutputStream file, int minY, int sections, boolean sky) throws IOException {
        this.file = file;
        this.minY = minY;
        this.sections = sections;
        this.sky = sky;
        out.begin(Records.MAP_HEADER).u8(Records.FORMAT).i32(minY).i32(sections).u8(sky ? 1 : 0).end();
        flush();
    }

    static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            return owner.getMethod(name, args);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /** One chunk as it was at {@code tick}. */
    void chunk(ChunkSnapshot s, int tick) throws IOException {
        int[][] palettes = new int[sections][];
        short[][] indices = new short[sections][];
        byte[][] skyLight = new byte[sections][];
        byte[][] blockLight = new byte[sections][];
        int[] skyUniform = new int[sections], blockUniform = new int[sections];
        int[][] biomeIds = new int[sections][];
        Map<BlockData, Integer> local = new HashMap<>();
        List<BlockData> order = new ArrayList<>();
        for (int si = 0; si < sections; si++) {
            int y0 = minY + si * 16;
            local.clear();
            order.clear();
            short[] idx = null;
            boolean empty = false;
            try {
                empty = s.isSectionEmpty(si);
            } catch (RuntimeException ignored) {
            }
            if (empty) {
                palettes[si] = new int[]{stateIndex(AIR)};
            } else {
                idx = new short[4096];
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockData d = s.getBlockData(x, y0 + y, z);
                            Integer i = local.get(d);
                            if (i == null) {
                                i = order.size();
                                local.put(d, i);
                                order.add(d);
                            }
                            idx[(y << 8) | (z << 4) | x] = (short) (int) i;
                        }
                    }
                }
                int[] pal = new int[order.size()];
                for (int i = 0; i < pal.length; i++) pal[i] = stateIndex(order.get(i));
                palettes[si] = pal;
                if (pal.length == 1) idx = null;
            }
            indices[si] = idx;
            skyUniform[si] = sky ? light(s, y0, true, skyLight, si) : -2;
            blockUniform[si] = light(s, y0, false, blockLight, si);
            biomeIds[si] = biomes(s, y0);
        }
        out.begin(Records.MAP_CHUNK).i32(s.getX()).i32(s.getZ()).varInt(tick);
        for (int si = 0; si < sections; si++) {
            int[] pal = palettes[si];
            out.varInt(pal.length);
            for (int p : pal) out.varInt(p);
            if (pal.length > 1) {
                short[] idx = indices[si];
                if (pal.length <= 256) {
                    byte[] b = new byte[4096];
                    for (int i = 0; i < 4096; i++) b[i] = (byte) idx[i];
                    out.bytes(b);
                } else {
                    for (int i = 0; i < 4096; i++) out.i16(idx[i]);
                }
            }
            lightOut(skyUniform[si], skyLight[si]);
            lightOut(blockUniform[si], blockLight[si]);
            int[] bio = biomeIds[si];
            boolean one = true;
            for (int b : bio) if (b != bio[0]) one = false;
            if (one) out.u8(1).varInt(bio[0]);
            else {
                out.u8(2);
                for (int b : bio) out.varInt(b);
            }
        }
        out.end();
        chunks++;
        flush();
    }

    private void lightOut(int uniform, byte[] all) {
        if (uniform == -2) out.u8(0);
        else if (uniform >= 0) out.u8(1).u8(uniform);
        else out.u8(2).bytes(all);
    }

    /** A section's light: its one value, or −1 with every value packed into {@code all[si]} as the game's nibbles. */
    private static int light(ChunkSnapshot s, int y0, boolean sky, byte[][] all, int si) {
        byte[] packed = new byte[2048];
        int first = -1;
        boolean uniform = true;
        for (int i = 0; i < 4096; i++) {
            int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            int v = sky ? s.getBlockSkyLight(x, y0 + y, z) : s.getBlockEmittedLight(x, y0 + y, z);
            v &= 15;
            if (first < 0) first = v;
            else if (v != first) uniform = false;
            packed[i >> 1] |= (byte) ((i & 1) == 0 ? v : v << 4);
        }
        if (uniform) return first;
        all[si] = packed;
        return -1;
    }

    /** The section's 64 biomes, by fours ({@code y·16 + z·4 + x}). */
    private int[] biomes(ChunkSnapshot s, int y0) {
        int[] ids = new int[64];
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Object b = null;
                    try {
                        b = BIOME_3D != null ? BIOME_3D.invoke(s, x * 4, y0 + y * 4, z * 4) : s.getBiome(x * 4, z * 4);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        // a snapshot taken without biomes
                    }
                    ids[(y << 4) | (z << 2) | x] = biomeIndex(b);
                }
            }
        }
        return ids;
    }

    private int biomeIndex(Object b) {
        if (b == null) return strings.of("minecraft:plains", out);
        Integer i = biomes.get(b);
        if (i != null) return i;
        String key;
        if (b instanceof Keyed) key = ((Keyed) b).getKey().toString();
        else key = "minecraft:" + ((Enum<?>) b).name().toLowerCase(Locale.ROOT);
        int n = strings.of(key, out);
        biomes.put(b, n);
        return n;
    }

    private static final Object AIR = new Object();

    private int stateIndex(Object d) {
        if (d == AIR) return strings.of("minecraft:air", out);
        BlockData data = (BlockData) d;
        Integer i = states.get(data);
        if (i != null) return i;
        int n = strings.of(data.getAsString(), out);
        states.put(data, n);
        return n;
    }

    private void flush() throws IOException {
        out.writeTo(file);
        out.take();
        file.flush();
    }

    void close() throws IOException {
        file.close();
    }

    /** A block that differs between two snapshots of a chunk: where, and what it is in the later one. */
    static final class Diff {
        final int x, y, z;
        final BlockData now;

        Diff(int x, int y, int z, BlockData now) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.now = now;
        }
    }

    /** Every block that differs between {@code before} and {@code now} (one chunk), sections both hold only air skipped. */
    static List<Diff> diff(ChunkSnapshot before, ChunkSnapshot now, int minY, int sections) {
        List<Diff> out = new ArrayList<>();
        int bx = now.getX() << 4, bz = now.getZ() << 4;
        for (int si = 0; si < sections; si++) {
            try {
                if (before.isSectionEmpty(si) && now.isSectionEmpty(si)) continue;
            } catch (RuntimeException ignored) {
            }
            int y0 = minY + si * 16;
            for (int y = y0; y < y0 + 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockData a = before.getBlockData(x, y, z), b = now.getBlockData(x, y, z);
                        if (!a.equals(b)) out.add(new Diff(bx + x, y, bz + z, b));
                    }
                }
            }
        }
        return out;
    }
}
