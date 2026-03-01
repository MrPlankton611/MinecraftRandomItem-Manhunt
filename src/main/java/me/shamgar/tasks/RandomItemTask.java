package me.shamgar.tasks;

import me.shamgar.McManhunt;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class RandomItemTask extends BukkitRunnable {

    private final McManhunt plugin;
    private final Random random = new Random();

    // Survival-useful potion types to randomly apply
    private static final PotionType[] USEFUL_POTIONS = {
            PotionType.FIRE_RESISTANCE,
            PotionType.HARMING,
            PotionType.HEALING,
            PotionType.INVISIBILITY,
            PotionType.LEAPING,
            PotionType.LONG_FIRE_RESISTANCE,
            PotionType.LONG_INVISIBILITY,
            PotionType.LONG_LEAPING,
            PotionType.LONG_NIGHT_VISION,
            PotionType.LONG_POISON,
            PotionType.LONG_REGENERATION,
            PotionType.LONG_SLOWNESS,
            PotionType.LONG_SLOW_FALLING,
            PotionType.LONG_STRENGTH,
            PotionType.LONG_SWIFTNESS,
            PotionType.LONG_TURTLE_MASTER,
            PotionType.LONG_WEAKNESS,
            PotionType.NIGHT_VISION,
            PotionType.POISON,
            PotionType.REGENERATION,
            PotionType.SLOWNESS,
            PotionType.SLOW_FALLING,
            PotionType.STRENGTH,
            PotionType.STRONG_HARMING,
            PotionType.STRONG_HEALING,
            PotionType.STRONG_LEAPING,
            PotionType.STRONG_POISON,
            PotionType.STRONG_REGENERATION,
            PotionType.STRONG_SLOWNESS,
            PotionType.STRONG_STRENGTH,
            PotionType.STRONG_SWIFTNESS,
            PotionType.STRONG_TURTLE_MASTER,
            PotionType.SWIFTNESS,
            PotionType.TURTLE_MASTER,
            PotionType.WEAKNESS,
            PotionType.WIND_CHARGED,
            PotionType.OOZING,
            PotionType.INFESTED,
            PotionType.WEAVING,
    };

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

            // Determine quantity based on item type
            int giveAmount;
            int dropAmount;
            int drops = 7;
            if (mat == Material.ENCHANTED_GOLDEN_APPLE) {
                giveAmount = 1;
                dropAmount = 1;
            } else if (mat == Material.GOLDEN_APPLE) {
                giveAmount = 16;
                dropAmount = 16;
            } else {
                int maxStack = new ItemStack(mat).getMaxStackSize();
                giveAmount = Math.max(1, maxStack);
                dropAmount = Math.max(1, maxStack);
            }

            // Build the item stack (apply potion effect if it's a potion type)
            ItemStack give = new ItemStack(mat, giveAmount);
            boolean isPotion = (mat == Material.POTION || mat == Material.SPLASH_POTION || mat == Material.LINGERING_POTION);
            PotionType chosenPotion = null;
            if (isPotion) {
                chosenPotion = USEFUL_POTIONS[random.nextInt(USEFUL_POTIONS.length)];
                PotionMeta pm = (PotionMeta) give.getItemMeta();
                pm.setBasePotionType(chosenPotion);
                give.setItemMeta(pm);
            }

            player.getInventory().addItem(give);
            String itemName = isPotion ? formatName(mat.name()) + " (" + formatName(chosenPotion.name()) + ")" : formatName(mat.name());
            player.sendMessage("§a§lRandom Item! §r§7You received: §f" + itemName);

            // Drop 7 stacks (or 7 singles for unstackable) around the player and protect them
            long protectMs = 0L; // 0 or negative => permanent protection (owner-only forever)

            Location base = player.getLocation();
            for (int i = 0; i < drops; i++) {
                double ox = (random.nextDouble() - 0.5) * 4.0;
                double oz = (random.nextDouble() - 0.5) * 4.0;
                Location dropLoc = base.clone().add(ox, 0.5, oz);

                ItemStack dropStack = new ItemStack(mat, dropAmount <= 1 ? 1 : dropAmount);
                if (isPotion && chosenPotion != null) {
                    PotionMeta dpm = (PotionMeta) dropStack.getItemMeta();
                    dpm.setBasePotionType(chosenPotion);
                    dropStack.setItemMeta(dpm);
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
