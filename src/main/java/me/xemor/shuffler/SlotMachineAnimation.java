package me.xemor.shuffler;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public class SlotMachineAnimation {

    private final Plugin plugin;

    public SlotMachineAnimation(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Plays a slot machine animation showing possible items before revealing the final result.
     *
     * @param player The player to show the animation to
     * @param possibleMaterials List of possible materials that could be selected
     * @param finalMaterial The material that will be shown at the end
     * @param durationTicks How long the animation should run (in ticks)
     * @return A CompletableFuture that completes when the animation is done
     */
    public CompletableFuture<Void> playAnimation(Player player, List<Material> possibleMaterials, Material finalMaterial, int durationTicks) {
        finalMaterial = transformMaterial(finalMaterial);
        CompletableFuture<Void> future = new CompletableFuture<>();

        final int numberOfItems = 5; // EXACTLY 5 items, no more
        double spacing = 0.4f;
        float itemSize = 0.4f;

        // Ensure final material is in possible materials
        List<Material> materialsPool = new ArrayList<>(possibleMaterials);
        if (!materialsPool.contains(finalMaterial)) {
            materialsPool.add(finalMaterial);
        }

        // Create persistent displays - these are the ONLY displays that will exist
        List<Display> displays = new ArrayList<>();

        // Get initial player view
        Location eyeLoc = player.getEyeLocation();
        Vector direction = eyeLoc.getDirection();
        Location centerPos = eyeLoc.clone().add(direction.multiply(1.1));

        // Spawn EXACTLY 5 displays once and only once
        for (int i = 0; i < numberOfItems; i++) {
            Material materialToDisplay = materialsPool.get(i % materialsPool.size());
            Display display = (Display) centerPos.getWorld().spawnEntity(centerPos, EntityType.ITEM_DISPLAY);
            display = setMaterial(display, materialToDisplay);

            display.setBillboard(ItemDisplay.Billboard.FIXED);
            displays.add(display);
        }

        // Track current item offsets for smooth scrolling
        double[] itemOffsets = new double[numberOfItems];
        for (int i = 0; i < numberOfItems; i++) {
            itemOffsets[i] = (i - 2) * spacing;
        }

        // Track which material index each display is currently showing
        int[] materialIndices = new int[numberOfItems];
        for (int i = 0; i < numberOfItems; i++) {
            materialIndices[i] = ThreadLocalRandom.current().nextInt(materialsPool.size());
        }

        // Create the animation loop
        Material finalFinalMaterial = finalMaterial;
        new BukkitRunnable() {
            int ticks = 0;
            double speed = 0.04;
            final double initialSpeed = 0.04;

            @Override
            public void run() {
                // Get current player view
                Location eyeLoc = player.getEyeLocation();
                Vector direction = eyeLoc.getDirection();
                Vector upVector = getUpVector(player);
                Location centerPos = eyeLoc.clone().add(direction.multiply(1.1));

                // Stop spinning after duration
                Display centerDisplay = displays.stream().sorted(Comparator.comparingDouble((it) -> {
                    Location relative = it.getLocation().subtract(eyeLoc);
                    return upVector.getX() * relative.getX() + upVector.getY() * relative.getY() + upVector.getZ() * relative.getZ();
                })).toList().get(2);
                Material centerMaterial = centerDisplay instanceof ItemDisplay itemDisplay ? itemDisplay.getItemStack().getType() : centerDisplay instanceof BlockDisplay blockDisplay ? blockDisplay.getBlock().getMaterial() : null;
                if (ticks >= (durationTicks * 0.75) && centerMaterial == finalFinalMaterial) {
                    this.cancel();

                    // Remove ALL displays immediately except center
                    displays.stream().filter(display -> display != centerDisplay).forEach(Entity::remove);

                    Transformation transform = centerDisplay.getTransformation();
                    transform.getScale().set(new Vector3f(itemSize * 1.4f, itemSize * 1.4f, itemSize * 1.4f));
                    centerDisplay.setTransformation(transform);
                    future.complete(null);

                    // Clean up the last display after 2 seconds
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            centerDisplay.remove();
                        }
                    }.runTaskLater(plugin, 40L);

                    return;
                }

                // Update item positions - scroll down
                for (int i = 0; i < numberOfItems; i++) {
                    itemOffsets[i] -= speed;

                    // Wrap around when item goes too far down
                    if (itemOffsets[i] < -spacing * (numberOfItems / 2D)) {
                        itemOffsets[i] += spacing * numberOfItems;

                        // Get next material from pre-generated list for this display
                        if (ticks < (durationTicks * 0.75)) {
                            materialIndices[i]++;
                            Display display = displays.get(i);
                            displays.set(i, setMaterial(display, materialsPool.get(materialIndices[i] % materialsPool.size())));
                        }
                        else {
                            Display display = displays.get(i);
                            displays.set(i, setMaterial(display, finalFinalMaterial));
                        }
                    }
                }

                // Update display positions and rotations
                for (int i = 0; i < numberOfItems; i++) {
                    double verticalOffset = itemOffsets[i];

                    Location itemLoc = centerPos.clone().add(upVector.clone().multiply(verticalOffset));

                    // Calculate tilt - items above center tilt up, below tilt down
                    float tiltAngle = (float) Math.toRadians(-verticalOffset * 20);
                    Quaternionf rotation = new Quaternionf()
                        .rotateX(tiltAngle);

                    Transformation transform = displays.get(i).getTransformation();
                    transform.getScale().set(new Vector3f(itemSize, itemSize, itemSize));
                    transform.getLeftRotation().set(rotation);
                    displays.get(i).setTransformation(transform);
                    displays.get(i).teleport(itemLoc);
                }

                // Decelerate
                final int slowdownStart = (int) (durationTicks * 0.7);
                if (ticks > slowdownStart) {
                    double progress = (double) (ticks - slowdownStart) / (durationTicks - slowdownStart);
                    speed = initialSpeed * (1.0 - Math.min(0.8, progress * 0.7));
                }

                ticks++;
            }
        }.runTaskTimer(plugin, 0L, 1L);

        return future;
    }

    private Material transformMaterial(Material material) {
        return switch (material) {
            case WATER -> Material.WATER_BUCKET;
            case LAVA -> Material.LAVA_BUCKET;
            default -> material;
        };
    }

    private Display setMaterial(Display originalDisplay, Material material) {
        material = transformMaterial(material);

        Display display;
        if (material.isItem()) {
            ItemDisplay itemDisplay;
            if (originalDisplay instanceof ItemDisplay itemDisplay1) {
                itemDisplay = itemDisplay1;
            }
            else {
                itemDisplay = (ItemDisplay) originalDisplay.getWorld().spawnEntity(originalDisplay.getLocation(), EntityType.ITEM_DISPLAY);
                originalDisplay.remove();
            }

            itemDisplay.setItemStack(new ItemStack(material));
            display = itemDisplay;
        }
        else {
            BlockDisplay blockDisplay;
            if (originalDisplay instanceof BlockDisplay blockDisplay1) {
                blockDisplay = blockDisplay1;
            }
            else {
                blockDisplay = (BlockDisplay) originalDisplay.getWorld().spawnEntity(originalDisplay.getLocation(), EntityType.BLOCK_DISPLAY);
                originalDisplay.remove();
            }

            blockDisplay.setBlock(material.createBlockData());
            display = blockDisplay;
        }
        return display;
    }

    private Vector getUpVector(Player player) {
        Location eyeLoc = player.getEyeLocation();

        // Convert yaw and pitch from degrees to radians
        double yawRad = Math.toRadians(eyeLoc.getYaw());
        double pitchRad = Math.toRadians(eyeLoc.getPitch());

        // Calculate the components of the up vector
        double x = -Math.sin(yawRad) * Math.sin(pitchRad);
        double y = Math.cos(pitchRad);
        double z = Math.cos(yawRad) * Math.sin(pitchRad);

        // Return the normalized vector
        return new Vector(x, y, z).normalize();
    }
}
