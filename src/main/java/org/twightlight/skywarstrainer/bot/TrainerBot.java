package org.twightlight.skywarstrainer.bot;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;

import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.awareness.ChestLocator;
import org.twightlight.skywarstrainer.awareness.FallDamageEstimator;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.awareness.LavaDetector;
import org.twightlight.skywarstrainer.awareness.MapScanner;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;
import org.twightlight.skywarstrainer.util.TickTimer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The core wrapper around a Citizens NPC that represents a single trainer bot.
 *
 * <p>TrainerBot is the central object tying together the NPC entity, the bot's
 * profile (difficulty, personalities, stats), and all AI subsystems
 * (decision engine, combat, movement, awareness, etc.). Each TrainerBot has
 * exactly one Citizens NPC and one BotProfile.</p>
 *
 * <p>The bot's AI tick loop runs through a custom Citizens {@link Trait}
 * ({@link SkyWarsTrainerTrait}), whose {@code run()} method is called every
 * server tick by Citizens. This trait delegates to TrainerBot's {@link #tick()}
 * method where the staggered tick budget is managed.</p>
 *
 * <p>Phase 2 provides: full movement subsystem (MovementController + sub-controllers)
 * and awareness subsystem (MapScanner, ThreatMap, IslandGraph, ChestLocator,
 * LavaDetector, VoidDetector, FallDamageEstimator, GamePhaseTracker). These run
 * on staggered tick schedules to maintain performance. Later phases add combat,
 * decisions, looting, bridging, and inventory management as additional subsystems.</p>
 *
 * <h3>Tick Budget (per tick group):</h3>
 * <ul>
 *   <li>Movement &amp; aim: every tick (20 TPS) — must be smooth</li>
 *   <li>Threat map: every tick — tracks enemy movement</li>
 *   <li>Void/fall detection: every 5 ticks — responsive edge safety</li>
 *   <li>Lava detection: every 15 ticks — periodic hazard check</li>
 *   <li>Chest locator update: every 60 ticks — syncs with map scan</li>
 *   <li>Map scanning: every 40-60 ticks — incremental terrain cache</li>
 *   <li>Island graph rebuild: every 200 ticks — expensive structural analysis</li>
 *   <li>Game phase update: every 30 ticks — strategic phase tracking</li>
 *   <li>Behavior tree traversal: every 2-4 ticks (Phase 3)</li>
 *   <li>Utility AI re-score: every 10-20 ticks (Phase 3)</li>
 *   <li>Full inventory audit: every 100 ticks (Phase 5)</li>
 * </ul>
 */
public class TrainerBot {

    private final SkyWarsTrainerPlugin plugin;

    private final Arena<?> arena;

    /** The unique identifier for this bot instance. */
    private final UUID botId;

    /** The Citizens NPC backing this bot. */
    private NPC npc;

    /** The bot's profile containing difficulty, personalities, and stats. */
    private final BotProfile profile;

    /** The skin applied to the NPC. */
    private final BotSkin skin;

    /** Whether this bot has been fully initialized and is ready to tick. */
    private boolean initialized;

    /** Whether this bot has been destroyed and should no longer be used. */
    private boolean destroyed;

    /**
     * Global tick counter local to this bot. Incremented each call to tick().
     * Used for staggered subsystem scheduling.
     */
    private long localTickCount;

    /**
     * Mistake injection timer. Periodically triggers an intentional mistake
     * to make the bot feel human at lower difficulties.
     */
    private TickTimer mistakeTimer;

    /**
     * The stagger offset for this bot within the tick loop. If staggering is
     * enabled, this bot only runs expensive logic on ticks where
     * (globalTick % staggerGroupSize == staggerOffset).
     */
    private int staggerOffset;

    // ═════════════════════════════════════════════════════════════
    //  PHASE 2: Movement Subsystem
    // ═════════════════════════════════════════════════════════════

    /** Central movement controller managing all bot locomotion. */
    private MovementController movementController;

    // ═════════════════════════════════════════════════════════════
    //  PHASE 2: Awareness Subsystem
    // ═════════════════════════════════════════════════════════════

