package me.xemor.shuffler.game;

import me.xemor.shuffler.game.crafting.CraftingDifficultyModel;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything sampled around one player: the drops of the blocks and mobs near them, the blocks they could stand on,
 * and the same two for what they can see from afar. Crafting is worked out for each player's sample on its own, since
 * what one player can easily make depends on where they have actually been (someone spending the game in a cave
 * has plenty of iron, and someone on the surface has plenty of leather, but neither finds a saddle easy).
 * The players' bags are only merged at the end.
 */
final class PlayerSample {

    /** The share of the drops bag, and of the stand-on bag, that comes from what the player can see from afar. */
    private static final double VIEW_WEIGHT = 0.2;

    /** The four bags a sample is made of. */
    enum Part { DROPS, STAND_ON, VIEWED_DROPS, VIEWED_BLOCKS }

    private final Map<Part, ProbabilityBag> parts = new EnumMap<>(Part.class);
    private int searches = 0;

    PlayerSample() {
        for (Part part : Part.values()) parts.put(part, new ProbabilityBag());
    }

    ProbabilityBag part(Part part) {
        return parts.get(part);
    }

    void addDrop(Material material, double weighting) {
        part(Part.DROPS).addWeighting(material, weighting);
    }

    void addStandOn(Material material, double weighting) {
        part(Part.STAND_ON).addWeighting(material, weighting);
    }

    ProbabilityBag viewedDrops() {
        return part(Part.VIEWED_DROPS);
    }

    ProbabilityBag viewedBlocks() {
        return part(Part.VIEWED_BLOCKS);
    }

    int searches() {
        return searches;
    }

    void countSearch() {
        searches++;
    }

    /**
     * @return whether nothing has been sampled yet, so there is no bag to speak of
     */
    boolean isEmpty() {
        return part(Part.DROPS).isEmpty() && viewedDrops().isEmpty();
    }

    /**
     * @return this player's bag: half of its weight is the drops with crafting worked out, half the blocks they could stand on
     */
    ProbabilityBag bag(CraftingDifficultyModel craftingModel) {
        ProbabilityBag craftable = craftingModel.propagate(withView(part(Part.DROPS), viewedDrops()));
        return ProbabilityBag.mean(List.of(craftable, withView(part(Part.STAND_ON), viewedBlocks())));
    }

    /**
     * @return the nearby bag and the viewed bag mixed so that {@link #VIEW_WEIGHT} of the weight comes from what is
     * seen, or just whichever of the two has anything in it
     */
    private static ProbabilityBag withView(ProbabilityBag nearby, ProbabilityBag viewed) {
        if (viewed.isEmpty()) return nearby;
        if (nearby.isEmpty()) return viewed;
        return new ProbabilityBag()
                .addNormalised(nearby, 1 - VIEW_WEIGHT)
                .addNormalised(viewed, VIEW_WEIGHT);
    }
}
