package me.xemor.shuffler.rating;

import me.xemor.shuffler.Shuffler;
import me.xemor.shuffler.game.Game;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

public class EloCommand {

    @Command("elo check")
    public void check(BukkitCommandActor actor, @Optional OfflinePlayer target) {
        OfflinePlayer player = target != null ? target : actor.requirePlayer();
        RatingHandler ratings = Game.getInstance().getRatingHandler();
        Bukkit.getScheduler().runTaskAsynchronously(Shuffler.getInstance(), () -> {
            double elo = Math.max(0, ratings.fetchConservativeElo(player.getUniqueId()));
            actor.reply("%s's elo: %.2f (%s)".formatted(player.getName(), elo, ratings.getRank(elo).name()));
        });
    }
}
