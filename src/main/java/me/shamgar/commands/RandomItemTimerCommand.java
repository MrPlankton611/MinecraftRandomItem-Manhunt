package me.shamgar.commands;

import me.shamgar.McManhunt;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class RandomItemTimerCommand implements CommandExecutor {

    private final McManhunt plugin;
    private final RandomItemCommand randomItemCommand;

    public RandomItemTimerCommand(McManhunt plugin, RandomItemCommand randomItemCommand) {
        this.plugin = plugin;
        this.randomItemCommand = randomItemCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp()) {
            sender.sendMessage("§cYou must be an operator to use this command.");
            return true;
        }

        if (args.length == 0) {
            long currentSeconds = plugin.getItemIntervalTicks() / 20;
            sender.sendMessage("§eCurrent interval: §f" + currentSeconds + " seconds§e. Usage: §f/randomitemtimer <seconds>");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cThat's not a valid number! Usage: §f/randomitemtimer <seconds>");
            return true;
        }

        if (seconds < 1) {
            sender.sendMessage("§cInterval must be at least 1 second.");
            return true;
        }

        long ticks = seconds * 20L;
        plugin.setItemIntervalTicks(ticks);

        // If random items are currently running, restart with the new interval
        if (randomItemCommand.isRunning()) {
            randomItemCommand.restart();
            sender.sendMessage("§aTimer updated to §f" + seconds + " seconds §aand task restarted!");
        } else {
            sender.sendMessage("§aTimer updated to §f" + seconds + " seconds§a. Start with §f/randomitem§a.");
        }

        return true;
    }
}

