# 🗡️ SkyWarsTrainer

> **Train against intelligent SkyWars bots that actually feel human.**
> Powered by Citizens2 NPCs, Behavior Trees, Utility AI, Reinforcement Learning, and optional LLM-driven coaching.

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-1.8.8-brightgreen?style=for-the-badge&logo=minecraft" alt="Minecraft Version">
  <img src="https://img.shields.io/badge/Java-8%2B-orange?style=for-the-badge&logo=openjdk" alt="Java 8">
  <img src="https://img.shields.io/badge/Spigot-Compatible-yellow?style=for-the-badge" alt="Spigot">
  <img src="https://img.shields.io/badge/Citizens2-Required-blue?style=for-the-badge" alt="Citizens2 Required">
  <img src="https://img.shields.io/badge/LostSkyWars-Required-red?style=for-the-badge" alt="LostSkyWars Required">
  <img src="https://img.shields.io/badge/Version-1.0.0-success?style=for-the-badge" alt="Version">
</p>

---

## 🎯 What is SkyWarsTrainer?

**SkyWarsTrainer** is a state-of-the-art Spigot/Bukkit plugin that brings genuinely intelligent practice bots to your SkyWars server. Forget walk-toward-player NPCs that punch the air — these bots **loot chests, enchant gear, build bridges (including God Bridge, Ninja Bridge, Speed Bridge, Diagonal Bridge, Moonwalk), pearl across gaps, throw fishing-rod combos, dodge projectiles, cut your bridges, MLG with water buckets, and clutch with golden apples.**

Built around the philosophy that *practice partners should be unpredictable, not flawless*, every bot has a **difficulty profile**, **1–3 stacking personalities**, and a **Utility AI engine** that weighs over a dozen real-time considerations every tick to decide what to do next. Combined with optional reinforcement-learning memory and LLM-powered tactical advice, it's the closest thing to playing against a real human opponent — without the queue times.

> *"Stop waiting for queues. Train the way pros train: against bots that fight back."*

---

## ✨ Why SkyWarsTrainer?

### 🧠 Real AI, Not Scripted Loops
Every bot decision flows through a **Utility AI Decision Engine** with **13+ considerations** running in parallel — threat assessment, threat *prediction*, health, resources, equipment gap, loot value, zone control, positional advantage, projectile opportunity, time pressure, player count, game phase, strategy alignment, and counter-play awareness. No two fights play out the same way.

### 🎭 12 Distinct Personalities, Mixable
Stack up to **3 personalities** on a single bot for emergent behavior. An `AGGRESSIVE + STRATEGIC + CLUTCH_MASTER` bot is a calculated terror; a `PASSIVE + COLLECTOR + CAUTIOUS` bot is the loot goblin who hides until endgame and water-MLGs out of every corner.

### 🌉 6 Real Bridging Techniques
Bots can perform **NormalBridge**, **SpeedBridge**, **GodBridge**, **NinjaBridge**, **MoonwalkBridge**, and **DiagonalBridge** — selected dynamically based on personality, difficulty, distance, and threat level.

### 📈 Reinforcement Learning Memory
Bots **learn from experience**. The plugin includes a full RL stack — `ReplayBuffer`, `EligibilityTraceTable`, `RewardCalculator`, `WeightAdjuster`, and `MemoryBank` — so weights drift toward what actually wins games on your server.

### 🤖 Optional LLM Tactical Advisor
Plug in an API key and let an LLM provide periodic strategic advice that the bot validates and acts on through `LLMAdviceValidator`. Disabled by default — fully optional, fully gated.

### 🛡️ Hard Dependency on Real SkyWars
Built **specifically** to integrate with the **LostSkyWars** game plugin. Bots respect game phases, cage spawns, refill events, deathmatch transitions, and proper game state — not generic PvP logic.

---

## 🏗️ Architecture Overview

SkyWarsTrainer is a layered AI system. Each layer can be inspected, tuned, or extended independently.

