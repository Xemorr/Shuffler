package me.xemor.shuffler;

import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLamp;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.orphan.Orphans;

public final class Shuffler extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        Lamp<BukkitCommandActor> lamp = BukkitLamp.builder(this).build();
        lamp.register(new GameCommand());
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
