package me.xemor.shuffler.game;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Set;
import java.util.stream.Collectors;

@Command("game")
public class GameCommand {

    @Subcommand("start")
    @CommandPermission("shuffler.game.start")
    public void startGame(BukkitCommandActor actor) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(ChatColor.RED + "GAME STARTING");
        }
        Game.getInstance().startGame();
    }

    @Subcommand("check")
    @CommandPermission("shuffler.game.check")
    public void check(BukkitCommandActor actor, Player player) {
        Set<Material> materials = Game.getInstance().searchingFor(player);
        actor.audience().ifPresent((audience) -> {
            audience.sendMessage(
                    Component.text()
                    .append(Component.text("%s is searching for ".formatted(player.getName())))
                    .append(SearchingForFormatter.formatSearchMessage(materials))
                    .append(Component.text("!"))
            );
        });
    }

}
