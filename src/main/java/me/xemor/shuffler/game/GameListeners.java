package me.xemor.shuffler.game;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

public class GameListeners implements Listener {

    private final Game game;

    public GameListeners(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent e) {
        if (game.isPlayerAlive(e.getPlayer())) {
            game.spawnPlayer(e.getPlayer());
        }
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        Item itemEntity = event.getEntity();
        ItemStack itemStack = itemEntity.getItemStack();

        if (itemStack.getType() == Material.GOLDEN_CARROT) {
            if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasEnchant(Enchantment.VANISHING_CURSE)) {
                event.setCancelled(true);
                itemEntity.getWorld().spawnParticle(Particle.DUST, itemEntity.getLocation(), 50, 1, 1, 1, 1, new Particle.DustOptions(Color.PURPLE, 3));
                itemEntity.getWorld().playSound(itemEntity.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 1, 1);
            }
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getInventory() instanceof PlayerInventory)) {
            if (e.getInventory().getLocation() != null) {
                Location location = e.getInventory().getLocation();
                @Nullable ItemStack[] contents = e.getInventory().getContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack item = contents[i];
                    if (item != null && item.getType() == Material.GOLDEN_CARROT && item.hasItemMeta() && item.getItemMeta().hasEnchant(Enchantment.VANISHING_CURSE)) {
                        e.getInventory().setItem(i, null);
                        HumanEntity player = e.getPlayer();

                        player.getWorld().spawnParticle(Particle.DUST, location, 50, 1, 1, 1, 1, new Particle.DustOptions(Color.PURPLE, 3));
                        player.getWorld().playSound(location, Sound.BLOCK_PORTAL_TRAVEL, 1, 1);
                    }
                }
            }
        }
    }

}