    /** Periodically scans terrain and caches block data around the bot. */
    private MapScanner mapScanner;

    /** Tracks visible enemy positions, velocities, and generates a heat map. */
    private ThreatMap threatMap;

    /** Understands island layout, bridge connections, and map structure. */
    private IslandGraph islandGraph;

    /** Locates and tracks chests, their looted status, and item quality. */
    private ChestLocator chestLocator;

    /** Detects lava hazards near the bot. */
    private LavaDetector lavaDetector;

    /** Detects void edges and unsafe positions around the bot. */
    private VoidDetector voidDetector;

    /** Estimates fall damage from current and hypothetical positions. */
    private FallDamageEstimator fallDamageEstimator;

    /** Tracks the strategic game phase (early/mid/late) for decision-making. */
    private GamePhaseTracker gamePhaseTracker;

    // ═════════════════════════════════════════════════════════════
    //  PHASE 2: Awareness Tick Timers
    // ═════════════════════════════════════════════════════════════

    /**
     * Timer for void/edge detection. Fires every 5 ticks for responsive edge safety.
     * Edge detection must be frequent because falling off is instant death.
     */
    private TickTimer voidDetectTimer;

    /**
     * Timer for lava hazard detection. Fires every 15 ticks.
     * Lava is a slower-developing threat than void edges.
     */
    private TickTimer lavaDetectTimer;

    /**
     * Timer for updating the chest locator from map scan data. Fires every 60 ticks.
     * Chest positions rarely change (only when broken), so infrequent updates suffice.
     */
    private TickTimer chestUpdateTimer;

    /**
     * Timer for island graph rebuilds. Fires every 200 ticks (10 seconds).
     * This is the most expensive awareness operation — full structural analysis
     * of island clusters and bridge connections.
     */
    private TickTimer islandGraphTimer;

    /**
     * Timer for game phase tracking updates. Fires every 30 ticks (1.5 seconds).
     * Strategic phase changes happen on the scale of minutes, so this is ample.
     */
    private TickTimer gamePhaseTimer;

