package me.xemor.shuffler.rating;

import com.pocketcombats.openskill.Adjudicator;
import com.pocketcombats.openskill.RatingModelConfig;
import com.pocketcombats.openskill.data.RatingAdjustment;
import com.pocketcombats.openskill.data.SimplePlayerResult;
import com.pocketcombats.openskill.data.SimpleTeamResult;
import com.pocketcombats.openskill.data.TeamResult;
import com.pocketcombats.openskill.model.PlackettLuce;
import me.xemor.shuffler.Shuffler;
import me.xemor.shuffler.dest.generated.tables.records.GameRecord;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Result;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static me.xemor.shuffler.dest.generated.Tables.*;
import static org.jooq.impl.DSL.*;

public class RatingHandler implements Listener {

    public record PlayerConservativeRating(UUID playerUUID, String name, double conservativeElo) {}
    private List<PlayerConservativeRating> top100Leaderboard = new ArrayList<>();
    private final Map<UUID, PlayerConservativeRating> playerConservativeRatings = new HashMap<>();
    private final List<Rank> ranks;

    public record PlayerRankPair(int rank, UUID playerUUID) {}
    record PlayerResultAndRank(SimplePlayerResult<UUID> playerResult, int rank) {}

    public RatingHandler() {
        if (Shuffler.getInstance().getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new RatingPlaceholders(this).register();
        }
        ranks = Shuffler.getInstance().getRatingYaml().ranks()
                .stream()
                .sorted(Comparator.comparingDouble(Rank::threshold).reversed())
                .toList();
        new BukkitRunnable() {
            @Override
            public void run() {
                retrieveLatestLeaderboard();
            }
        }.runTaskTimerAsynchronously(Shuffler.getInstance(), 0, 20 * 60 * 5);
        Shuffler.getInstance().getServer().getPluginManager().registerEvents(this, Shuffler.getInstance());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player player = e.getPlayer();
                Shuffler.getInstance().getContext().transaction(configuration -> {
                    DSLContext ctx = DSL.using(configuration);

                    Field<Double> conservativeElo = ELO.CURRENT_ELO.minus(ELO.CURRENT_UNCERTAINTY.mul(3)).as("conservativeElo");
                    PlayerConservativeRating rating = ctx.select(PLAYER.UUID, PLAYER.NAME, conservativeElo)
                            .from(PLAYER)
                            .join(ELO).on(PLAYER.ID.eq(ELO.PLAYER_ID))
                            .where(PLAYER.UUID.eq(e.getPlayer().getUniqueId()))
                            .orderBy(ELO.INSERTED_AT.desc())
                            .limit(1)
                            .fetch()
                            .stream()
                            .map(it -> new PlayerConservativeRating(it.value1(), it.value2(), it.value3()))
                            .findAny()
                            .orElse(new PlayerConservativeRating(player.getUniqueId(), player.getName(), 0));
                    playerConservativeRatings.put(player.getUniqueId(), rating);
                    if (top100Leaderboard.stream().noneMatch((it) -> it.playerUUID().equals(rating.playerUUID()))) {
                        top100Leaderboard.add(rating);
                    }
                    Rank rank = ranks.stream().filter(r -> rating.conservativeElo() >= r.threshold()).findFirst().orElseThrow(() -> new IllegalStateException("no valid rank for player with elo " + rating.conservativeElo()));
                    player.addAttachment(Shuffler.getInstance(), "shuffler.rank." + rank.name(), true);
                    if (!rating.name().equals(player.getName())) {
                        ctx.update(PLAYER).set(PLAYER.NAME, rating.name()).where(PLAYER.UUID.eq(player.getUniqueId())).execute();
                    }
                });
            }
        }.runTaskAsynchronously(Shuffler.getInstance());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        playerConservativeRatings.remove(e.getPlayer().getUniqueId());
    }

    public void retrieveLatestLeaderboard() {
        Shuffler.getInstance().getContext().transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            Field<Double> calcElo = ELO.CURRENT_ELO.minus(ELO.CURRENT_UNCERTAINTY.mul(3)).as("conservative_elo_val");
            var latestElo = ctx.select(
                            ELO.PLAYER_ID.as("PLAYER_ID"),
                            calcElo, // Put the calculated field here
                            rowNumber().over(
                                    partitionBy(ELO.PLAYER_ID).orderBy(ELO.INSERTED_AT.desc())
                            ).as("rownumber")
                    )
                    .from(ELO)
                    .asTable("latest_elo");

            Field<Double> topEloField = latestElo.field("conservative_elo_val", Double.class);

            top100Leaderboard = ctx
                    .select(
                            PLAYER.UUID,
                            PLAYER.NAME,
                            topEloField
                    )
                    .from(PLAYER)
                    .join(latestElo).on(PLAYER.ID.eq(latestElo.field("PLAYER_ID", Integer.class)))
                    .where(latestElo.field("rownumber", Integer.class).eq(1))
                    .orderBy(topEloField.desc())
                    .limit(100)
                    .fetch()
                    .map(it -> new PlayerConservativeRating(
                            it.get(PLAYER.UUID),
                            it.get(PLAYER.NAME),
                            it.get(topEloField)
                    ));
        });
    }

    public Optional<PlayerConservativeRating> getLeaderboardEntryUpTo100(int rank) {
        if (rank <= top100Leaderboard.size()) {
            return Optional.of(top100Leaderboard.get(rank - 1));
        } else return Optional.empty();
    }

    public Optional<PlayerConservativeRating> getPlayerRating(UUID playerUUID) {
        return Optional.ofNullable(playerConservativeRatings.get(playerUUID));
    }

    public void updateElo(List<PlayerRankPair> rankToUUID) {
        Shuffler.getInstance().getContext().transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

            int gameId = ctx.insertInto(GAME)
                    .columns(GAME.INSERTED_AT)
                    .values(Instant.now().atOffset(ZoneOffset.UTC))
                    .returning()
                    .fetch()
                    .stream()
                    .findFirst()
                    .map(GameRecord::getId)
                    .orElseThrow();

            for (PlayerRankPair pair : rankToUUID) {
                ctx.insertInto(GAME_RANKING)
                    .columns(GAME_RANKING.GAME_ID, GAME_RANKING.PLAYER_ID, GAME_RANKING.RANK)
                    .select(ctx.select(val(gameId), PLAYER.ID, val(pair.rank)).from(PLAYER).where(PLAYER.UUID.eq(pair.playerUUID())))
                    .execute();
            }

            List<TeamResult<UUID>> teamResults = rankToUUID
                    .stream()
                    .map((it) -> {
                        String name = Bukkit.getOfflinePlayer(it.playerUUID()).getName();
                        ctx.insertInto(PLAYER)
                                .set(PLAYER.UUID, it.playerUUID())
                                .set(PLAYER.NAME, name)
                                .onConflict(PLAYER.UUID)
                                .doUpdate()
                                .set(PLAYER.NAME, name)
                                .execute();

                        Record2<Double, Double> record = ctx.select(ELO.CURRENT_ELO, ELO.CURRENT_UNCERTAINTY)
                                .from(PLAYER)
                                .leftJoin(ELO).on(PLAYER.ID.eq(ELO.PLAYER_ID))
                                .where(PLAYER.UUID.eq(it.playerUUID()))
                                .orderBy(ELO.INSERTED_AT.desc())
                                .limit(1)
                                .fetchOne();

                        double currentElo = record.get(ELO.CURRENT_ELO) != null ? record.get(ELO.CURRENT_ELO) : 25.0;
                        double currentUncertainty = record.get(ELO.CURRENT_UNCERTAINTY) != null ? record.get(ELO.CURRENT_UNCERTAINTY) : 8.3333;

                        return new PlayerResultAndRank(new SimplePlayerResult<>(it.playerUUID(), currentElo, currentUncertainty), it.rank());
                    })
                    .map((it) -> (TeamResult<UUID>) new SimpleTeamResult<>(it.playerResult().mu(), it.playerResult().sigma(), it.rank(), List.of(it.playerResult())))
                    .toList();

            RatingModelConfig config = RatingModelConfig.builder()
                    .setBeta(25.0 / 6)
                    .build();

            Adjudicator<UUID> adjudicator = new Adjudicator<>(
                    config,
                    new PlackettLuce(config)
            );

            List<RatingAdjustment<UUID>> adjustments = adjudicator.rate(teamResults);
            for (RatingAdjustment<UUID> adjustment : adjustments) {
                ctx.insertInto(ELO)
                        .columns(ELO.PLAYER_ID, ELO.CURRENT_ELO, ELO.CURRENT_UNCERTAINTY, ELO.INSERTED_AT)
                        .select(
                            ctx.select(PLAYER.ID, val(adjustment.mu()), val(adjustment.sigma()), val(Instant.now().atOffset(ZoneOffset.UTC)))
                            .from(PLAYER)
                            .where(PLAYER.UUID.eq(adjustment.playerId()))
                        )
                        .execute();
            }
        });
    }

}
