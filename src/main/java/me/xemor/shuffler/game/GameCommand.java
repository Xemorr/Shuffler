package me.xemor.shuffler.game;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

@Command("game")
public class GameCommand {

    @Subcommand("start")
    public void startGame() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(ChatColor.RED + "GAME STARTING");
        }
        Game.getInstance().startGame();
    }

    @Subcommand("check")
    public void check(BukkitCommandActor actor, Player player) {
        Material material = Game.getInstance().searchingFor(player);
        actor.sendRawMessage("%s is searching for %s".formatted(player.getName(), material == null ? "Nothing" : material.name()));
    }

}
