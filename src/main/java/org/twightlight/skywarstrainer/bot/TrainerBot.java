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
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.ai.engine.*;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.awareness.*;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.combat.CombatEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.inventory.InventoryManager;
import org.twightlight.skywarstrainer.loot.LootEngine;
import org.twightlight.skywarstrainer.movement.MovementController;
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
 * (decision engine, state machine, combat, movement, awareness, bridging,
 * looting, and inventory management).</p>
 *
 * <p>The bot's AI tick loop runs through a custom Citizens {@link Trait}
 * ({@link SkyWarsTrainerTrait}), whose {@code run()} method is called every
 * server tick by Citizens. This trait delegates to TrainerBot's {@link #tick()}
 * method where the staggered tick budget is managed.</p>
 *
 * <h3>Architecture Pipeline:</h3>
 * <p>Utility AI (DecisionEngine) picks WHAT to do → State Machine manages
 * transitions → Behavior Tree (per state) executes HOW to do it → Subsystems
 * (combat, movement, bridging, loot, inventory) perform the actual actions.</p>
 *
 * <h3>Tick Budget (per tick group):</h3>
 * <ul>
 *   <li>Movement &amp; aim: every tick (20 TPS)</li>
 *   <li>Threat map: every tick</li>
 *   <li>Combat engine: every 1-2 ticks (when active)</li>
 *   <li>Behavior tree traversal: every 2-4 ticks</li>
 *   <li>Utility AI re-score: every 10-20 ticks (unless interrupted)</li>
 *   <li>Void/fall detection: every 5 ticks</li>
 *   <li>Lava detection: every 15 ticks</li>
 *   <li>Game phase update: every 30 ticks</li>
 *   <li>Map scanning: every 40-60 ticks</li>
 *   <li>Chest locator: every 60 ticks</li>
 *   <li>Full inventory audit: every 100 ticks</li>
 *   <li>Island graph rebuild: every 200 ticks</li>
 * </ul>
 */
public class TrainerBot {

    private final SkyWarsTrainerPlugin plugin;
    private final Arena<?> arena;
    private final UUID botId;
    private NPC npc;
    private final BotProfile profile;
    private final BotSkin skin;
    private boolean initialized;
    private boolean destroyed;
    private long localTickCount;
    private TickTimer mistakeTimer;
    private int staggerOffset;

    // ═══════════════════════════════════════════════════════════
    //  AI BRAIN
    // ═══════════════════════════════════════════════════════════

    /** The state machine governing macro-behavioral state. */
    private BotStateMachine stateMachine;

    /** The Utility AI decision engine — picks WHAT to do. */
    private DecisionEngine decisionEngine;

    // ═══════════════════════════════════════════════════════════
    //  SUBSYSTEMS
    // ═══════════════════════════════════════════════════════════

    /** Central movement controller managing all bot locomotion. */
    private MovementController movementController;

    /** Combat engine — melee, ranged, strategies, aim, clicks. */
    private CombatEngine combatEngine;

    /** Bridge engine — all bridge construction behaviors. */
    private BridgeEngine bridgeEngine;

    /** Loot engine — chest interaction and looting strategies. */
    private LootEngine lootEngine;

    /** Inventory manager — armor equipping, hotbar, potions, food. */
    private InventoryManager inventoryManager;

    // ═══════════════════════════════════════════════════════════
    //  AWARENESS SUBSYSTEM
    // ═══════════════════════════════════════════════════════════

    private MapScanner mapScanner;
    private ThreatMap threatMap;
    private IslandGraph islandGraph;
    private ChestLocator chestLocator;
    private LavaDetector lavaDetector;
    private VoidDetector voidDetector;
    private FallDamageEstimator fallDamageEstimator;
    private GamePhaseTracker gamePhaseTracker;

    // ═══════════════════════════════════════════════════════════
    //  TICK TIMERS
    // ═══════════════════════════════════════════════════════════

    private TickTimer voidDetectTimer;
    private TickTimer lavaDetectTimer;
    private TickTimer chestUpdateTimer;
    private TickTimer islandGraphTimer;
    private TickTimer gamePhaseTimer;
    private TickTimer behaviorTreeTimer;
    private TickTimer inventoryAuditTimer;

