package me.shamgar.commands;

import me.shamgar.McManhunt;
import me.shamgar.tasks.PlayerTrackerTask;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;

public class TrackCommand implements CommandExecutor {

    private final McManhunt plugin;

    public TrackCommand(McManhunt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cYou must be an operator to use this command.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        if (args.length == 0) {
            // List trackable players
            player.sendMessage("§6§l--- Trackable Players ---");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == player) continue;
                if (online.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
                player.sendMessage("§e - §f" + online.getName());
            }
            player.sendMessage("§7Usage: §f/track <player>");
            player.sendMessage("§7Use §f/track stop §7to stop tracking.");
            player.sendMessage("§6§l--------------------------");
            return true;
        }

        if (args[0].equalsIgnoreCase("stop")) {
            plugin.getPlayerTrackerTask().stopTracking(player);
            player.sendMessage("§cStopped tracking.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage("§cPlayer not found: §f" + args[0]);
            return true;
        }

        if (target == player) {
            player.sendMessage("§cYou can't track yourself!");
            return true;
        }

        // Give the player a tracking compass if they don't have one
        boolean hasCompass = false;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.COMPASS) {
                hasCompass = true;
                break;
            }
        }
        if (!hasCompass) {
            ItemStack compass = new ItemStack(Material.COMPASS);
            CompassMeta meta = (CompassMeta) compass.getItemMeta();
            meta.setDisplayName("§b§lPlayer Tracker");
            meta.setLodestone(target.getLocation());
            meta.setLodestoneTracked(false);
            compass.setItemMeta(meta);
            player.getInventory().addItem(compass);
        }

        plugin.getPlayerTrackerTask().startTracking(player, target);
        player.sendMessage("§aNow tracking §f" + target.getName() + "§a! Your compass points to them.");
        return true;
    }
}

