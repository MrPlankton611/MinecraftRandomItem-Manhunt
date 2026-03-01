package me.shamgar.tasks;

import me.shamgar.McManhunt;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerTrackerTask extends BukkitRunnable {

    private final McManhunt plugin;

    // tracker UUID -> target UUID
    private final Map<UUID, UUID> tracking = new HashMap<>();
    // tracker UUID -> bossbar
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    private static final double MAX_TRACK_DISTANCE = 1500.0; // matches world border

    public PlayerTrackerTask(McManhunt plugin) {
        this.plugin = plugin;
    }

    public void startTracking(Player tracker, Player target) {
        UUID trackerUUID = tracker.getUniqueId();
        UUID targetUUID = target.getUniqueId();

        // Remove old bossbar if re-tracking
        stopTracking(tracker);

        tracking.put(trackerUUID, targetUUID);

        // Create bossbar
        BossBar bar = Bukkit.createBossBar(
                "§bTracking: §f" + target.getName() + " §7| §eCalculating...",
                BarColor.BLUE,
                BarStyle.SOLID
        );
        bar.addPlayer(tracker);
        bar.setVisible(true);
        bossBars.put(trackerUUID, bar);
    }

    public void stopTracking(Player tracker) {
        UUID trackerUUID = tracker.getUniqueId();
        tracking.remove(trackerUUID);

        BossBar bar = bossBars.remove(trackerUUID);
        if (bar != null) {
            bar.removeAll();
        }
    }

    public void stopAll() {
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        bossBars.clear();
        tracking.clear();
    }

    @Override
    public void run() {
        // Iterate over a copy to avoid ConcurrentModificationException
        for (Map.Entry<UUID, UUID> entry : Map.copyOf(tracking).entrySet()) {
            Player tracker = Bukkit.getPlayer(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());

            if (tracker == null || !tracker.isOnline()) {
                // Tracker went offline, clean up
                BossBar bar = bossBars.remove(entry.getKey());
                if (bar != null) bar.removeAll();
                tracking.remove(entry.getKey());
                continue;
            }

            if (target == null || !target.isOnline()) {
                // Target went offline
                BossBar bar = bossBars.get(entry.getKey());
                if (bar != null) {
                    bar.setTitle("§bTracking: §c(offline)");
                    bar.setProgress(0);
                    bar.setColor(BarColor.RED);
                }
                continue;
            }

            // Different world check
            if (!tracker.getWorld().equals(target.getWorld())) {
                BossBar bar = bossBars.get(entry.getKey());
                if (bar != null) {
                    bar.setTitle("§bTracking: §f" + target.getName() + " §7| §cDifferent world");
                    bar.setProgress(0);
                    bar.setColor(BarColor.RED);
                }
                continue;
            }

            Location trackerLoc = tracker.getLocation();
            Location targetLoc = target.getLocation();
            double distance = trackerLoc.distance(targetLoc);

            // Update compass to point to target
            for (ItemStack item : tracker.getInventory().getContents()) {
                if (item != null && item.getType() == Material.COMPASS) {
                    CompassMeta meta = (CompassMeta) item.getItemMeta();
                    meta.setLodestone(targetLoc);
                    meta.setLodestoneTracked(false);
                    item.setItemMeta(meta);
                }
            }

            // Update bossbar
            BossBar bar = bossBars.get(entry.getKey());
            if (bar != null) {
                int distInt = (int) distance;
                String distText = distInt + "m";

                // Color based on distance
                BarColor color;
                if (distance < 50) {
                    color = BarColor.RED;
                } else if (distance < 150) {
                    color = BarColor.YELLOW;
                } else {
                    color = BarColor.BLUE;
                }

                bar.setTitle("§bTracking: §f" + target.getName() + " §7| §e" + distText + " away");
                bar.setColor(color);

                // Progress bar: full when close, empty when far
                double progress = Math.max(0, 1.0 - (distance / MAX_TRACK_DISTANCE));
                bar.setProgress(Math.min(1.0, Math.max(0.0, progress)));
            }
        }
    }
}

