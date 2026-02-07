package me.xemor.shuffler;

import com.google.common.collect.HashMultimap;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ItemGenerator implements Listener {

    private final List<Recipe> recipes;
    private final ProbabilityBag availableMaterialsBag = new ProbabilityBag();
    private final ProbabilityBag availableBlocksToStandOnBag = new ProbabilityBag();

    public ItemGenerator() {
        recipes = new ArrayList<>();
        Bukkit.recipeIterator().forEachRemaining(
                recipes::add
        );
    }

    public Map<UUID, ProbabilityBag.SampleResult> generateMaterials(List<? extends Player> alivePlayers, int level) {
        Map<UUID, ProbabilityBag.SampleResult> sampleResults = new HashMap<>();
        if (level == 1) {
            for (Player player : alivePlayers) {
                ProbabilityBag bag = getLevelOneBagForPlayer(player);
                ProbabilityBag.SampleResult sample = bag.sample(1 / 3D);
                sampleResults.put(player.getUniqueId(), sample);
            }
        }
        else {
            ProbabilityBag bag = getBagForPlayers(alivePlayers, level);
            List<ProbabilityBag.SampleResult> samples = bag.samples(alivePlayers.size(), Math.pow(1 / 3D, level));
            int i = 0;
            for (Player player : alivePlayers) {
                sampleResults.put(player.getUniqueId(), samples.get(i++));
            }
        }
        return sampleResults;
    }

    private ProbabilityBag getLevelOneBagForPlayer(Player player) {
        World world = player.getWorld();
        Chunk chunk = player.getLocation().getChunk();
        ProbabilityBag bag = new ProbabilityBag();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int minHeight = Math.max(world.getMinHeight(), player.getLocation().getBlockY() - 8);
                for (int y = minHeight; y < world.getMaxHeight(); y++) {
                    Block block = chunk.getBlock(x, y, z);
                    if (block.getType().isAir()) continue;
                    bag.add(block.getType());
                    bag.add(block.getDrops().stream().map(ItemStack::getType).toList());
                }
            }
        }
        return bag;
    }

    private ProbabilityBag getBagForPlayers(List<? extends Player> alivePlayers, int level) {
        if (alivePlayers.isEmpty()) throw new IllegalArgumentException("Alive players must contain at least one player");
        Set<String> worldNames = alivePlayers.stream().map(Entity::getWorld).map(World::getName).collect(Collectors.toSet());
        List<Chunk> chunks = worldNames.stream().map(Bukkit::getWorld).filter(Objects::nonNull).flatMap((world) -> Arrays.stream(world.getLoadedChunks()))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        Collections.shuffle(chunks);
        chunks = chunks.stream().limit(30).toList();
        int averageY = (int) (alivePlayers.stream().map(Entity::getY).reduce(Double::sum).orElse(0D) / alivePlayers.size());

        HashMultimap<Long, Material> availableMaterialsPerChunkKey = HashMultimap.create();
        List<Material> availableMaterials = new ArrayList<>();
        List<Material> availableBlocksToStandOn = new ArrayList<>();
        for (Chunk chunk : chunks) {
            List<Material> mobDrops = Arrays.stream(chunk.getEntities()).flatMap((entity) -> {
                LootTables lootTables = Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey()));
                if (lootTables == null) return Stream.of();
                return lootTables.getLootTable().populateLoot(ThreadLocalRandom.current(), (new LootContext.Builder(entity.getLocation()).lootedEntity(entity).killer(alivePlayers.getFirst())).build())
                        .stream().map(ItemStack::getType);
            }).toList();
            availableMaterialsPerChunkKey.putAll(chunk.getChunkKey(), mobDrops);
            // Weight mob drops more highly
            for (int i = 0; i < 3; i++) {
                availableMaterials.addAll(mobDrops);
            }
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int minHeight = (int) Math.max(chunk.getWorld().getMinHeight(), averageY - ((Math.pow(2, level - 1) + 10)));
                    for (int y = minHeight; y < chunk.getWorld().getMaxHeight(); y++) {
                        Block block = chunk.getBlock(x, y, z);
                        if (block.getType().isAir()) continue;
                        availableBlocksToStandOn.add(block.getType());
                        availableMaterialsPerChunkKey.put(chunk.getChunkKey(), block.getType());
                        availableMaterials.addAll(block.getDrops().stream().map(ItemStack::getType).toList());
                        availableMaterialsPerChunkKey.putAll(chunk.getChunkKey(), block.getDrops().stream().map(ItemStack::getType).toList());
                    }
                }
            }
        }

        Map<Material, Integer> materialChunkOccurrences = availableMaterialsPerChunkKey.asMap().values().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.groupingBy(
                        material -> material,
                        Collectors.collectingAndThen(
                                Collectors.counting(),
                                Long::intValue
                        )
                ));


        Map<Material, Integer> availableMaterialFrequencies = availableMaterials.stream()
                .collect(Collectors.groupingBy(
                        material -> material,
                        Collectors.collectingAndThen(
                                Collectors.counting(),
                                Long::intValue
                        )
                ));

        Map<Material, Integer> availableBlocksFrequencies = availableBlocksToStandOn.stream()
                .collect(Collectors.groupingBy(
                        material -> material,
                        Collectors.collectingAndThen(
                                Collectors.counting(),
                                Long::intValue
                        )
                ));

        for (Map.Entry<Material, Integer> entry : availableMaterialFrequencies.entrySet()) {
            // Math.min is here as blocks in literally every chunk, but rare within each chunk were being overweighted previously
            availableMaterialsBag.add(entry.getKey(), Math.min(20, materialChunkOccurrences.getOrDefault(entry.getKey(), 0)) * entry.getValue());
        }

        for (Map.Entry<Material, Integer> entry : availableBlocksFrequencies.entrySet()) {
            // Math.min is here as blocks in literally every chunk, but rare within each chunk were being overweighted previously
            availableBlocksToStandOnBag.add(entry.getKey(), Math.min(20, materialChunkOccurrences.getOrDefault(entry.getKey(), 0)) * entry.getValue());
        }

        ProbabilityBag craftingBag = new ProbabilityBag().add(availableMaterialsBag);
        // Cap of 8 BFS over Crafting for performance reasons and to limit increased weighting of crafting recipes from repeated adding
        for (int i = 0; i < Math.min(level - 1, 8); i++) {
            for (Recipe recipe : recipes) {
                for (int j = 0; j < 3; j++) {
                    List<Material> ingredients = switch (recipe) {
                        case ShapelessRecipe shapelessRecipe ->
                                shapelessRecipe.getChoiceList().stream().filter(Objects::nonNull).map((choice) -> {
                                    List<Material> materials = (switch (choice) {
                                        case RecipeChoice.MaterialChoice materialChoice ->
                                                materialChoice.getChoices().stream().toList();
                                        case RecipeChoice.ExactChoice exactChoice ->
                                                exactChoice.getChoices().stream().map(ItemStack::getType).toList();
                                        default -> List.of();
                                    });
                                    return materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
                                }).toList();
                        case ShapedRecipe shapedRecipe ->
                                shapedRecipe.getChoiceMap().values().stream().filter(Objects::nonNull).map((choice) -> {
                                    List<Material> materials = (switch (choice) {
                                        case RecipeChoice.MaterialChoice materialChoice ->
                                                materialChoice.getChoices().stream().toList();
                                        case RecipeChoice.ExactChoice exactChoice ->
                                                exactChoice.getChoices().stream().map(ItemStack::getType).toList();
                                        default -> List.of();
                                    });
                                    return materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
                                }).toList();
                        case CookingRecipe cookingRecipe -> {
                            RecipeChoice inputChoice = cookingRecipe.getInputChoice();
                            if (inputChoice == null) yield List.of();
                            List<Material> materials = switch (inputChoice) {
                                case RecipeChoice.MaterialChoice materialChoice ->
                                        materialChoice.getChoices().stream().toList();
                                case RecipeChoice.ExactChoice exactChoice ->
                                        exactChoice.getChoices().stream().map(ItemStack::getType).toList();
                                default -> List.of();
                            };
                            yield List.of(materials.get(ThreadLocalRandom.current().nextInt(materials.size())));
                        }
                        case StonecuttingRecipe stonecuttingRecipe -> {
                            RecipeChoice inputChoice = stonecuttingRecipe.getInputChoice();
                            if (inputChoice == null) yield List.of();
                            List<Material> materials = switch (inputChoice) {
                                case RecipeChoice.MaterialChoice materialChoice ->
                                        materialChoice.getChoices().stream().toList();
                                case RecipeChoice.ExactChoice exactChoice ->
                                        exactChoice.getChoices().stream().map(ItemStack::getType).toList();
                                default -> List.of();
                            };
                            yield List.of(materials.get(ThreadLocalRandom.current().nextInt(materials.size())));
                        }
                        default -> List.of();
                    };
                    Map<Material, Integer> groupedMaterials = ingredients.stream().collect(Collectors.groupingBy(x -> x, Collectors.summingInt(x -> 1)));
                    if (craftingBag.containsAll(ingredients)) {
                        Double p = groupedMaterials.keySet()
                                .stream()
                                .map(craftingBag::getWeighting)
                                //.map((it) -> craftingBag.get(it.getKey()) / it.getValue())
                                .reduce(Double.MAX_VALUE, Math::min);
                        if (p == Double.MAX_VALUE) p = 0D;
                        craftingBag.max(recipe.getResult().getType(), p);
                    }
                }
            }
        }
        return craftingBag.add(availableBlocksToStandOnBag);
    }

