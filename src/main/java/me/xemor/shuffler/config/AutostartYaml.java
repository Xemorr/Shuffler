package me.xemor.shuffler.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AutostartYaml(
        @JsonProperty boolean enabled,
        @JsonProperty int minPlayers,
        @JsonProperty double secondsUntilStart,
        @JsonProperty double speedupPerPlayer
) {
}
