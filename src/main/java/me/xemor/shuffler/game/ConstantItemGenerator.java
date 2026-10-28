package me.xemor.shuffler.game;

import me.xemor.shuffler.game.crafting.CraftingDifficultyModel;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import me.xemor.shuffler.Shuffler;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.Listener;
import org.bukkit.inventory.*;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTables;

import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConstantItemGenerator implements Listener {

    private final List<RecipeNode> recipeNodes;
    private final CraftingDifficultyModel craftingDifficultyModel;
    /** What has been found around each player since the round began. */
    private final Map<UUID, SampleHistory> histories = new HashMap<>();
    /** Each finished round's bag, worked out once and never revisited. */
    private final List<FrozenRound> frozenRounds = new ArrayList<>();

    /**
     * @param searches how many searches the round's bag is made from, which is how much it counts when rounds are merged
     */
    private record FrozenRound(ProbabilityBag bag, int searches) {}
    /** Seconds apart that finds can be and still largely come from the same place. */
    private static final double SAME_PLACE_TAU = 120;
    /** Seconds between the moments whose surroundings are worked out and averaged. */
    private static final double WINDOW_SPACING = 60;

    public ConstantItemGenerator() {
        this(CraftingDifficultyModel.Type.fromConfig(Shuffler.getInstance().getConfigYaml().game().craftingModel()));
    }

    public ConstantItemGenerator(CraftingDifficultyModel.Type craftingModelType) {
        recipeNodes = unorderedRecipeNodes();
        craftingDifficultyModel = CraftingDifficultyModel.create(craftingModelType, recipeNodes);
    }

    sealed interface ItemNode permits WorldNode, RecipeNode {
        Material material();
    }
    record WorldNode(Material material) implements ItemNode {}
    public record RecipeNode(Material material, int amount, Map<Material, Integer> ingredients, RecipeType recipeType) implements ItemNode {
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
    public enum RecipeType {
        CRAFTING(null), COOKING(Material.FURNACE), STONECUTTING(Material.STONECUTTER);

        private final Material workstation;

        RecipeType(Material workstation) {
            this.workstation = workstation;
        }

        /**
         * @return the workstation unorderedRecipeNodes adds as an ingredient of this recipe type, or null if none
         */
        public Material workstation() {
            return workstation;
        }
    }

    /**
     * Starts sampling around the players of this game, which fills the bags {@link #getBagForPlayers} reads.
     */
    public void setupLegalItemsTracker(Game game) {
        histories.clear();
        frozenRounds.clear();
        new SampleTracker(game, histories).runTaskTimer(Shuffler.getInstance(), 0, 1);
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

    /**
     * Freezes the round that has just finished and returns the bag of all the rounds so far. Call it once per round.
     * <p>
     * Within the round, each player's finds are smoothed over time on their own, since finds close in time are more
     * likely to be from the same place, but finds by different players are independent. Crafting is worked out on each
     * player's smoothed windows, and only then are the players merged ({@link SampleHistory}). That bag is never worked
     * out again: the rounds are merged in proportion to how many searches each is made from, so smoothing only ever
     * looks at one round's samples. Players who have not been sampled yet add nothing.
     */
    public ProbabilityBag getBagForPlayers(Collection<? extends Player> alivePlayers) {
        if (alivePlayers.isEmpty()) throw new IllegalArgumentException("Alive players must contain at least one player");

        freezeRound(alivePlayers);
        int searches = frozenRounds.stream().mapToInt(FrozenRound::searches).sum();
        ProbabilityBag merged = new ProbabilityBag();
        for (FrozenRound round : frozenRounds) merged.addNormalised(round.bag(), (double) round.searches() / searches);
        return merged;
    }

    /**
     * Works out the bag of the samples taken since the last round and starts the next round's samples afresh.
     */
    private void freezeRound(Collection<? extends Player> alivePlayers) {
        List<SampleHistory> roundHistories = alivePlayers.stream()
                .map(player -> histories.get(player.getUniqueId()))
                .filter(Objects::nonNull)
                .toList();
        int searches = roundHistories.stream().mapToInt(SampleHistory::size).sum();
        histories.clear();
        if (searches == 0) return;

        ProbabilityBag bag = SampleHistory.bag(roundHistories, craftingDifficultyModel, SAME_PLACE_TAU, WINDOW_SPACING);
        if (!bag.isEmpty()) frozenRounds.add(new FrozenRound(bag, searches));
    }

    /**
     * @return the materials one kill of this mob would drop, rolled from its loot table; none for babies and for mobs
     * without a loot table
     * @param killer who killed it, or null for nobody
     */
    static List<Material> mobDrops(Entity entity, @Nullable Player killer) {
        if (entity instanceof Ageable ageable && !ageable.isAdult()) return List.of();
        EntityType type = entity.getType();
        LootTables lootTables = switch (type) {
            case SHEEP -> {
                assert entity instanceof Sheep;
                yield Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey().toLowerCase() + "/" + ((Sheep) entity).getColor().name().toLowerCase()));
            }
            default -> Registry.LOOT_TABLES.get(NamespacedKey.minecraft("entities/" + entity.getType().getKey().getKey().toLowerCase()));
        };
        if (lootTables == null) return List.of();
        LootContext.Builder context = new LootContext.Builder(entity.getLocation()).lootedEntity(entity);
        if (killer != null) context.killer(killer);
        return lootTables.getLootTable().populateLoot(ThreadLocalRandom.current(), context.build())
                .stream().map(ItemStack::getType).toList();
    }

}
