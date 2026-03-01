package me.shamgar.commands;

import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class DebugCommand implements CommandExecutor {

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

        String worldName = player.getWorld().getName();
        int x = player.getLocation().getBlockX();
        int y = player.getLocation().getBlockY();
        int z = player.getLocation().getBlockZ();
        String gameMode = player.getGameMode().name();

        WorldBorder border = player.getWorld().getWorldBorder();
        double borderSize = border.getSize();
        double borderCenterX = border.getCenter().getX();
        double borderCenterZ = border.getCenter().getZ();

        player.sendMessage("§6§l--- Debug Info ---");
        player.sendMessage("§eWorld: §f" + worldName);
        player.sendMessage("§eCoords: §f" + x + ", " + y + ", " + z);
        player.sendMessage("§eGameMode: §f" + gameMode);
        player.sendMessage("§ePlayers online: §f" + player.getServer().getOnlinePlayers().size());
        player.sendMessage("§eWorld Border: §f" + (int) borderSize + "x" + (int) borderSize
                + " §7(center: " + (int) borderCenterX + ", " + (int) borderCenterZ + ")");
        player.sendMessage("§6§l------------------");
        return true;
    }
}

