package com.sakuraclient.integration.paper.record;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.List;

/**
 * The events a recording needs (docs/integration.md §9.1): blocks that may have changed (each is read at the next
 * tick, so a change another plugin cancelled records nothing), and what only an event says: swings, hurts, deaths,
 * a block a player broke, explosions, chat. Each goes to the recordings of its world. Monitor priority: after every
 * other plugin has decided.
 */
final class RecordListener implements Listener {
    private final Recordings rec;

    RecordListener(Recordings rec) {
        this.rec = rec;
    }

    private void mark(Block b) {
        if (b == null) return;
        int x = b.getX(), y = b.getY(), z = b.getZ();
        rec.eachBlock(b.getWorld(), x, y, z, r -> r.mark(x, y, z));
    }

    private void mark(Location l) {
        if (l == null) return;
        World w = l.getWorld();
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        rec.eachBlock(w, x, y, z, r -> r.mark(x, y, z));
    }

    private void mark(List<Block> blocks) {
        for (Block b : blocks) mark(b);
    }

    // ── blocks ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent e) {
        for (BlockState s : e.getReplacedBlockStates()) mark(s.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        int x = b.getX(), y = b.getY(), z = b.getZ();
        Player p = e.getPlayer();
        rec.eachBlock(b.getWorld(), x, y, z, r -> r.broke(x, y, z, b.getBlockData(), p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        mark(e.getBlock());
        mark(e.getSource());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(LeavesDecayEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        mark(e.getBlock());
        mark(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonOut(BlockPistonExtendEvent e) {
        piston(e.getBlock(), e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonIn(BlockPistonRetractEvent e) {
        piston(e.getBlock(), e.getBlocks(), e.getDirection());
    }

    private void piston(Block piston, List<Block> moved, BlockFace direction) {
        mark(piston);
        mark(piston.getRelative(direction));
        mark(piston.getRelative(direction, 2));
        for (Block b : moved) {
            mark(b);
            mark(b.getRelative(direction));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBlock(EntityChangeBlockEvent e) {
        mark(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(PlayerBucketEmptyEvent e) {
        mark(e.getBlockClicked());
        mark(e.getBlockClicked().getRelative(e.getBlockFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(PlayerBucketFillEvent e) {
        mark(e.getBlockClicked());
        mark(e.getBlockClicked().getRelative(e.getBlockFace()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructure(StructureGrowEvent e) {
        for (BlockState s : e.getBlocks()) mark(s.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        mark(e.getBlock());
        for (BlockState s : e.getBlocks()) mark(s.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(SpongeAbsorbEvent e) {
        mark(e.getBlock());
        for (BlockState s : e.getBlocks()) mark(s.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCauldron(CauldronLevelChangeEvent e) {
        mark(e.getBlock());
    }

    /** Doors, trapdoors, levers, buttons: a click can change a block (and a door's other half) with no event of its own. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        mark(b);
        mark(b.getRelative(BlockFace.UP));
        mark(b.getRelative(BlockFace.DOWN));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Location l = e.getLocation();
        float power = e.getEntity() instanceof Explosive ? ((Explosive) e.getEntity()).getYield() : 4.0F;
        rec.each(l.getWorld(), r -> r.explode(l.getX(), l.getY(), l.getZ(), power));
        mark(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Location l = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
        rec.each(l.getWorld(), r -> r.explode(l.getX(), l.getY(), l.getZ(), 5.0F));
        mark(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunk(ChunkLoadEvent e) {
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        rec.each(e.getWorld(), r -> r.chunkLoaded(cx, cz));
    }

    // ── entities ────────────────────────────────────────────────────────

    // What spawns is watched from its first tick (an arrow is gone before a sweep would find it).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent e) {
        Entity en = e.getEntity();
        rec.eachEntity(en, r -> r.added(en));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreature(CreatureSpawnEvent e) {
        Entity en = e.getEntity();
        rec.eachEntity(en, r -> r.added(en));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItem(ItemSpawnEvent e) {
        Entity en = e.getEntity();
        rec.eachEntity(en, r -> r.added(en));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        Entity en = e.getEntity();
        rec.eachEntity(en, r -> r.added(en));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        String type = e.getAnimationType().name();
        int hand = type.startsWith("OFF") ? 1 : 0;
        if (!type.endsWith("ARM_SWING")) return;
        Player p = e.getPlayer();
        rec.eachEntity(p, r -> r.swing(p, hand));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        Entity attacker = null;
        if (e instanceof EntityDamageByEntityEvent) {
            attacker = ((EntityDamageByEntityEvent) e).getDamager();
            if (attacker instanceof Projectile) {
                ProjectileSource shooter = ((Projectile) attacker).getShooter();
                if (shooter instanceof Entity) attacker = (Entity) shooter;
            }
        }
        Entity by = attacker;
        String cause = e.getCause().name();
        rec.eachEntity(victim, r -> r.hurt(victim, cause, by));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();
        Entity killer = e.getEntity().getKiller();
        @SuppressWarnings("deprecation") String message = e instanceof PlayerDeathEvent ? ((PlayerDeathEvent) e).getDeathMessage() : null;
        rec.eachEntity(dead, r -> r.death(dead, killer, message));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        String text;
        try {
            text = String.format(e.getFormat(), e.getPlayer().getDisplayName(), e.getMessage());
        } catch (RuntimeException ex) {
            text = "<" + e.getPlayer().getName() + "> " + e.getMessage();
        }
        rec.chat(e.getPlayer().getWorld(), text, e.getPlayer().getUniqueId(), 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroadcast(BroadcastMessageEvent e) {
        rec.chat(null, e.getMessage(), null, 1);
    }

    /** Paper's own events, where the server has them. */
    static final class Paper implements Listener {
        static final String DESTROY = "com.destroystokyo.paper.event.block.BlockDestroyEvent";
        static final String ADD = "com.destroystokyo.paper.event.entity.EntityAddToWorldEvent";

        private final Recordings rec;

        Paper(Recordings rec) {
            this.rec = rec;
        }

        static boolean available() {
            try {
                Class.forName(DESTROY);
                Class.forName(ADD);
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        /** Every entity that comes into a world, whatever brought it (Paper only). */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onAdd(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent e) {
            Entity en = e.getEntity();
            rec.eachEntity(en, r -> r.added(en));
        }

        /** A block the game destroys by itself (a torch whose wall went, sand that fell). */
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onDestroy(com.destroystokyo.paper.event.block.BlockDestroyEvent e) {
            Block b = e.getBlock();
            int x = b.getX(), y = b.getY(), z = b.getZ();
            rec.eachBlock(b.getWorld(), x, y, z, r -> r.mark(x, y, z));
        }
    }
}
