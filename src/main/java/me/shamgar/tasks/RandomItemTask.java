package me.shamgar.tasks;

import me.shamgar.McManhunt;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class RandomItemTask extends BukkitRunnable {

    private final McManhunt plugin;
    private final Random random = new Random();

    public RandomItemTask(McManhunt plugin) {
        this.plugin = plugin;
    }

    private Material weightedPick() {
        Map<Material, Double> weights = plugin.getItemWeights();
        double total = 0.0;
        for (double w : weights.values()) total += w;
        if (total <= 0) return null;
        double r = random.nextDouble() * total;
        double acc = 0.0;
        for (Map.Entry<Material, Double> ent : weights.entrySet()) {
            acc += ent.getValue();
            if (r <= acc) return ent.getKey();
        }
        // fallback
        return weights.keySet().iterator().next();
    }

    @Override
    public void run() {
        List<Material> items = plugin.getAllowedItems();
        if (items.isEmpty()) {
            plugin.getLogger().warning("No allowed items loaded – skipping random item drop.");
            return;
        }

        // Reset countdown to interval
        plugin.resetCountdown();

        for (Player player : Bukkit.getOnlinePlayers()) {
            // Skip spectators/dead players
            if (player.getGameMode() != GameMode.SURVIVAL) continue;

            // Select random material using weights
            Material mat = weightedPick();
            if (mat == null) continue;

            // Determine max stack size for this material
            int maxStack = new ItemStack(mat).getMaxStackSize();

            // Give the player one full stack (or 1 if unstackable)
            int giveAmount = Math.max(1, Math.min(maxStack, maxStack));
            ItemStack give = new ItemStack(mat, giveAmount);
            player.getInventory().addItem(give);
            player.sendMessage("§a§lRandom Item! §r§7You received: §f" + formatName(mat.name()));

            // Drop 7 stacks (or 7 singles for unstackable) around the player and protect them
            int drops = 7;
            long protectMs = 0L; // 0 or negative => permanent protection (owner-only forever)

            Location base = player.getLocation();
            for (int i = 0; i < drops; i++) {
                double ox = (random.nextDouble() - 0.5) * 4.0;
                double oz = (random.nextDouble() - 0.5) * 4.0;
                Location dropLoc = base.clone().add(ox, 0.5, oz);

                ItemStack dropStack;
                if (maxStack <= 1) {
                    dropStack = new ItemStack(mat, 1);
                } else {
                    dropStack = new ItemStack(mat, maxStack);
                }

                Item dropped = player.getWorld().dropItemNaturally(dropLoc, dropStack);
                if (dropped != null) {
                    plugin.protectDroppedItem(dropped, player.getUniqueId(), protectMs);
                }
            }

            // Ensure hunger full + low saturation so regen is slow
            player.setFoodLevel(20);
            player.setSaturation(0f);
        }

        // Start countdown for next drop
        plugin.startCountdown((int)(plugin.getItemIntervalTicks() / 20L));
    }

    private String formatName(String name) {
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }
}
