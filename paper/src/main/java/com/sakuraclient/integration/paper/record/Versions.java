package com.sakuraclient.integration.paper.record;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Keyed;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;

/**
 * What a recording reads that not every server version has (docs/integration.md §9.1). The plugin is built against
 * Paper 1.13.2's API and runs up to 26.x: anything newer is found once by reflection, and an older server gets the
 * nearest thing it has (a pose from the flags before 1.14, no game time before the API had it, no skins on Spigot).
 */
final class Versions {
    private Versions() {}

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            return owner.getMethod(name, args);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    private static final Method MIN_HEIGHT = method(World.class, "getMinHeight");
    private static final Method GAME_TIME = method(World.class, "getGameTime");
    private static final Method WORLD_TYPE = method(World.class, "getWorldType");
    private static final Method POSE = method(Entity.class, "getPose");
    private static final Method INVISIBLE = method(LivingEntity.class, "isInvisible");
    private static final Method SERIALIZE = method(ItemStack.class, "serializeAsBytes");
    private static final Method MC_VERSION = method(Bukkit.class, "getMinecraftVersion");
    private static final Method PROFILE = method(Player.class, "getPlayerProfile");
    private static final java.util.Map<Object, String> POSE_NAMES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Method SNAPSHOT_LIGHT = method(Chunk.class, "getChunkSnapshot", boolean.class, boolean.class, boolean.class, boolean.class);

    /** A chunk's blocks alone, for comparing: without its light where the server can leave it out (Paper 1.20 on), which is most of a snapshot's cost. */
    static ChunkSnapshot blocksOnly(Chunk c) {
        if (SNAPSHOT_LIGHT != null) {
            try {
                return (ChunkSnapshot) SNAPSHOT_LIGHT.invoke(c, false, false, false, false);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return c.getChunkSnapshot(false, false, false);
    }

    static int minHeight(World w) {
        try {
            return MIN_HEIGHT != null ? (Integer) MIN_HEIGHT.invoke(w) : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** The world's game time (the ticks it has run, what the game's time packets carry), or −1 where the API has none. */
    static long gameTime(World w) {
        try {
            return GAME_TIME != null ? (Long) GAME_TIME.invoke(w) : -1;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    static boolean flat(World w) {
        try {
            Object t = WORLD_TYPE != null ? WORLD_TYPE.invoke(w) : null;
            return t != null && "FLAT".equals(((Enum<?>) t).name());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The entity's type as the server names it ({@code minecraft:zombie_pigman} on 1.13), or null for one no client knows. */
    static String typeKey(Entity e) {
        if (e instanceof Player) return "minecraft:player";
        EntityType t = e.getType();
        if (t == null || t == EntityType.UNKNOWN) return null;
        Object o = t;
        if (o instanceof Keyed) return ((Keyed) o).getKey().toString();
        @SuppressWarnings("deprecation") String name = t.getName();
        return name == null ? null : name.contains(":") ? name : "minecraft:" + name;
    }

    static boolean invisible(LivingEntity e) {
        try {
            if (INVISIBLE != null) return (Boolean) INVISIBLE.invoke(e);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return e.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    /**
     * The entity's pose as the game names it ({@code standing}, {@code crouching}, {@code swimming}, …): the server's
     * own from 1.14, worked out from its flags before (crouching was a flag then).
     */
    static String pose(Entity e) {
        if (POSE != null) {
            try {
                Object p = POSE.invoke(e);
                if (p != null) {
                    String n = POSE_NAMES.get(p);
                    if (n == null) {
                        n = ((Enum<?>) p).name().toLowerCase(Locale.ROOT);
                        if ("sneaking".equals(n)) n = "crouching";
                        POSE_NAMES.put(p, n);
                    }
                    return n;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        if (e.isDead()) return "dying";
        if (e instanceof HumanEntity && ((HumanEntity) e).isSleeping()) return "sleeping";
        if (e instanceof LivingEntity) {
            LivingEntity le = (LivingEntity) e;
            if (le.isGliding()) return "fall_flying";
            if (le.isSwimming()) return "swimming";
            if (le.isRiptiding()) return "spin_attack";
        }
        if (e instanceof Player && ((Player) e).isSneaking()) return "crouching";
        return "standing";
    }

    /** The item exactly, as the server would save it (Paper's {@code serializeAsBytes}, 1.16.5 on), or null. */
    static byte[] exact(ItemStack s) {
        try {
            return SERIALIZE != null ? (byte[]) SERIALIZE.invoke(s) : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** {@code [value, signature]} of the player's skin, where the server has a profile with one (Paper); else empty. */
    static String[] textures(Player p) {
        try {
            if (PROFILE == null) return new String[]{"", ""};
            Object profile = PROFILE.invoke(p);
            Object props = profile.getClass().getMethod("getProperties").invoke(profile);
            if (props instanceof Collection) {
                for (Object prop : (Collection<?>) props) {
                    Class<?> c = prop.getClass();
                    if (!"textures".equals(c.getMethod("getName").invoke(prop))) continue;
                    Object value = c.getMethod("getValue").invoke(prop);
                    Object signature = c.getMethod("getSignature").invoke(prop);
                    return new String[]{value == null ? "" : value.toString(), signature == null ? "" : signature.toString()};
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Spigot's profile has no signature, or no profile at all: no skin
        }
        return new String[]{"", ""};
    }

    /** {@code 1.13.2}, {@code 26.1.2}. */
    static String minecraft() {
        try {
            if (MC_VERSION != null) return (String) MC_VERSION.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        String v = Bukkit.getBukkitVersion();
        int dash = v.indexOf('-');
        return dash > 0 ? v.substring(0, dash) : v;
    }

    @SuppressWarnings("deprecation")
    static int dataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /** The server's protocol number where the API says it (Paper 1.15 on), else −1. */
    static int protocol() {
        try {
            Object unsafe = Bukkit.getUnsafe();
            Method m = unsafe.getClass().getMethod("getProtocolVersion");
            return (Integer) m.invoke(unsafe);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
