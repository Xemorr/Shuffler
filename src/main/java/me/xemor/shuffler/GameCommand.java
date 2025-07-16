package me.xemor.shuffler;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

@Command("game")
public class GameCommand {

    private final Game game = new Game();

    @Subcommand("start")
    public void startGame() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(ChatColor.RED + "GAME STARTING");
        }
        game.startGame();
    }

    @Subcommand("check")
    public void check(BukkitCommandActor actor, Player player) {
        Material material = game.searchingFor(player);
        actor.sendRawError("%s is searching for %s".formatted(player.getName(), material == null ? "Nothing" : material.name()));
    }

}
