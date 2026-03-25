package me.xemor.shuffler.game;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class PlayerRespawnHandler implements Listener {

    private final Game game;

    public PlayerRespawnHandler(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onRespawn(PlayerPostRespawnEvent e) {
        if (game.isPlayerAlive(e.getPlayer())) {
            game.spawnPlayer(e.getPlayer());
        }
    }

}
