package me.xemor.shuffler.game;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.SetMultimap;
import me.xemor.shuffler.Shuffler;
import me.xemor.shuffler.rating.RatingHandler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static me.xemor.shuffler.game.Spawnpoint.SpawnType.*;
import static org.bukkit.Bukkit.getServer;

public class Game {

    private static Game instance;

    private final List<CompletableFuture<Void>> futuresWaitingOn = new ArrayList<>();
    private SetMultimap<UUID, Material> searchingFor = MultimapBuilder.hashKeys().hashSetValues().build();
    private final Map<UUID, Spawnpoint> spawnpoints = new HashMap<>();
    private Set<UUID> alivePlayers = new HashSet<>();
    private final Map<UUID, Integer> deadPlayers = new HashMap<>();
    private int level = 1;
    private final ItemGenerator itemGenerator = new ItemGenerator();
    private Instant roundStarted;
    private Instant roundEnds;
    private BossBar bossBar;
    private boolean gameStarted = false;
    private boolean extension = false;
    private final RatingHandler ratingHandler = new RatingHandler();

    private Game(Shuffler shuffler) {
        shuffler.getServer().getPluginManager().registerEvents(new PlayerRespawnHandler(this), shuffler);
    }

    public static Game getInstance() {
        if (instance == null) {
            instance = new Game(Shuffler.getInstance());
        }
        return instance;
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public void startGame() {
        if (gameStarted) {
            return;
        }
        if (Shuffler.getInstance().getConfigYaml().automating().cloudnet().markAsInGame()) {
            CloudNetHelper.setInstanceToInGameAndStartNewInstance();
        }
        gameStarted = true;
        level = 1;
        bossBar = Bukkit.createBossBar("Game Starting...", BarColor.WHITE, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
        alivePlayers = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(HashSet::new, HashSet::add, HashSet::addAll);
        for (Player player : Bukkit.getOnlinePlayers()) {
            discoverRecipes(player);
            player.setHealth(player.getAttribute(Attribute.MAX_HEALTH).getValue());
            player.getInventory().clear();
            player.setItemOnCursor(null);
            player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 8));
            player.getInventory().addItem(new ItemStack(Material.TORCH, 64));
            for (String playerCommand : Shuffler.getInstance().getConfigYaml().automating().commandsPerPlayerOnStart()) {
                getServer().dispatchCommand(Bukkit.getConsoleSender(), playerCommand.replace("<player>", player.getName()));
            }
        }
        List<CompletableFuture<Boolean>> futures = teleportPlayersAndSetSpawnpoints(Bukkit.getOnlinePlayers());
        for (String command : Shuffler.getInstance().getConfigYaml().automating().commandsOnStart()) {
            getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            Map<UUID, ProbabilityBag> bags = itemGenerator.generateLevelOneBags(Bukkit.getOnlinePlayers());
                            ProbabilityBag bag = bags.get(player.getUniqueId());
                            ProbabilityBag.SampleResult sample = bag.sample(1);
                            futuresWaitingOn.add(new SlotMachineAnimation(Shuffler.getPlugin(Shuffler.class))
                                    .playAnimation(
                                            player,
                                            bag.getSamplesAroundItem(sample.material(), 20, 1D),
                                            sample.material(),
                                            180
                                    ).thenAccept((_) -> {
                                        searchingFor.put(player.getUniqueId(), sample.material());
                                        roundStarted = Instant.now();
                                        roundEnds = roundStarted.plusSeconds(180);
                                    }));
                            roundStarted = Instant.now();
                            roundEnds = roundStarted.plusSeconds(180);
                        }
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                tick();
                            }
                        }.runTaskTimer(Shuffler.getPlugin(Shuffler.class), 0, 1);
                    }
                }.runTask(Shuffler.getPlugin(Shuffler.class));
            }
        }.runTaskAsynchronously(Shuffler.getPlugin(Shuffler.class));
    }

    private List<CompletableFuture<Boolean>> teleportPlayersAndSetSpawnpoints(Collection<? extends Player> players) {
        World world = Bukkit.getWorld(Shuffler.getInstance().getConfigYaml().game().world());
        if (world == null) throw new IllegalStateException("Invalid World Specified at config.yml/game.world");
        Location sumLocation = players.stream()
                .map(Entity::getLocation)
                .reduce(Location::add)
                .orElse(world.getSpawnLocation().multiply(players.size()));
        Location origin = sumLocation.multiply(1 / (double) players.size());
        List<@NotNull Chunk> chunks = new ArrayList<>(Arrays.stream(world.getLoadedChunks())
                // Manhattan Distance is less than 25 chunk coordinates away from origin
                .filter(chunk -> Math.abs(chunk.getX() + chunk.getZ() - origin.getChunk().getX() - origin.getChunk().getZ()) <= 25)
                .toList());
        Collections.shuffle(chunks);

        List<@NotNull Chunk> nChunks = chunks.stream()
                .filter((chunk -> {
                    ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot(true, true, false);
                    int highestBlockYAt = chunkSnapshot.getHighestBlockYAt(0, 0);
                    Biome biome = chunkSnapshot.getBiome(0, highestBlockYAt - 2, 0);
                    return !biome.getKey().asString().contains("ocean");
                }))
                .limit(players.size())
                .toList();

        Spawnpoint.SpawnType spawnType = STANDARD;
        if (nChunks.isEmpty() && chunks.isEmpty()) {
            Location location = new Location(
                    world,
                    0,
                    world.getHighestBlockYAt(0, 0),
                    0
            );
            for (Player player : players) {
                spawnpoints.put(player.getUniqueId(), new Spawnpoint(NO_CHUNKS, location));
            }
        }
        else {
            if (nChunks.isEmpty()) {
                nChunks = chunks.stream().limit(players.size()).toList();
                spawnType = BOAT;
            }
            Iterator<? extends Player> playerIterator = players.iterator();
            for (int i = 0; i < players.size(); i++) {
                Chunk chunkToSpawnAt = nChunks.get(i % nChunks.size());
                Player player = playerIterator.next();
                int x1 = ThreadLocalRandom.current().nextInt(16);
                int z1 = ThreadLocalRandom.current().nextInt(16);
                Location locationWithoutY = new Location(world, chunkToSpawnAt.getX() * 16 + x1, 0, chunkToSpawnAt.getZ() + z1);
                int highestBlockYAt = world.getHighestBlockYAt(locationWithoutY);
                locationWithoutY.setY(highestBlockYAt + 2);
                Location location = locationWithoutY;
                spawnpoints.put(player.getUniqueId(), new Spawnpoint(spawnType, location));
            }
        }
        return players.stream().map(this::spawnPlayer).toList();
    }

    public CompletableFuture<Boolean> spawnPlayer(Player player) {
        Spawnpoint spawnpoint = spawnpoints.get(player.getUniqueId());
        return switch (spawnpoint) {
            case Spawnpoint(Spawnpoint.SpawnType spawntype, Location location) when spawntype == STANDARD -> {
                yield player.teleportAsync(location);
            }
            case Spawnpoint(Spawnpoint.SpawnType spawntype, Location location) when spawntype == BOAT -> {
                player.getInventory().addItem(new ItemStack(Material.OAK_BOAT, 1));
                yield player.teleportAsync(location);
            }
            case Spawnpoint(Spawnpoint.SpawnType spawntype, Location location) when spawntype == NO_CHUNKS -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 20 * 120, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 120, 1));
                yield player.teleportAsync(location);
            }
            default -> throw new IllegalStateException("Unexpected value: " + spawnpoint);
        };
    }

    public Set<Material> searchingFor(Player player) {
        return searchingFor.get(player.getUniqueId());
    }

    public void discoverRecipes(Player player) {
        getServer().recipeIterator().forEachRemaining(recipe -> {
            if (recipe instanceof ShapelessRecipe shapelessRecipe) {
                player.discoverRecipe(shapelessRecipe.getKey());
            } else if (recipe instanceof ShapedRecipe shapedRecipe) {
                player.discoverRecipe(shapedRecipe.getKey());
            }
        });
    }

    public void tick() {
        updateSpectatorMode();

        boolean notDuringAnimation = futuresWaitingOn.stream().allMatch(CompletableFuture::isDone);
        if (!notDuringAnimation) return;

        long timeRemainingInSeconds = roundEnds.getEpochSecond() - Instant.now().getEpochSecond();

        if (timeRemainingInSeconds <= 0 || searchingFor.isEmpty()) {
            handleRoundEnd();
        } else {
            handleActiveRound(timeRemainingInSeconds);
        }
    }

    private void updateSpectatorMode() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!alivePlayers.contains(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private void handleRoundEnd() {
        futuresWaitingOn.clear();
        if (alivePlayers.containsAll(searchingFor.keySet()) && searchingFor.keySet().containsAll(alivePlayers) && !extension) {
            roundEnds = Instant.now().plusSeconds(300);
            extension = true;

            Component mainTitle = MiniMessage.miniMessage().deserialize("<rainbow>Extension</rainbow>");
            Component subtitle = Component.text("Find any item to survive this round!", NamedTextColor.WHITE);

            Title.Times times = Title.Times.times(
                    Duration.ofMillis(500),  // Fade in
                    Duration.ofSeconds(3),   // Stay
                    Duration.ofMillis(500)   // Fade out
            );

            Title title = Title.title(mainTitle, subtitle, times);

            List<Material> allMaterialsToSearchFor = new ArrayList<>(searchingFor.values());
            searchingFor.clear();
            for (UUID alivePlayer : alivePlayers) {
                searchingFor.putAll(alivePlayer, allMaterialsToSearchFor);
                Player player = Bukkit.getPlayer(alivePlayer);
                if (player == null) continue;
                player.showTitle(title);
            }
        } else {
            level++;
            deadPlayers.putAll(
                    searchingFor.keySet()
                    .stream()
                    .collect(Collectors.toMap((it) -> it, (_) -> level))
            );
            alivePlayers.removeAll(searchingFor.keySet());
            if (alivePlayers.isEmpty() || !deadPlayers.isEmpty() && alivePlayers.size() == 1) {
                handleGameEnd();
            } else {
                startNextLevel();
            }
        }
    }

    private void handleGameEnd() {
        futuresWaitingOn.add(new CompletableFuture<>()); // future that never ends to block future ticking.
        showGameEndTitles();

        if (alivePlayers.size() + deadPlayers.size() >= 2) {
            ratingHandler.updateElo(
                    calculateRanks().entrySet().stream().map((it) -> new RatingHandler.PlayerRankPair(it.getValue(), it.getKey())).toList()
            );
        }

        if (Shuffler.getInstance().getConfigYaml().automating().stopServerOnGameEnd()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    getServer().shutdown();
                }
            }.runTaskLater(Shuffler.getInstance(), 100L);
        }
    }

    private Map<UUID, Integer> calculateRanks() {
        List<Integer> sortedLevels = deadPlayers.values().stream()
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();

        Map<Integer, Integer> levelToRank = IntStream.range(0, sortedLevels.size())
                .boxed()
                .collect(Collectors.toMap(
                        sortedLevels::get,
                        index -> index + 1
                ));

        Map<UUID, Integer> deadPlayersRanks = deadPlayers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> levelToRank.get(entry.getValue()) + 1 // + 1 to account for winner yet to be added
                ));

        Map<UUID, Integer> playersRanks = deadPlayersRanks;
        for (UUID player : alivePlayers) {
            playersRanks.put(player, 1);
        }

        return playersRanks;
    }

    private void showGameEndTitles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (alivePlayers.contains(player.getUniqueId())) {
                player.showTitle(Title.title(Component.text("Congratulations!"), Component.text("You won!")));
            } else {
                OfflinePlayer winner = null;
                try {
                    winner = Bukkit.getOfflinePlayer(alivePlayers.iterator().next());
                } catch (NoSuchElementException _) {}

                if (winner != null) {
                    player.showTitle(Title.title(Component.text("Game Over"), Component.text("The winner is %s".formatted(winner.getName()))));
                } else {
                    player.showTitle(Title.title(Component.text("Game Over"), Component.text("No one won!")));
                }
            }
        }
    }

    private void startNextLevel() {
        extension = false;
        bossBar.setTitle("Shuffling!");
        bossBar.setProgress(1.0);
        searchingFor.clear();

        ProbabilityBag bag = itemGenerator.getBagForPlayers(
                alivePlayers.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList(),
                level
        );
        double weightingCap = Math.pow(1 / 3D, level - 1);
        List<ProbabilityBag.SampleResult> samples = bag.samples(alivePlayers.size(), weightingCap);

        Iterator<UUID> iterator = alivePlayers.iterator();
        for (int i = 0; i < alivePlayers.size(); i++) {
            Player player = Bukkit.getPlayer(iterator.next());
            if (player == null) continue;

            ProbabilityBag.SampleResult sample = samples.get(i);
            futuresWaitingOn.add(
                    new SlotMachineAnimation(Shuffler.getPlugin(Shuffler.class))
                            .playAnimation(
                                    player,
                                    bag.getSamplesAroundItem(sample.material(), 20, weightingCap),
                                    sample.material(),
                                    180
                            ).thenAccept((_) -> {
                                searchingFor.put(player.getUniqueId(), sample.material());
                                player.sendMessage("You are searching for %s, p = %s".formatted(sample.material().name(), sample.weighting()));
                                roundStarted = Instant.now();
                                roundEnds = roundStarted.plusSeconds(getMaximumTimeAllotted());
                            })
            );
        }
    }

    private void handleActiveRound(long timeRemainingInSeconds) {
        updateBossBar(timeRemainingInSeconds);
        checkPlayerProgress();
    }

    private void updateBossBar(long timeRemainingInSeconds) {
        bossBar.setProgress((double) timeRemainingInSeconds / getMaximumTimeAllotted());
        if (!extension) {
            bossBar.setTitle("Round %s. Time Remaining: %s".formatted(level, timeRemainingInSeconds));
        }
        else {
            bossBar.setTitle("EXTENSION! Time Remaining: %s".formatted(timeRemainingInSeconds));
        }
    }

    private void checkPlayerProgress() {
        for (UUID playerUUID : alivePlayers) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) continue;

            Set<Material> materials = searchingFor.get(player.getUniqueId());

            if (hasPlayerFoundItem(player, materials)) {
                announceItemFound(player, materials);
                searchingFor.removeAll(player.getUniqueId());
            }

            updatePlayerActionBar(player, materials);
        }
    }

    private boolean hasPlayerFoundItem(Player player, Set<Material> materials) {
        boolean inInventory = Arrays.stream(player.getInventory().getContents())
                .anyMatch((item) -> item != null && materials.contains(item.getType()));

        return materials.contains(player.getLocation().subtract(0, 1, 0).getBlock().getType())
                || materials.contains(player.getLocation().getBlock().getType())
                || inInventory;
    }

    private void announceItemFound(Player player, Set<Material> materials) {
        for (Player player1 : Bukkit.getOnlinePlayers()) {
            if (materials.size() == 1) {
                player1.sendMessage(Component.text("%s has found ".formatted(player.getName()))
                        .append(SearchingForFormatter.formatSearchMessage(Set.of(materials.iterator().next())))
                        .append(Component.text("!")));
            }
            else {
                player1.sendMessage("%s has found their item!".formatted(player.getName()));
            }
        }
    }

    private void updatePlayerActionBar(Player player, Set<Material> materials) {
        if (materials.isEmpty()) {
            player.sendActionBar(Component.text("(%s players left) You have found the item!".formatted(alivePlayers.size())));
        } else {
            player.sendActionBar(
                    Component.text("(%s player left) Searching for ".formatted(alivePlayers.size()))
                            .append(SearchingForFormatter.formatSearchMessage(materials))
                            .append(Component.text("!"))
            );
        }
    }

    private int getMaximumTimeAllotted() {
        return 180 + Math.min(120, (level - 1) * 60);
    }

    public boolean isPlayerAlive(Player player) {
        return alivePlayers.contains(player.getUniqueId());
    }

}
