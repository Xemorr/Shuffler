package me.xemor.shuffler.migrations;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import static org.jooq.impl.DSL.*;

public class V2__GameTable extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        DSLContext create = DSL.using(context.getConnection());

        create.transaction(transaction -> {
            DSLContext ctx = using(transaction);

            ctx.createTableIfNotExists("game")
                    .column("id", SQLDataType.INTEGER.identity(true))
                    .column("inserted_at", SQLDataType.INSTANT(9).nullable(false))
                    .constraints(
                            primaryKey("id")
                    )
                    .execute();

            ctx.createTableIfNotExists("game_ranking")
                            .column("game_id", SQLDataType.INTEGER.nullable(false))
                            .column("player_id", SQLDataType.INTEGER.nullable(false))
                            .column("rank", SQLDataType.INTEGER.nullable(false))
                            .constraints(
                                    primaryKey("game_id", "player_id"),
                                    foreignKey("game_id").references("game", "id").onDeleteCascade(),
                                    foreignKey("player_id").references("player", "id").onDeleteCascade()
                            )
                            .execute();
        });
    }
}
