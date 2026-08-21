package me.xemor.shuffler.game;

import com.fasterxml.jackson.databind.util.LRUMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue;
import me.xemor.shuffler.Shuffler;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConstantItemGenerator implements Listener {

    private final List<RecipeNode> recipeNodes;
    private final ProbabilityBag availableMaterialsBag = new ProbabilityBag();
    private final ProbabilityBag availableBlocksToStandOnBag = new ProbabilityBag();

    public ConstantItemGenerator() {
        recipeNodes = unorderedRecipeNodes();
    }

    sealed interface ItemNode permits WorldNode, RecipeNode {
        Material material();
    }
    record WorldNode(Material material) implements ItemNode {}
    record RecipeNode(Material material, int amount, Map<Material, Integer> ingredients, RecipeType recipeType) implements ItemNode {
        public RecipeNode(Material material, int amount, Map<Material, Integer> ingredients, RecipeType recipeType) {
            Objects.requireNonNull(material, "Material cannot be null");
            Objects.requireNonNull(ingredients, "Ingredients cannot be null");
            Objects.requireNonNull(recipeType, "Recipe type cannot be null");
            this.material = material;
            this.amount = amount;
            this.ingredients = new HashMap<>(ingredients); // this must be mutable
            this.recipeType = recipeType;
        }
    }
    enum RecipeType {
        CRAFTING, COOKING, STONECUTTING
    }

    public record DijkstraNode(DijkstraLocation location, double distanceFromSource) {}

    public record DijkstraLocation(int x, int y, int z) {
        public DijkstraLocation(Location location) {
            this(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        public long toLong() {
            final int NUM_X_BITS = 26;
            final int NUM_Z_BITS = NUM_X_BITS;
            final int NUM_Y_BITS = 64 - NUM_X_BITS - NUM_Z_BITS;
            final int Y_SHIFT = NUM_Z_BITS;
            final int X_SHIFT = Y_SHIFT + NUM_Y_BITS;
            final long X_MASK = (1L << NUM_X_BITS) - 1L;
            final long Y_MASK = (1L << NUM_Y_BITS) - 1L;
            final long Z_MASK = (1L << NUM_Z_BITS) - 1L;
            return ((long) x & X_MASK) << X_SHIFT | ((long) y & Y_MASK) << Y_SHIFT | ((long) z & Z_MASK);
        }

        public Location toLocation(World world) {
            return new Location(world, x, y, z);
        }

        public DijkstraLocation add(int x, int y, int z) {
            return new DijkstraLocation(this.x + x, this.y + y, this.z + z);
        }

        public DijkstraLocation clone() {
            return new DijkstraLocation(x, y, z);
        }
    }

    public void setupLegalItemsTracker(Game game) {
        new BukkitRunnable() {
            private ObjectHeapPriorityQueue<DijkstraNode> queue = null;
            private LongOpenHashSet visited = null;
            private final LRUMap<Material, Collection<ItemStack>> dropsCache = new LRUMap<>(100, 100);
            private Set<UUID> seenEntities = null;
            private World world = null;
            private int ticksRunning = 0;
            private DijkstraLocation start = null;
            private Player selectedPlayer = null;
            private double maxDistance = 0;
            private int yCap = 1000;
            private final int MAX_TICKS = 200;
            private int BUDGET_PER_TICK = 6000;
            private int BUDGET_DELTA = 1000;

            @Override
            public void run() {
                if (queue == null || !selectedPlayer.isOnline()) {
                    List<? extends Player> players = Bukkit.getOnlinePlayers().stream()
                            .filter(game::isPlayerAlive).toList();
                    if (players.isEmpty()) return;

                    selectedPlayer = players.get(ThreadLocalRandom.current().nextInt(players.size()));
                    world = selectedPlayer.getWorld();
                    start = new DijkstraLocation(selectedPlayer.getEyeLocation());

                    queue = new ObjectHeapPriorityQueue<>(Comparator.comparing(DijkstraNode::distanceFromSource));
                    visited = new LongOpenHashSet();
                    seenEntities = new HashSet<>();
                    ticksRunning = 0;
                    maxDistance = 0;
                    yCap = selectedPlayer.getWorld().getHighestBlockYAt(start.x(), start.z()) + 5;

                    queue.enqueue(new DijkstraNode(start, 0));
                    visited.add(new DijkstraLocation(start.x(), start.y(), start.z()).toLong());
                }

                int processedThisTick = 0;
                while (!queue.isEmpty() && processedThisTick < BUDGET_PER_TICK) {
                    DijkstraNode node = queue.dequeue();
                    DijkstraLocation location = node.location();
                    processedThisTick++;

                    maxDistance = Math.max(maxDistance, node.distanceFromSource());
                    double distanceMultiplier = distanceMultiplier(node.distanceFromSource());

                    Block block = location.toLocation(world).getBlock();
                    if (!block.getType().isAir()) {
                        Collection<ItemStack> drops = dropsCache.get(block.getType());
                        if (drops == null) {
                            drops = block.getDrops();
                            dropsCache.put(block.getType(), drops);
                        }
                        for (ItemStack drop : drops) {
                            availableMaterialsBag.addWeighting(drop.getType(), distanceMultiplier);
                        }
                        availableBlocksToStandOnBag.addWeighting(block.getType(), distanceMultiplier);
                    }
                    int i = ThreadLocalRandom.current().nextInt(5 * 5 * 5);
                    if (i == 1) {
                        Collection<Entity> nearbyEntities = world.getNearbyEntities(location.toLocation(world), 5, 5, 5);
                        List<Material> mobDrops = nearbyEntities.stream().filter(entity -> !seenEntities.contains(entity.getUniqueId())).flatMap((entity) -> {
                            seenEntities.add(entity.getUniqueId());
                            if (entity instanceof Ageable ageable && !ageable.isAdult()) return Stream.of();
                            EntityType type = entity.getType();
                            LootTables lootTables = switch (type) {
                                case SHEEP -> {
                                    assert entity instanceof Sheep;
                                    yield Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey().toLowerCase() + "/" + ((Sheep) entity).getColor().name().toLowerCase()));
                                }
                                default -> Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey().toLowerCase()));
                            };
                            if (lootTables == null) return Stream.of();
                            if (lootTables == null && type == EntityType.SHEEP) {
                                System.out.println("sheep broken");
                                return Stream.of();
                            }
                            return lootTables.getLootTable().populateLoot(ThreadLocalRandom.current(), (new LootContext.Builder(entity.getLocation()).lootedEntity(entity).killer(selectedPlayer)).build())
                                    .stream().map(ItemStack::getType);
                        }).toList();
                        for (Material mobDrop : mobDrops) {
                            availableMaterialsBag.addWeighting(mobDrop, 1000 * distanceMultiplier);
                        }
                    }

                    double levelVerticalMultiplier = Math.max(1, (3.5 - (game.getLevel() / 2D)));
                    double levelOccludingMultiplier = Math.max(1, (5 - (game.getLevel() / 2D)));
                    for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                        DijkstraLocation neighbour = location.add(face.getModX(), face.getModY(), face.getModZ());
                        if (neighbour.y() > yCap) continue;
                        if (visited.add(neighbour.toLong())) {
                            boolean occluding = block.getType().isOccluding();
                            boolean water = block.getType() == Material.WATER;
                            double weighting = 1;
                            if (water) weighting *= 1.25 * levelOccludingMultiplier;
                            else if (occluding) weighting *= 1.5 * levelOccludingMultiplier;
                            if (face == BlockFace.UP) weighting *= 1.25;
                            if (face == BlockFace.UP || face == BlockFace.DOWN) weighting *= levelVerticalMultiplier;
                            queue.enqueue(new DijkstraNode(neighbour, node.distanceFromSource() + weighting));
                        }
                    }
                }

                ticksRunning++;

                if (queue.isEmpty() || ticksRunning >= MAX_TICKS) {
                    if (selectedPlayer != null && selectedPlayer.isOnline()) {
                        selectedPlayer.sendMessage(String.format("§aTracker reached maximum spread: §Distance: %.2f blocks", maxDistance));
                    }
                    if (ticksRunning >= MAX_TICKS) {
                        if (!queue.isEmpty()) {
                            Shuffler.getInstance().getLogger().fine("Tracker did not finish in " + MAX_TICKS + " ticks. Remaining queue size: " + queue.size());
                            if (Bukkit.getTPS()[0] >= 19.9) {
                                BUDGET_PER_TICK += BUDGET_DELTA;
                                BUDGET_DELTA = Math.max(50, BUDGET_DELTA - 5);
                            }
                            else {
                                BUDGET_PER_TICK -= BUDGET_DELTA;
                                BUDGET_DELTA = Math.max(50, BUDGET_DELTA - 5);
                            }
                            System.out.println(BUDGET_PER_TICK);
                        }
                        queue = null;
                    }
                }
            }
        }.runTaskTimer(Shuffler.getInstance(), 0, 1);
    }

    private double distanceMultiplier(double distanceFromSource) {
        return Math.pow(0.985, distanceFromSource);
    }

    private ProbabilityBag flattenedCraftingProbabilities(ProbabilityBag rootBag, int iterations) {
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
                    Map<Material, Integer> groupedIngredients = recipe.ingredients();

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
                    Map<Material, Integer> groupedIngredients = recipe.ingredients();

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
                case ShapelessRecipe shapelessRecipe -> {
                    Stream<Set<Material>> ingredients = shapelessRecipe.getChoiceList().stream().filter(Objects::nonNull).map((choice) -> {
                        return (switch (choice) {
                            case RecipeChoice.MaterialChoice materialChoice -> {
                                yield new HashSet<>(materialChoice.getChoices());
                            }
                            case RecipeChoice.ExactChoice exactChoice ->
                                    exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                            default -> Set.of();
                        });
                    });
                    /*if (shapelessRecipe.getChoiceList().size() > 4) {
                        yield Stream.concat(ingredients, Stream.of(Set.of(Material.CRAFTING_TABLE)));
                    } else yield ingredients;
                     */
                    yield ingredients;
                }
                case ShapedRecipe shapedRecipe -> {
                    Stream<Set<Material>> ingredients = Arrays.stream(shapedRecipe.getShape()).flatMapToInt(String::chars).mapToObj((shapeIdentifier) -> {
                        RecipeChoice choice = shapedRecipe.getChoiceMap().get((char) shapeIdentifier);
                        if (choice == null) return Set.of();
                        return (switch (choice) {
                            case RecipeChoice.MaterialChoice materialChoice -> new HashSet<>(materialChoice.getChoices());
                            case RecipeChoice.ExactChoice exactChoice ->
                                    exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                            default -> Set.of();
                        });
                    });
                    /*String[] shape = shapedRecipe.getShape();
                    if (shape.length <= 2
                            && (shape.length == 0 || shape[0].length() <= 2)
                            && (shape.length <= 1 || shape[1].length() <= 2)) {
                        yield ingredients;
                    } else yield Stream.concat(ingredients, Stream.of(Set.of(Material.CRAFTING_TABLE)));
                     */
                    yield ingredients;
                }
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
                        case RecipeChoice.MaterialChoice materialChoice -> new HashSet<>(materialChoice.getChoices());
                        case RecipeChoice.ExactChoice exactChoice ->
                                exactChoice.getChoices().stream().map(ItemStack::getType).collect(Collectors.toSet());
                        default -> Set.of();
                    };
                    yield Stream.concat(Stream.of(materials), Stream.of(Set.of(Material.STONECUTTER)));
                }
                default -> Stream.of(Set.of());
            };
            List<Set<Material>> listOfChoicesInASlot = ingredientChoicesStream.toList();

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
                allPossibleConcreteRecipes = cartesianProductGroupingIdenticalSets(listOfChoicesInASlot)
                        .stream()
                        .map((it) -> new ConcreteRecipeAndUniques(it, new HashSet<>(it).size()))
                        .sorted(Comparator.comparingInt(ConcreteRecipeAndUniques::uniques))
                        .toList();
            } catch (IllegalArgumentException e) {
                System.out.println(listOfChoicesInASlot);
                throw e;
            }

            // Solve furnace problem
            int minimumUniqueMaterials = allPossibleConcreteRecipes.stream()
                    .map(ConcreteRecipeAndUniques::uniques)
                    .reduce(Math::min)
                    .orElse(0);
            List<List<Material>> minimumUniquesRecipes = allPossibleConcreteRecipes.stream()
                    .filter((it) -> it.uniques() == minimumUniqueMaterials)
                    .map(ConcreteRecipeAndUniques::ingredients)
                    .toList();

            for (List<Material> ingredients : minimumUniquesRecipes) {
                nodes.add(
                    new RecipeNode(
                        recipe.getResult().getType(),
                        recipe.getResult().getAmount(),
                        ingredients.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.summingInt(_ -> 1))),
                        recipeType
                    )
                );
            }
        }

        /*
        SetMultimap<Material, RecipeNode> lookUpTable = nodes.stream()
                .collect(Multimaps.toMultimap(
                        RecipeNode::material,    // Function to extract the key
                        v -> v,         // Function to extract the value
                        MultimapBuilder.hashKeys().hashSetValues()::build
                ));
        record SetToCount<T>(Set<T> recipes, int count) {}
        record ListToCount<T>(Map<T, Integer> recipes, int count) {}

        record MapWithFunkyEqualsAndHashCode(Map<Material, Integer> map) {
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                MapWithFunkyEqualsAndHashCode that = (MapWithFunkyEqualsAndHashCode) o;
                return map.equals(that.map);
            }
        }

        nodes = nodes.stream().flatMap((it) -> {
            SetOfMapInteger setOfMapInteger = new SetOfMapInteger();
            setOfMapInteger.add(it.ingredients());
            int previousSize = 1;
            for (int i = 0; i < 10; i++) {
                Set<Map<Material, Integer>> newList = new HashSet<>();
                for (Map<Material, Integer> ingredients : setOfMapInteger.getAllOptimalMaps()) {
                    Map<Material, Integer> groupedIngredients = ingredients;
                    List<Set<ListToCount<Material>>> toCartesianProduct = groupedIngredients.entrySet().stream()
                            .map((entry) -> new SetToCount<>(lookUpTable.get(entry.getKey()), entry.getValue()))
                            .map((recipesSetToCount) -> {
                                Stream<Map<Material, Integer>> subIngredients = recipesSetToCount.recipes().stream().map(RecipeNode::ingredients);
                                return subIngredients
                                        .map((ingredient) -> new ListToCount<>(ingredient, recipesSetToCount.count()))
                                        .collect(Collectors.toSet());
                            })
                            .toList();

                    Set<List<ListToCount<Material>>> ingredientOptions = Sets.cartesianProduct(
                            toCartesianProduct
                    );

                    newList.addAll(ingredientOptions.stream()
                            .map((listOfListToCount) -> listOfListToCount.stream()
                                    .flatMap((listToCount) -> IntStream.range(0, listToCount.count())
                                            .boxed()
                                            .flatMap((_) -> listToCount.recipes().entrySet().stream())
                                    )
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum))
                            ).collect(Collectors.toSet()));
                }
                setOfMapInteger.addAll(newList);
                if (previousSize == setOfMapInteger.size()) break;
                else previousSize = setOfMapInteger.size();
            }
            return setOfMapInteger.getAllOptimalMaps().stream().map((ingredients) -> new RecipeNode(it.material(), it.amount(), ingredients, it.recipeType()));
        }).toList();
         */

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

    public Map<UUID, ProbabilityBag> generateLevelOneBags(Collection<? extends Player> alivePlayers) {
        Map<UUID, ProbabilityBag> sampleResults = new HashMap<>();
        for (Player player : alivePlayers) {
            ProbabilityBag bag = getLevelOneBagForPlayer(player);
            sampleResults.put(player.getUniqueId(), bag);
        }
        return sampleResults;
    }

    public record GenerationResult(Map<UUID, ProbabilityBag.SampleResult> sampleResults, List<Material> possibleMaterials) {}

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

    public ProbabilityBag getBagForPlayers(Collection<? extends Player> alivePlayers) {
        if (alivePlayers.isEmpty()) throw new IllegalArgumentException("Alive players must contain at least one player");

        ProbabilityBag bag = runLoopyBeliefCraftingPropagation(availableMaterialsBag, 10);
        for (ProbabilityBag.Entry standOnEntry : availableBlocksToStandOnBag.entries()) {
            bag.addWeighting(standOnEntry.material(), standOnEntry.probability());
        }

        return bag;
    }

}
