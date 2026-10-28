package me.xemor.shuffler.game;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Samples what a player can see. Rays are cast from the eye in directions spread evenly over the sphere (limited to a
 * band around the horizon), and each ray credits the first block it hits. Every ray counts the same, so a block's
 * weight is proportional to the solid angle it takes up in the player's view: far things fall off roughly as 1/d^2,
 * hidden blocks are never hit, and big things (a mountain face) count for more than small ones (a flower).
 * <p>
 * Rays use {@link World#rayTrace}, so they can also hit mobs, and are only as long as the distance within which every chunk around the
 * player is loaded, so this never loads or generates chunks. Nearby hits count like any other, since they take up
 * a big part of the view.
 */
final class ViewSampler {

    /** Furthest a ray travels. */
    static final double RANGE = 160;
    /** Rays are limited to this far above and below the horizon. */
    static final double MAX_PITCH_SINE = Math.sin(Math.toRadians(45));

    private final World world;
    private final @Nullable Player killer;
    private final Location eye;
    private final double range;
    private final ProbabilityBag drops, blocks;
    private final Map<Material, Collection<ItemStack>> dropsCache;
    private int remaining;

    ViewSampler(Location eye, @Nullable Player killer, int rays, ProbabilityBag drops, ProbabilityBag blocks, Map<Material, Collection<ItemStack>> dropsCache) {
        this.killer = killer;
        this.eye = eye.clone();
        this.world = eye.getWorld();
        this.range = loadedRange(this.world, eye);
        this.remaining = rays;
        this.drops = drops;
        this.blocks = blocks;
        this.dropsCache = dropsCache;
    }

    /**
     * @return how far in a straight line from the eye, in any horizontal direction, is entirely inside loaded chunks
     */
    static double loadedRange(World world, Location eye) {
        int chunkX = eye.getBlockX() >> 4, chunkZ = eye.getBlockZ() >> 4;
        int radius = -1;
        while ((radius + 1) * 16 < RANGE && ringLoaded(world, chunkX, chunkZ, radius + 1)) radius++;
        if (radius < 0) return 0;
        double west = eye.getX() - (chunkX - radius) * 16, east = (chunkX + radius + 1) * 16 - eye.getX();
        double north = eye.getZ() - (chunkZ - radius) * 16, south = (chunkZ + radius + 1) * 16 - eye.getZ();
        return Math.min(RANGE, Math.min(Math.min(west, east), Math.min(north, south)));
    }

    private static boolean ringLoaded(World world, int chunkX, int chunkZ, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                if (!world.isChunkLoaded(chunkX + dx, chunkZ + dz)) return false;
            }
        }
        return true;
    }

    boolean finished() {
        return remaining <= 0;
    }

    /**
     * Casts up to this many more rays.
     *
     * @return the number of rays that hit something
     */
    int step(int rays) {
        int hits = 0;
        for (int i = 0; i < rays && remaining > 0; i++, remaining--) {
            if (castRay()) hits++;
        }
        return hits;
    }

    private boolean castRay() {
        if (range <= 0) return false;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Uniform over the sphere means a uniform sine of the pitch, so the band is uniform in solid angle.
        double sinPitch = random.nextDouble(-MAX_PITCH_SINE, MAX_PITCH_SINE);
        double cosPitch = Math.sqrt(1 - sinPitch * sinPitch);
        double yaw = random.nextDouble(0, 2 * Math.PI);
        Vector direction = new Vector(cosPitch * Math.cos(yaw), sinPitch, cosPitch * Math.sin(yaw));

        RayTraceResult result = world.rayTrace(eye, direction, range, FluidCollisionMode.NEVER, false, 0,
                entity -> entity instanceof LivingEntity && !(entity instanceof Player));
        if (result == null) return false;

        Entity entity = result.getHitEntity();
        if (entity != null) {
            boolean dropped = false;
            for (Material drop : ConstantItemGenerator.mobDrops(entity, killer)) {
                if (drop.isAir()) continue;
                drops.addWeighting(drop, 1);
                dropped = true;
            }
            return dropped;
        }

        Block block = result.getHitBlock();
        if (block == null) return false;
        Material type = block.getType();
        if (type.isAir()) return false;
        Collection<ItemStack> blockDrops = dropsCache.get(type);
        if (blockDrops == null) {
            blockDrops = block.getDrops();
            dropsCache.put(type, blockDrops);
        }
        for (ItemStack drop : blockDrops) {
            if (!drop.getType().isAir()) drops.addWeighting(drop.getType(), 1);
        }
        blocks.addWeighting(type, 1);
        return true;
    }

    static Map<Material, Collection<ItemStack>> newCache() {
        return new EnumMap<>(Material.class);
    }
}
