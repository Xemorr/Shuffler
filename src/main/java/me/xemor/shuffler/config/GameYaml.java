package me.xemor.shuffler.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GameYaml(
        @JsonProperty String world
) {
}
