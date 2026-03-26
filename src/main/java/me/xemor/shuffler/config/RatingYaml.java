package me.xemor.shuffler.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.xemor.shuffler.rating.Rank;

import java.util.List;

public record RatingYaml(@JsonProperty List<Rank> ranks) {
}
