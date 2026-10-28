package me.xemor.shuffler.game;

import me.xemor.shuffler.game.crafting.CraftingDifficultyModel;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What one player's searches found, kept in time order so that how close in time two finds were can matter.
 * <p>
 * Whether an item's ingredients count as coming from the same place depends on how far apart in time they were found.
 * Instead of a cutoff, the environment around any moment is a window: every snapshot counts, weighted by
 * {@code exp(-|t - t_snapshot| / tau)}. Crafting is worked out for each window on its own, then the windows are averaged.
 * <ul>
 *     <li>As tau falls to 0 a window is one snapshot, so ingredients must come from the same place.</li>
 *     <li>As tau grows to infinity a window is everything the player has found, which is how a single running total behaves.</li>
 *     <li>In between the dependence fades smoothly with the gap.</li>
 * </ul>
 */
final class SampleHistory {

    /** Snapshots further than this many taus from a moment are too light to matter (e^-6 is about 0.25%). */
    private static final double CUTOFF_TAUS = 6;
    /**
     * Materials making up less than this share of a window are left out. Real searches never find anything lighter than
     * about 1e-7 of a bag, and the crafting model's tolerances are not meant for weights far below that, which only a
     * far-off snapshot at the edge of the kernel could add.
     */
    private static final double MIN_SHARE = 1e-8;
    private static final int MATERIAL_COUNT = Material.values().length;
    private static final Material[] MATERIALS = Material.values();

    /** A bag as the materials it holds, scaled so that all of it weighs 1. */
    private record Sparse(int[] materials, double[] shares) {
        static Sparse of(ProbabilityBag bag) {
            List<ProbabilityBag.Entry> entries = bag.entries();
            int[] materials = new int[entries.size()];
            double[] shares = new double[entries.size()];
            for (int i = 0; i < materials.length; i++) {
                materials[i] = entries.get(i).material().ordinal();
                shares[i] = entries.get(i).probability();
            }
            return new Sparse(materials, shares);
        }

        void addTo(double[] dense, double scale) {
            for (int i = 0; i < materials.length; i++) dense[materials[i]] += shares[i] * scale;
        }
    }

    private record Snapshot(double seconds, Sparse[] parts) {}

    /** The crafted bag of the window around one moment. */
    record Window(double seconds, ProbabilityBag bag) {}

    private final List<Snapshot> snapshots = new ArrayList<>();

    /**
     * Records what one search found. Each snapshot counts the same however much it found.
     *
     * @param seconds when it was taken, never earlier than the one before
     */
    void add(double seconds, PlayerSample sample) {
        if (!snapshots.isEmpty() && seconds < snapshots.getLast().seconds()) {
            throw new IllegalArgumentException("Snapshots must be added in time order");
        }
        PlayerSample.Part[] parts = PlayerSample.Part.values();
        Sparse[] sparse = new Sparse[parts.length];
        for (int i = 0; i < parts.length; i++) sparse[i] = Sparse.of(sample.part(parts[i]));
        snapshots.add(new Snapshot(seconds, sparse));
    }

    boolean isEmpty() {
        return snapshots.isEmpty();
    }

    /**
     * @return how many searches have been recorded
     */
    int size() {
        return snapshots.size();
    }

    /**
     * @param tau how many seconds apart finds can be and still count as largely the same place; infinity for no limit
     * @return the environment around this moment: every snapshot, weighted by how close in time it is, or null if
     * none is close enough to count
     */
    PlayerSample window(double seconds, double tau) {
        PlayerSample.Part[] parts = PlayerSample.Part.values();
        double[][] dense = new double[parts.length][MATERIAL_COUNT];
        double total = 0;
        for (Snapshot snapshot : snapshots) {
            double gap = Math.abs(snapshot.seconds() - seconds);
            if (gap > CUTOFF_TAUS * tau) continue;
            double weight = Math.exp(-gap / tau);
            total += weight;
            for (int i = 0; i < parts.length; i++) snapshot.parts()[i].addTo(dense[i], weight);
        }
        if (total == 0) return null;

        PlayerSample window = new PlayerSample();
        for (int i = 0; i < parts.length; i++) {
            for (int material = 0; material < MATERIAL_COUNT; material++) {
                if (dense[i][material] >= MIN_SHARE * total) window.part(parts[i]).addWeighting(MATERIALS[material], dense[i][material]);
            }
        }
        return window;
    }

    /**
     * Combines several players' histories. Each player's finds are smoothed over time on their own, since finds
     * close in time are more likely to be from the same place, and crafting is worked out on each of those windows
     * on its own, so finds by different players are independent however close in time they were. Only the crafted
     * bags of the players around the same moment are then merged, each player counting equally.
     *
     * @param spacing seconds between the moments, from the first snapshot of any player to the last
     */
    static List<Window> windows(Collection<SampleHistory> histories, CraftingDifficultyModel craftingModel, double tau, double spacing) {
        List<Window> windows = new ArrayList<>();
        List<SampleHistory> filled = histories.stream().filter(history -> !history.isEmpty()).toList();
        if (filled.isEmpty()) return windows;
        double start = filled.stream().mapToDouble(history -> history.snapshots.getFirst().seconds()).min().orElseThrow();
        double end = filled.stream().mapToDouble(history -> history.snapshots.getLast().seconds()).max().orElseThrow();
        // with no limit on tau every window is the same one
        double last = Double.isInfinite(tau) ? start : end;
        for (double seconds = start; seconds <= last + 1e-9; seconds += spacing) {
            double moment = seconds;
            List<ProbabilityBag> crafted = filled.stream()
                    .map(history -> history.window(moment, tau))
                    .filter(Objects::nonNull)
                    .map(window -> window.bag(craftingModel))
                    .toList();
            if (!crafted.isEmpty()) windows.add(new Window(moment, ProbabilityBag.mean(crafted)));
        }
        return windows;
    }

    /**
     * @return the bag of these players together: the players' crafted windows around each moment merged, then the
     * moments averaged
     */
    static ProbabilityBag bag(Collection<SampleHistory> histories, CraftingDifficultyModel craftingModel, double tau, double spacing) {
        return ProbabilityBag.mean(windows(histories, craftingModel, tau, spacing).stream().map(Window::bag).toList());
    }
}
