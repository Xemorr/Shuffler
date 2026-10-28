package me.xemor.shuffler.game;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class GameListeners implements Listener {

    private final Game game;

    public GameListeners(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        for (ItemStack item : e.getPlayer().getInventory().getContents()) {
            if (item != null && item.getType() == Material.GOLDEN_CARROT && item.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                e.getItemsToKeep().add(item.clone());
            }
        }

        List<ItemStack> drops = e.getDrops();
        int regular = Math.min(count(drops, Material.TORCH), Game.STARTING_TORCHES);
        int copper = Math.min(count(drops, Material.COPPER_TORCH), Game.STARTING_TORCHES);
        Material torch = regular > copper ? Material.TORCH : Material.COPPER_TORCH;
        int toKeep = Math.max(regular, copper);
        // players keep up to starting torches, max of the two types; default to copper if equal (64+)
        for (ItemStack drop : drops) {
            if (toKeep == 0) break;
            if (drop.getType() != torch) continue;
            int kept = Math.min(toKeep, drop.getAmount());
            e.getItemsToKeep().add(drop.asQuantity(kept));
            drop.subtract(kept);
            toKeep -= kept;
        }
        drops.removeIf(ItemStack::isEmpty);
    }

    private static int count(List<ItemStack> items, Material type) {
        return items.stream().filter(item -> item.getType() == type).mapToInt(ItemStack::getAmount).sum();
    }

    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent e) {
        if (game.isPlayerAlive(e.getPlayer()) && !e.isBedSpawn() && !e.isAnchorSpawn()) {
            game.spawnPlayer(e.getPlayer());
        }
    }

}