    /**
     * Creates a new TrainerBot. Does NOT spawn the NPC yet — call {@link #spawn(Location)}.
     *
     * @param plugin  the plugin instance
     * @param arena   the arena this bot belongs to
     * @param profile the bot's profile
     * @param skin    the skin to apply
     */
    public TrainerBot(@Nonnull SkyWarsTrainerPlugin plugin, @Nonnull Arena<?> arena,
                      @Nonnull BotProfile profile, @Nonnull BotSkin skin) {
        this.plugin = plugin;
        this.botId = UUID.randomUUID();
        this.profile = profile;
        this.skin = skin;
        this.initialized = false;
        this.destroyed = false;
        this.localTickCount = 0;
        this.staggerOffset = 0;
        this.arena = arena;

        int mistakeIntervalTicks = profile.getDifficultyProfile().getMistakeIntervalTicks();
        this.mistakeTimer = new TickTimer(mistakeIntervalTicks, mistakeIntervalTicks / 4);
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    /**
     * Spawns the bot's NPC at the given location, creates the Citizens NPC,
     * applies skin, registers the trait, and initializes ALL subsystems.
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
            npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, skin.getDisplayName());

            if (plugin.getConfigManager().isSkinsEnabled()) {
                npc.data().set(NPC.Metadata.PLAYER_SKIN_UUID, skin.getSkinName());
                npc.data().set("player-skin-name", skin.getSkinName());
            }

            npc.setProtected(false);
            npc.data().set(NPC.Metadata.DEFAULT_PROTECTED, false);
            npc.addTrait(new SkyWarsTrainerTrait(this));
            npc.spawn(location);

            initializeAllSubsystems();
            initialized = true;

            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[DEBUG] Bot spawned: " + skin.getDisplayName()
                        + " at " + formatLocation(location)
                        + " (" + profile.getDifficulty().name() + ")");
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to spawn bot: " + skin.getDisplayName(), e);
            if (npc != null) {
                npc.destroy();
                npc = null;
            }
            return false;
        }
    }

    /**
     * Initializes ALL subsystems in the correct dependency order.
     *
     * <p>Order matters: awareness first (others depend on it), then movement,
     * then combat/bridge/loot/inventory, then the AI brain (depends on all others).</p>
     */
    private void initializeAllSubsystems() {
        // ── 1. Awareness Subsystem (no dependencies on other subsystems) ──
        int mapScanInterval = plugin.getConfigManager().getMapScanInterval();
        this.mapScanner = new MapScanner(this, mapScanInterval);
        this.threatMap = new ThreatMap(this);
        this.islandGraph = new IslandGraph(this);
        this.chestLocator = new ChestLocator(this);
        this.lavaDetector = new LavaDetector(this);
        this.voidDetector = new VoidDetector(this);
        this.fallDamageEstimator = new FallDamageEstimator(this);
        this.gamePhaseTracker = new GamePhaseTracker(this);

        // ── 2. Movement Subsystem (depends on awareness for edge detection) ──
        this.movementController = new MovementController(this);

        // ── 3. Combat Subsystem (depends on movement, awareness) ──
        this.combatEngine = new CombatEngine(this);

        // ── 4. Bridge Engine (depends on movement, awareness) ──
        this.bridgeEngine = new BridgeEngine(this);

        // ── 5. Loot Engine (depends on awareness for chest locations) ──
        this.lootEngine = new LootEngine(this);

        // ── 6. Inventory Manager (depends on nothing special) ──
        this.inventoryManager = new InventoryManager(this);

        // ── 7. AI Brain (depends on ALL subsystems above) ──
        this.stateMachine = new BotStateMachine(this);
        this.decisionEngine = new DecisionEngine(this, stateMachine);

        // Build and register behavior trees for each state
        buildBehaviorTrees();

        // ── 8. Tick Timers ──
        this.voidDetectTimer = new TickTimer(5, 1);
        this.lavaDetectTimer = new TickTimer(15, 3);
        this.chestUpdateTimer = new TickTimer(60, 10);
        this.islandGraphTimer = new TickTimer(200, 40);
        this.gamePhaseTimer = new TickTimer(30, 5);
        this.behaviorTreeTimer = new TickTimer(3, 1);     // BT every 2-4 ticks
        this.inventoryAuditTimer = new TickTimer(100, 20); // Inventory every 100 ticks

        // Force an initial map scan
        mapScanner.forceRescan();
    }

    /**
     * Builds behavior trees for each BotState and registers them with the state machine.
     * Each tree defines the micro-actions for that state.
     */
    private void buildBehaviorTrees() {
        // ── IDLE state: just look around, maybe organize inventory ──
        stateMachine.registerTree(BotState.IDLE, new BehaviorTree("IDLE",
                new SequenceNode("idle-sequence",
                        new ActionNode("look-around", bot -> {
                            if (movementController != null) {
                                float yaw = movementController.getCurrentYaw() + (float)(RandomUtil.nextDouble() - 0.5) * 5.0f;
                                movementController.setCurrentYaw(yaw);
                            }
                            return NodeStatus.SUCCESS;
                        })
                )
        ));

        // ── LOOTING state: find chest → pathfind → loot → equip ──
        stateMachine.registerTree(BotState.LOOTING, new BehaviorTree("LOOTING",
                new SequenceNode("loot-sequence",
                        new ConditionNode("has-unlooted-chest", bot -> {
                            return chestLocator != null && chestLocator.getUnlootedCount() > 0;
                        }),
                        new ActionNode("loot-tick", bot -> {
                            if (lootEngine != null) {
                                lootEngine.tick();
                            }
                            return NodeStatus.RUNNING;
                        })
                )
        ));

        // ── FIGHTING state: fully delegated to CombatEngine ──
        stateMachine.registerTree(BotState.FIGHTING, new BehaviorTree("FIGHTING",
                new ActionNode("combat-tick", bot -> {
                    if (combatEngine != null) {
                        if (!combatEngine.isActive()) {
                            // Auto-engage nearest threat
                            LivingEntity target = findNearestThreat();
                            if (target != null) {
                                combatEngine.engage(target);
                            } else {
                                return NodeStatus.FAILURE; // No target → leave fight state
                            }
                        }
                        // Combat engine ticks itself; we just confirm it's active
                        return NodeStatus.RUNNING;
                    }
                    return NodeStatus.FAILURE;
                })
        ));

        // ── BRIDGING state: delegated to BridgeEngine ──
        // ── BRIDGING state: determine destination, start bridge, tick bridge engine ──
        stateMachine.registerTree(BotState.BRIDGING, new BehaviorTree("BRIDGING",
                new SequenceNode("bridge-sequence",
                        new ConditionNode("has-blocks", bot -> {
                            return bot.getInventoryManager() != null
                                    && bot.getInventoryManager().getBlockCounter().getTotalBlocks() > 0;
                        }),
                        new ActionNode("bridge-tick", bot -> {
                            if (bridgeEngine == null) return NodeStatus.FAILURE;

                            // If bridge isn't active, we need to determine where to bridge
                            if (!bridgeEngine.isActive()) {
                                Location destination = determineBridgeDestination();
                                if (destination == null) return NodeStatus.FAILURE;
                                bridgeEngine.startBridge(destination, bot.getInventoryManager().getBlockCounter().getTotalBlocks());

                                // Fire BotBridgeEvent
                                org.bukkit.Bukkit.getPluginManager().callEvent(
                                        new org.twightlight.skywarstrainer.api.events.BotBridgeEvent(
                                                TrainerBot.this,
                                                bridgeEngine.getActiveStrategy().getName(),
                                                destination));
                            }

                            bridgeEngine.tick();
                            return bridgeEngine.isActive() ? NodeStatus.RUNNING : NodeStatus.SUCCESS;
                        })
                )
        ));


        // ── FLEEING state: sprint away, eat gap, pearl escape ──
        stateMachine.registerTree(BotState.FLEEING, new BehaviorTree("FLEEING",
                new ActionNode("flee-tick", bot -> {
                    LivingEntity entity = getLivingEntity();
                    if (entity == null) return NodeStatus.FAILURE;
                    MovementController mc = getMovementController();
                    if (mc == null) return NodeStatus.FAILURE;

                    // Sprint away from nearest threat
                    mc.getSprintController().startSprinting();
                    ThreatMap tm = getThreatMap();
                    if (tm != null && !tm.getVisibleThreats().isEmpty()) {
                        ThreatMap.ThreatEntry nearest = tm.getNearestThreat();
                        if (nearest != null) {
                            Location botLoc = entity.getLocation();
                            Location threatLoc = nearest.currentPosition;
                            if (threatLoc != null) {
                                // Move in the opposite direction
                                double dx = botLoc.getX() - threatLoc.getX();
                                double dz = botLoc.getZ() - threatLoc.getZ();
                                double len = Math.sqrt(dx * dx + dz * dz);
                                if (len > 0.01) {
                                    dx /= len; dz /= len;
                                    Location fleeTarget = botLoc.clone().add(dx * 10, 0, dz * 10);
                                    mc.setMoveTarget(fleeTarget);
                                }
                            }
                        }
                    }

                    // Check if health has recovered enough to stop fleeing
                    double healthFrac = entity.getHealth() / entity.getMaxHealth();
                    if (healthFrac > getDifficultyProfile().getFleeHealthThreshold() * 1.5) {
                        return NodeStatus.SUCCESS; // Safe enough to stop fleeing
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ── ENCHANTING state: pathfind to enchant table, enchant items ──
        stateMachine.registerTree(BotState.ENCHANTING, new BehaviorTree("ENCHANTING",
                new ActionNode("enchant-tick", bot -> {
                    // Simplified: enchant handler integration
                    if (inventoryManager != null) {
                        inventoryManager.tick();
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ── HUNTING state: find target, bridge/walk toward them ──
        stateMachine.registerTree(BotState.HUNTING, new BehaviorTree("HUNTING",
                new ActionNode("hunt-tick", bot -> {
                    LivingEntity target = findNearestThreat();
                    if (target == null) return NodeStatus.FAILURE;

                    LivingEntity entity = getLivingEntity();
                    if (entity == null) return NodeStatus.FAILURE;

                    double distance = entity.getLocation().distance(target.getLocation());
                    if (distance <= 4.0) {
                        // Close enough to fight — transition will happen via interrupt
                        return NodeStatus.SUCCESS;
                    }

                    // Move toward target
                    MovementController mc = getMovementController();
                    if (mc != null) {
                        mc.getSprintController().startSprinting();
                        mc.setMoveTarget(target.getLocation());
                        mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ── CAMPING state: build fortification, watch for enemies ──
        stateMachine.registerTree(BotState.CAMPING, new BehaviorTree("CAMPING",
                new ActionNode("camp-tick", bot -> {
                    // Look around slowly (360 degree scan)
                    if (movementController != null) {
                        float yaw = movementController.getCurrentYaw() + 2.0f;
                        movementController.setCurrentYaw(yaw);
                    }

                    // Check for enemies
                    if (threatMap != null && threatMap.getVisibleEnemyCount() > 0) {
                        ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                        if (nearest != null) {
                            double distance = 999;
                            LivingEntity entity = getLivingEntity();
                            if (entity != null && nearest.currentPosition != null) {
                                distance = entity.getLocation().distance(nearest.currentPosition);
                            }
                            if (distance < 15) {
                                return NodeStatus.SUCCESS; // Enemy close — trigger re-eval
                            }
                        }
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ── END_GAME state: celebrate or just stand still ──
        stateMachine.registerTree(BotState.END_GAME, new BehaviorTree("END_GAME",
                new ActionNode("end-game-tick", bot -> {
                    // Celebratory jumps
                    if (movementController != null && RandomUtil.chance(0.05)) {
                        movementController.getJumpController().jump();
                    }
                    return NodeStatus.RUNNING;
                })
        ));
    }

    /**
     * Helper to find the nearest visible threat entity.
     *
     * @return the nearest threatening LivingEntity, or null
     */
    @Nullable
    private LivingEntity findNearestThreat() {
        if (threatMap == null) return null;
        ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
        if (nearest == null) return null;

        LivingEntity botEntity = getLivingEntity();
        if (botEntity == null) return null;

        double radius = getDifficultyProfile().getAwarenessRadius();
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(radius, radius, radius)) {
            if (entity.getUniqueId().equals(nearest.playerId) && entity instanceof LivingEntity) {
                return (LivingEntity) entity;
            }
        }
        return null;
    }

    /**
     * Determines the best destination for bridging based on the decision engine's
     * last chosen action and personality.
     *
     * @return the bridge destination, or null if none found
     */
    @Nullable
    private Location determineBridgeDestination() {
        if (decisionEngine == null) return null;

        DecisionEngine.BotAction lastAction = decisionEngine.getLastChosenAction();

        switch (lastAction) {
            case BRIDGE_TO_MID:
                // Bridge to mid island center
                if (islandGraph != null) {
                    Location midCenter = islandGraph.getMidIsland().center;
                    if (midCenter != null) return midCenter;
                }
                // Fallback: bridge toward world center (0, Y, 0)
                LivingEntity entity = getLivingEntity();
                if (entity != null) {
                    return new Location(entity.getWorld(), 0, entity.getLocation().getY(), 0);
                }
                return null;

            case BRIDGE_TO_PLAYER:
                // Bridge toward nearest enemy
                if (threatMap != null) {
                    ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                    if (nearest != null && nearest.currentPosition != null) {
                        return nearest.currentPosition;
                    }
                }
                return null;

            default:
                return null;
        }
    }

    /**
     * Destroys this bot: despawns and deregisters the NPC, clears all subsystem
     * references. After this call, the bot object should not be used.
     */
    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        initialized = false;

        // Disengage combat if active
        if (combatEngine != null && combatEngine.isActive()) {
            combatEngine.disengage();
        }

        // Clear subsystem references
        movementController = null;
        mapScanner = null;
        threatMap = null;
        islandGraph = null;
        chestLocator = null;
        lavaDetector = null;
        voidDetector = null;
        fallDamageEstimator = null;
        gamePhaseTracker = null;
        combatEngine = null;
        bridgeEngine = null;
        lootEngine = null;
        inventoryManager = null;
        stateMachine = null;
        decisionEngine = null;

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
     * Returns true if this bot is alive (spawned, not destroyed, entity exists and alive).
     *
     * @return true if the bot is alive
     */
    public boolean isAlive() {
        if (destroyed || !initialized || npc == null || !npc.isSpawned()) {
            return false;
        }
        LivingEntity entity = getLivingEntity();
        return entity != null && !entity.isDead();
    }

    // ─── Main Tick Loop ─────────────────────────────────────────

    /**
     * Main tick method called every server tick by the Citizens Trait.
     *
     * <p>Full pipeline:
     * <ol>
     *   <li>Movement &amp; aim (every tick)</li>
     *   <li>Threat map (every tick)</li>
     *   <li>Combat engine (every tick when active)</li>
     *   <li>Decision engine / Utility AI (every N ticks or on interrupt)</li>
     *   <li>Behavior tree (every 2-4 ticks)</li>
     *   <li>Void detection (every 5 ticks)</li>
     *   <li>Lava detection (every 15 ticks)</li>
     *   <li>Game phase (every 30 ticks)</li>
     *   <li>Map scanning (incremental every tick, full rescan on timer)</li>
     *   <li>Chest locator (every 60 ticks)</li>
     *   <li>Inventory audit (every 100 ticks)</li>
     *   <li>Island graph (every 200 ticks)</li>
     *   <li>Mistake injection (periodic based on difficulty)</li>
     * </ol></p>
     */
    public void tick() {
        if (!initialized || destroyed || profile.isPaused()) return;
        if (!isAlive()) return;

        localTickCount++;

        // ── 1. Movement (every tick) ──
        tickSafe("movement", () -> {
            if (movementController != null) movementController.tick();
        });

        // ── 2. Threat tracking (every tick) ──
        tickSafe("threatMap", () -> {
            if (threatMap != null) threatMap.tick();
        });

        // ── 3. Combat Engine (every tick when active) ──
        tickSafe("combat", () -> {
            if (combatEngine != null && combatEngine.isActive()) {
                combatEngine.tick();
            }
        });

        // ── 4. Decision Engine (every N ticks or on interrupt) ──
        tickSafe("decision", () -> {
            if (decisionEngine != null) decisionEngine.tick();
        });

        // ── 5. Behavior Tree (every 2-4 ticks) ──
        if (behaviorTreeTimer != null && behaviorTreeTimer.tick()) {
            tickSafe("behaviorTree", () -> {
                if (stateMachine != null) stateMachine.tick();
            });
        }

        // ── 6. Void/edge detection (every 5 ticks) ──
        if (voidDetectTimer != null && voidDetectTimer.tick()) {
            tickSafe("voidDetect", () -> {
                if (voidDetector != null) voidDetector.scan();
                if (fallDamageEstimator != null) fallDamageEstimator.estimateCurrentPosition();
            });
        }

        // ── 7. Lava detection (every 15 ticks) ──
        if (lavaDetectTimer != null && lavaDetectTimer.tick()) {
            tickSafe("lavaDetect", () -> {
                if (lavaDetector != null) lavaDetector.scan();
            });
        }

        // ── 8. Game phase (every 30 ticks) ──
        if (gamePhaseTimer != null && gamePhaseTimer.tick()) {
            tickSafe("gamePhase", () -> {
                if (gamePhaseTracker != null) gamePhaseTracker.update();
            });
        }

        // ── 9. Map scanning (incremental) ──
        tickSafe("mapScan", () -> {
            if (mapScanner != null) mapScanner.tick();
        });

        // ── 10. Chest locator (every 60 ticks) ──
        if (chestUpdateTimer != null && chestUpdateTimer.tick()) {
            tickSafe("chestUpdate", () -> {
                if (chestLocator != null && mapScanner != null) {
                    chestLocator.updateFromScanner(mapScanner);
                }
            });
        }

        // ── 11. Inventory audit (every 100 ticks) ──
        if (inventoryAuditTimer != null && inventoryAuditTimer.tick()) {
            tickSafe("inventory", () -> {
                if (inventoryManager != null) inventoryManager.tick();
            });
        }

        // ── 12. Island graph (every 200 ticks) ──
        if (islandGraphTimer != null && islandGraphTimer.tick()) {
            tickSafe("islandGraph", () -> {
                if (islandGraph != null && mapScanner != null) {
                    islandGraph.rebuild(mapScanner);
                }
            });
        }

        // ── 13. Mistake injection ──
        if (mistakeTimer != null && mistakeTimer.tick()) {
            injectMistake();
        }

        // ── Debug output ──
        if (profile.isDebugMode() && localTickCount % 100 == 0) {
            logDebugStatus();
        }
    }

    /**
     * Wraps a subsystem tick call in a try-catch to prevent one subsystem failure
     * from killing the entire bot tick loop.
     *
     * @param systemName the subsystem name for logging
     * @param runnable   the tick operation
     */
    private void tickSafe(@Nonnull String systemName, @Nonnull Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING,
                    "Error in " + systemName + " tick for bot " + getName(), e);
        }
    }

    /**
     * Injects a random intentional mistake to make the bot feel human.
     */
    private void injectMistake() {
        if (!isAlive()) return;
        LivingEntity entity = getLivingEntity();
        if (entity == null) return;

        double roll = RandomUtil.nextDouble();

        if (roll < 0.35) {
            // Head jitter
            double noiseYaw = (RandomUtil.nextDouble() - 0.5) * 40.0;
            double noisePitch = (RandomUtil.nextDouble() - 0.5) * 20.0;
            if (movementController != null) {
                movementController.setCurrentYaw(movementController.getCurrentYaw() + (float) noiseYaw);
                movementController.setCurrentPitch(movementController.getCurrentPitch() + (float) noisePitch);
            }
        } else if (roll < 0.55) {
            // Brief movement freeze
            if (movementController != null && movementController.isMoving()) {
                movementController.setFrozen(true);
                int freezeTicks = RandomUtil.nextInt(3, 8);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (movementController != null && !destroyed) {
                        movementController.setFrozen(false);
                    }
                }, freezeTicks);
            }
        } else if (roll < 0.70) {
            // Random unnecessary jump
            if (movementController != null) {
                movementController.getJumpController().jump();
            }
        } else if (roll < 0.85) {
            // Accidental sneak toggle
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
            // Sprint stop
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
    }

    /**
     * Logs a debug status summary.
     */
    private void logDebugStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DEBUG] ").append(getName()).append(" tick=").append(localTickCount);

        Location loc = getLocation();
        if (loc != null) {
            sb.append(String.format(" pos=(%.1f,%.1f,%.1f)", loc.getX(), loc.getY(), loc.getZ()));
        }

        if (stateMachine != null) {
            sb.append(" state=").append(stateMachine.getCurrentState().name());
        }

        if (decisionEngine != null) {
            sb.append(" action=").append(decisionEngine.getLastChosenAction().name());
        }

        if (combatEngine != null) {
            sb.append(" combat=").append(combatEngine.isActive());
            if (combatEngine.getCurrentTarget() != null) {
                sb.append(" target=").append(combatEngine.getCurrentTarget().getName());
            }
        }

        if (movementController != null) {
            sb.append(" move=").append(movementController.isMoving());
            sb.append(" sprint=").append(movementController.getSprintController().isSprinting());
        }

        if (threatMap != null) {
            sb.append(" threats=").append(threatMap.getVisibleEnemyCount());
        }

        if (chestLocator != null) {
            sb.append(" chests=").append(chestLocator.getUnlootedCount())
                    .append("/").append(chestLocator.getTotalChestCount());
        }

        if (gamePhaseTracker != null) {
            sb.append(" phase=").append(gamePhaseTracker.getPhase().name());
        }

        plugin.getLogger().info(sb.toString());
    }

    // ─── Accessors ──────────────────────────────────────────────

    @Nonnull public UUID getBotId() { return botId; }
    @Nullable public NPC getNpc() { return npc; }
    @Nonnull public Arena<?> getArena() { return arena; }
    @Nonnull public BotProfile getProfile() { return profile; }
    @Nonnull public BotSkin getSkin() { return skin; }
    @Nonnull public DifficultyProfile getDifficultyProfile() { return profile.getDifficultyProfile(); }
    @Nonnull public SkyWarsTrainerPlugin getPlugin() { return plugin; }
    public boolean isInitialized() { return initialized; }
    public boolean isDestroyed() { return destroyed; }
    public long getLocalTickCount() { return localTickCount; }
    public int getStaggerOffset() { return staggerOffset; }
    public void setStaggerOffset(int offset) { this.staggerOffset = offset; }

    @Nonnull
    public String getName() {
        return skin.getDisplayName();
    }

    @Nullable
    public LivingEntity getLivingEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        return (npc.getEntity() instanceof LivingEntity) ? (LivingEntity) npc.getEntity() : null;
    }

    @Nullable
    public Player getPlayerEntity() {
        if (npc == null || !npc.isSpawned()) return null;
        return (npc.getEntity() instanceof Player) ? (Player) npc.getEntity() : null;
    }

    @Nullable
    public Location getLocation() {
        LivingEntity entity = getLivingEntity();
        return entity != null ? entity.getLocation() : null;
    }

    // ── Subsystem Accessors ──
    @Nullable public MovementController getMovementController() { return movementController; }
    @Nullable public CombatEngine getCombatEngine() { return combatEngine; }
    @Nullable public BridgeEngine getBridgeEngine() { return bridgeEngine; }
    @Nullable public LootEngine getLootEngine() { return lootEngine; }
    @Nullable public InventoryManager getInventoryManager() { return inventoryManager; }
    @Nullable public MapScanner getMapScanner() { return mapScanner; }
    @Nullable public ThreatMap getThreatMap() { return threatMap; }
    @Nullable public IslandGraph getIslandGraph() { return islandGraph; }
    @Nullable public ChestLocator getChestLocator() { return chestLocator; }
    @Nullable public LavaDetector getLavaDetector() { return lavaDetector; }
    @Nullable public VoidDetector getVoidDetector() { return voidDetector; }
    @Nullable public FallDamageEstimator getFallDamageEstimator() { return fallDamageEstimator; }
    @Nullable public GamePhaseTracker getGamePhaseTracker() { return gamePhaseTracker; }
    @Nullable public BotStateMachine getStateMachine() { return stateMachine; }
    @Nullable public DecisionEngine getDecisionEngine() { return decisionEngine; }

    private static String formatLocation(Location loc) {
        return String.format("(%.1f, %.1f, %.1f in %s)",
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "null");
    }

    @Override
    public String toString() {
        return "TrainerBot{name='" + getName() + "', id=" + botId
                + ", alive=" + isAlive()
                + ", state=" + (stateMachine != null ? stateMachine.getCurrentState().name() : "N/A")
                + ", " + profile.getDifficulty().name() + "}";
    }

    // ═══════════════════════════════════════════════════════════
    //  INNER: SkyWarsTrainerTrait
    // ═══════════════════════════════════════════════════════════

    /**
     * Citizens Trait that hooks into the per-tick NPC update cycle.
     * Delegates to the owning TrainerBot's tick method.
     */
    public static class SkyWarsTrainerTrait extends Trait {

        public static final String TRAIT_NAME = "skywarstrainer";
        private TrainerBot ownerBot;

        public SkyWarsTrainerTrait(@Nonnull TrainerBot bot) {
            super(TRAIT_NAME);
            this.ownerBot = bot;
        }

        public SkyWarsTrainerTrait() {
            super(TRAIT_NAME);
            this.ownerBot = null;
        }

        @Override
        public void run() {
            if (ownerBot != null && !ownerBot.isDestroyed()) {
                ownerBot.tick();
            }
        }

        @Override
        public void onSpawn() { }

        @Override
        public void onDespawn() { }

        @Override
        public void onRemove() {
            ownerBot = null;
        }

        @Nullable
        public TrainerBot getOwnerBot() {
            return ownerBot;
        }
    }
}
