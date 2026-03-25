package me.xemor.shuffler.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record AutomatingYaml(
        @JsonProperty boolean stopServerOnGameEnd,
        @JsonProperty AutostartYaml autostart,
        @JsonProperty CloudnetYaml cloudnet,
        @JsonProperty List<String> commandsOnStart,
        @JsonProperty List<String> commandsPerPlayerOnStart
) {
}
