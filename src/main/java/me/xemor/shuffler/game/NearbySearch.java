package me.xemor.shuffler.game;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A search outward from a player's eye, nearest first, where moving through solid blocks costs more than moving
 * through air. Everything it passes is credited to the player's {@link PlayerSample}, worth less the further it is.
 * <p>
 * It is done in steps so it can be spread over many ticks.
 */
final class NearbySearch {

    // Tuned by ablation over 7 biome start points, scored on the final bag (propagated drops plus stand-on blocks, each
    // half of the weight): keeps wood well above stone, and meat a little rarer than wood.
    private static final double OCCLUDING_COST_SCALE = 1;
    // These two used to fall with the game level (from 4.5 and 3 at level 1); they are now fixed at their level 1 values.
    private static final double OCCLUDING_COST_ADDITIVE = 4.5;
    private static final double VERTICAL_COST = 3;
    private static final double MOB_DROP_WEIGHT = 140;
    /** The weight of what is found falls by this factor for each unit of cost to reach it. */
    private static final double DISTANCE_DECAY = 0.985;
    /** On average one block in this many looks for mobs around it. */
    private static final int BLOCKS_PER_MOB_LOOK = 5 * 5 * 5;
    private static final double MOB_LOOK_RADIUS = 5;
    private static final int ABOVE_SURFACE = 5;
    private static final BlockFace[] NEIGHBOURS = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN};

    private record Node(Position position, double distanceFromSource) {}

    private record Position(int x, int y, int z) {
        Position(Location location) {
            this(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        Position add(BlockFace face) {
            return new Position(x + face.getModX(), y + face.getModY(), z + face.getModZ());
        }

        Location toLocation(World world) {
            return new Location(world, x, y, z);
        }

        long toLong() {
            final int numXBits = 26;
            final int numZBits = numXBits;
            final int numYBits = 64 - numXBits - numZBits;
            final int yShift = numZBits;
            final int xShift = yShift + numYBits;
            final long xMask = (1L << numXBits) - 1L;
            final long yMask = (1L << numYBits) - 1L;
            final long zMask = (1L << numZBits) - 1L;
            return ((long) x & xMask) << xShift | ((long) y & yMask) << yShift | ((long) z & zMask);
        }
    }

    private final @Nullable Player killer;
    private final World world;
    private final PlayerSample sample;
    private final Map<Material, Collection<ItemStack>> dropsCache;
    private final ObjectHeapPriorityQueue<Node> queue = new ObjectHeapPriorityQueue<>(Comparator.comparing(Node::distanceFromSource));
    private final LongOpenHashSet visited = new LongOpenHashSet();
    private final Set<UUID> seenEntities = new HashSet<>();
    private final int highestY;
    private double maxDistance = 0;

    NearbySearch(Player player, PlayerSample sample, Map<Material, Collection<ItemStack>> dropsCache) {
        this(player.getEyeLocation(), player, sample, dropsCache);
    }

    /**
     * @param eye where the search starts
     * @param killer who the mobs seen would have been killed by, or null for nobody
     * @param dropsCache what each kind of block drops, shared between searches because asking is slow
     */
    NearbySearch(Location eye, @Nullable Player killer, PlayerSample sample, Map<Material, Collection<ItemStack>> dropsCache) {
        this.killer = killer;
        this.world = eye.getWorld();
        this.sample = sample;
        this.dropsCache = dropsCache;
        Position start = new Position(eye);
        this.highestY = world.getHighestBlockYAt(start.x(), start.z()) + ABOVE_SURFACE;
        queue.enqueue(new Node(start, 0));
        visited.add(start.toLong());
    }

    boolean finished() {
        return queue.isEmpty();
    }

    /**
     * @return how far, in cost, the search has reached
     */
    double maxDistance() {
        return maxDistance;
    }

    /**
     * Visits up to this many more blocks.
     */
    void step(int blocks) {
        for (int visitedNow = 0; visitedNow < blocks && !queue.isEmpty(); visitedNow++) {
            Node node = queue.dequeue();
            maxDistance = Math.max(maxDistance, node.distanceFromSource());
            Block block = node.position().toLocation(world).getBlock();
            double weight = Math.pow(DISTANCE_DECAY, node.distanceFromSource());
            collectBlock(block, weight);
            if (ThreadLocalRandom.current().nextInt(BLOCKS_PER_MOB_LOOK) == 1) collectMobs(node.position(), weight);
            expand(node, block.getType());
        }
    }

    private void collectBlock(Block block, double weight) {
        Material type = block.getType();
        if (type.isAir()) return;
        for (ItemStack drop : dropsCache.computeIfAbsent(type, _ -> block.getDrops())) {
            if (!drop.getType().isAir()) sample.addDrop(drop.getType(), weight);
        }
        sample.addStandOn(type, weight);
    }

    private void collectMobs(Position position, double weight) {
        for (Entity entity : world.getNearbyEntities(position.toLocation(world), MOB_LOOK_RADIUS, MOB_LOOK_RADIUS, MOB_LOOK_RADIUS)) {
            if (!seenEntities.add(entity.getUniqueId())) continue;
            for (Material drop : ConstantItemGenerator.mobDrops(entity, killer)) {
                if (!drop.isAir()) sample.addDrop(drop, MOB_DROP_WEIGHT * weight);
            }
        }
    }

    private void expand(Node node, Material through) {
        for (BlockFace face : NEIGHBOURS) {
            Position neighbour = node.position().add(face);
            if (neighbour.y() > highestY || !visited.add(neighbour.toLong())) continue;
            queue.enqueue(new Node(neighbour, node.distanceFromSource() + stepCost(through, face)));
        }
    }

    /**
     * @return the cost of moving out of a block in this direction
     */
    private static double stepCost(Material through, BlockFace face) {
        double cost = 1;
        if (through == Material.WATER) cost *= 1.5 + OCCLUDING_COST_ADDITIVE;
        else if (through.isOccluding()) cost *= (2.5 + OCCLUDING_COST_ADDITIVE) * OCCLUDING_COST_SCALE;
        if (face == BlockFace.UP) cost *= 1.25;
        if (face == BlockFace.UP || face == BlockFace.DOWN) cost *= VERTICAL_COST;
        return cost;
    }
}