```
┌─────────────────────────────────────────────────────────────┐
│              TrainerBot  (Citizens2 Trait, ticked)          │
└─────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│   Decision Engine    │  │   Behavior Tree      │  │   State Machine      │
│   (Utility AI)       │  │   (Selectors,        │  │   (BotState +        │
│   + 13 Considerations│  │   Sequences,         │  │   Transitions)       │
│                      │  │   Decorators)        │  │                      │
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
            │                      │                          │
            ▼                      ▼                          ▼
┌─────────────────────────────────────────────────────────────┐
│ Awareness Layer:  ChestLocator · ThreatMap · ThreatPredictor │
│                   IslandGraph · MapScanner · LavaDetector    │
│                   VoidDetector · FallDamageEstimator         │
│                   GamePhaseTracker · SurvivalGuard           │
└─────────────────────────────────────────────────────────────┘
            │
            ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│   Combat         │ │   Bridging       │ │   Strategy       │
│   AimController  │ │   6 Strategies   │ │   StrategyPlanner│
│   ClickController│ │   PathPlanner    │ │   StrategicPhase │
│   ComboTracker   │ │   MovementCtrl   │ │                  │
│   Knockback      │ │                  │ │                  │
│   Projectiles    │ │                  │ │                  │
│   Counter / Def. │ │                  │ │                  │
└──────────────────┘ └──────────────────┘ └──────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────┐
│ Learning Layer:   ReplayBuffer · EligibilityTraceTable       │
│                   RewardCalculator · WeightAdjuster          │
│                   MemoryBank · MemoryPruner · StateEncoder   │
└─────────────────────────────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────────────────────────────┐
│ Optional LLM Layer:  LLMClient · LLMPromptBuilder            │
│                      LLMResponseParser · LLMAdviceValidator  │
│                      LLMKeyManager · LLMConfig               │
└─────────────────────────────────────────────────────────────┘
```

---

## 🎮 Core Features

### 🧮 Utility AI with 13 Real-Time Considerations

Each decision tick evaluates and scores actions using these **considerations**, found in `ai/decision/considerations/`:

| Consideration | What It Models |
|---|---|
| **HealthConsideration** | Bot's current HP relative to flee thresholds |
| **ThreatConsideration** | Immediate enemies in range, gear, distance |
| **ThreatPredictionConsideration** | Where enemies *will* be in 1–5 seconds |
| **ResourceConsideration** | Blocks, food, projectiles, pearls remaining |
| **EquipmentGapConsideration** | Gear delta vs. nearby enemies |
| **LootValueConsideration** | Expected value of nearby unopened chests |
| **ZoneControlConsideration** | Who controls mid / chokepoints right now |
| **PositionalConsideration** | Height advantage, edge proximity, cover |
| **ProjectileOpportunityConsideration** | Clear line of sight for bow/rod plays |
| **TimePressureConsideration** | Refill timers, deathmatch countdown |
| **PlayerCountConsideration** | Alive count drives endgame behavior |
| **GamePhaseConsideration** | EARLY · MID · LATE · DEATHMATCH |
| **StrategyAlignmentConsideration** | Does the action match the active StrategyPlan? |
| **CounterPlayConsideration** | Hard-counters to current enemy behavior |

These feed into a `UtilityScorer` that picks the highest-utility action via `DecisionEngine` and `DecisionContext`.

### 🌳 Full Behavior Tree Engine

A complete behavior-tree implementation (`ai/engine/`) including:

- **Composite nodes**: `SelectorNode`, `SequenceNode`, `ParallelNode`, `RandomSelectorNode`
- **Action / Condition nodes**: `ActionNode`, `ConditionNode`
- **Decorators**: `InverterDecorator`, `RepeaterDecorator`, `CooldownDecorator`, `TimeoutDecorator`, `ChanceDecorator`, `DifficultyGateDecorator`
- **Status enum**: `NodeStatus` (SUCCESS · FAILURE · RUNNING)

`DifficultyGateDecorator` is the secret sauce — it lets you author one tree where advanced techniques are simply *gated* behind difficulty thresholds, so a `BEGINNER` bot literally cannot perform a Ninja Bridge.

### 🎭 Personality System (12 Personalities)

Defined in `ai/personality/Personality.java`. Mix up to 3 (subject to `PersonalityConflictTable`).