/*
    public ProbabilityBag.SampleResult generateMaterialForPlayer(List<? extends Player> alivePlayers, Player player, int level) {
        World playerWorld = player.getWorld();
        List<Chunk> chunks = null;
        if (level == 1) {
            chunks = List.of(player.getLocation().getChunk());
        }
        else {
            Set<String> worldNames = alivePlayers.stream().map(Entity::getWorld).map(World::getName).collect(Collectors.toSet());
            chunks = worldNames.stream().map(Bukkit::getWorld).filter(Objects::nonNull).flatMap((world) -> Arrays.stream(world.getLoadedChunks()))
                    .toList();
        }

        chunks = new ArrayList<>(chunks);
        Collections.shuffle(chunks);
        chunks = chunks.stream().limit(10).toList();

        for (Chunk chunk : chunks) {
            availableMaterials.add(Arrays.stream(chunk.getEntities()).flatMap((entity) -> {
                LootTables lootTables = Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey()));
                if (lootTables == null) return Stream.of();
                return lootTables.getLootTable().populateLoot(ThreadLocalRandom.current(), (new LootContext.Builder(entity.getLocation()).lootedEntity(entity).killer(player)).build())
                        .stream().map(ItemStack::getType).toList().stream();
            }).toList());
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int minHeight = (int) Math.max(playerWorld.getMinHeight(), player.getLocation().getBlockY() - (Math.pow(2, level - 1) * 16));
                    for (int y = minHeight; y < playerWorld.getMaxHeight(); y++) {
                        Block block = chunk.getBlock(x, y, z);
                        availableBlocksToStandOn.add(block.getType());
                        availableMaterials.add(block.getDrops().stream().map(ItemStack::getType).toList());
                    }
                }
            }
        }

        if (level > 1) {
            for (Recipe recipe : recipes) {
                List<Material> ingredients = null;
                if (recipe instanceof ShapelessRecipe shapelessRecipe) {
                    ingredients = shapelessRecipe.getChoiceList().stream().flatMap((choice) -> {
                        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
                            return materialChoice.getChoices().stream();
                        }
                        return Stream.of();
                    }).toList();
                }
                else if (recipe instanceof ShapedRecipe shapedRecipe) {
                    ingredients = shapedRecipe.getChoiceMap().values().stream().flatMap((choice) -> {
                        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
                            return materialChoice.getChoices().stream();
                        }
                        return Stream.of();
                    }).toList();
                }
                else if (recipe instanceof CookingRecipe cookingRecipe) {
                    if (cookingRecipe.getInputChoice() instanceof RecipeChoice.MaterialChoice materialChoice) {
                        ingredients = materialChoice.getChoices();
                    }
                }
                else if (recipe instanceof StonecuttingRecipe stonecuttingRecipe) {
                    if (stonecuttingRecipe.getInputChoice() instanceof RecipeChoice.MaterialChoice materialChoice) {
                        ingredients = materialChoice.getChoices();
                    }
                }
                else {
                    continue;
                }
                if (availableMaterials.containsAll(ingredients)) {
                    Double v = ingredients.stream().map((ingredient) -> availableMaterials.get(ingredient)).reduce((x1, x2) -> x1 * x2).orElseThrow();
                    availableMaterials.add(recipe.getResult().getType(), v);
                }
            }
        }

        ProbabilityBag totalBag = new ProbabilityBag().add(availableMaterials).add(availableBlocksToStandOn);
        double weightingCap = 1 / Math.pow(3, level);
        return totalBag.sample(weightingCap);
    }
 */

}
