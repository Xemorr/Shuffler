package me.xemor.shuffler.game;

import org.bukkit.Location;

public record Spawnpoint(SpawnType spawnType, Location location) {

    public enum SpawnType {
        STANDARD,
        BOAT,
        SKY
    }

}
