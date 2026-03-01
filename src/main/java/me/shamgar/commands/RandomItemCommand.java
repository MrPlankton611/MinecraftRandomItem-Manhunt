package me.shamgar.commands;

import me.shamgar.McManhunt;
import me.shamgar.tasks.RandomItemTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public class RandomItemCommand implements CommandExecutor {

    private final McManhunt plugin;
    private RandomItemTask task;
    private boolean running = false;

    public RandomItemCommand(McManhunt plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return running;
    }

    /** Restart the task with the current interval (called when timer changes). */
    public void restart() {
        if (running && task != null) {
            task.cancel();
            task = new RandomItemTask(plugin);
            task.runTaskTimer(plugin, plugin.getItemIntervalTicks(), plugin.getItemIntervalTicks());
        }
    }

    /** Force stop the game (called when all players die). */
    public void forceStop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        running = false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.isOp()) {
            sender.sendMessage("§cYou must be an operator to use this command.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            if (!running) {
                sender.sendMessage("§cRandom items are not currently running.");
                return true;
            }
            task.cancel();
            task = null;
            running = false;
            String msgStop = "§c§l⚠ Random items stopped!";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(msgStop));

            // Send everyone back to lobby
            plugin.getLobbyManager().sendAllToLobby();
            return true;
        }

        if (running) {
            sender.sendMessage("§eRandom items are already running! Use §f/randomitem stop §eto stop.");
            return true;
        }

        // Load a random pregenerated world
        sender.sendMessage("§eLoading a random world...");
        World world = plugin.loadRandomPregenWorld();
        if (world == null) {
            sender.sendMessage("§cNo pregenerated world found! Make sure pregen_worlds/ contains valid worlds.");
            return true;
        }

        // Find a safe spawn
        Location safeSpawn = plugin.findSafeSpawn(world);

        // Mark game as running
        plugin.getLobbyManager().setGameRunning(true);
        plugin.getLobbyManager().setGameSpawn(safeSpawn);

        // Reset advancements for all players at game start
        plugin.resetAllAdvancements();

        // Announce countdown
        String preMsg1 = "§6§l✦ Srikar Fuck you! ✦";
        String preMsg2 = "§eTeleporting to §b" + world.getName() + " §ein 5 seconds...";
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(preMsg1));
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(preMsg2));

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(Component.text("§6§l✦ GET READY ✦"), Component.text("§eTeleporting in 5 seconds..."), Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(60*50), Duration.ofMillis(20*50))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        }

        // 5-second countdown then teleport + start items
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Random rnd = new Random();
            Set<Location> used = new HashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                // Compute a random offset within radius 10 (avoid exact same block by checking used set)
                Location loc = null;
                for (int attempt = 0; attempt < 40; attempt++) {
                    double angle = rnd.nextDouble() * Math.PI * 2.0;
                    double r = rnd.nextDouble() * 10.0; // up to 10 blocks
                    double dx = Math.cos(angle) * r;
                    double dz = Math.sin(angle) * r;
                    double cx = safeSpawn.getX() + dx;
                    double cz = safeSpawn.getZ() + dz;
                    // Round to block centers
                    int bx = (int)Math.floor(cx);
                    int bz = (int)Math.floor(cz);
                    // Ask plugin to check this column for a safe standing spot
                    Location safe = plugin.checkColumnSafe(world, bx, bz);
                    if (safe != null && !used.contains(safe)) {
                        loc = safe;
                        break;
                    }
                }
                if (loc == null) {
                    // fallback to the general safeSpawn found earlier
                    loc = safeSpawn.clone();
                }

                used.add(loc);

                player.teleport(loc);
                player.setGameMode(GameMode.SURVIVAL);
                player.setHealth(20);
                player.setFoodLevel(20);
                player.setSaturation(0f); // full hunger, no saturation = slow regen
                player.getInventory().clear();
                player.showTitle(Title.title(Component.text("§a§lGO!"), Component.text("§7Random items every §b" + (plugin.getItemIntervalTicks() / 20) + "s"), Title.Times.times(Duration.ofMillis(0), Duration.ofMillis(40*50), Duration.ofMillis(20*50))));
                player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 1.5f);
            }

            // Disable PvP for the first 10 seconds so players can't immediately fight on teleport
            plugin.getLobbyManager().protectPvPForSeconds(10);

            // Set the game world border to 1500x1500 centered on spawn
            WorldBorder border = world.getWorldBorder();
            border.setCenter(safeSpawn.getX(), safeSpawn.getZ());
            border.setSize(1500);
            String borderMsg = "§eWorld border set to §b1500x1500 §ecentered on spawn.";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(borderMsg));

            // Start scheduled border shrink in plugin (100 blocks per minute down to 500)
            plugin.startBorderShrink(world, safeSpawn.getX(), safeSpawn.getZ());

            // Start the random item task
            task = new RandomItemTask(plugin);
            long interval = plugin.getItemIntervalTicks();
            task.runTaskTimer(plugin, interval, interval);
            running = true;
            String started = "§a§l✦ Random items started! ✦ §7(every " + (interval / 20) + "s)";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(started));
        }, 100L); // 5 seconds = 100 ticks

        return true;
    }
}
