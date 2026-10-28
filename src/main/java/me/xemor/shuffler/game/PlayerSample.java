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
 * The players' samples are only merged once crafting has been worked out for each.
 * <p>
 * A sample is the result of one search; {@link SampleHistory} keeps a player's samples in time order.
 */
final class PlayerSample {

    /** The share of the drops bag, and of the stand-on bag, that comes from what the player can see from afar. */
    private static final double VIEW_WEIGHT = 0.2;
    /** The share of each of those that is what the player is carrying, taken from the nearby share. */
    private static final double INVENTORY_WEIGHT = 0.1;

    /** The bags a sample is made of. */
    enum Part { DROPS, STAND_ON, VIEWED_DROPS, VIEWED_BLOCKS, CARRIED, CARRIED_BLOCKS }

    private final Map<Part, ProbabilityBag> parts = new EnumMap<>(Part.class);

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

    /**
     * Records what the player is carrying, so a stack of 64 weighs 64 times a single item. Blocks also count
     * towards what they could stand on.
     */
    void addCarried(Material material, int amount) {
        if (material.isAir()) return;
        part(Part.CARRIED).addWeighting(material, amount);
        if (material.isBlock()) part(Part.CARRIED_BLOCKS).addWeighting(material, amount);
    }

    ProbabilityBag viewedDrops() {
        return part(Part.VIEWED_DROPS);
    }

    ProbabilityBag viewedBlocks() {
        return part(Part.VIEWED_BLOCKS);
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
        ProbabilityBag craftable = craftingModel.propagate(mixed(part(Part.DROPS), viewedDrops(), part(Part.CARRIED)));
        return ProbabilityBag.mean(List.of(craftable, mixed(part(Part.STAND_ON), viewedBlocks(), part(Part.CARRIED_BLOCKS))));
    }

    /**
     * @return the three bags mixed so that {@link #VIEW_WEIGHT} of the weight comes from what is seen and
     * {@link #INVENTORY_WEIGHT} from what is carried, leaving the rest for what is nearby. A bag with nothing in it
     * gives up its share to the others.
     */
    private static ProbabilityBag mixed(ProbabilityBag nearby, ProbabilityBag viewed, ProbabilityBag carried) {
        double viewShare = viewed.isEmpty() ? 0 : VIEW_WEIGHT;
        double carriedShare = carried.isEmpty() ? 0 : INVENTORY_WEIGHT;
        ProbabilityBag mixed = new ProbabilityBag().addNormalised(viewed, viewShare).addNormalised(carried, carriedShare);
        return mixed.addNormalised(nearby, 1 - viewShare - carriedShare);
    }
}
