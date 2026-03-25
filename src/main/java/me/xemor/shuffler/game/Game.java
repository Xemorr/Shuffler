package me.xemor.shuffler.game;

import me.xemor.shuffler.Shuffler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Biome;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

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
    private Map<UUID, Material> searchingFor = new HashMap<>();
    private final Map<UUID, Spawnpoint> spawnpoints = new HashMap<>();
    private Set<UUID> alivePlayers = new HashSet<>();
    private final Map<UUID, Integer> deadPlayers = new HashMap<>();
    private int level = 1;
    private final ItemGenerator itemGenerator = new ItemGenerator();
    private Instant roundStarted;
    private Instant roundEnds;
    private BossBar bossBar;
    private boolean gameStarted = false;
    private final EloHandler eloHandler = new EloHandler();

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
                                    ).thenAccept((it) -> {
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
        List<@NotNull Chunk> chunks = new ArrayList<>(Arrays.stream(world.getLoadedChunks())
                // Manhattan Distance is less than 19 chunk coordinates away from 0,0
                .filter(chunk -> chunk.getX() + chunk.getZ() <= 19)
                .toList());
        Collections.shuffle(chunks);
        List<@NotNull Chunk> nChunks = chunks.stream()
                .filter((chunk -> {
                    ChunkSnapshot chunkSnapshot = chunk.getChunkSnapshot(true, true, false);
                    int highestBlockYAt = chunkSnapshot.getHighestBlockYAt(0, 0);
                    Biome biome = chunkSnapshot.getBiome(0, highestBlockYAt, 0);
                    return !biome.getKey().asString().contains("ocean");
                }))
                .limit(players.size())
                .toList();

        Spawnpoint.SpawnType spawnType = STANDARD;
        if (nChunks.isEmpty() && chunks.isEmpty()) {
            Location location = new Location(
                    world,
                    0,
                    world.getHighestBlockYAt(0, 0) + 80,
                    0
            );
            for (Player player : players) {
                spawnpoints.put(player.getUniqueId(), new Spawnpoint(SKY, location));
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
            case Spawnpoint(Spawnpoint.SpawnType spawntype, Location location) when spawntype == SKY -> {
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 120, 1));
                player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 120, 1));
                yield player.teleportAsync(location);
            }
            default -> throw new IllegalStateException("Unexpected value: " + spawnpoint);
        };
    }

    public Material searchingFor(Player player) {
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
        level++;
        futuresWaitingOn.clear();

        if (alivePlayers.containsAll(searchingFor.keySet()) && searchingFor.keySet().containsAll(alivePlayers)) {
            roundEnds = Instant.now().plusSeconds(30);
        } else {
            deadPlayers.putAll(
                    searchingFor.keySet()
                    .stream()
                    .collect(Collectors.toMap((it) -> it, (_) -> level))
            );
            alivePlayers.removeAll(searchingFor.keySet());
            if (alivePlayers.size() <= 1) {
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
            eloHandler.updateElo(
                    calculateRanks().entrySet().stream().map((it) -> new EloHandler.PlayerRankPair(it.getValue(), it.getKey())).toList()
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

        if (alivePlayers.size() > 1) throw new IllegalStateException("more than 1 winner");

        Map<UUID, Integer> playersRanks = deadPlayersRanks;
        playersRanks.put(alivePlayers.iterator().next(), 1);

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
        bossBar.setTitle("Shuffling!");
        bossBar.setProgress(1.0);
        searchingFor = searchingFor.entrySet().stream()
                .filter((entry) -> alivePlayers.contains(entry.getKey()))
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);

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
        bossBar.setTitle("Round %s. Time Remaining: %s".formatted(level, timeRemainingInSeconds));
    }

    private void checkPlayerProgress() {
        for (UUID playerUUID : alivePlayers) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) continue;

            Material material = searchingFor.get(player.getUniqueId());

            if (hasPlayerFoundItem(player, material)) {
                searchingFor.remove(player.getUniqueId());
                announceItemFound(player, material);
            }

            updatePlayerActionBar(player, material);
        }
    }

    private boolean hasPlayerFoundItem(Player player, Material material) {
        boolean inInventory = Arrays.stream(player.getInventory().getContents())
                .anyMatch((item) -> item != null && item.getType() == material);

        return player.getLocation().subtract(0, 1, 0).getBlock().getType() == material
                || player.getLocation().getBlock().getType() == material
                || inInventory;
    }

    private void announceItemFound(Player player, Material material) {
        for (Player player1 : Bukkit.getOnlinePlayers()) {
            player1.sendMessage("%s has found %s".formatted(player.getName(), material));
        }
    }

    private void updatePlayerActionBar(Player player, Material material) {
        if (material == null) {
            player.sendActionBar(Component.text("(%s players left) You have found the item!".formatted(alivePlayers.size())));
        } else {
            player.sendActionBar(Component.text("(%s player left) Searching for %s!".formatted(alivePlayers.size(), material.name())));
        }
    }

    private int getMaximumTimeAllotted() {
        return 180 + Math.min(120, (level - 1) * 60);
    }

    public boolean isPlayerAlive(Player player) {
        return alivePlayers.contains(player.getUniqueId());
    }

}