| Personality | Vibe |
|---|---|
| 🩸 **AGGRESSIVE** | This bot wants blood. Rushes enemies, fights under-geared, chases relentlessly. |
| 🛡️ **PASSIVE** | Avoids fights. Loots fully, enchants, fights only when cornered or in endgame. |
| 💨 **RUSHER** | Speed demon. Rushes mid immediately, skips own island chests entirely. |
| 🏰 **CAMPER** | Fortifies a position, watches bridges, waits for enemies to approach. |
| 🧠 **STRATEGIC** | Big-brain player. Optimal decisions, reads enemy gear, uses environment. |
| 💎 **COLLECTOR** | Loot goblin. Systematically loots every chest. Fights only when fully geared. |
| 🔥 **BERSERKER** | All-in warrior. Never retreats, burns golden apples, charges into everything. |
| 🏹 **SNIPER** | Ranged specialist. Bow-spam, rod-combo, keeps distance. |
| 🎩 **TRICKSTER** | Dirty-tricks specialist. Pearl plays, traps, bridge breaking, fake retreats. |
| 🐢 **CAUTIOUS** | Careful player. Checks surroundings, crouches near edges, never rushes. |
| 🎯 **CLUTCH_MASTER** | Water MLG, block clutching, pearl saves, stays calm under pressure. |
| 🤝 **TEAMWORK** | Team player. Shares loot, bodyblocks, focuses same target as teammates. |

Personalities apply **multiplicative modifiers** to utility weights and skill parameters, so combinations stack predictably.

### 🌉 Bridging — Six Real Techniques

Found in `bridging/strategies/`. A `BridgeStrategy` is selected by `BridgePathPlanner` + `BridgeMovementSelector` based on personality, skill, distance, and threat.

- **NormalBridge** — Reliable, the baseline.
- **SpeedBridge** — Faster placement at the cost of safety.
- **GodBridge** — High-skill diagonal block placement (gated by difficulty).
- **NinjaBridge** — Crouch-cancelled placement for stealth.
- **MoonwalkBridge** — Walking backward while placing — used to keep the enemy in sight.
- **DiagonalBridge** — Reaches mid faster, harder to track.

All movement flows through `BridgeMovementController` → `BridgeMovementDirective` for clean separation between *what to build* and *how to move while building it*.

### ⚔️ Combat System

A complete combat stack lives under `combat/`:

- **`CombatEngine`** — main loop, target selection, swing timing
- **`AimController`** — humanized look (with reaction-time delays + jitter at lower skill)
- **`ClickController`** — CPS modulation (calm vs. panic)
- **`ComboTracker`** — w-tap, s-tap, combo extension logic
- **`KnockbackCalculator`** — NMS-level knockback prediction (1.8.8 R3)
- **`ProjectileHandler`** + **`RangedCombatHandler`** — bow charging, rod-combo, pearl plays
- **`combat/counter/`** — `EnemyBehaviorAnalyzer`, `EnemyProfile`, `CounterStrategySelector`, `CounterModifiers` (the bots literally profile *you* and adapt)
- **`combat/defense/`** — `BlockBarricade`, `BridgeCutter`, `BridgeTrap` (yes, they will cut your bridge from under you)

### 🗺️ Awareness Layer

Bots don't just react to what they see — they reason about the map. Modules under `awareness/`:

- **`ChestLocator`** — Tracks unopened/opened chests across the map
- **`IslandGraph`** — Topological model of all islands and connections
- **`MapScanner`** — Builds a navmesh of safe / unsafe terrain
- **`ThreatMap`** + **`ThreatPredictor`** — Spatial heatmap of danger; predicts enemy paths
- **`GamePhaseTracker`** — EARLY → MID → LATE → DEATHMATCH transitions
- **`LavaDetector`**, **`VoidDetector`** — Hazard awareness
- **`FallDamageEstimator`** — Computes whether a jump is survivable
- **`SurvivalGuard`** — Last-line-of-defense system that overrides bad decisions before death

### 🧬 Reinforcement Learning

Optional but powerful — `ai/learning/`:

- **`ReplayBuffer`** — Stores `ReplayEntry` snapshots of (state, action, reward, next-state)
- **`EligibilityTraceTable`** — Credit assignment over multi-step decisions
- **`ExperienceRecorder`** + **`ExperienceEntry`** — Captures every meaningful outcome
- **`RewardCalculator`** — Multi-objective shaping (kills, survival, loot, position, time)
- **`WeightAdjuster`** — Updates utility-action weights based on long-run reward
- **`MemoryBank`** + **`MemoryPruner`** — Long-term memory with intelligent pruning
- **`LearningSerializer`** — Persistent JSON storage so bots remember across restarts
- **`StateEncoder`** — Compresses world state into a learnable representation

