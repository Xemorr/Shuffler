package me.xemor.shuffler.rating;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

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
            String[] s = identifier.split("_", 2);
            if (s.length >= 3) {
                int rank = Integer.parseInt(s[1]);
                if (rank > 100) throw new IllegalArgumentException("Rank must be between 1 and 100");
                RatingHandler.PlayerConservativeRating rating = ratingHandler.getLeaderboardEntryUpTo100(rank);
                String nameOrConservativeElo = s[2];
                if ("conservative_elo".equals(nameOrConservativeElo)) {
                    return rating.conservativeElo() + "";
                }
                else if ("name".equals(nameOrConservativeElo)) {
                    return rating.name();
                }
                else if ("uuid".equals(nameOrConservativeElo)) {
                    return rating.playerUUID() + "";
                }
                else {
                    throw new IllegalArgumentException(identifier + " is not a valid placeholder!");
                }
            }
        }
        return null;
    }
}
