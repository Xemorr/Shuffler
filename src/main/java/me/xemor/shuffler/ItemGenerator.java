package me.xemor.shuffler;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ItemGenerator implements Listener {

    private final List<RecipeNode> recipeNodes;
    private final ProbabilityBag availableMaterialsBag = new ProbabilityBag();
    private final ProbabilityBag availableBlocksToStandOnBag = new ProbabilityBag();

    public ItemGenerator() {
        recipeNodes = unorderedRecipeNodes();
    }

    sealed interface ItemNode permits WorldNode, RecipeNode {
        Material material();
    }
    record WorldNode(Material material) implements ItemNode {}
    record RecipeNode(Material material, int amount, List<Material> ingredients, RecipeType recipeType) implements ItemNode {
        public RecipeNode(Material material, int amount, List<Material> ingredients, RecipeType recipeType) {
            Objects.requireNonNull(material, "Material cannot be null");
            Objects.requireNonNull(ingredients, "Ingredients cannot be null");
            Objects.requireNonNull(recipeType, "Recipe type cannot be null");
            this.material = material;
            this.amount = amount;
            this.ingredients = new ArrayList<>(ingredients); // this must be mutable
            this.recipeType = recipeType;
        }
    }
    enum RecipeType {
        CRAFTING, COOKING, STONECUTTING
    }

    /*
    private List<ItemNode> topologicallySortedItemNodes() {
        List<RecipeNode> nodes = unorderedRecipeNodes();
        Deque<ItemNode> unvisitedNodes = Arrays.stream(Material.values()).map(WorldNode::new).collect(Collectors.toCollection(ArrayDeque::new));
        List<ItemNode> sortedNodes = new ArrayList<>();

        while (!unvisitedNodes.isEmpty()) {
            ItemNode node = unvisitedNodes.pop();
            sortedNodes.add(node);
            for (RecipeNode child : nodes) {
                int i = child.ingredients().indexOf(node.material());
                if (i != -1) {
                    child.ingredients().remove(i);
                    if (child.ingredients().isEmpty()) {
                        unvisitedNodes.push(child);
                    }
                }
            }
        }

        int edgesCount = nodes.stream().map((it) -> it.ingredients().size()).reduce(Integer::sum).orElse(0);
        if (edgesCount > 0) {
            throw new IllegalStateException("Recipes do not form a DAG");
        }
        else {
            return sortedNodes;
        }
    }
     */

    // Replace the craftingBag generation in getBagForPlayers with this LBP approach:
    private ProbabilityBag runLoopyBeliefCraftingPropagation(ProbabilityBag rootBag, int iterations) {
        List<RecipeNode> allRecipes = recipeNodes;
        ProbabilityBag startingBag = new ProbabilityBag();
        for (ProbabilityBag.Entry entry : rootBag.entries()) {
            startingBag.addWeighting(entry.material(), entry.probability());
        }
        ProbabilityBag currentState = new ProbabilityBag().addWeighting(startingBag);

        // Group recipes by their output Material to evaluate them together
        Map<Material, List<RecipeNode>> recipesByResult = allRecipes.stream()
                .collect(Collectors.groupingBy(RecipeNode::material));

        // Iterate to propagate probabilities through loops
        for (int i = 0; i < iterations; i++) {
            ProbabilityBag nextState = new ProbabilityBag().addWeighting(startingBag);

            for (Map.Entry<Material, List<RecipeNode>> entry : recipesByResult.entrySet()) {
                Material resultMaterial = entry.getKey();
                double recipeWeightSum = 0D;

                for (RecipeNode recipe : entry.getValue()) {
                    // Re-using your exact Vibe Math, but pulling from currentState
                    Map<Material, Integer> groupedIngredients = recipe.ingredients().stream()
                            .collect(Collectors.groupingBy(x -> x, Collectors.summingInt(x -> 1)));

                    if (!currentState.containsAll(groupedIngredients.keySet())) {
                        continue;
                    }

                    final ProbabilityBag effectivelyFinalCurrentState = currentState;
                    // Maybe I should do a check to see if they're 'crafting independent' or not
                    double independentP = groupedIngredients.entrySet().stream()
                            .map(it -> effectivelyFinalCurrentState.get(it.getKey()) / it.getValue())
                            .reduce(1D, (x, y) -> x * y);

                    if (independentP == 1D) independentP = 0D;

                    double minP = groupedIngredients.keySet().stream()
                            .map(currentState::get)
                            .reduce(Double.MAX_VALUE, Math::min);

                    if (minP == Double.MAX_VALUE) minP = 0D;

                    double p = (minP * 0.9 + 0.1 * independentP);
                    if (recipe.recipeType() == RecipeType.COOKING) {
                        p *= 0.97;
                    }
                    p *= recipe.amount();

                    // Sum to better represent the difficulty of e.g sticks, rather than taking max.
                    recipeWeightSum += p;
                }

                if (recipeWeightSum > 0) {
                    // Update the next state with the best crafted weight
                    nextState.maxWeighting(resultMaterial, recipeWeightSum);
                }
            }
            currentState = nextState; // Move to next time step
        }
        return currentState;
    }

    private List<RecipeNode> unorderedRecipeNodes() {
        List<Recipe> recipes = new ArrayList<>();
        Bukkit.recipeIterator().forEachRemaining(
                recipes::add
        );
        List<RecipeNode> nodes = new ArrayList<>(recipes.size());
        for (Recipe recipe : recipes) {
            Stream<Set<Material>> ingredientChoicesStream = switch (recipe) {
                case ShapelessRecipe shapelessRecipe ->
                        shapelessRecipe.getChoiceList().stream().filter(Objects::nonNull).map((choice) -> {
                            return (switch (choice) {
                                case RecipeChoice.MaterialChoice materialChoice -> {
                                    yield new HashSet<>(materialChoice.getChoices());
                                }
                                case RecipeChoice.ExactChoice exactChoice ->
                                        exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                                default -> Set.of();
                            });
                        });
                case ShapedRecipe shapedRecipe ->
                    Arrays.stream(shapedRecipe.getShape()).flatMapToInt(String::chars).mapToObj((shapeIdentifier) -> {
                        RecipeChoice choice = shapedRecipe.getChoiceMap().get((char) shapeIdentifier);
                        if (choice == null) return Set.of();
                        return (switch (choice) {
                            case RecipeChoice.MaterialChoice materialChoice ->
                                    new HashSet(materialChoice.getChoices());
                            case RecipeChoice.ExactChoice exactChoice ->
                                    exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                            default -> Set.of();
                        });
                    });
                case CookingRecipe cookingRecipe -> {
                    RecipeChoice inputChoice = cookingRecipe.getInputChoice();
                    Set<Material> materials = switch (inputChoice) {
                        case RecipeChoice.MaterialChoice materialChoice ->
                                new HashSet<>(materialChoice.getChoices());
                        case RecipeChoice.ExactChoice exactChoice ->
                                exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                        default -> Set.of();
                    };
                    yield Stream.concat(Stream.of(materials), Stream.of(Set.of(Material.FURNACE)));
                }
                case StonecuttingRecipe stonecuttingRecipe -> {
                    RecipeChoice inputChoice = stonecuttingRecipe.getInputChoice();
                    Set<Material> materials = switch (inputChoice) {
                        case RecipeChoice.MaterialChoice materialChoice ->
                                new HashSet<Material>(materialChoice.getChoices());
                        case RecipeChoice.ExactChoice exactChoice ->
                                exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                        default -> Set.of();
                    };
                    yield Stream.concat(Stream.of(materials), Stream.of(Set.of(Material.STONECUTTER)));
                }
                default -> Stream.of(Set.of());
            };
            List<Set<Material>> ingredientChoices = ingredientChoicesStream.toList();

            RecipeType recipeType = switch (recipe) {
                case ShapelessRecipe shapelessRecipe -> {
                    yield RecipeType.CRAFTING;
                }
                case ShapedRecipe shapedRecipe -> {
                    yield RecipeType.CRAFTING;
                }
                case CookingRecipe cookingRecipe -> {
                    yield RecipeType.COOKING;
                }
                case StonecuttingRecipe stonecuttingRecipe -> {
                    yield RecipeType.STONECUTTING;
                }
                default -> null;
            };
            if (recipeType == null) continue;
            record ConcreteRecipeAndUniques(List<Material> ingredients, int uniques) {}
            List<ConcreteRecipeAndUniques> allPossibleConcreteRecipes;
            try {
                allPossibleConcreteRecipes = cartesianProductGroupingIdenticalSets(ingredientChoices)
                        .stream()
                        .map((it) -> new ConcreteRecipeAndUniques(it, new HashSet<>(it).size()))
                        .sorted(Comparator.comparingInt(ConcreteRecipeAndUniques::uniques))
                        .toList();
            } catch (IllegalArgumentException e) {
                System.out.println(ingredientChoices);
                throw e;
            }

            int minimumUniqueMaterials = allPossibleConcreteRecipes.stream()
                    .map(ConcreteRecipeAndUniques::uniques)
                    .reduce(Math::min)
                    .orElse(0);
            List<List<Material>> minimumUniquesRecipes = allPossibleConcreteRecipes.stream()
                    .filter((it) -> it.uniques() == minimumUniqueMaterials)
                    .map(ConcreteRecipeAndUniques::ingredients)
                    .toList();
            // Solve furnace problem
            for (List<Material> ingredients : minimumUniquesRecipes) {
                nodes.add(new RecipeNode(recipe.getResult().getType(), recipe.getResult().getAmount(), ingredients, recipeType));
            }
        }
        return nodes;
    }

    List<List<Material>> cartesianProductGroupingIdenticalSets(List<Set<Material>> sets) {
        Map<Set<Material>, Long> deduplicatedMaterials = sets.stream().filter((it) -> !it.isEmpty()).collect(Collectors.groupingBy(
                it -> it,
                Collectors.counting()
        ));
        List<Set<Material>> deduplicatedMaterialsKeys = new ArrayList<>(deduplicatedMaterials.keySet());
        record ConcreteMaterialAndSetOriginatesFrom(Set<Material> setItOriginatesFrom, Material material) {}
        return Sets.cartesianProduct(deduplicatedMaterialsKeys).stream().map(
                (deduplicatedConcreteRecipe) -> {
                    return Streams.zip(deduplicatedMaterialsKeys.stream(), deduplicatedConcreteRecipe.stream(), ConcreteMaterialAndSetOriginatesFrom::new)
                            .flatMap((it) -> {
                                long count = deduplicatedMaterials.get(it.setItOriginatesFrom);
                                return Stream.generate(() -> it.material).limit(count);
                            }).toList();
                }
        ).toList();
    }

    public Map<UUID, ProbabilityBag.SampleResult> generateMaterials(List<? extends Player> alivePlayers, int level) {
        Map<UUID, ProbabilityBag.SampleResult> sampleResults = new HashMap<>();
        if (level == 1) {
            for (Player player : alivePlayers) {
                ProbabilityBag bag = getLevelOneBagForPlayer(player);
                ProbabilityBag.SampleResult sample = bag.sample(1 / 2D);
                sampleResults.put(player.getUniqueId(), sample);
            }
        }
        else {
            ProbabilityBag bag = getBagForPlayers(alivePlayers, level);
            List<ProbabilityBag.SampleResult> samples = bag.samples(alivePlayers.size(), Math.pow(1 / 2D, level));
            int i = 0;
            for (Player player : alivePlayers) {
                sampleResults.put(player.getUniqueId(), samples.get(i++));
                player.sendMessage(bag.entries(Math.pow(1 / 2D, level))
                        .stream()
                        .sorted((it1, it2) -> -Double.compare(it1.probability(), it2.probability()))
                        .limit(20)
                        .map((it) -> "{%s, %s}".formatted(it.material().name(), it.probability()))
                .collect(Collectors.joining(", ")));
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
                    bag.addWeighting(block.getType());
                    bag.addWeighting(block.getDrops().stream().map(ItemStack::getType).toList());
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
        List<Chunk> chunksToCheckForMobs = chunks.stream().limit(250).toList();
        chunks = chunks.stream().limit(30).toList();
        int averageY = (int) (alivePlayers.stream().map(Entity::getY).reduce(0D, Double::sum) / alivePlayers.size());

        HashMultimap<Long, Material> availableMaterialsPerChunkKey = HashMultimap.create();
        Map<Material, Integer> availableMaterials = new HashMap<>();
        Map<Material, Integer> availableBlocksToStandOn = new HashMap<>();
        for (Chunk chunk : chunksToCheckForMobs) {
            int minHeight = (int) Math.max(chunk.getWorld().getMinHeight(), averageY - ((Math.pow(2, level - 1) + 10)));
            List<Material> mobDrops = Arrays.stream(chunk.getEntities()).flatMap((entity) -> {
                if (entity.getY() > minHeight) {
                    LootTables lootTables = Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey().toLowerCase()));
                    if (lootTables == null) return Stream.of();
                    return lootTables.getLootTable().populateLoot(ThreadLocalRandom.current(), (new LootContext.Builder(entity.getLocation()).lootedEntity(entity).killer(alivePlayers.getFirst())).build())
                            .stream().map(ItemStack::getType);
                }
                return Stream.of();
            }).toList();
            availableMaterialsPerChunkKey.putAll(chunk.getChunkKey(), mobDrops);
            for (Material mobDrop : mobDrops) {
                // Weight mob drops much more highly
                availableMaterials.put(mobDrop, availableMaterials.getOrDefault(mobDrop, 0) + 200000);
            }
        }

        for (Chunk chunk : chunks) {
            int minHeight = (int) Math.max(chunk.getWorld().getMinHeight(), averageY - ((Math.pow(2, level - 1) + 10)));
            for (int y = minHeight; y < chunk.getWorld().getMaxHeight(); y++) {
                for (int bx = 0; bx < 4; bx++) {
                    for (int bz = 0; bz < 4; bz++) {
                        boolean seenAir = false;
                        for (int x = 0; x < 4; x++) {
                            for (int z = 0; z < 4; z++) {
                                Block block = chunk.getBlock(bx * 4 + x, y, bz * 4 + z);
                                if (block.getType().isAir()) {
                                    seenAir = true;
                                    continue;
                                };
                                int toAdd = (seenAir ? 4 : 1) * (y > 60 ? 4 : 1);
                                availableBlocksToStandOn.put(block.getType(), availableBlocksToStandOn.getOrDefault(block.getType(), 0) + toAdd);
                                availableMaterialsPerChunkKey.put(chunk.getChunkKey(), block.getType());
                                Collection<ItemStack> drops = block.getDrops();
                                for (ItemStack drop : drops) {
                                    availableMaterials.put(drop.getType(), availableMaterials.getOrDefault(drop.getType(), 0) + (toAdd * drop.getAmount()));
                                }
                                availableMaterialsPerChunkKey.putAll(chunk.getChunkKey(), block.getDrops().stream().map(ItemStack::getType).toList());
                            }
                        }
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

        for (Map.Entry<Material, Integer> entry : availableMaterials.entrySet()) {
            // Math.min is here as blocks in literally every chunk, but rare within each chunk were being overweighted previously
            availableMaterialsBag.addWeighting(entry.getKey(), Math.min(20, materialChunkOccurrences.getOrDefault(entry.getKey(), 0)) * entry.getValue());
        }

        for (Map.Entry<Material, Integer> entry : availableBlocksToStandOn.entrySet()) {
            // Math.min is here as blocks in literally every chunk, but rare within each chunk were being overweighted previously
            availableBlocksToStandOnBag.addWeighting(entry.getKey(), Math.min(20, materialChunkOccurrences.getOrDefault(entry.getKey(), 0)) * entry.getValue());
        }

        ProbabilityBag bag = runLoopyBeliefCraftingPropagation(availableMaterialsBag, 10);
        for (ProbabilityBag.Entry standOnEntry : availableBlocksToStandOnBag.entries()) {
            bag.addWeighting(standOnEntry.material(), standOnEntry.probability());
        }

        return bag;
    }

}