### 🤖 Optional LLM Coach

`ai/llm/` provides a complete, sandboxed LLM integration:

- **`LLMClient`** — Async HTTP client with rate limiting and shutdown safety
- **`LLMPromptBuilder`** — Builds compact, tactical prompts from current bot state
- **`LLMResponseParser`** — Strict JSON output parsing
- **`LLMAdviceValidator`** — Validates that LLM-suggested actions are safe & legal in the current state — refusing nonsense
- **`LLMKeyManager`** — Secure, rotatable key storage
- **`LLMConfig`** — Fully-disabled by default; opt-in only

### 🌐 Public Developer API

`api/SkyWarsTrainerAPI` + `api/BotBuilder` give other plugins clean access. **Eight Bukkit events** are fired:

- `BotSpawnEvent`
- `BotDespawnEvent`
- `BotDeathEvent`
- `BotKillPlayerEvent`
- `BotCombatEvent`
- `BotLootEvent`
- `BotBridgeEvent`
- `BotDecisionEvent`
- `BotStateChangeEvent`

Build your own minigames, leaderboards, or analytics on top.

---

## 📦 Installation

### Requirements

| Requirement | Version | Required? |
|---|---|---|
| **Minecraft Server** | Spigot / Paper 1.8.8 (NMS `v1_8_R3`) | ✅ Hard |
| **Java** | 8 or higher | ✅ Hard |
| **[Citizens2](https://www.spigotmc.org/resources/citizens.13811/)** | 2.0.30-SNAPSHOT or compatible | ✅ Hard |
| **LostSkyWars** | 2.5.00+ | ✅ Hard |

### Steps

1. Install **Citizens2** and **LostSkyWars** on your 1.8.8 server.
2. Drop `SkyWarsTrainer-1.0.0.jar` into your `plugins/` folder.
3. Start the server. Configs are auto-generated in `plugins/SkyWarsTrainer/`.
4. Configure `config.yml`, `difficulty.yml`, and `personality.yml` to taste.
5. Run `/swt help` in-game to see all commands.

### Building from Source

```bash
# Install BuildTools artifact for 1.8.8 first:
java -jar BuildTools.jar --rev 1.8.8

# Then build the plugin:
git clone https://github.com/TwightLightDev/SkyWarsTrainer.git
cd SkyWarsTrainer
mvn clean package
# Output: target/SkyWarsTrainer-1.0.0.jar
```

---

## 🎛️ Commands

Base command: `/swt` (aliases: `/skywarstrainer`, `/trainer`)

| Command | Description | Permission |
|---|---|---|
| `/swt help` | Show all commands | `skywarstrainer.spawn` |
| `/swt spawn <difficulty> [personalities] [name]` | Spawn a bot | `skywarstrainer.spawn` |
| `/swt remove <name\|all>` | Remove a bot or all bots | `skywarstrainer.remove` |
| `/swt list` | List all active bots | `skywarstrainer.list` |
| `/swt difficulty <bot> <level>` | Change a bot's difficulty | `skywarstrainer.modify` |
| `/swt personality <bot> <personalities>` | Change a bot's personality | `skywarstrainer.modify` |
| `/swt stats <bot>` | Show bot statistics | `skywarstrainer.stats` |
| `/swt fill <count> [difficulty]` | Fill a SkyWars game with bots | `skywarstrainer.fill` |
| `/swt pause <bot>` | Pause / resume a bot's AI | `skywarstrainer.control` |
| `/swt teleport <bot>` | Teleport to a bot (alias `/swt tp`) | `skywarstrainer.teleport` |
| `/swt debug` | Toggle debug mode | `skywarstrainer.debug` |
| `/swt debugtoggle` | Per-bot debug toggle | `skywarstrainer.debug` |
| `/swt learning` | Inspect / control RL state | `skywarstrainer.debug` |
| `/swt test` | Internal test command | `skywarstrainer.debug` |
| `/swt reload` | Reload all configs | `skywarstrainer.modify` |

---

## 🔑 Permissions

```yaml
skywarstrainer.*           # All permissions (default: op)
skywarstrainer.spawn       # Spawn bots
skywarstrainer.remove      # Remove bots
skywarstrainer.list        # List bots
skywarstrainer.modify      # Change difficulty / personality / reload
skywarstrainer.stats       # View bot statistics
skywarstrainer.debug       # Toggle debug mode
skywarstrainer.preset      # Save / load presets
skywarstrainer.fill        # Fill a game with bots
skywarstrainer.control     # Pause / resume AI
skywarstrainer.teleport    # Teleport to bots
```

---

## ⚡ Quick Start

```text
# 1. Start a SkyWars game (LostSkyWars handles this).
# 2. Fill the lobby with 5 medium-difficulty bots:
/swt fill 5 MEDIUM

# 3. Spawn one named, mixed-personality bot:
/swt spawn HARD AGGRESSIVE,STRATEGIC,CLUTCH_MASTER Frostbite

# 4. Inspect:
/swt list
/swt stats Frostbite

# 5. After the match — they remember.
```

---

## 🧪 Configuration

Three config files are auto-generated:

- **`config.yml`** — Global plugin settings, debug toggles, LLM credentials slot.
- **`difficulty.yml`** — Define your own difficulty profiles (reaction time, aim error, CPS, mistake rate, gated techniques).
- **`personality.yml`** — Tune existing personalities or define overrides for the conflict table.

Every numeric parameter referenced in the AI is exposed — nothing is hardcoded. Run `/swt reload` after edits.

---

## 🧰 Tech Stack

- **Language:** Java 8 (Spigot 1.8.8 NMS)
- **Build:** Maven (with `maven-shade-plugin` fat-jar)
- **NPC Framework:** [Citizens2](https://github.com/CitizensDev/Citizens2)
- **JSON:** Gson 2.11.0 (for learning serialization & LLM I/O)
- **Game Integration:** LostSkyWars 2.5.00+

---

## 📋 FAQ

**Q: Will this work on 1.12 / 1.16 / 1.20?**
A: No. The combat, knockback, and packet code is hand-written against `net.minecraft.server.v1_8_R3`. A higher-version port is non-trivial.

**Q: Do I have to use the LLM features?**
A: No. They are **opt-in and disabled by default**. The plugin runs in 100% local mode unless you configure an API key.

**Q: Will bots break my arena?**
A: No. Bots are bound to the LostSkyWars game lifecycle — they spawn in cages, are removed on game end, and respect the same world boundaries as players.

**Q: Can I run 20 bots at once?**
A: Yes — performance was a primary design goal. Bots use **staggered ticking** (each bot ticks on its own offset) and the `BotManager.maintenance()` cleanup pass runs only every 100 ticks. Realistic capacity depends on your server hardware, but double-digit bot counts are well within design parameters.

**Q: Does the bot cheat?**
A: No. Every bot uses normal, packet-level interactions — block placement, swings, look packets, knockback. Reaction time, aim error, CPS, and mistake rate are all *bounded* by the difficulty profile. A `BEGINNER` bot will miss shots, mis-place blocks, and panic.

---

## 🐛 Known Limitations

- Hard-locked to **Minecraft 1.8.8** (NMS dependency).
- Requires **LostSkyWars** specifically — not a generic SkyWars driver.
- The LLM advisor adds latency on the network thread; keep it disabled for competitive testing.
- RL weight files grow over long runtimes — `MemoryPruner` mitigates but does not eliminate this.

---

## 🤝 Contributing

Pull requests, bug reports, and tactical suggestions are all welcome. Open an [issue](https://github.com/TwightLightDev/SkyWarsTrainer/issues) on the repository.

When contributing code, please:
- Match the existing package layout and Javadoc style.
- Keep new AI behavior **gated by difficulty** so beginner bots stay beginner.
- Add to existing considerations rather than special-casing in the engine.

---

## 👤 Author

**TwightLightDev**
GitHub: [@TwightLightDev](https://github.com/TwightLightDev)
Repository: [SkyWarsTrainer](https://github.com/TwightLightDev/SkyWarsTrainer)

---

## 📜 License

This project is currently distributed without a published license file. Until one is added, all rights are reserved by the author. Please contact the author for usage permissions outside of personal/test environments.

---

<p align="center">
  <strong>SkyWarsTrainer — because your aim deserves a real opponent.</strong><br>
  <sub>Built with ❤️, far too much caffeine, and a 100-class AI stack by <a href="https://github.com/TwightLightDev">TwightLightDev</a>.</sub>
</p>
