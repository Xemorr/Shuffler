package me.xemor.shuffler.migrations;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import static org.jooq.impl.DSL.*;

public class V1__Initial extends BaseJavaMigration {
    @Override
    public void migrate(Context context) {
        DSLContext create = DSL.using(context.getConnection());

        create.transaction(transaction -> {
            DSLContext ctx = using(transaction);
            ctx.createTableIfNotExists("player")
                    .column("id", SQLDataType.INTEGER.identity(true))
                    .column("uuid", SQLDataType.UUID.nullable(false))
                    .column("name", SQLDataType.VARCHAR(255).nullable(false))
                    .constraints(
                            primaryKey("id"),
                            unique("uuid")
                    )
                    .execute();

            ctx.createTableIfNotExists("elo")
                    .column("id", SQLDataType.INTEGER.identity(true))
                    .column("player_id", SQLDataType.INTEGER.nullable(false))
                    .column("current_elo", SQLDataType.DOUBLE.nullable(false))
                    .column("current_uncertainty", SQLDataType.DOUBLE.nullable(false))
                    .column("inserted_at", SQLDataType.INSTANT(9).nullable(false))
                    .constraints(
                            primaryKey("id"),
                            foreignKey("player_id").references("player", "id").onDeleteCascade()
                    )
                    .execute();

            ctx.createIndex("idx_elo_player")
                    .on("elo", "player_id")
                    .execute();
        });
    }
}
