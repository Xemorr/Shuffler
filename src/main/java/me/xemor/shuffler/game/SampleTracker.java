package me.xemor.shuffler.game;

import me.xemor.shuffler.Shuffler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.jetbrains.annotations.Nullable;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps sampling around the players while a game runs, one player at a time, spreading each search over many ticks.
 * The player with the fewest searches so far goes next, so everyone's sample fills at the same pace however long
 * they have been in the game. Each search is recorded in the player's {@link SampleHistory} as it finishes.
 * <p>
 * The work done per tick adapts to the server: a search that runs out of time while still finding new blocks means
 * there was room to do more, if the server kept up with it.
 */
final class SampleTracker extends BukkitRunnable {

    private static final int MAX_TICKS_PER_SEARCH = 200;
    private static final int DROPS_CACHE_SIZE = 100;
    private static final int VIEW_RAYS_PER_SEARCH = 1000;
    // Rays share the nearby search's per-tick budget, which adapts to the server's TPS. A ray costs about as much as
    // 5 search nodes (measured at 3-6 nodes warm with 100 mobs in range, more with heavy mob farms), and rays may use
    // this share of the budget each tick.
    private static final double VIEW_SHARE_OF_BUDGET = 0.1;
    private static final int VIEW_RAY_COST = 5;
    private static final double HEALTHY_TPS = 19.9;
    private static final int MIN_BUDGET_STEP = 50;
    private static final int MIN_BUDGET_PER_TICK = 100;

    private final Game game;
    private final Map<UUID, SampleHistory> histories;
    private final Map<Material, Collection<ItemStack>> dropsCache = new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Material, Collection<ItemStack>> eldest) {
            return size() > DROPS_CACHE_SIZE;
        }
    };
    private final Map<Material, Collection<ItemStack>> viewDropsCache = ViewSampler.newCache();
    private int budgetPerTick = 6000;
    private int budgetStep = 1000;

    private Player player = null;
    private PlayerSample sample = null;
    private NearbySearch search = null;
    private ViewSampler view = null;
    private int ticksRunning = 0;

    /**
     * @param histories where each player's searches are kept; the tracker adds to it
     */
    SampleTracker(Game game, Map<UUID, SampleHistory> histories) {
        this.game = game;
        this.histories = histories;
    }

    @Override
    public void run() {
        if (search == null || !player.isOnline()) {
            if (!startSearch()) return;
        }

        int spentOnView = stepView();
        search.step(budgetPerTick - spentOnView);
        ticksRunning++;

        if (search.finished()) finishSearch();
        else if (ticksRunning >= MAX_TICKS_PER_SEARCH) {
            adaptBudget();
            finishSearch();
        }
    }

    /**
     * @return whether there was a player to start a search around
     */
    private boolean startSearch() {
        List<? extends Player> alive = Bukkit.getOnlinePlayers().stream().filter(game::isPlayerAlive).toList();
        if (alive.isEmpty()) return false;

        // Players with the same number of searches are picked between at random.
        int fewest = alive.stream().mapToInt(candidate -> historyOf(candidate).size()).min().orElseThrow();
        List<? extends Player> due = alive.stream().filter(candidate -> historyOf(candidate).size() == fewest).toList();
        player = due.get(ThreadLocalRandom.current().nextInt(due.size()));

        // Each search gets a sample of its own, which joins the player's history when the search ends.
        sample = new PlayerSample();
        for (ItemStack stack : player.getInventory().getContents()) addCarried(stack);
        search = new NearbySearch(player, sample, dropsCache);
        view = new ViewSampler(player.getEyeLocation(), player, VIEW_RAYS_PER_SEARCH, sample.viewedDrops(), sample.viewedBlocks(), viewDropsCache);
        ticksRunning = 0;
        return true;
    }

    /**
     * Counts a stack the player is carrying, and what is inside it if it is a bundle.
     */
    private void addCarried(@Nullable ItemStack stack) {
        if (stack == null) return;
        sample.addCarried(stack.getType());
        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            for (ItemStack inside : bundle.getItems()) addCarried(inside);
        }
    }

    /**
     * @return how much of this tick's budget the rays used
     */
    private int stepView() {
        if (view.finished()) return 0;
        int rays = (int) (budgetPerTick * VIEW_SHARE_OF_BUDGET / VIEW_RAY_COST);
        view.step(rays);
        return rays * VIEW_RAY_COST;
    }

    private void finishSearch() {
        if (!sample.isEmpty()) historyOf(player).add(System.nanoTime() / 1e9, sample);
        if (player.isOnline()) {
            player.sendMessage("§aYou and your surroundings have been sampled into the bag!");
        }
        search = null;
    }

    /**
     * A search that ran out of time with blocks left to visit could have done more: give it more per tick if the
     * server is keeping up, less if not, in ever smaller steps.
     */
    private void adaptBudget() {
        budgetPerTick = Math.max(MIN_BUDGET_PER_TICK, budgetPerTick + (Bukkit.getTPS()[0] >= HEALTHY_TPS ? budgetStep : -budgetStep));
        budgetStep = Math.max(MIN_BUDGET_STEP, budgetStep - 5);
        Shuffler.getInstance().getLogger().fine("Tracker did not finish in " + MAX_TICKS_PER_SEARCH + " ticks. Budget per tick is now " + budgetPerTick);
    }

    private SampleHistory historyOf(Player player) {
        return histories.computeIfAbsent(player.getUniqueId(), _ -> new SampleHistory());
    }
}
