package me.xemor.shuffler.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ConfigYaml(
    @JsonProperty AutomatingYaml automating,
    @JsonProperty GameYaml game
) {}
