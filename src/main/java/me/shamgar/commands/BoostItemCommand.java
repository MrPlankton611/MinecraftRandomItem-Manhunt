package me.shamgar.commands;

import me.shamgar.McManhunt;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BoostItemCommand implements CommandExecutor {

    private final McManhunt plugin;

    public BoostItemCommand(McManhunt plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            sender.sendMessage("§eUsage: /boostitem <MATERIAL>");
            return true;
        }

        try {
            Material mat = Material.valueOf(args[0].toUpperCase());

            Material current = plugin.getPlayerBoost(player.getUniqueId());
            if (current != null && current.equals(mat)) {
                sender.sendMessage("§cYou already boosted that item. You can only have one boost at a time.");
                return true;
            }

            // If the player had a different boost, remove it first
            if (current != null && !current.equals(mat)) {
                plugin.clearPlayerBoost(player.getUniqueId());
                sender.sendMessage("§ePrevious boost on " + current.name() + " removed.");
            }

            // Apply boost to the selected material
            double nw = plugin.boostItem(mat);
            plugin.setPlayerBoost(player.getUniqueId(), mat);
            sender.sendMessage("§aBoosted " + mat.name() + " to weight: §f" + nw + " §a(you own this boost)");
        } catch (IllegalArgumentException e) {
            sender.sendMessage("§cUnknown material: " + args[0]);
        }
        return true;
    }
}