    /**
     * Creates a new TrainerBot. Does NOT spawn the NPC yet — call {@link #spawn(Location)}
     * to create and spawn the NPC in the world.
     *
     * @param plugin  the plugin instance
     * @param profile the bot's profile
     * @param skin    the skin to apply
     */
    public TrainerBot(@Nonnull SkyWarsTrainerPlugin plugin, @Nonnull Arena<?> arena , @Nonnull BotProfile profile, @Nonnull BotSkin skin) {
        this.plugin = plugin;
        this.botId = UUID.randomUUID();
        this.profile = profile;
        this.skin = skin;
        this.initialized = false;
        this.destroyed = false;
        this.localTickCount = 0;
        this.staggerOffset = 0;
        this.arena = arena;

        // Initialize the mistake timer based on difficulty
        int mistakeIntervalTicks = profile.getDifficultyProfile().getMistakeIntervalTicks();
        this.mistakeTimer = new TickTimer(mistakeIntervalTicks, mistakeIntervalTicks / 4);
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Spawns the bot's NPC at the given location.
     *
     * <p>This creates a Citizens NPC with the PLAYER entity type, applies the
     * skin, registers the custom trait for tick processing, spawns the entity,
     * and initializes all Phase 2 subsystems (movement and awareness).</p>
     *
     * @param location the location to spawn at
     * @return true if spawning succeeded
     */
    public boolean spawn(@Nonnull Location location) {
        if (destroyed) {
            plugin.getLogger().warning("Cannot spawn a destroyed bot: " + skin.getDisplayName());
            return false;
        }

        try {
            // Create the NPC via Citizens API
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, skin.getDisplayName());

            // Apply skin: Citizens uses a SkinTrait internally
            if (plugin.getConfigManager().isSkinsEnabled()) {
                /*
                 * Citizens2 SkinTrait: calling npc.data().set(NPC.PLAYER_SKIN_UUID_METADATA, skinName)
                 * tells Citizens to fetch and apply the skin from that username.
                 */
                npc.data().set(NPC.Metadata.PLAYER_SKIN_UUID, skin.getSkinName());
                npc.data().set("player-skin-name", skin.getSkinName());
            }

            // Configure NPC properties
            npc.setProtected(false); // Bots should be damageable
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);

            // Register our custom trait for tick processing
            npc.addTrait(new SkyWarsTrainerTrait(this));

            // Spawn the NPC at the location
            npc.spawn(location);

            // Initialize Phase 2 subsystems now that the entity exists
            initializeSubsystems();

            initialized = true;

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[DEBUG] Bot spawned: " + skin.getDisplayName()
                        + " at " + formatLocation(location)
                        + " (" + profile.getDifficulty().name() + ")");
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn bot: " + skin.getDisplayName(), e);
            // Clean up partial creation
            if (npc != null) {
                npc.destroy();
                npc = null;
            }
            return false;
        }
    }

    /**
     * Initializes all Phase 2 subsystems: movement and awareness.
     *
     * <p>Movement subsystem is created first because awareness systems may
     * reference movement state (e.g., "is the bot moving?" for threat assessment).
     * Awareness timers are staggered with variance to prevent multiple subsystems
     * from running expensive operations on the same tick.</p>
     */
    private void initializeSubsystems() {
        // ── Movement Subsystem ──
        this.movementController = new MovementController(this);

        // ── Awareness Subsystem ──
        int mapScanInterval = plugin.getConfigManager().getMapScanInterval();
        this.mapScanner = new MapScanner(this, mapScanInterval);
        this.threatMap = new ThreatMap(this);
        this.islandGraph = new IslandGraph(this);
        this.chestLocator = new ChestLocator(this);
        this.lavaDetector = new LavaDetector(this);
        this.voidDetector = new VoidDetector(this);
        this.fallDamageEstimator = new FallDamageEstimator(this);
        this.gamePhaseTracker = new GamePhaseTracker(this);

        /*
         * Initialize tick timers for awareness subsystems with variance to prevent
         * multiple timers from firing simultaneously. The variance spreads their
         * execution across different ticks, reducing per-tick CPU spikes.
         */
        this.voidDetectTimer = new TickTimer(5, 1);
        this.lavaDetectTimer = new TickTimer(15, 3);
        this.chestUpdateTimer = new TickTimer(60, 10);
        this.islandGraphTimer = new TickTimer(200, 40);
        this.gamePhaseTimer = new TickTimer(30, 5);

        // Force an initial map scan so the bot has terrain awareness immediately
        mapScanner.forceRescan();
    }

    /**
     * Destroys this bot: despawns and deregisters the NPC, clears references,
     * nullifies all subsystems. After this call, the bot object should not be used.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        initialized = false;

        // Clear subsystem references to allow GC
        movementController = null;
        mapScanner = null;
        threatMap = null;
        islandGraph = null;
        chestLocator = null;
        lavaDetector = null;
        voidDetector = null;
        fallDamageEstimator = null;
        gamePhaseTracker = null;

        if (npc != null) {
            if (npc.isSpawned()) {
                npc.despawn();
            }
            npc.destroy();
            npc = null;
        }

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[DEBUG] Bot destroyed: " + skin.getDisplayName());
        }
    }

    /**
     * Returns true if this bot is alive (spawned, not destroyed, and the NPC
     * entity exists and is alive).
     *
     * @return true if the bot is alive and active
     */
    public boolean isAlive() {
        if (destroyed || !initialized || npc == null || !npc.isSpawned()) {
            return false;
        }
        LivingEntity entity = getLivingEntity();
        return entity != null && !entity.isDead();
    }

    // ─── Tick Loop ──────────────────────────────────────────────

    /**
     * Main tick method called every server tick by the Citizens Trait.
     * This is the bot's heartbeat.
     *
     * <p>Tick processing order (Phase 2):
     * <ol>
     *   <li>Movement controller (every tick — must be smooth)</li>
     *   <li>Threat map update (every tick — tracks enemy movement)</li>
     *   <li>Void/edge detection (every 5 ticks — responsive edge safety)</li>
     *   <li>Lava detection (every 15 ticks — periodic hazard check)</li>
     *   <li>Map scanner (every 40-60 ticks — incremental terrain cache)</li>
     *   <li>Chest locator (every 60 ticks — syncs with map scan)</li>
     *   <li>Island graph (every 200 ticks — expensive structural analysis)</li>
     *   <li>Game phase tracker (every 30 ticks — strategic phase detection)</li>
     *   <li>Mistake injection (periodic based on difficulty)</li>
     * </ol></p>
     *
     * <p>Phase 3+ will insert into this pipeline:
     * <ul>
     *   <li>Combat engine: every 1-2 ticks (between movement and awareness)</li>
     *   <li>Behavior tree traversal: every 2-4 ticks</li>
     *   <li>Utility AI re-score: every 10-20 ticks (or on interrupt)</li>
     *   <li>Inventory manager: every 100 ticks</li>
     * </ul></p>
     */
    public void tick() {
        if (!initialized || destroyed || profile.isPaused()) {
            return;
        }

        if (!isAlive()) {
            return;
        }

        localTickCount++;

        // ── 1. Movement (every tick) ──────────────────────────
        // Movement must run every tick for smooth motion and accurate aim tracking.
        tickMovement();

        // ── 2. Threat tracking (every tick) ───────────────────
        // Threat map must be updated every tick to accurately track enemy positions
        // and velocity vectors. This is a lightweight operation (iterates nearby entities).
        tickThreatMap();

        // ── 3. Void/edge detection (every 5 ticks) ───────────
        // Edge detection is frequent because falling into void is instant death.
        // The bot needs fast awareness of nearby edges for crouch/avoidance behavior.
        if (voidDetectTimer.tick()) {
            tickVoidDetection();
        }

        // ── 4. Lava detection (every 15 ticks) ───────────────
        // Lava is a slower threat than void edges. Periodic scans suffice.
        if (lavaDetectTimer.tick()) {
            tickLavaDetection();
        }

        // ── 5. Map scanning (staggered, every 40-60 ticks) ───
        // The map scanner runs incrementally — it processes a few hundred blocks
        // per tick, working outward from the bot in concentric rings. The timer
        // triggers a new scan cycle; the per-tick incremental processing runs
        // inside mapScanner.tick() every time.
        tickMapScanner();

        // ── 6. Chest locator (every 60 ticks) ────────────────
        // Syncs chest data from the map scanner. Only needs to run after
        // the scanner has had time to discover new chests.
        if (chestUpdateTimer.tick()) {
            tickChestLocator();
        }

        // ── 7. Island graph (every 200 ticks) ────────────────
        // The most expensive awareness operation. Flood-fills to detect island
        // clusters and bridge connections. Runs infrequently.
        if (islandGraphTimer.tick()) {
            tickIslandGraph();
        }

        // ── 8. Game phase tracker (every 30 ticks) ───────────
        // Updates the strategic game phase (early/mid/late) based on
        // elapsed time and alive player count.
        if (gamePhaseTimer.tick()) {
            tickGamePhase();
        }

        // ── 9. Mistake injection ─────────────────────────────
        // Periodically trigger a human-like error based on difficulty.
        if (mistakeTimer.tick()) {
            injectMistake();
        }

        /*
         * Phase 3+ will add:
         * - decisionEngine.evaluate()     (every N ticks, or on interrupt)
         * - behaviorTree.tick()           (every 2-4 ticks)
         * - combatEngine.tick()           (every 1-2 ticks)
         * - inventoryManager.tick()       (every 100 ticks)
         */

        // ── Debug output ─────────────────────────────────────
        if (profile.isDebugMode() && localTickCount % 100 == 0) {
            logDebugStatus();
        }
    }

    // ─── Subsystem Tick Methods ─────────────────────────────────

    /**
     * Ticks the movement controller. Runs every tick for smooth bot locomotion.
     * Updates look direction, applies velocity, manages sprint/sneak/jump states.
     */
    private void tickMovement() {
        if (movementController != null) {
            try {
                movementController.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in movement tick for bot " + getName(), e);
            }
        }
    }

    /**
     * Ticks the threat map. Runs every tick to track enemy positions with
     * per-tick granularity. This is essential for accurate velocity estimation
     * and predictive aim (Phase 4).
     */
    private void tickThreatMap() {
        if (threatMap != null) {
            try {
                threatMap.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in threat map tick for bot " + getName(), e);
            }
        }
    }

    /**
     * Ticks void edge detection. Scans 8 directions for nearby void edges
     * and updates edge proximity data.
     *
     * <p>If the bot is near an edge, this information can be used by
     * the movement system to auto-crouch (CAUTIOUS personality) or by
     * the combat system to avoid positioning near void.</p>
     */
    private void tickVoidDetection() {
        if (voidDetector != null) {
            try {
                voidDetector.scan();

                // Fall damage estimation benefits from void detection running first
                if (fallDamageEstimator != null) {
                    fallDamageEstimator.estimateCurrentPosition();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in void detection for bot " + getName(), e);
            }
        }
    }

    /**
     * Ticks lava detection. Scans a small area around the bot for lava blocks.
     */
    private void tickLavaDetection() {
        if (lavaDetector != null) {
            try {
                lavaDetector.scan();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in lava detection for bot " + getName(), e);
            }
        }
    }

    /**
     * Ticks the map scanner. The scanner handles its own internal timer for
     * starting new scan cycles; this call processes incremental scan blocks.
     */
    private void tickMapScanner() {
        if (mapScanner != null) {
            try {
                mapScanner.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in map scanner for bot " + getName(), e);
            }
        }
    }

    /**
     * Updates the chest locator by syncing discovered chest positions from
     * the map scanner. Also updates the island graph's awareness of chest
     * locations on each island.
     */
    private void tickChestLocator() {
        if (chestLocator != null && mapScanner != null) {
            try {
                chestLocator.updateFromScanner(mapScanner);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in chest locator update for bot " + getName(), e);
            }
        }
    }

    /**
     * Rebuilds the island graph from the map scanner's cached block data.
     * This identifies island clusters, mid island, spawn island, and bridge
     * connections. It is the most expensive awareness operation.
     */
    private void tickIslandGraph() {
        if (islandGraph != null && mapScanner != null) {
            try {
                islandGraph.rebuild(mapScanner);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in island graph rebuild for bot " + getName(), e);
            }
        }
    }

    /**
     * Updates the game phase tracker. Determines whether the game is in
     * early, mid, or late phase based on time and player count.
     */
    private void tickGamePhase() {
        if (gamePhaseTracker != null) {
            try {
                gamePhaseTracker.update();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error in game phase update for bot " + getName(), e);
            }
        }
    }

    /**
     * Injects a random intentional mistake to make the bot feel human.
     * The type of mistake and its impact scale with difficulty — lower
     * difficulty bots make more impactful mistakes more frequently.
     *
     * <p>Mistake types (expanded from Phase 1):
     * <ul>
     *   <li>Look the wrong way briefly (head jitter)</li>
     *   <li>Stop moving for a moment (movement freeze)</li>
     *   <li>Jump needlessly (random jump)</li>
     *   <li>Toggle sneak briefly (accidental shift tap)</li>
     *   <li>Switch to wrong hotbar slot momentarily (Phase 5)</li>
     *   <li>Place a block incorrectly while bridging (Phase 5)</li>
     * </ul></p>
     */
    private void injectMistake() {
        if (!isAlive()) return;

        LivingEntity entity = getLivingEntity();
        if (entity == null) return;

        double roll = RandomUtil.nextDouble();

        if (roll < 0.35) {
            // Mistake type: head jitter — look the wrong way briefly
            double noiseYaw = (RandomUtil.nextDouble() - 0.5) * 40.0;
            double noisePitch = (RandomUtil.nextDouble() - 0.5) * 20.0;

            if (movementController != null) {
                float currentYaw = movementController.getCurrentYaw();
                float currentPitch = movementController.getCurrentPitch();
                movementController.setCurrentYaw(currentYaw + (float) noiseYaw);
                movementController.setCurrentPitch(currentPitch + (float) noisePitch);
            } else {
                // Fallback for when movement controller isn't available
                Location loc = entity.getLocation();
                loc.setYaw(loc.getYaw() + (float) noiseYaw);
                loc.setPitch(loc.getPitch() + (float) noisePitch);
                PacketUtil.sendHeadRotation(entity, loc.getYaw());
            }
        } else if (roll < 0.55) {
            // Mistake type: brief movement freeze (stop for 3-8 ticks)
            if (movementController != null && movementController.isMoving()) {
                movementController.setFrozen(true);
                // Schedule unfreeze after a short delay
                int freezeTicks = RandomUtil.nextInt(3, 8);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (movementController != null && !destroyed) {
                        movementController.setFrozen(false);
                    }
                }, freezeTicks);
            }
        } else if (roll < 0.70) {
            // Mistake type: random unnecessary jump
            if (movementController != null) {
                movementController.getJumpController().jump();
            }
        } else if (roll < 0.85) {
            // Mistake type: accidental sneak toggle (tap shift for 2-5 ticks)
            if (movementController != null && !movementController.isSneaking()) {
                movementController.setSneaking(true);
                int sneakTicks = RandomUtil.nextInt(2, 5);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (movementController != null && !destroyed) {
                        movementController.setSneaking(false);
                    }
                }, sneakTicks);
            }
        } else {
            // Mistake type: sprint stop (briefly lose sprint)
            if (movementController != null
                    && movementController.getSprintController().isSprinting()) {
                movementController.getSprintController().stopSprinting();
                int stopTicks = RandomUtil.nextInt(3, 6);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (movementController != null && !destroyed) {
                        movementController.getSprintController().startSprinting();
                    }
                }, stopTicks);
            }
        }

        if (profile.isDebugMode()) {
            plugin.getLogger().info("[DEBUG] " + getName()
                    + " mistake injected (type=" + describeMistakeType(roll) + ")");
        }
    }

    /**
     * Returns a human-readable description of the mistake type based on the roll value.
     * Used for debug logging only.
     */
    private static String describeMistakeType(double roll) {
        if (roll < 0.35) return "head_jitter";
        if (roll < 0.55) return "movement_freeze";
        if (roll < 0.70) return "random_jump";
        if (roll < 0.85) return "sneak_toggle";
        return "sprint_stop";
    }

    /**
     * Logs a debug status summary. Called every 100 ticks when debug mode is active.
     * Shows the current state of all subsystems for troubleshooting.
     */
    private void logDebugStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DEBUG] ").append(getName()).append(" status @ tick ").append(localTickCount);

        // Location
        Location loc = getLocation();
        if (loc != null) {
            sb.append(String.format(" | pos=(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ()));
        }

        // Movement
        if (movementController != null) {
            sb.append(" | moving=").append(movementController.isMoving());
            sb.append(" sprint=").append(movementController.getSprintController().isSprinting());
            sb.append(" sneak=").append(movementController.isSneaking());
        }

        // Threats
        if (threatMap != null) {
            sb.append(" | threats=").append(threatMap.getVisibleEnemyCount());
        }

        // Awareness
        if (voidDetector != null) {
            sb.append(" | nearVoid=").append(voidDetector.isNearVoidEdge());
            if (voidDetector.isNearVoidEdge()) {
                sb.append(String.format("(%.1f)", voidDetector.getDistanceToVoidEdge()));
            }
        }

        if (lavaDetector != null) {
            sb.append(" | lava=").append(lavaDetector.isLavaDetected());
        }

        if (chestLocator != null) {
            sb.append(" | chests=").append(chestLocator.getUnlootedCount())
                    .append("/").append(chestLocator.getTotalChestCount());
        }

        // Map
        if (mapScanner != null) {
            sb.append(" | mapCache=").append(mapScanner.getCacheSize());
        }

        if (islandGraph != null) {
            sb.append(" | islands=").append(islandGraph.getIslands().size());
            sb.append(" bridges=").append(islandGraph.getBridges().size());
            if (islandGraph.getCurrentIsland() != null) {
                sb.append(" onIsland=").append(islandGraph.getCurrentIsland().id);
            }
        }

        // Game phase
        if (gamePhaseTracker != null) {
            sb.append(" | phase=").append(gamePhaseTracker.getPhase().name());
            sb.append(String.format(" progress=%.2f", gamePhaseTracker.getGameProgress()));
        }

        // Fall safety
        if (fallDamageEstimator != null) {
            sb.append(String.format(" | safety=%.2f", fallDamageEstimator.getPositionSafetyScore()));
        }

        plugin.getLogger().info(sb.toString());
    }

    // ─── Accessors ──────────────────────────────────────────────

    /**
     * Returns the unique bot instance ID.
     *
     * @return the bot UUID
     */
    @Nonnull
    public UUID getBotId() {
        return botId;
    }

    /**
     * Returns the Citizens NPC.
     *
     * @return the NPC, or null if not yet spawned or destroyed
     */
    @Nullable
    public NPC getNpc() {
        return npc;
    }

    /**
     * Returns the NPC's entity as a LivingEntity.
     *
     * @return the living entity, or null if not spawned
     */
    @Nullable
    public LivingEntity getLivingEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        if (npc.getEntity() instanceof LivingEntity) {
            return (LivingEntity) npc.getEntity();
        }
        return null;
    }

    /**
     * Returns the NPC's entity as a Player (since NPC type is PLAYER).
     *
     * @return the player entity, or null if not spawned
     */
    @Nullable
    public Player getPlayerEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        if (npc.getEntity() instanceof Player) {
            return (Player) npc.getEntity();
        }
        return null;
    }

    /**
     * Returns the bot's current location.
     *
     * @return the location, or null if not spawned
     */
    @Nullable
    public Location getLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : null;
    }

    /**
     * Returns the bot's display name.
     *
     * @return the name
     */
    @Nonnull
    public String getName() {
        return skin.getDisplayName();
    }

    /**
     * Returns the bot's arena.
     *
     * @return the arena
     */
    @Nonnull
    public Arena<?> getArena() {
        return arena;
    }

    /**
     * Returns the bot's profile.
     *
     * @return the profile
     */
    @Nonnull
    public BotProfile getProfile() {
        return profile;
    }

    /**
     * Returns the bot's skin information.
     *
     * @return the skin
     */
    @Nonnull
    public BotSkin getSkin() {
        return skin;
    }

    /**
     * Returns the difficulty profile (shorthand for profile.getDifficultyProfile()).
     *
     * @return the difficulty profile
     */
    @Nonnull
    public DifficultyProfile getDifficultyProfile() {
        return profile.getDifficultyProfile();
    }

    /** @return true if the bot has been initialized */
    public boolean isInitialized() {
        return initialized;
    }

    /** @return true if the bot has been destroyed */
    public boolean isDestroyed() {
        return destroyed;
    }

    /** @return the local tick count */
    public long getLocalTickCount() {
        return localTickCount;
    }

    /** @return the stagger offset */
    public int getStaggerOffset() {
        return staggerOffset;
    }

    /** @param offset the stagger offset for tick distribution */
    public void setStaggerOffset(int offset) {
        this.staggerOffset = offset;
    }

    // ─── Subsystem Accessors (Phase 2) ──────────────────────────

    /**
     * Returns the movement controller for this bot.
     * Used by combat engine, bridge engine, and other systems that need
     * to command bot movement.
     *
     * @return the movement controller, or null if not yet initialized
     */
    @Nullable
    public MovementController getMovementController() {
        return movementController;
    }

    /**
     * Returns the map scanner for this bot.
     * Used by awareness subsystems and the decision engine for terrain queries.
     *
     * @return the map scanner, or null if not yet initialized
     */
    @Nullable
    public MapScanner getMapScanner() {
        return mapScanner;
    }

    /**
     * Returns the threat map for this bot.
     * Used by the combat engine and decision engine for enemy tracking.
     *
     * @return the threat map, or null if not yet initialized
     */
    @Nullable
    public ThreatMap getThreatMap() {
        return threatMap;
    }

    /**
     * Returns the island graph for this bot.
     * Used by the bridge path planner and decision engine for map navigation.
     *
     * @return the island graph, or null if not yet initialized
     */
    @Nullable
    public IslandGraph getIslandGraph() {
        return islandGraph;
    }

    /**
     * Returns the chest locator for this bot.
     * Used by the loot engine to find and track chests.
     *
     * @return the chest locator, or null if not yet initialized
     */
    @Nullable
    public ChestLocator getChestLocator() {
        return chestLocator;
    }

    /**
     * Returns the lava detector for this bot.
     * Used by movement and combat systems for hazard avoidance.
     *
     * @return the lava detector, or null if not yet initialized
     */
    @Nullable
    public LavaDetector getLavaDetector() {
        return lavaDetector;
    }

    /**
     * Returns the void detector for this bot.
     * Used by movement, combat, and bridge systems for edge safety.
     *
     * @return the void detector, or null if not yet initialized
     */
    @Nullable
    public VoidDetector getVoidDetector() {
        return voidDetector;
    }

    /**
     * Returns the fall damage estimator for this bot.
     * Used by movement and decision systems for positioning safety.
     *
     * @return the fall damage estimator, or null if not yet initialized
     */
    @Nullable
    public FallDamageEstimator getFallDamageEstimator() {
        return fallDamageEstimator;
    }

    /**
     * Returns the game phase tracker for this bot.
     * Used by the decision engine for strategic phase awareness.
     *
     * @return the game phase tracker, or null if not yet initialized
     */
    @Nullable
    public GamePhaseTracker getGamePhaseTracker() {
        return gamePhaseTracker;
    }

    /**
     * Helper to format a location for debug logging.
     */
    private static String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f in %s)",
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "null");
    }

    @Override
    public String toString() {
        return "TrainerBot{name='" + getName() + "', id=" + botId
                + ", alive=" + isAlive()
                + ", " + profile.getDifficulty().name() + "}";
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: SkyWarsTrainerTrait
    // ═════════════════════════════════════════════════════════════

    /**
     * Citizens Trait that hooks into the per-tick NPC update cycle.
     *
     * <p>Citizens calls {@link Trait#run()} every server tick for each spawned NPC.
     * This trait delegates to the owning TrainerBot's tick method, which is where
     * all AI logic is driven.</p>
     *
     * <p>Using a Trait rather than a separate Bukkit scheduled task ensures:
     * <ol>
     *   <li>Ticking is automatically tied to the NPC's lifecycle (no orphaned tasks).</li>
     *   <li>Citizens handles the scheduling — no extra task management needed.</li>
     *   <li>The trait has direct access to the NPC object.</li>
     * </ol></p>
     */
    public static class SkyWarsTrainerTrait extends Trait {

        /** The trait name registered with Citizens. */
        public static final String TRAIT_NAME = "skywarstrainer";

        /** Reference to the owning bot. May be null if trait was loaded from persistence. */
        private TrainerBot ownerBot;

        /**
         * Creates a new trait for the given bot.
         *
         * @param bot the owning TrainerBot
         */
        public SkyWarsTrainerTrait(@Nonnull TrainerBot bot) {
            super(TRAIT_NAME);
            this.ownerBot = bot;
        }

        /**
         * Default constructor required by Citizens for trait persistence/reload.
         * A trait created this way has no owner bot and will not tick.
         */
        public SkyWarsTrainerTrait() {
            super(TRAIT_NAME);
            this.ownerBot = null;
        }

        /**
         * Called every server tick by Citizens. Delegates to the bot's tick loop.
         */
        @Override
        public void run() {
            if (ownerBot != null && !ownerBot.isDestroyed()) {
                ownerBot.tick();
            }
        }

        /**
         * Called when the NPC is spawned. Can be used for initialization.
         */
        @Override
        public void onSpawn() {
            // Subsystem initialization happens in TrainerBot.spawn()
        }

        /**
         * Called when the NPC is despawned. Can be used for cleanup.
         */
        @Override
        public void onDespawn() {
            // Cleanup happens in TrainerBot.destroy()
        }

        /**
         * Called when the NPC is removed/destroyed.
         */
        @Override
        public void onRemove() {
            ownerBot = null;
        }

        /**
         * Returns the owning bot, if any.
         *
         * @return the owner bot, or null
         */
        @Nullable
        public TrainerBot getOwnerBot() {
            return ownerBot;
        }
    }
}
