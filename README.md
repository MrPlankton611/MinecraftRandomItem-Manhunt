# 🎯 McManhunt+ — Minecraft Random Item Manhunt Plugin

A Paper/Spigot plugin for Minecraft 1.21+ that runs a **Random Item Manhunt** game mode. Players are teleported to a random pregenerated world and receive random items on a timer — last player standing wins!

---

## ✨ Features

### 🎲 Random Item Drops
- Every configurable interval (default: 60 seconds), each surviving player receives a **random item** from a curated list of survival-friendly items.
- Players get **1 stack in their inventory + 7 stacks dropped** around them.
- Dropped items are **owner-protected** — other players can never pick up your items.
- Items are selected using a **weighted random system** that players can influence with boosts.

### 🗺️ Pregenerated Worlds
- The plugin randomly selects a world from a `pregen_worlds/` folder in your server directory.
- Each world is only used **once** before all worlds are cycled through (persists across restarts).
- Players spawn on a **safe surface** (no suffocation, no lava, no water).
- Each player spawns at a **random offset** within 10 blocks of the spawn point — no two players on the same block.

### 🏟️ Lobby System
- A beautiful **circular lobby platform** is auto-built at the overworld spawn with:
  - Gradient floor (quartz → concrete → cyan terracotta)
  - Glass border walls
  - Central beacon with beam
  - Corner pillars with sea lanterns
  - Soul lantern ring
  - Info sign
- Players are kept in the lobby (Adventure mode) until a game starts.
- **PvP is disabled** in the lobby.

### 🌍 World Border
- Game worlds start with a **1500×1500 border** centered on spawn.
- Border **shrinks by 100 blocks every minute**, hard-stopping at **500×500**.
- **10-second warning** with a bright red title and sound before each shrink.
- Live countdown displayed on the actionbar during the final 10 seconds.
- Border resets after every round.

### 💀 Death & Spectating
- When a player dies, they become a **spectator** in the game world so they can watch the remaining players.
- Death drops are **protected** — other players cannot pick up a dead player's items.
- When only **1 player remains**, they win! The game announces the winner and returns everyone to the lobby.
- **Block drops are disabled** during the game to keep focus on random items.

### 🏆 Persistent Leaderboard
- Wins and losses are tracked in `scores.txt` inside the plugin data folder.
- A **sidebar scoreboard** displays all known players with their win/loss record.
- Stats **persist across server restarts**.

### 🧭 Player Tracker
- Use `/track <player>` to get a **tracking compass** that points to another player.
- A **boss bar** shows the tracked player's name, distance, and color-coded proximity:
  - 🔴 Red: < 50 blocks
  - 🟡 Yellow: < 150 blocks
  - 🔵 Blue: > 150 blocks

### ⚡ Item Boosting
- Players can use `/boostitem <MATERIAL>` to **boost an item's drop probability by 5%**.
- Each player can only have **one active boost** at a time.
- Switching boosts removes the previous one automatically.
- Boosting the same item twice gives an error.

### 🍖 Hunger System
- Players always have **full hunger** (20 food) but **zero saturation**.
- This means health regeneration is **slow but permanent** — no fast healing from food.

### 🛡️ Grace Period
- A **10-second PvP grace period** at the start of each game prevents immediate fighting after teleport.

---

## 📋 Commands

| Command | Description |
|---|---|
| `/randomitem` | Start a new random item game (loads a random world, teleports players, starts item drops) |
| `/randomitem stop` | Stop the current game and return everyone to the lobby |
| `/randomitemtimer <seconds>` | Change the interval between random item drops |
| `/boostitem <MATERIAL>` | Boost an item's drop probability by 5% (one boost per player) |
| `/track <player>` | Track a player with compass + boss bar |
| `/track stop` | Stop tracking |
| `/mhdebug` | Show debug info (world, coords, gamemode, world border) |

---

## 📁 Project Structure

```
src/main/
├── java/me/shamgar/
│   ├── McManhunt.java              # Main plugin class — item loading, world management, scoring, border shrink
│   ├── LobbyManager.java           # Lobby building, event handling (death, PvP, drops, pickups)
│   ├── commands/
│   │   ├── RandomItemCommand.java       # /randomitem — starts/stops the game
│   │   ├── RandomItemTimerCommand.java  # /randomitemtimer — adjusts drop interval
│   │   ├── BoostItemCommand.java        # /boostitem — per-player item probability boost
│   │   ├── TrackCommand.java            # /track — player tracking with compass + bossbar
│   │   ├── DebugCommand.java            # /mhdebug — debug info
│   │   └── ManhuntCommand.java          # /manhunt — placeholder
│   └── tasks/
│       ├── RandomItemTask.java          # Scheduled task that gives random items to all alive players
│       └── PlayerTrackerTask.java       # Scheduled task that updates compass + bossbar every second
└── resources/
    ├── plugin.yml                   # Plugin metadata & command registration
    └── allowed_items.txt            # Curated list of survival-friendly items (editable!)
```

---

## ⚙️ Setup

### Prerequisites
- **Paper** or **Spigot** server running Minecraft **1.21+**
- **Java 21+**
- **Maven** (for building)

### Installation

1. **Clone the repository:**
   ```bash
   git clone https://github.com/MrPlankton611/MinecraftRandomItem-Manhunt.git
   cd MinecraftRandomItem-Manhunt
   ```

2. **Build the plugin:**
   ```bash
   mvn clean package
   ```

3. **Copy the JAR** from `target/mcmanhuntplus-1.0-SNAPSHOT.jar` to your server's `plugins/` folder.

4. **Set up pregenerated worlds:**
   - Create a `pregen_worlds/` folder in your server root directory.
   - Add pregenerated world folders inside it (each must contain a `level.dat`).
   - You can use tools like [Chunky](https://github.com/pop4959/Chunky) to pregenerate worlds.

5. **Start your server** and the plugin will:
   - Build the lobby automatically
   - Copy `allowed_items.txt` to the plugin data folder (editable from there)

### Customizing Items

Edit `plugins/McManhuntPlus/allowed_items.txt` on your server to add or remove items. The file uses Bukkit `Material` names, one per line. Lines starting with `#` are comments.

---

## 🔧 Building

```bash
mvn clean package
```

The compiled JAR will be at `target/mcmanhuntplus-1.0-SNAPSHOT.jar`.

---

## 📝 License

This project is open source. Feel free to modify and use it for your server!

