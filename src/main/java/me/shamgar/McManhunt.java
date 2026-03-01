package me.shamgar;

import me.shamgar.commands.DebugCommand;
import me.shamgar.commands.RandomItemCommand;
import me.shamgar.commands.RandomItemTimerCommand;
import me.shamgar.commands.TrackCommand;
import me.shamgar.tasks.PlayerTrackerTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.WorldBorder;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class McManhunt extends JavaPlugin {

    private static McManhunt instance;
    private final List<Material> allowedItems = new ArrayList<>();
    private final Random random = new Random();
    private long itemIntervalTicks = 1200L; // default 60 seconds
    private LobbyManager lobbyManager;
    private RandomItemCommand randomItemCmd;
    private PlayerTrackerTask playerTrackerTask;
    private NamespacedKey ownerKey;

    // Weights for items used for weighted random selection
    private final Map<Material, Double> itemWeights = new ConcurrentHashMap<>();
    // Number of active boosts applied to each material (so multiple players boosting same item stacks correctly)
    private final Map<Material, Integer> boostCounts = new ConcurrentHashMap<>();

    // Countdown / timer for next drop (seconds)
    private final AtomicInteger nextDropSeconds = new AtomicInteger(0);
    private BukkitTask countdownTask;

    // Hunger enforcer task
    private BukkitTask hungerEnforcerTask;

    // Scoreboard updater task
    private BukkitTask scoreboardTask;
    // Border shrink task for active game world
    private BukkitTask borderShrinkTask;
    // Countdown task for next border shrink (seconds)
    private BukkitTask borderCountdownTask;
    private final AtomicInteger borderNextShrinkSeconds = new AtomicInteger(0);

    // Protected item tracking: itemEntityUUID -> ProtectedEntry
    private final Map<UUID, ProtectedEntry> protectedItems = new ConcurrentHashMap<>();

    // Special sentinel UUID meaning "no player may pick this item up"
    public static final UUID NO_PICKUP = new UUID(0L, 0L);

    // Per-player boosted item: player UUID -> Material
    private final Map<UUID, Material> playerBoosts = new ConcurrentHashMap<>();

    // Wins per player for scoreboard (resets each round)
    private final Map<UUID, Integer> roundWins = new ConcurrentHashMap<>();

    // Persistent totals stored across restarts
    private final Map<UUID, Integer> totalWins = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> totalLosses = new ConcurrentHashMap<>();

    // Track which pregenerated world folder names have been used this session so we avoid reusing them
    private final java.util.Set<String> usedPregenWorlds = ConcurrentHashMap.newKeySet();

    private static class ProtectedEntry {
        final UUID owner;
        final long expiryMs;

        ProtectedEntry(UUID owner, long expiryMs) {
            this.owner = owner;
            this.expiryMs = expiryMs;
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        // key used to tag ItemStack meta with owner UUID so drops can be re-protect
        ownerKey = new NamespacedKey(this, "mc_owner");
        // Ensure data folder exists
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        // Load persistent scores
        loadPersistentScores();
        loadAllowedItems();
        // Load used pregen world list persisted across restarts
        loadUsedPregenWorlds();
        getLogger().info("McManhuntPlus enabled! Loaded " + allowedItems.size() + " allowed items.");

        // Setup lobby
        lobbyManager = new LobbyManager(this);
        Bukkit.getPluginManager().registerEvents(lobbyManager, this);

        // Build lobby after a short delay so the world is fully loaded
        Bukkit.getScheduler().runTaskLater(this, () -> {
            lobbyManager.buildLobby();
            // TP any already-online players to lobby
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                if (lobbyManager.getLobbySpawn() != null) {
                    player.teleport(lobbyManager.getLobbySpawn());
                    player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                }
            }
        }, 20L); // 1 second delay

        randomItemCmd = new RandomItemCommand(this);
        getCommand("randomitem").setExecutor(randomItemCmd);
        getCommand("randomitemtimer").setExecutor(new RandomItemTimerCommand(this, randomItemCmd));
        getCommand("mhdebug").setExecutor(new DebugCommand());
        getCommand("track").setExecutor(new TrackCommand(this));
        getCommand("boostitem").setExecutor(new me.shamgar.commands.BoostItemCommand(this));

        // Start the player tracker task (updates compass + bossbar every second)
        playerTrackerTask = new PlayerTrackerTask(this);
        playerTrackerTask.runTaskTimer(this, 0L, 20L); // every 1 second

        // initialize weights (default 1.0 per allowed item)
        for (Material m : allowedItems) {
            itemWeights.put(m, 1.0);
            boostCounts.put(m, 0);
        }

        // Start a lightweight hunger enforcer which keeps survival players' food full and saturation at 0 always
        // (no longer tied to game running state)
        hungerEnforcerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() == org.bukkit.GameMode.SURVIVAL) {
                    p.setFoodLevel(20);
                    p.setSaturation(0f);
                }
            }
        }, 0L, 20L);

        // Scoreboard updater: show known players and their round wins in the sidebar
        scoreboardTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                ScoreboardManager mgr = Bukkit.getScoreboardManager();
                if (mgr == null) return;
                Scoreboard board = mgr.getNewScoreboard();
                // Title includes next border shrink countdown if active
                int next = borderNextShrinkSeconds.get();
                String title = (next > 0) ? ("§6McManhunt - Next shrink: §e" + next + "s") : "§6McManhunt - Leaderboard";
                Objective obj = board.registerNewObjective("mh_leader", "dummy", Component.text(title));
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);

                // Build a combined set of all known player UUIDs (those with wins OR losses)
                java.util.Set<UUID> allKnown = new java.util.HashSet<>(totalWins.keySet());
                allKnown.addAll(totalLosses.keySet());

                // Build a list of players sorted by total wins (descending)
                List<Map.Entry<UUID, Integer>> entries = new ArrayList<>();
                for (UUID id : allKnown) {
                    entries.add(Map.entry(id, totalWins.getOrDefault(id, 0)));
                }
                entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

                int shown = 0;
                for (Map.Entry<UUID, Integer> e : entries) {
                    if (shown >= 14) break; // keep room for title
                    String name = "unknown";
                    org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(e.getKey());
                    if (off != null && off.getName() != null) name = off.getName();
                    int wins = e.getValue();
                    int losses = totalLosses.getOrDefault(e.getKey(), 0);
                    // Scoreboard lines must be unique strings; include wins for score and losses in the label
                    String line = name + " §7(" + wins + "w/" + losses + "l)";
                    obj.getScore(line).setScore(wins);
                    shown++;
                }

                // Also show online players that may not be in totals at all (show 0)
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    if (!allKnown.contains(p.getUniqueId())) {
                        if (shown >= 14) break;
                        String line = p.getName() + " §7(0w/0l)";
                        obj.getScore(line).setScore(0);
                        shown++;
                    }
                }

                // Apply scoreboard to players
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    p.setScoreboard(board);
                }
            } catch (Throwable t) {
                getLogger().warning("Scoreboard update failed: " + t.getMessage());
            }
        }, 0L, 20L);
    }

    @Override
    public void onDisable() {
        // Save persistent scores on shutdown
        savePersistentScores();
        // Save used pregenerated-world tracking so we don't repeat worlds across restarts
        saveUsedPregenWorlds();
        getLogger().info("McManhuntPlus disabled.");
    }

    private void loadAllowedItems() {
        allowedItems.clear();

        // Only copy bundled allowed_items.txt into plugin data folder if it doesn't already exist
        if (!new File(getDataFolder(), "allowed_items.txt").exists()) {
            saveResource("allowed_items.txt", false);
        }

        File file = new File(getDataFolder(), "allowed_items.txt");
        if (!file.exists()) {
            getLogger().warning("allowed_items.txt not found!");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#")) continue;

                try {
                    Material mat = Material.valueOf(line);
                    allowedItems.add(mat);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("Unknown material in allowed_items.txt: " + line);
                }
            }
        } catch (IOException e) {
            getLogger().severe("Failed to read allowed_items.txt: " + e.getMessage());
        }
    }

    public List<Material> getAllowedItems() {
        return allowedItems;
    }

    public long getItemIntervalTicks() {
        return itemIntervalTicks;
    }

    public void setItemIntervalTicks(long ticks) {
        this.itemIntervalTicks = ticks;
    }

    public LobbyManager getLobbyManager() {
        return lobbyManager;
    }

    public RandomItemCommand getRandomItemCommand() {
        return randomItemCmd;
    }

    public PlayerTrackerTask getPlayerTrackerTask() {
        return playerTrackerTask;
    }

    // Materials that are NOT safe to stand on
    private static final Set<Material> UNSAFE_GROUND = Set.of(
            Material.LAVA, Material.WATER, Material.MAGMA_BLOCK,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.CACTUS, Material.SWEET_BERRY_BUSH,
            Material.POWDER_SNOW, Material.POINTED_DRIPSTONE
    );

    /**
     * Scans the pregen_worlds folder in the server directory, picks a random world,
     * loads it into Bukkit, and returns it. Returns null if no worlds are found.
     */
    public World loadRandomPregenWorld() {
        getLogger().info("World container: " + Bukkit.getWorldContainer().getAbsolutePath());
        getLogger().info("Working directory: " + new File(".").getAbsolutePath());

        // Try world container first, then fall back to working directory (server root)
        File pregenFolder = new File(Bukkit.getWorldContainer(), "pregen_worlds");
        getLogger().info("Checking world container path: " + pregenFolder.getAbsolutePath()
                + " exists=" + pregenFolder.exists());

        if (!pregenFolder.exists() || !pregenFolder.isDirectory()) {
            pregenFolder = new File("pregen_worlds");
            getLogger().info("Checking working dir path: " + pregenFolder.getAbsolutePath()
                    + " exists=" + pregenFolder.exists());
        }

        if (!pregenFolder.exists() || !pregenFolder.isDirectory()) {
            getLogger().warning("pregen_worlds folder not found anywhere!");
            return null;
        }

        // List all subdirectories
        File[] allDirs = pregenFolder.listFiles(File::isDirectory);
        if (allDirs == null || allDirs.length == 0) {
            getLogger().warning("pregen_worlds/ folder is empty (no subdirectories)!");
            // Also log ALL contents for debugging
            File[] allFiles = pregenFolder.listFiles();
            if (allFiles != null) {
                for (File f : allFiles) {
                    getLogger().info("  found: " + f.getName() + " (dir=" + f.isDirectory() + ")");
                }
            }
            return null;
        }

        // Filter to only valid worlds that contain level.dat
        File[] worldFolders = pregenFolder.listFiles(f ->
                f.isDirectory() && new File(f, "level.dat").exists());

        if (worldFolders == null || worldFolders.length == 0) {
            getLogger().warning("No valid worlds found in pregen_worlds/ – each world folder must contain a level.dat file");
            return null;
        }

        // Build a list of candidate folders that we haven't used yet this session
        List<File> candidates = new ArrayList<>();
        for (File f : worldFolders) {
            if (!usedPregenWorlds.contains(f.getName())) {
                candidates.add(f);
            }
        }

        // If we've used them all, clear the used set and allow reuse
        if (candidates.isEmpty()) {
            getLogger().info("All pregenerated worlds have been used this session — resetting used list to allow reuse.");
            usedPregenWorlds.clear();
            // persist clearing so restarts also see the reset
            saveUsedPregenWorlds();
            for (File f : worldFolders) candidates.add(f);
        }

        // Pick a random candidate
        File chosen = candidates.get(new java.util.Random().nextInt(candidates.size()));
        // Mark as used
        usedPregenWorlds.add(chosen.getName());
        // persist the fact we've used this folder so restarts won't reuse it
        saveUsedPregenWorlds();

        String worldPath = "pregen_worlds/" + chosen.getName();

        getLogger().info("Loading pregenerated world: " + worldPath + " (chosen folder: " + chosen.getName() + ")");

        // WorldCreator resolves paths relative to the server's world container
        World world = new WorldCreator(worldPath).createWorld();
        if (world != null) {
            getLogger().info("World loaded successfully: " + world.getName());
        } else {
            getLogger().severe("Failed to load world: " + worldPath);
        }
        return world;
    }

    /**
     * Finds a safe spawn location in the given world.
     * Searches outward in a spiral from the world spawn to find solid ground
     * with 2 air blocks above (no water, lava, etc.).
     */
    public Location findSafeSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        int centerX = spawn.getBlockX();
        int centerZ = spawn.getBlockZ();

        // Search in an expanding square spiral, up to 500 blocks out
        for (int radius = 0; radius <= 500; radius += 4) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    // Only check the edges of each ring (skip inner already-checked area)
                    if (radius > 0 && Math.abs(dx) < radius && Math.abs(dz) < radius) continue;

                    int x = centerX + dx;
                    int z = centerZ + dz;

                    Location safe = checkColumnSafe(world, x, z);
                    if (safe != null) {
                        getLogger().info("Found safe spawn at " + safe.getBlockX() + ", "
                                + safe.getBlockY() + ", " + safe.getBlockZ());
                        return safe;
                    }
                }
            }
        }

        // Fallback: just use the highest block at spawn and place them on top
        getLogger().warning("No ideal safe spawn found, using highest block at world spawn.");
        int highestY = world.getHighestBlockYAt(centerX, centerZ);
        return new Location(world, centerX + 0.5, highestY + 1, centerZ + 0.5);
    }

    public Location checkColumnSafe(World world, int x, int z) {
        int highestY = world.getHighestBlockYAt(x, z);
        if (highestY < 1) return null;

        org.bukkit.block.Block ground = world.getBlockAt(x, highestY, z);
        Material groundType = ground.getType();

        // Ground must be solid and not dangerous
        if (!groundType.isSolid()) return null;
        if (UNSAFE_GROUND.contains(groundType)) return null;

        // The two blocks above must be air (room for the player)
        org.bukkit.block.Block feet = world.getBlockAt(x, highestY + 1, z);
        org.bukkit.block.Block head = world.getBlockAt(x, highestY + 2, z);
        if (!feet.getType().isAir()) return null;
        if (!head.getType().isAir()) return null;

        // +0.5 to center on the block
        return new Location(world, x + 0.5, highestY + 1, z + 0.5);
    }

    /**
     * Protects a dropped item entity so only `owner` can pick it up.
     * Protection lasts for durationMs milliseconds (if durationMs <= 0 then protection is permanent).
     */
    public void protectDroppedItem(org.bukkit.entity.Item itemEntity, UUID owner, long durationMs) {
        if (itemEntity == null) return;
        UUID id = itemEntity.getUniqueId();
        long expiry;
        if (durationMs <= 0L) {
            expiry = Long.MAX_VALUE;
        } else {
            expiry = System.currentTimeMillis() + durationMs;
        }
        protectedItems.put(id, new ProtectedEntry(owner, expiry));
        // We intentionally do NOT schedule automatic cleanup — protection remains until owner picks it up or item is removed
    }

    /**
     * Removes protection for a dropped item (e.g., when owner picks it up)
     */
    public void removeProtection(UUID itemEntityId) {
        if (itemEntityId == null) return;
        protectedItems.remove(itemEntityId);
    }

    /**
     * Returns the owner UUID if the item is currently protected, or null otherwise.
     * Cleans up expired entries.
     */
    public UUID getProtectedOwner(UUID itemEntityId) {
        ProtectedEntry e = protectedItems.get(itemEntityId);
        if (e == null) return null;
        if (e.expiryMs != Long.MAX_VALUE && System.currentTimeMillis() > e.expiryMs) {
            protectedItems.remove(itemEntityId);
            return null;
        }
        return e.owner;
    }

    /**
     * Stops the current game and sends everyone back to lobby.
     */
    public void stopGame() {
        if (randomItemCmd != null && randomItemCmd.isRunning()) {
            randomItemCmd.forceStop();
        }
        if (playerTrackerTask != null) {
            playerTrackerTask.stopAll();
        }
        // Stop any ongoing border shrink
        stopBorderShrink();

        // Clear dropped items in the game world (if set)
        if (lobbyManager != null && lobbyManager.getGameSpawn() != null) {
            World gw = lobbyManager.getGameSpawn().getWorld();
            if (gw != null) {
                int removed = 0;
                for (org.bukkit.entity.Entity ent : gw.getEntities()) {
                    if (ent instanceof org.bukkit.entity.Item) {
                        UUID id = ent.getUniqueId();
                        ent.remove();
                        protectedItems.remove(id);
                        removed++;
                    }
                }
                getLogger().info("Cleared " + removed + " dropped items from game world.");
                String _clearMsg = "§eCleared §b" + removed + " §edropped items from the game world.";
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(_clearMsg));

                // Reset that world's world border to a very large size and center on world spawn
                try {
                    WorldBorder wb = gw.getWorldBorder();
                    Location spawn = gw.getSpawnLocation();
                    wb.setCenter(spawn.getX(), spawn.getZ());
                    double safeMax = 59999967.0;
                    try {
                        wb.setSize(Math.min(safeMax, wb.getSize()));
                    } catch (Throwable t) {
                        wb.setSize(safeMax);
                    }
                    getLogger().info("Reset world border for world " + gw.getName() + " to large default.");
                } catch (Throwable ignored) {
                }
            }
        }
        // Reset per-round wins
        roundWins.clear();
        // Clear boosts
        // Revert any active boosts so itemWeights are restored
        for (UUID pid : new ArrayList<>(playerBoosts.keySet())) {
            clearPlayerBoost(pid);
        }
        // Reset advancements for all players so each round starts fresh
        resetAllAdvancements();
        lobbyManager.sendAllToLobby();
    }

    /**
     * Starts a scheduled border shrink on the provided world: shrink 100 blocks every minute
     * until the border reaches 500 (hard stop).
     */
    public void startBorderShrink(org.bukkit.World world, double centerX, double centerZ) {
        if (world == null) return;
        // Cancel existing
        stopBorderShrink();
        // Center border (size should already be set by caller)
        WorldBorder border = world.getWorldBorder();
        border.setCenter(centerX, centerZ);

        // initialize countdown to the first shrink (60 seconds)
        borderNextShrinkSeconds.set(60);
        // start a short-lived countdown task that decrements every second
        borderCountdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int s = borderNextShrinkSeconds.get();
            if (s <= 0) return;
            // When there are 10 seconds remaining, show a bright red title warning to all players
            if (s == 10) {
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        p.showTitle(Title.title(Component.text("§c§l!!! BORDER SHRINK !!!"), Component.text("§c§l10 seconds"), Title.Times.times(ticksToDuration(5), ticksToDuration(40), ticksToDuration(5))));
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.8f);
                    } catch (Throwable ignored) {}
                }
            }
            // During the final 10 seconds, keep an actionbar countdown updated
            if (s <= 10) {
                String action = "§c§lBorder shrinking in: §f" + s + "s";
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                    try {
                        p.sendActionBar(Component.text(action));
                    } catch (Throwable ignored) {}
                }
            }
            borderNextShrinkSeconds.set(s - 1);
        }, 20L, 20L);

        borderShrinkTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            // operate regardless of world load state; world null checked earlier
             WorldBorder wb = world.getWorldBorder();
             double size = wb.getSize();
             if (size <= 500.0) {
                // Already at or below minimum — cancel
                try { wb.setSize(500.0); } catch (Throwable ignored) {}
                if (borderShrinkTask != null) {
                    borderShrinkTask.cancel();
                    borderShrinkTask = null;
                }
                String _msg = "§aWorld border has reached minimum size: §f500x500§a.";
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(_msg));
                // stop countdown too
                if (borderCountdownTask != null) {
                    borderCountdownTask.cancel();
                    borderCountdownTask = null;
                }
                return;
            }
            double newSize = Math.max(500.0, size - 100.0);
            try { wb.setSize(newSize); } catch (Throwable ignored) {}
            String _msg2 = "§eWorld border is shrinking! New size: §b" + (int)newSize + "x" + (int)newSize + "§e.";
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(_msg2));
            // reset countdown for next shrink
            borderNextShrinkSeconds.set(60);
            if (newSize <= 500.0) {
                // final stop
                if (borderShrinkTask != null) {
                    borderShrinkTask.cancel();
                    borderShrinkTask = null;
                }
                String _msg3 = "§aWorld border shrink stopped at minimum size: §f500x500§a.";
                for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.sendMessage(Component.text(_msg3));
                if (borderCountdownTask != null) {
                    borderCountdownTask.cancel();
                    borderCountdownTask = null;
                }
            }
         }, 20L * 60L, 20L * 60L); // first run after 60s, repeat every 60s
     }

     public void stopBorderShrink() {
         if (borderShrinkTask != null) {
             borderShrinkTask.cancel();
             borderShrinkTask = null;
         }
        if (borderCountdownTask != null) {
            borderCountdownTask.cancel();
            borderCountdownTask = null;
        }
        borderNextShrinkSeconds.set(0);
     }

    /**
     * Tag an ItemStack with the owner's UUID so when it is dropped it can be protected.
     */
    public void tagItemStackOwner(org.bukkit.inventory.ItemStack stack, UUID owner) {
        if (stack == null || owner == null) return;
        if (!stack.hasItemMeta()) stack.setItemMeta(stack.getItemMeta());
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        stack.setItemMeta(meta);
    }

    public NamespacedKey getOwnerKey() {
        return ownerKey;
    }

    public Map<Material, Double> getItemWeights() {
        return itemWeights;
    }

    /** Multiply an item's weight by 1.05 (boost by 5%) and return new weight. */
    public double boostItem(Material mat) {
        if (mat == null) return 0.0;
        // increment boost count for this material and set weight = 1.0 * (1.05^count)
        int count = boostCounts.getOrDefault(mat, 0) + 1;
        boostCounts.put(mat, count);
        double nw = Math.pow(1.05, count);
        itemWeights.put(mat, nw);
        return nw;
    }

    // New API: per-player boosts
    public Material getPlayerBoost(UUID player) {
        return playerBoosts.get(player);
    }

    public void setPlayerBoost(UUID player, Material mat) {
        if (player == null) return;
        if (mat == null) {
            playerBoosts.remove(player);
            return;
        }
        playerBoosts.put(player, mat);
    }

    public void clearPlayerBoost(UUID player) {
        if (player == null) return;
        Material prev = playerBoosts.remove(player);
        if (prev == null) return;
        // decrement boost count and recompute weight
        int count = boostCounts.getOrDefault(prev, 0) - 1;
        if (count < 0) count = 0;
        boostCounts.put(prev, count);
        double nw = Math.pow(1.05, count);
        itemWeights.put(prev, nw);
    }

    // Win tracking for scoreboard
    public void addWin(UUID player) {
        if (player == null) return;
        roundWins.put(player, roundWins.getOrDefault(player, 0) + 1);
        // update persistent totals
        totalWins.put(player, totalWins.getOrDefault(player, 0) + 1);
        savePersistentScores();
    }

    public void addLoss(UUID player) {
        if (player == null) return;
        totalLosses.put(player, totalLosses.getOrDefault(player, 0) + 1);
        savePersistentScores();
    }

    public int getTotalWins(UUID player) {
        return totalWins.getOrDefault(player, 0);
    }

    public int getTotalLosses(UUID player) {
        return totalLosses.getOrDefault(player, 0);
    }

    public int getWins(UUID player) {
        return roundWins.getOrDefault(player, 0);
    }

    public Map<UUID, Integer> getRoundWinsMap() {
        return roundWins;
    }

    public void resetRoundStats() {
        roundWins.clear();
        // Revert active boosts before clearing
        for (UUID pid : new ArrayList<>(playerBoosts.keySet())) {
            clearPlayerBoost(pid);
        }
    }

    // Countdown control for actionbar timer
    public void startCountdown(int seconds) {
        nextDropSeconds.set(seconds);
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int s = nextDropSeconds.getAndDecrement();
            if (s < 0) {
                nextDropSeconds.set(0);
                return;
            }
            String msg = "§eNext item in: §f" + s + "s";
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(Component.text(msg));
            }
        }, 0L, 20L);
    }

    public void resetCountdown() {
        nextDropSeconds.set((int)(itemIntervalTicks / 20L));
    }

    public void stopCountdown() {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
        nextDropSeconds.set(0);
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.sendActionBar(Component.text(""));
        }
    }

    public void stopScoreboardTask() {
        if (scoreboardTask != null) scoreboardTask.cancel();
        scoreboardTask = null;
        // Reset player scoreboards to main server board
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /**
     * Load persistent scores from plugin data folder (scores.txt).
     * Format: uuid|wins|losses per line
     */
    private void loadPersistentScores() {
        File f = new File(getDataFolder(), "scores.txt");
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|", -1);
                if (parts.length < 3) continue;
                try {
                    UUID id = UUID.fromString(parts[0]);
                    int w = Integer.parseInt(parts[1]);
                    int l = Integer.parseInt(parts[2]);
                    if (w > 0) totalWins.put(id, w);
                    if (l > 0) totalLosses.put(id, l);
                } catch (Exception e) {
                    getLogger().warning("Invalid line in scores.txt: " + line);
                }
            }
        } catch (IOException e) {
            getLogger().severe("Failed to read scores.txt: " + e.getMessage());
        }
    }

    /**
     * Save persistent scores to plugin data folder (scores.txt).
     */
    private synchronized void savePersistentScores() {
        File f = new File(getDataFolder(), "scores.txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f))) {
            // header
            bw.write("# UUID|wins|losses"); bw.newLine();
            for (UUID id : totalWins.keySet()) {
                int w = totalWins.getOrDefault(id, 0);
                int l = totalLosses.getOrDefault(id, 0);
                bw.write(id.toString() + "|" + w + "|" + l);
                bw.newLine();
            }
            // ensure we also write any players that have losses but no wins
            for (UUID id : totalLosses.keySet()) {
                if (totalWins.containsKey(id)) continue;
                int w = totalWins.getOrDefault(id, 0);
                int l = totalLosses.getOrDefault(id, 0);
                bw.write(id.toString() + "|" + w + "|" + l);
                bw.newLine();
            }
        } catch (IOException e) {
            getLogger().severe("Failed to write scores.txt: " + e.getMessage());
        }
    }

    /**
     * Load the list of used pregenerated world folder names from disk (one per line).
     * File location: <plugin data folder>/used_pregen.txt
     */
    private void loadUsedPregenWorlds() {
        File f = new File(getDataFolder(), "used_pregen.txt");
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                usedPregenWorlds.add(line);
            }
            getLogger().info("Loaded " + usedPregenWorlds.size() + " used pregenerated world(s) from disk.");
        } catch (IOException e) {
            getLogger().warning("Failed to read used_pregen.txt: " + e.getMessage());
        }
    }

    /**
     * Persist the current used pregenerated world folder names to disk.
     */
    private synchronized void saveUsedPregenWorlds() {
        File f = new File(getDataFolder(), "used_pregen.txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f))) {
            bw.write("# used pregenerated world folder names - one per line"); bw.newLine();
            for (String s : usedPregenWorlds) {
                bw.write(s);
                bw.newLine();
            }
        } catch (IOException e) {
            getLogger().severe("Failed to write used_pregen.txt: " + e.getMessage());
        }
    }

    /**
     * Revoke all advancements for every online player so each round starts fresh.
     */
    public void resetAllAdvancements() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            java.util.Iterator<org.bukkit.advancement.Advancement> it = Bukkit.advancementIterator();
            while (it.hasNext()) {
                org.bukkit.advancement.Advancement adv = it.next();
                org.bukkit.advancement.AdvancementProgress progress = p.getAdvancementProgress(adv);
                for (String criteria : progress.getAwardedCriteria()) {
                    progress.revokeCriteria(criteria);
                }
            }
        }
        getLogger().info("Reset all advancements for all online players.");
    }

    private static Duration ticksToDuration(long ticks) {
        return Duration.ofMillis(ticks * 50L);
    }
}
