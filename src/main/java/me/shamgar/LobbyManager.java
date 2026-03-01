package me.shamgar;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import java.time.Duration;
import java.util.UUID;

public class LobbyManager implements Listener {

    private final McManhunt plugin;
    private boolean gameRunning = false;
    // When true, players cannot damage each other (PvP disabled) temporarily (e.g., grace period)
    private boolean pvpProtected = false;
    // Persistent lobby protection: players in the lobby should always be prevented from PvP
    private boolean lobbyProtected = false;
    private Location lobbySpawn;
    private Location gameSpawn; // where the current game is taking place

    public LobbyManager(McManhunt plugin) {
        this.plugin = plugin;
    }

    private static Duration ticksToDuration(long ticks) {
        return Duration.ofMillis(ticks * 50L);
    }

    public boolean isPvPProtected() {
        return pvpProtected || lobbyProtected;
    }

    /**
     * Protects PvP (disables player-vs-player damage) for the specified number of seconds.
     * If seconds <= 0, protection is cleared immediately.
     */
    public void protectPvPForSeconds(int seconds) {
        if (seconds <= 0) {
            pvpProtected = false;
            return;
        }
        pvpProtected = true;
        String msg = "§ePvP disabled for §b" + seconds + "§es.";
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(msg));
        // Schedule task to lift protection after seconds
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pvpProtected = false;
            String msg2 = "§aPvP is now enabled.";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(msg2));
        }, seconds * 20L);
    }

    public void clearPvPProtection() {
        pvpProtected = false;
    }

    /**
     * Set or clear the persistent lobby protection state. When true, PvP will be blocked by default
     * (useful when players are in the lobby outside of a game).
     */
    public void setLobbyProtected(boolean protectedState) {
        this.lobbyProtected = protectedState;
    }

    public boolean isGameRunning() {
        return gameRunning;
    }

    public void setGameRunning(boolean running) {
        this.gameRunning = running;
    }

    public Location getGameSpawn() {
        return gameSpawn;
    }

    public void setGameSpawn(Location gameSpawn) {
        this.gameSpawn = gameSpawn;
    }

    public Location getLobbySpawn() {
        return lobbySpawn;
    }

    /**
     * Builds a cool lobby platform at the world spawn.
     */
    public void buildLobby() {
        World world = Bukkit.getWorlds().get(0); // default overworld
        int cx = world.getSpawnLocation().getBlockX();
        int cz = world.getSpawnLocation().getBlockZ();
        int baseY = world.getHighestBlockYAt(cx, cz) + 5; // elevated platform

        plugin.getLogger().info("Building lobby at " + cx + ", " + baseY + ", " + cz);

        // --- Layer 1: Main circular platform (radius 10) ---
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= 10) {
                    // Gradient: outer = cyan terracotta, inner = quartz
                    Material mat;
                    if (dist > 8) {
                        mat = Material.CYAN_TERRACOTTA;
                    } else if (dist > 6) {
                        mat = Material.LIGHT_BLUE_CONCRETE;
                    } else if (dist > 4) {
                        mat = Material.WHITE_CONCRETE;
                    } else {
                        mat = Material.QUARTZ_BLOCK;
                    }
                    world.getBlockAt(cx + dx, baseY, cz + dz).setType(mat);

                    // Clear 5 blocks above for headroom
                    for (int y = 1; y <= 5; y++) {
                        world.getBlockAt(cx + dx, baseY + y, cz + dz).setType(Material.AIR);
                    }
                }
            }
        }

        // --- Layer 2: Glass border wall (2 high) ---
        for (int dx = -10; dx <= 10; dx++) {
            for (int dz = -10; dz <= 10; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 9.5 && dist <= 10.5) {
                    world.getBlockAt(cx + dx, baseY + 1, cz + dz).setType(Material.CYAN_STAINED_GLASS);
                    world.getBlockAt(cx + dx, baseY + 2, cz + dz).setType(Material.CYAN_STAINED_GLASS);
                }
            }
        }

        // --- Beacon in the center ---
        for (int dx2 = -1; dx2 <= 1; dx2++) {
            for (int dz2 = -1; dz2 <= 1; dz2++) {
                world.getBlockAt(cx + dx2, baseY - 1, cz + dz2).setType(Material.IRON_BLOCK);
            }
        }
        world.getBlockAt(cx, baseY, cz).setType(Material.BEACON);
        // Clear above beacon so the beam shows
        for (int y = 1; y <= 60; y++) {
            Block above = world.getBlockAt(cx, baseY + y, cz);
            if (above.getType() != Material.AIR) {
                above.setType(Material.AIR);
            }
        }

        // --- Corner pillars with sea lanterns ---
        int[][] corners = {{-8, -8}, {8, -8}, {-8, 8}, {8, 8}};
        for (int[] corner : corners) {
            int px = cx + corner[0];
            int pz = cz + corner[1];
            world.getBlockAt(px, baseY + 1, pz).setType(Material.DARK_OAK_FENCE);
            world.getBlockAt(px, baseY + 2, pz).setType(Material.DARK_OAK_FENCE);
            world.getBlockAt(px, baseY + 3, pz).setType(Material.SEA_LANTERN);
        }

        // --- Ring of soul lanterns at radius ~6 ---
        for (int angle = 0; angle < 360; angle += 45) {
            double rad = Math.toRadians(angle);
            int tx = cx + (int) Math.round(6 * Math.cos(rad));
            int tz = cz + (int) Math.round(6 * Math.sin(rad));
            world.getBlockAt(tx, baseY + 1, tz).setType(Material.SOUL_LANTERN);
        }

        // --- Info sign near center ---
        Block signBlock = world.getBlockAt(cx + 2, baseY + 2, cz);
        signBlock.setType(Material.OAK_SIGN);
        if (signBlock.getState() instanceof Sign sign) {
            sign.getSide(Side.FRONT).setLine(0, "§6§l✦ McManhunt+ ✦");
            sign.getSide(Side.FRONT).setLine(1, "§bWaiting for");
            sign.getSide(Side.FRONT).setLine(2, "§bthe game to start");
            sign.getSide(Side.FRONT).setLine(3, "§7/randomitem");
            sign.update();
        }

        // --- Glowstone underglow ---
        for (int dx3 = -10; dx3 <= 10; dx3++) {
            for (int dz3 = -10; dz3 <= 10; dz3++) {
                double dist = Math.sqrt(dx3 * dx3 + dz3 * dz3);
                if (dist <= 10 && (dx3 + dz3) % 4 == 0) {
                    world.getBlockAt(cx + dx3, baseY - 1, cz + dz3).setType(Material.GLOWSTONE);
                }
            }
        }

        // Set lobby spawn
        lobbySpawn = new Location(world, cx + 0.5, baseY + 1, cz + 0.5);
        world.setSpawnLocation(cx, baseY + 1, cz);

        // Fix world border – center it on lobby and make it huge so players don't die
        WorldBorder border = world.getWorldBorder();
        border.setCenter(cx, cz);
        // clamp to safe max to avoid IllegalArgumentException from craft world border
        double safeMax = 59999967.0; // slightly below server-side maximum
        try {
            border.setSize(Math.min(safeMax, border.getSize()));
        } catch (Throwable t) {
            try {
                border.setSize(safeMax);
            } catch (Throwable ignored) {
                plugin.getLogger().warning("Failed to set large world border size: " + t.getMessage());
            }
        }

        // Ensure lobby is PvP protected so players can't hit in the lobby
        lobbyProtected = true;

        plugin.getLogger().info("Lobby built successfully!");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!gameRunning && lobbySpawn != null) {
            Player player = event.getPlayer();
            player.teleport(lobbySpawn);
            player.setGameMode(GameMode.ADVENTURE);
            player.showTitle(Title.title(Component.text("§6§l✦ McManhunt+ ✦"), Component.text("§bWaiting for game to start..."), Title.Times.times(ticksToDuration(10), ticksToDuration(60), ticksToDuration(20))));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.5f);
        }
        // Ensure the player appears on the scoreboard / roundWins map
        if (plugin != null) {
            UUID id = event.getPlayer().getUniqueId();
            plugin.getRoundWinsMap().putIfAbsent(id, 0);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Prevent leaving the platform before game starts
        if (!gameRunning && lobbySpawn != null) {
            Location to = event.getTo();
            if (to != null && to.getWorld() == lobbySpawn.getWorld()) {
                double dist = Math.sqrt(
                        Math.pow(to.getX() - lobbySpawn.getX(), 2) +
                        Math.pow(to.getZ() - lobbySpawn.getZ(), 2));
                if (dist > 9.5 || to.getY() < lobbySpawn.getY() - 1) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage("§cWait for the game to start!");
                }
            }
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (gameRunning) {
            // Block still breaks, but no items drop
            event.setDropItems(false);
        }
    }

    @EventHandler
    public void onBlockDropItem(org.bukkit.event.block.BlockDropItemEvent event) {
        if (gameRunning) {
            // Cancel items being dropped by block breaking events
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        if (gameRunning) {
            // Allow explosion physics (knockback) to still apply, but prevent blocks from dropping and stop block changes
            try {
                event.blockList().clear();
                // keep the event not cancelled so entities still get velocity/knockback
            } catch (Throwable ignored) {
                // fallback to cancelling if something unexpected happens
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        if (gameRunning) {
            // Allow explosion to apply velocities (so wind/knockback effects still launch players)
            // but prevent the explosion from modifying blocks or dropping items
            try {
                event.blockList().clear();
                event.setYield(0.0f);
            } catch (Throwable ignored) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        // Only care about players picking up
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        org.bukkit.entity.Item item = event.getItem();
        if (item == null) return;

        UUID owner = plugin.getProtectedOwner(item.getUniqueId());
        if (owner == null) return; // not protected

        // If owner is NO_PICKUP sentinel, nobody can pick it up
        if (owner.equals(McManhunt.NO_PICKUP)) {
            event.setCancelled(true);
            return;
        }

        // Allow owner to pick up; cancel for everyone else
        if (p.getUniqueId().equals(owner)) {
            // Owner picks it up — remove protection for this specific entity
            plugin.removeProtection(item.getUniqueId());
            // allow pickup
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Determine attacker and victim players first
        org.bukkit.entity.Entity damager = event.getDamager();
        org.bukkit.entity.Entity victim = event.getEntity();
        Player attacker = null;
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player) attacker = (Player) src;
        }
        if (attacker == null) return;
        if (!(victim instanceof Player)) return;
        Player victimPlayer = (Player) victim;

        // If temporary global PvP protection is active, always cancel
        if (pvpProtected) {
            event.setCancelled(true);
            return;
        }

        // If lobby protection is enabled, only block PvP when players are in the lobby world
        // or when no game is running (safety)
        if (lobbyProtected) {
            if (!gameRunning) {
                event.setCancelled(true);
                return;
            }
            // If both players are in the lobby world (same as lobbySpawn world) cancel
            if (lobbySpawn != null) {
                World lobbyWorld = lobbySpawn.getWorld();
                if (lobbyWorld != null && attacker.getWorld().equals(lobbyWorld) && victimPlayer.getWorld().equals(lobbyWorld)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        // Otherwise allow damage
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!gameRunning) return;
        org.bukkit.entity.Item dropped = event.getItemDrop();
        if (dropped == null) return;
        // Protect item so only the dropper can pick it up (permanent protection)
        plugin.protectDroppedItem(dropped, event.getPlayer().getUniqueId(), 0L);
    }

    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        // When an Item entity spawns, check if its ItemStack contains owner metadata and protect it
        if (!(event.getEntity() instanceof Item)) return;
        Item spawned = event.getEntity();
        if (spawned == null) return;
        org.bukkit.inventory.ItemStack stack = spawned.getItemStack();
        if (stack == null || !stack.hasItemMeta()) return;
        try {
            String ownerStr = stack.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getOwnerKey(), org.bukkit.persistence.PersistentDataType.STRING);
            if (ownerStr != null && !ownerStr.isEmpty()) {
                try {
                    UUID owner = UUID.fromString(ownerStr);
                    // protect permanently so others can never pick it up
                    plugin.protectDroppedItem(spawned, owner, 0L);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Throwable ignored) {
            // ignore if persistent data not present or any other issue
        }
    }

    /**
     * Sends all players back to lobby and resets game state.
     */
    public void sendAllToLobby() {
        if (lobbySpawn == null) return;
        gameRunning = false;
        // Ensure PvP remains disabled in the lobby
        lobbyProtected = true;
        gameSpawn = null;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.teleport(lobbySpawn);
            player.setGameMode(GameMode.ADVENTURE);
            player.showTitle(Title.title(Component.text("§6§lBack to Lobby"), Component.text("§7Game ended"), Title.Times.times(ticksToDuration(10), ticksToDuration(40), ticksToDuration(20))));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!gameRunning) return; // only during the game

        Player player = event.getEntity();
        // Tag all ItemStacks that will be dropped with the owner's UUID so spawned entities become protected
        if (player != null) {
            UUID ownerId = player.getUniqueId();
            for (org.bukkit.inventory.ItemStack drop : event.getDrops()) {
                if (drop != null) {
                    plugin.tagItemStackOwner(drop, ownerId);
                }
            }
        }

        // Switch to spectator after a short delay (after respawn screen)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (gameRunning) {
                player.setGameMode(GameMode.SPECTATOR);
                // Record the loss for this player
                plugin.addLoss(player.getUniqueId());
                player.showTitle(Title.title(Component.text("§c§lYOU DIED"), Component.text("§7You are now spectating"), Title.Times.times(ticksToDuration(10), ticksToDuration(60), ticksToDuration(20))));
                player.sendMessage(Component.text("§7You died and are now spectating. Wait for the game to end.").toString());

                // Check if game should end (1 or 0 players left)
                checkGameOver();
            }
        }, 2L);
    }

    /**
     * Checks how many survival players remain.
     * If 0 — everyone died. If 1 — that player wins.
     */
    private void checkGameOver() {
        if (!gameRunning) return;

        Player lastAlive = null;
        int aliveCount = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SURVIVAL) {
                lastAlive = p;
                aliveCount++;
            }
        }

        if (aliveCount == 0) {
            String msg = "§c§l☠ Everyone has died! Game over! ☠";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(msg));
            Bukkit.getScheduler().runTaskLater(plugin, plugin::stopGame, 60L);
        } else if (aliveCount == 1 && Bukkit.getOnlinePlayers().size() > 1) {
            // Last player standing wins!
            String winMsg = "§6§l✦ " + lastAlive.getName() + " wins! ✦";
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(winMsg));
            lastAlive.showTitle(Title.title(Component.text("§6§l✦ YOU WIN! ✦"), Component.text("§aLast player standing!"), Title.Times.times(ticksToDuration(10), ticksToDuration(80), ticksToDuration(20))));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != lastAlive) {
                    p.showTitle(Title.title(Component.text("§c§lGAME OVER"), Component.text("§f" + lastAlive.getName() + " §7wins!"), Title.Times.times(ticksToDuration(10), ticksToDuration(80), ticksToDuration(20))));
                }
            }
             // Record win
             plugin.addWin(lastAlive.getUniqueId());
            Bukkit.getScheduler().runTaskLater(plugin, plugin::stopGame, 100L); // 5 second delay
         }
     }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (gameRunning && gameSpawn != null) {
            // Respawn in the game world so they can spectate other players
            event.setRespawnLocation(gameSpawn);
        } else if (!gameRunning && lobbySpawn != null) {
            // Respawning outside game → send to lobby
            event.setRespawnLocation(lobbySpawn);
        }
    }
}
