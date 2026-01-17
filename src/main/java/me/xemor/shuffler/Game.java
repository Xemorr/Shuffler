package me.xemor.shuffler;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static org.bukkit.Bukkit.getServer;

public class Game {

    private Map<UUID, Material> searchingFor = new HashMap<>();
    private Set<UUID> alivePlayers = new HashSet<>();
    private int level = 1;
    private ItemGenerator itemGenerator = new ItemGenerator();
    private Instant roundStarted;
    private Instant roundEnds;

    public void startGame() {
        level = 1;
        alivePlayers = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(HashSet::new, HashSet::add, HashSet::addAll);
        Map<UUID, ProbabilityBag.SampleResult> samples = itemGenerator.generateMaterials(Bukkit.getOnlinePlayers().stream().toList(), 1);
        for (Player player : Bukkit.getOnlinePlayers()) {
            discoverRecipes(player);
            player.getInventory().clear();
            player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 8));
            player.getInventory().addItem(new ItemStack(Material.TORCH, 64));
            int x = ThreadLocalRandom.current().nextInt(300) - 150;
            int z = ThreadLocalRandom.current().nextInt(300) - 150;
            player.teleport(
                new Location(
                    player.getWorld(),
                    x,
                    player.getWorld().getHighestBlockYAt(x, z),
                    z
                )
            );
            ProbabilityBag.SampleResult sample = samples.get(player.getUniqueId());
            searchingFor.put(player.getUniqueId(), sample.material());
            player.sendMessage("You are searching for %s, p = %s".formatted(sample.material().name(), sample.weighting()));
        }
        roundStarted = Instant.now();
        roundEnds = roundStarted.plusSeconds(180);
        new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(Shuffler.getPlugin(Shuffler.class), 0, 1);
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
        if (timeRemainingInSeconds <= 0 || searchingFor.isEmpty()) {
            if (alivePlayers.containsAll(searchingFor.keySet()) && searchingFor.keySet().containsAll(alivePlayers)) {
                roundEnds = Instant.now().plusSeconds(30);
            }
            else {
                alivePlayers.removeAll(searchingFor.keySet());
                searchingFor = searchingFor.entrySet().stream().filter((entry) -> alivePlayers.contains(entry.getKey())).collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
                Map<UUID, ProbabilityBag.SampleResult> uuidToSample = itemGenerator.generateMaterials(alivePlayers.stream().map(Bukkit::getPlayer).filter((Objects::nonNull)).toList(), level);
                for (UUID playerUUID : alivePlayers) {
                    Player player = Bukkit.getPlayer(playerUUID);
                    if (player == null) continue;
                    ProbabilityBag.SampleResult sample = uuidToSample.get(playerUUID);
                    searchingFor.put(player.getUniqueId(), sample.material());
                    player.sendMessage("You are searching for %s, p = %s".formatted(sample.material().name(), sample.weighting()));
                }
                level++;
                roundStarted = Instant.now();
                roundEnds = roundStarted.plusSeconds(180 + Math.min(120, (level - 1) * 60));
            }
        }
        for (UUID playerUUID : alivePlayers) {
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) continue;
            Material material = searchingFor.get(player.getUniqueId());
            boolean matched = Arrays.stream(player.getInventory().getContents()).anyMatch((item) -> {
                if (item == null) return false;
                return item.getType() == material;
            });
            if (player.getLocation().subtract(0, 1, 0).getBlock().getType() == material || matched) {
                searchingFor.remove(player.getUniqueId());
                for (Player player1 : Bukkit.getOnlinePlayers()) {
                    player1.sendMessage("%s has found %s".formatted(player.getName(), material));
                }
            }
            if (material == null) {
                player.sendActionBar(Component.text("(%s players left) You have found the item! Time remaining, %s seconds".formatted(alivePlayers.size(), timeRemainingInSeconds)));
            }
            else {
                player.sendActionBar(Component.text("(%s player left) Searching for %s! Time remaining, %s seconds".formatted(alivePlayers.size(), material.name(), timeRemainingInSeconds)));
            }
        }
    }

}
