package me.xemor.shuffler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import me.xemor.shuffler.config.ConfigYaml;
import me.xemor.shuffler.config.DatabaseYaml;
import me.xemor.shuffler.config.RatingYaml;
import me.xemor.shuffler.game.AutostartListener;
import me.xemor.shuffler.game.Game;
import me.xemor.shuffler.game.GameCommand;
import me.xemor.shuffler.migrations.V1__Initial;
import org.bukkit.plugin.java.JavaPlugin;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

public final class Shuffler extends JavaPlugin {

    private ConfigYaml configYaml;
    private DatabaseYaml databaseYaml;
    private RatingYaml ratingYaml;
    private HikariDataSource dataSource;
    private SQLDialect dialect;

    @Override
    public void onEnable() {
        // Plugin startup logic
        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(this).build();
        lamp.register(new GameCommand());
        ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
        saveDefaultConfig();
        saveResource("database.yml", false);
        saveResource("rating.yml", false);
        try {
            configYaml = objectMapper.readValue(new File(this.getDataFolder(), "config.yml"), ConfigYaml.class);
            databaseYaml = objectMapper.readValue(new File(this.getDataFolder(), "database.yml"), DatabaseYaml.class);
            ratingYaml = objectMapper.readValue(new File(this.getDataFolder(), "rating.yml"), RatingYaml.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        getServer().getPluginManager().registerEvents(new AutostartListener(Game.getInstance()), this);
        setupDatabase(databaseYaml);
    }

    public void setupDatabase(DatabaseYaml databaseYaml) {
        HikariConfig config = new HikariConfig();
        if ("postgresql".equalsIgnoreCase(databaseYaml.type())) {
            dialect = SQLDialect.POSTGRES;
            config.setJdbcUrl(databaseYaml.url());
            config.setUsername(databaseYaml.username());
            config.setPassword(databaseYaml.password());
            config.setDriverClassName("me.xemor.shuffler.libs.postgresql.Driver");
        }
        else {
            dialect = SQLDialect.H2;
            // H2 Fallback - stores data in /plugins/Shuffler/data/database.mv.db
            File dbFile = new File(getDataFolder(), "data/database");
            dbFile.getParentFile().mkdirs(); // Ensure directory exists

            // "delay_threshold=-1" keeps the DB open as long as the connection pool exists
            config.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath() + ";SCHEMA=public;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
            config.setUsername("shuffler");
            config.setPassword("");
            config.setDriverClassName("org.h2.Driver");
        }
        dataSource = new HikariDataSource(config);
        Flyway flyway = Flyway.configure(this.getClass().getClassLoader())
                .dataSource(dataSource)
                .javaMigrations(new V1__Initial())
                .load();

        flyway.migrate();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public static Shuffler getInstance() {
        return getPlugin(Shuffler.class);
    }

    public ConfigYaml getConfigYaml() {
        return configYaml;
    }

    public RatingYaml getRatingYaml() {
        return ratingYaml;
    }

    public DSLContext getContext() {
        return DSL.using(dataSource, dialect);
    }
}
