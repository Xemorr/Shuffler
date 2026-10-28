package me.xemor.shuffler.game;

import me.xemor.shuffler.game.crafting.CraftingDifficultyModel;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

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
     * Works out crafting for the window around each of evenly spaced moments, from the first snapshot to the last.
     *
     * @param spacing seconds between the moments
     */
    List<Window> windows(CraftingDifficultyModel craftingModel, double tau, double spacing) {
        List<Window> windows = new ArrayList<>();
        if (snapshots.isEmpty()) return windows;
        double start = snapshots.getFirst().seconds(), end = snapshots.getLast().seconds();
        if (Double.isInfinite(tau)) {
            // every window is the same one
            PlayerSample everything = window(start, tau);
            windows.add(new Window(start, everything.bag(craftingModel)));
            return windows;
        }
        for (double seconds = start; seconds <= end + 1e-9; seconds += spacing) {
            PlayerSample window = window(seconds, tau);
            if (window != null) windows.add(new Window(seconds, window.bag(craftingModel)));
        }
        return windows;
    }

    /**
     * @return this player's bag: crafting worked out for the window around each moment, then averaged
     */
    ProbabilityBag bag(CraftingDifficultyModel craftingModel, double tau, double spacing) {
        return ProbabilityBag.mean(windows(craftingModel, tau, spacing).stream().map(Window::bag).toList());
    }
}
