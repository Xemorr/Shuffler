package me.xemor.shuffler.game;

import com.pocketcombats.openskill.Adjudicator;
import com.pocketcombats.openskill.RatingModelConfig;
import com.pocketcombats.openskill.data.RatingAdjustment;
import com.pocketcombats.openskill.data.SimplePlayerResult;
import com.pocketcombats.openskill.data.SimpleTeamResult;
import com.pocketcombats.openskill.data.TeamResult;
import com.pocketcombats.openskill.model.PlackettLuce;
import com.pocketcombats.openskill.model.ThurstoneMostellerFull;
import me.xemor.shuffler.Shuffler;
import org.bukkit.Bukkit;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.impl.DSL;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static me.xemor.shuffler.dest.generated.Tables.ELO;
import static me.xemor.shuffler.dest.generated.Tables.PLAYER;
import static org.jooq.impl.DSL.val;

public class EloHandler {

    public record PlayerRankPair(int rank, UUID playerUUID) {}
    record PlayerResultAndRank(SimplePlayerResult<UUID> playerResult, int rank) {}

    public void updateElo(List<PlayerRankPair> rankToUUID) {
        Shuffler.getInstance().getContext().transaction(configuration -> {
            DSLContext ctx = DSL.using(configuration);

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
                    .setZ(25.0 / 6) // default is 3, my vibe is that draws are quite likely in BlockShuffle.
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
