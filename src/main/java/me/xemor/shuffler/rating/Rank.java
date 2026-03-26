package me.xemor.shuffler.rating;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Rank(@JsonProperty String name, @JsonProperty double threshold) {
}
