package me.xemor.shuffler.rating;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

public class RatingPlaceholders extends PlaceholderExpansion {

    private final RatingHandler ratingHandler;

    public RatingPlaceholders(RatingHandler ratingHandler) {
        this.ratingHandler = ratingHandler;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "shuffler";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Xemor";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String identifier) {
        if (identifier.startsWith("leaderboard")) {
            String[] s = identifier.split("_", 3);
            if (s.length >= 3) {
                int rank = Integer.parseInt(s[1]);
                if (rank > 100) throw new IllegalArgumentException("Rank must be between 1 and 100");
                Optional<RatingHandler.PlayerConservativeRating> optRating = ratingHandler.getLeaderboardEntryUpTo100(rank);
                return optRating.map((rating) -> {
                    String nameOrConservativeElo = s[2];
                    return switch (nameOrConservativeElo) {
                        case "conservative_elo" -> rating.conservativeElo() + "";
                        case "name" -> rating.name();
                        case "uuid" -> rating.playerUUID() + "";
                        case null, default ->
                                throw new IllegalArgumentException(identifier + " is not a valid placeholder!");
                    };
                }).orElse("None");
            }
        }
        else if (identifier.equals("conservative_elo")) {
            return ratingHandler.getPlayerRating(player.getUniqueId())
                    .map(RatingHandler.PlayerConservativeRating::conservativeElo)
                    .map(String::valueOf).orElse("0");
        }
        return null;
    }
}
