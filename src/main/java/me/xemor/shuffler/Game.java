package me.xemor.shuffler;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.bukkit.Bukkit.getServer;

public class Game {

    private final List<CompletableFuture<Void>> futuresWaitingOn = new ArrayList<>();
    private Map<UUID, Material> searchingFor = new HashMap<>();
    private Set<UUID> alivePlayers = new HashSet<>();
    private int level = 1;
    private ItemGenerator itemGenerator = new ItemGenerator();
    private Instant roundStarted;
    private Instant roundEnds;
    private BossBar bossBar;

    public void startGame() {
        level = 1;
        bossBar = Bukkit.createBossBar("Game Starting...", BarColor.WHITE, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bossBar::addPlayer);
        alivePlayers = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(HashSet::new, HashSet::add, HashSet::addAll);
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            discoverRecipes(player);
            player.getInventory().clear();
            player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 8));
            player.getInventory().addItem(new ItemStack(Material.TORCH, 64));
            int x = ThreadLocalRandom.current().nextInt(300) - 150;
            int z = ThreadLocalRandom.current().nextInt(300) - 150;
            futures.add(player.teleportAsync(
                    new Location(
                            player.getWorld(),
                            x,
                            player.getWorld().getHighestBlockYAt(x, z),
                            z
                    )
            ));
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
                                        player.sendMessage("You are searching for %s, p = %s".formatted(sample.material().name(), sample.weighting()));
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
        long timeRemainingInSeconds = roundEnds.getEpochSecond() - Instant.now().getEpochSecond();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!alivePlayers.contains(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        }
        boolean notDuringAnimation = futuresWaitingOn.stream().allMatch(CompletableFuture::isDone);
        if (!notDuringAnimation) return;
        if ((timeRemainingInSeconds <= 0 || searchingFor.isEmpty())) {
            level++;
            futuresWaitingOn.clear();
            if (alivePlayers.containsAll(searchingFor.keySet()) && searchingFor.keySet().containsAll(alivePlayers)) {
                roundEnds = Instant.now().plusSeconds(30);
            }
            else {
                alivePlayers.removeAll(searchingFor.keySet());
                bossBar.setTitle("Shuffling!");
                bossBar.setProgress(1.0);
                searchingFor = searchingFor.entrySet().stream().filter((entry) -> alivePlayers.contains(entry.getKey())).collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
                ProbabilityBag bag = itemGenerator.getBagForPlayers(alivePlayers.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList(), level);
                double weightingCap = Math.pow(1 / 3D, level - 1);
                List<ProbabilityBag.SampleResult> samples = bag.samples(alivePlayers.size(), weightingCap);
                Iterator<UUID> iterator = alivePlayers.iterator();
                for (int i = 0; i < alivePlayers.size(); i++) {
                    Player player = Bukkit.getPlayer(iterator.next());
                    if (player == null) continue;
                    ProbabilityBag.SampleResult sample = samples.get(i);
                    futuresWaitingOn.add(new SlotMachineAnimation(Shuffler.getPlugin(Shuffler.class))
                            .playAnimation(
                                player,
                                bag.getSamplesAroundItem(sample.material(), 20, weightingCap),
                                sample.material(),
                                180
                            ).thenAccept((it) -> {
                                searchingFor.put(player.getUniqueId(), sample.material());
                                player.sendMessage("You are searching for %s, p = %s".formatted(sample.material().name(), sample.weighting()));
                                roundStarted = Instant.now();
                                roundEnds = roundStarted.plusSeconds(getMaximumTimeAllotted());
                            }));
                }
            }
        }
        else {
            bossBar.setProgress((double) timeRemainingInSeconds / getMaximumTimeAllotted());
            bossBar.setTitle("Round %s. Time Remaining: %s".formatted(level, timeRemainingInSeconds));
            for (UUID playerUUID : alivePlayers) {
                Player player = Bukkit.getPlayer(playerUUID);
                if (player == null) continue;
                Material material = searchingFor.get(player.getUniqueId());
                boolean matched = Arrays.stream(player.getInventory().getContents()).anyMatch((item) -> {
                    if (item == null) return false;
                    return item.getType() == material;
                });
                if (player.getLocation().subtract(0, 1, 0).getBlock().getType() == material
                        || player.getLocation().getBlock().getType() == material
                        || matched) {
                    searchingFor.remove(player.getUniqueId());
                    for (Player player1 : Bukkit.getOnlinePlayers()) {
                        player1.sendMessage("%s has found %s".formatted(player.getName(), material));
                    }
                }
                if (material == null) {
                    player.sendActionBar(Component.text("(%s players left) You have found the item!".formatted(alivePlayers.size())));
                }
                else {
                    player.sendActionBar(Component.text("(%s player left) Searching for %s!".formatted(alivePlayers.size(), material.name())));
                }
            }
        }
    }

    private int getMaximumTimeAllotted() {
        return 180 + Math.min(120, (level - 1) * 60);
    }

}
