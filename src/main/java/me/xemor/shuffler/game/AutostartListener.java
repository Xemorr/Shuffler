package me.xemor.shuffler.game;

import me.xemor.shuffler.Shuffler;
import me.xemor.shuffler.config.AutostartYaml;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class AutostartListener implements Listener {

    private final Game game;
    private BukkitTask countdownTask;
    private int countdown;

    public AutostartListener(Game game) {
        this.game = game;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        AutostartYaml autostart = Shuffler.getInstance().getConfigYaml().automating().autostart();

        if (!autostart.enabled()) {
            return;
        }

        int playerCount = Bukkit.getOnlinePlayers().size();

        if (playerCount >= autostart.minPlayers()) {
            startOrUpdateCountdown(playerCount, autostart);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        AutostartYaml autostart = Shuffler.getInstance().getConfigYaml().automating().autostart();
        if (!autostart.enabled()) {
            return;
        }

        // Schedule for next tick to get accurate player count after quit
        new BukkitRunnable() {
            @Override
            public void run() {
                int playerCount = Bukkit.getOnlinePlayers().size();

                if (playerCount < autostart.minPlayers()) {
                    cancelCountdown();
                    Bukkit.getServer().broadcast(Component.text("Game start cancelled - not enough players!"));
                } else {
                    startOrUpdateCountdown(playerCount, autostart);
                }
            }
        }.runTask(Shuffler.getInstance());
    }

    private void startOrUpdateCountdown(int playerCount, AutostartYaml autostart) {
        if (game.isGameStarted()) {
            return;
        }

        int playersAboveMin = playerCount - autostart.minPlayers();
        double speedupReduction = playersAboveMin * autostart.speedupPerPlayer();
        int newCountdown = (int) Math.max(1, autostart.secondsUntilStart() - speedupReduction);

        if (countdownTask != null) {
            // Update countdown if it would be shorter
            if (newCountdown < countdown) {
                countdown = newCountdown;
                Bukkit.getServer().broadcast(Component.text("Game starting in " + countdown + " seconds!"));
            }
        } else {
            // Start new countdown
            countdown = newCountdown;
            Bukkit.getServer().broadcast(Component.text("Game starting in " + countdown + " seconds!"));

            countdownTask = new BukkitRunnable() {
                @Override
                public void run() {
                    countdown--;
                    if (game.isGameStarted()) {
                        countdownTask.cancel();
                        return;
                    }

                    if (countdown <= 0) {
                        game.startGame();
                        countdownTask = null;
                        cancel();
                    } else if (countdown <= 10 || countdown % 30 == 0) {
                        Bukkit.getServer().broadcast(Component.text("Game starting in " + countdown + " seconds!"));
                    }
                }
            }.runTaskTimer(Shuffler.getInstance(), 20L, 20L);
        }
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }
}
