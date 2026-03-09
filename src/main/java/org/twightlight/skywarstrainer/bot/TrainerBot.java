package org.twightlight.skywarstrainer.bot;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.ai.engine.*;
import org.twightlight.skywarstrainer.ai.learning.LearningModule;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.awareness.*;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.bridging.movement.BridgeMovementController;
import org.twightlight.skywarstrainer.combat.CombatEngine;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;
import org.twightlight.skywarstrainer.combat.defense.DefensiveActionEngine;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.game.BotChatManager;
import org.twightlight.skywarstrainer.inventory.InventoryManager;
import org.twightlight.skywarstrainer.loot.LootEngine;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.positional.PositionalEngine;
import org.twightlight.skywarstrainer.movement.strategies.ApproachEngine;
import org.twightlight.skywarstrainer.movement.strategies.ApproachTickResult;
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
 * profile (difficulty, personalities, stats), and all AI subsystems.</p>
 *
 * <p>The bot's AI tick loop runs through a custom Citizens {@link Trait}
 * ({@link SkyWarsTrainerTrait}), whose {@code run()} method is called every
 * server tick by Citizens. This trait delegates to TrainerBot's {@link #tick()}
 * method where the staggered tick budget is managed.</p>
 */
public class TrainerBot {

    private final SkyWarsTrainer plugin;
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
    private BotStateMachine stateMachine;
    private DecisionEngine decisionEngine;

    // ═══════════════════════════════════════════════════════════
    //  SUBSYSTEMS
    // ═══════════════════════════════════════════════════════════
    private MovementController movementController;
    private CombatEngine combatEngine;
    private BridgeEngine bridgeEngine;
    private LootEngine lootEngine;
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

    private ApproachEngine approachManager;
    private PositionalEngine positionalManager;
    private DefensiveActionEngine defenseManager;
    private EnemyBehaviorAnalyzer enemyAnalyzer;
    private LearningModule learningModule;
    private BridgeMovementController bridgeMovementController;

    // ── New Tick Timers ──
    private TickTimer positionalTimer;  // 50 ticks
    private TickTimer enemyAnalyzerTimer;  // 20 ticks
    private TickTimer learningModuleTimer;

    /**
     * Creates a new TrainerBot. Does NOT spawn the NPC yet — call {@link #spawn(Location)}.
     *
     * @param plugin  the plugin instance
     * @param arena   the arena this bot belongs to
     * @param profile the bot's profile
     * @param skin    the skin to apply
     */
    public TrainerBot(@Nonnull SkyWarsTrainer plugin, @Nonnull Arena<?> arena,
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
                skin.applyToNPC(npc);
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
     */
    private void initializeAllSubsystems() {
        // ── 1. Awareness (UNCHANGED) ──
        int mapScanInterval = plugin.getConfigManager().getMapScanInterval();
        this.mapScanner = new MapScanner(this, mapScanInterval);
        this.threatMap = new ThreatMap(this);
        this.islandGraph = new IslandGraph(this);
        this.chestLocator = new ChestLocator(this);
        this.lavaDetector = new LavaDetector(this);
        this.voidDetector = new VoidDetector(this);
        this.fallDamageEstimator = new FallDamageEstimator(this);
        this.gamePhaseTracker = new GamePhaseTracker(this);

        // ── 2. Movement (UNCHANGED) ──
        this.movementController = new MovementController(this);

        // ── 3. Combat + Extensions ──
        this.combatEngine = new CombatEngine(this);

        // ── 4. Bridge + Movement Controller ──
        this.bridgeEngine = new BridgeEngine(this);
        this.bridgeMovementController = new BridgeMovementController(this);

        // ── 5. Loot (UNCHANGED) ──
        this.lootEngine = new LootEngine(this);

        // ── 6. Inventory (UNCHANGED) ──
        this.inventoryManager = new InventoryManager(this);

        // ── 7. Advanced Subsystems (Phase 7) ──
        this.approachManager = new ApproachEngine(this);
        this.positionalManager = new PositionalEngine(this);
        this.defenseManager = new DefensiveActionEngine(this);
        this.enemyAnalyzer = new EnemyBehaviorAnalyzer(this);

        // ── 7b. Learning Module ──
        if (plugin.getLearningManager().getLearningConfig() != null && plugin.getLearningManager().getLearningConfig().isEnabled()
                && plugin.getLearningManager().getSharedMemoryBank() != null && plugin.getLearningManager().getSharedReplayBuffer() != null) {
            this.learningModule = new LearningModule(this, plugin.getLearningManager().getSharedMemoryBank(), plugin.getLearningManager().getSharedReplayBuffer());
        }

        // ── 8. AI Brain (UNCHANGED init, but builds enhanced BTs) ──
        this.stateMachine = new BotStateMachine(this);
        this.decisionEngine = new DecisionEngine(this, stateMachine);
        buildBehaviorTrees(); // Now builds the enhanced trees

        // ── 9. Tick Timers (existing + new) ──
        this.voidDetectTimer = new TickTimer(5, 1);
        this.lavaDetectTimer = new TickTimer(15, 3);
        this.chestUpdateTimer = new TickTimer(60, 10);
        this.islandGraphTimer = new TickTimer(200, 40);
        this.gamePhaseTimer = new TickTimer(30, 5);
        this.behaviorTreeTimer = new TickTimer(3, 1);
        this.inventoryAuditTimer = new TickTimer(100, 20);
        this.positionalTimer = new TickTimer(50, 10);      // NEW
        this.enemyAnalyzerTimer = new TickTimer(20, 4);     // NEW
        this.learningModuleTimer = new TickTimer(10, 2);  // every 10 ticks

        mapScanner.forceRescan();
    }

    /**
     * Builds behavior trees for each BotState and registers them with the state machine.
     *
     * <p>Key design decisions in these trees:</p>
     * <ul>
     *   <li>LOOTING: Checks if loot engine is already active OR has unlooted chests.
     *       This prevents resetting a loot-in-progress when the BT re-evaluates from root.</li>
     *   <li>FIGHTING: CombatEngine manages its own target lifecycle. The BT just
     *       ensures a target exists and delegates to the engine.</li>
     *   <li>BRIDGING: Null-safe access to activeStrategy. Bridge destination is
     *       determined before starting the bridge, not after.</li>
     *   <li>FLEEING: Integrates golden apple eating and ender pearl escape.</li>
     * </ul>
     */
    private void buildBehaviorTrees() {
        // ── IDLE: look around, organize inventory ──
        stateMachine.registerTree(BotState.IDLE, new BehaviorTree("IDLE",
                new SequenceNode("idle-sequence",
                        new ActionNode("look-around", bot -> {
                            if (movementController != null) {
                                float yaw = movementController.getCurrentYaw()
                                        + (float) (RandomUtil.nextDouble() - 0.5) * 5.0f;
                                movementController.setCurrentYaw(yaw);
                            }
                            return NodeStatus.SUCCESS;
                        })
                )
        ));

        // ── LOOTING: find chest → pathfind → loot → equip ──
        // The loot engine manages its own state machine (IDLE→MOVING→LOOTING→EQUIPPING).
        // We check if the loot engine is already active OR has unlooted chests.
        stateMachine.registerTree(BotState.LOOTING, new BehaviorTree("LOOTING",
                new SelectorNode("loot-selector",
                        // Branch 1: Loot engine is already in progress — keep ticking it
                        new SequenceNode("loot-in-progress",
                                new ConditionNode("loot-engine-active", bot ->
                                        lootEngine != null && lootEngine.isActive()),
                                new ActionNode("loot-tick-active", bot -> {
                                    lootEngine.tick();
                                    return NodeStatus.RUNNING;
                                })
                        ),
                        // Branch 2: Not active — check if there are chests to loot and start
                        new SequenceNode("loot-start-new",
                                new ConditionNode("has-unlooted-chest", bot ->
                                        chestLocator != null && chestLocator.getUnlootedCount() > 0),
                                new ActionNode("loot-tick-new", bot -> {
                                    if (lootEngine != null) {
                                        lootEngine.tick();
                                    }
                                    return NodeStatus.RUNNING;
                                })
                        )
                        // If neither branch succeeds, the BT returns FAILURE,
                        // which signals the decision engine to re-evaluate
                )
        ));

        // ══ FIGHTING — ENHANCED with engagement patterns ══
        stateMachine.registerTree(BotState.FIGHTING, new BehaviorTree("FIGHTING",
                new SequenceNode("fight-sequence",
                        new ActionNode("combat-tick", bot -> {
                            if (combatEngine == null) return NodeStatus.FAILURE;

                            if (!combatEngine.isActive()) {
                                LivingEntity target = findNearestThreat();
                                if (target != null) {
                                    combatEngine.engage(target);
                                } else {
                                    return NodeStatus.FAILURE;
                                }
                            }

                            if (defenseManager != null) {
                                LivingEntity entity = getLivingEntity();
                                if (entity != null) {
                                    double hp = entity.getHealth() / entity.getMaxHealth();
                                    if (hp < 0.5 && getDifficultyProfile().getRetreatHealSkill() > 0.2) {
                                        // Defense manager will evaluate RetreatHealer
                                        defenseManager.tick(TrainerBot.this);
                                    }
                                }
                            }

                            // Check if bot should flee
                            if (combatEngine.shouldFlee()) {
                                if (decisionEngine != null) decisionEngine.triggerInterrupt();
                            }

                            return NodeStatus.RUNNING;
                        })
                )
        ));

        // ══ BRIDGING — ENHANCED with BridgeMovementController ══
        stateMachine.registerTree(BotState.BRIDGING, new BehaviorTree("BRIDGING",
                new SequenceNode("bridge-sequence",
                        new ConditionNode("has-blocks", bot ->
                                inventoryManager != null
                                        && inventoryManager.getBlockCounter().getTotalBlocks() > 0),
                        new ActionNode("bridge-tick", bot -> {
                            if (bridgeEngine == null) return NodeStatus.FAILURE;

                            if (!bridgeEngine.isActive()) {
                                Location destination = determineBridgeDestination();
                                if (destination == null) return NodeStatus.FAILURE;

                                // Select bridge movement type BEFORE starting bridge
                                if (bridgeMovementController != null) {
                                    bridgeMovementController.selectMovement(destination);
                                }

                                boolean started = bridgeEngine.startBridge(destination,
                                        inventoryManager.getBlockCounter().getTotalBlocks());
                                if (!started) return NodeStatus.FAILURE;

                                String strategyName = bridgeEngine.getActiveStrategy() != null
                                        ? bridgeEngine.getActiveStrategy().getName() : "Unknown";
                                Bukkit.getPluginManager().callEvent(
                                        new org.twightlight.skywarstrainer.api.events.BotBridgeEvent(
                                                TrainerBot.this, strategyName, destination));
                            }

                            // BridgeEngine now handles movement directive integration
                            // (see BridgeEngine changes)
                            BridgeEngine.BridgeTickResult result = bridgeEngine.tick();

                            // Check for incoming threats while bridging
                            if (threatMap != null && threatMap.getVisibleEnemyCount() > 0) {
                                ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                                if (nearest != null && nearest.currentPosition != null) {
                                    LivingEntity entity = getLivingEntity();
                                    if (entity != null) {
                                        double dist = entity.getLocation().distance(nearest.currentPosition);
                                        if (dist < 6.0) {
                                            // Enemy is very close — interrupt bridging for combat
                                            bridgeEngine.stopBridge();
                                            if (decisionEngine != null) decisionEngine.triggerInterrupt();
                                            return NodeStatus.SUCCESS;
                                        }
                                    }
                                }
                            }

                            switch (result) {
                                case COMPLETE:
                                case TIMEOUT:
                                case OUT_OF_BLOCKS:
                                    return NodeStatus.SUCCESS;
                                case FAILED:
                                    return NodeStatus.FAILURE;
                                default:
                                    return NodeStatus.RUNNING;
                            }
                        })
                )
        ));

        // ── FLEEING (enhanced — check for retreat-heal re-engage) ──
        stateMachine.registerTree(BotState.FLEEING, new BehaviorTree("FLEEING",
                new ActionNode("flee-tick", bot -> {
                    LivingEntity entity = getLivingEntity();
                    if (entity == null) return NodeStatus.FAILURE;
                    MovementController mc = getMovementController();
                    if (mc == null) return NodeStatus.FAILURE;

                    mc.getSprintController().startSprinting();
                    ThreatMap tm = getThreatMap();
                    if (tm != null && !tm.getVisibleThreats().isEmpty()) {
                        ThreatMap.ThreatEntry nearest = tm.getNearestThreat();
                        if (nearest != null && nearest.currentPosition != null) {
                            Location botLoc = entity.getLocation();
                            Location threatLoc = nearest.currentPosition;
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

                    if (inventoryManager != null) {
                        inventoryManager.getFoodHandler().tick();
                    }

                    double healthFrac = entity.getHealth() / entity.getMaxHealth();

                    // Phase 7: Retreat-heal re-engage check
                    // If health recovers to 70%+ AND retreatHealSkill is high,
                    // re-engage instead of staying in FLEE
                    double retreatSkill = getDifficultyProfile().getRetreatHealSkill();
                    if (healthFrac > 0.7 && retreatSkill > 0.3) {
                        // Re-engage! Trigger interrupt — FIGHT score will be recalculated
                        if (decisionEngine != null) decisionEngine.triggerInterrupt();
                        return NodeStatus.SUCCESS;
                    }

                    // Original: stop fleeing if health recovers past threshold * 1.5
                    if (healthFrac > getDifficultyProfile().getFleeHealthThreshold() * 1.5) {
                        return NodeStatus.SUCCESS;
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ── ENCHANTING ──
        stateMachine.registerTree(BotState.ENCHANTING, new BehaviorTree("ENCHANTING",
                new ActionNode("enchant-tick", bot -> {
                    if (inventoryManager != null) {
                        inventoryManager.tick();
                    }
                    return NodeStatus.RUNNING;
                })
        ));

        // ══ HUNTING — ENHANCED with ApproachManager ══
        stateMachine.registerTree(BotState.HUNTING, new BehaviorTree("HUNTING",
                new SequenceNode("hunt-sequence",
                        // Step 1: Find target
                        new ActionNode("find-target", bot -> {
                            LivingEntity target = findNearestThreat();
                            if (target == null) return NodeStatus.FAILURE;
                            return NodeStatus.SUCCESS;
                        }),
                        // Step 2: Navigate to target (same island or different)
                        new SelectorNode("hunt-navigate",
                                // Branch A: Target on same island — pathfind directly
                                new SequenceNode("hunt-same-island",
                                        new ConditionNode("on-same-island", bot -> {
                                            LivingEntity target = findNearestThreat();
                                            if (target == null || islandGraph == null) return false;
                                            Location botLoc = getLocation();
                                            if (botLoc == null) return false;
                                            IslandGraph.Island botIsland = islandGraph.getIslandAt(botLoc);
                                            IslandGraph.Island targetIsland = islandGraph.getIslandAt(target.getLocation());
                                            return botIsland != null && botIsland.equals(targetIsland);
                                        }),
                                        new ActionNode("pathfind-to-target", bot -> {
                                            LivingEntity target = findNearestThreat();
                                            if (target == null) return NodeStatus.FAILURE;
                                            LivingEntity entity = getLivingEntity();
                                            if (entity == null) return NodeStatus.FAILURE;

                                            double distance = entity.getLocation().distance(target.getLocation());
                                            if (distance <= 4.0) {
                                                if (decisionEngine != null) decisionEngine.triggerInterrupt();
                                                return NodeStatus.SUCCESS;
                                            }

                                            MovementController mc = getMovementController();
                                            if (mc != null) {
                                                mc.getSprintController().startSprinting();
                                                mc.setMoveTarget(target.getLocation());
                                                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
                                            }
                                            return NodeStatus.RUNNING;
                                        })
                                ),
                                // Branch B: Target on different island — use ApproachManager
                                new SequenceNode("hunt-approach",
                                        new ActionNode("approach-tick", bot -> {
                                            if (approachManager == null) return NodeStatus.FAILURE;

                                            if (!approachManager.isActive()) {
                                                // Start a new approach
                                                LivingEntity target = findNearestThreat();
                                                if (target == null) return NodeStatus.FAILURE;
                                                boolean started = approachManager.startApproach(target);
                                                if (!started) {
                                                    // Fallback: use basic bridge
                                                    return NodeStatus.FAILURE;
                                                }
                                            }

                                            ApproachTickResult result = approachManager.tick();
                                            switch (result) {
                                                case ARRIVED:
                                                    // We've reached the target's island — trigger re-eval for FIGHT
                                                    if (decisionEngine != null) decisionEngine.triggerInterrupt();
                                                    return NodeStatus.SUCCESS;
                                                case FAILED:
                                                case INTERRUPTED:
                                                    return NodeStatus.FAILURE;
                                                default:
                                                    return NodeStatus.RUNNING;
                                            }
                                        })
                                )
                        )
                )
        ));

        // ══ CAMPING — ENHANCED with DefensiveActionManager ══
        stateMachine.registerTree(BotState.CAMPING, new BehaviorTree("CAMPING",
                new ParallelNode("camp-parallel", ParallelNode.Policy.ONE_SUCCESS, // Succeed if ANY child succeeds
                        // Branch 1: Existing watch behavior
                        new ActionNode("camp-watch", bot -> {
                            if (movementController != null) {
                                float yaw = movementController.getCurrentYaw() + 2.0f;
                                movementController.setCurrentYaw(yaw);
                            }

                            if (threatMap != null && threatMap.getVisibleEnemyCount() > 0) {
                                ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                                if (nearest != null) {
                                    double distance = 999;
                                    LivingEntity entity = getLivingEntity();
                                    if (entity != null && nearest.currentPosition != null) {
                                        distance = entity.getLocation().distance(nearest.currentPosition);
                                    }
                                    if (distance < 15) {
                                        if (decisionEngine != null) decisionEngine.triggerInterrupt();
                                        return NodeStatus.SUCCESS;
                                    }
                                }
                            }
                            return NodeStatus.RUNNING;
                        }),
                        new CooldownDecorator(
                                "defense-cooldown",
                                new ActionNode("defense-tick", bot -> {
                                    if (defenseManager != null) {
                                        defenseManager.tick(TrainerBot.this);
                                    }
                                    return NodeStatus.RUNNING;
                                }),
                                40
                        )
                )
        ));


        // ── END_GAME ──
        stateMachine.registerTree(BotState.END_GAME, new BehaviorTree("END_GAME",
                new ActionNode("end-game-tick", bot -> {
                    if (movementController != null && RandomUtil.chance(0.05)) {
                        movementController.getJumpController().jump();
                    }
                    return NodeStatus.RUNNING;
                })
        ));
    }

    /**
     * Helper to find the nearest visible threat entity.
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
     */
    @Nullable
    private Location determineBridgeDestination() {
        if (decisionEngine == null) return null;

        DecisionEngine.BotAction lastAction = decisionEngine.getLastChosenAction();

        switch (lastAction) {
            case BRIDGE_TO_MID:
                if (islandGraph != null) {
                    IslandGraph.Island midIsland = islandGraph.getMidIsland();
                    if (midIsland != null && midIsland.center != null) {
                        return midIsland.center;
                    }
                }
                // Fallback: bridge toward world center
                LivingEntity entity = getLivingEntity();
                if (entity != null) {
                    return new Location(entity.getWorld(), 0, entity.getLocation().getY(), 0);
                }
                return null;

            case BRIDGE_TO_PLAYER:
                if (threatMap != null) {
                    ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                    if (nearest != null && nearest.currentPosition != null) {
                        return nearest.currentPosition;
                    }
                }
                return null;

            default:
                // For non-bridge actions that somehow ended up in BRIDGING state,
                // try bridging to mid as a fallback
                if (islandGraph != null) {
                    IslandGraph.Island midIsland = islandGraph.getMidIsland();
                    if (midIsland != null && midIsland.center != null) {
                        return midIsland.center;
                    }
                }
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

        // Stop bridge if active
        if (bridgeEngine != null && bridgeEngine.isActive()) {
            bridgeEngine.stopBridge();
        }

        // Clear chat cooldowns for this bot
        BotChatManager.clearCooldown(botId);

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
        approachManager = null;
        positionalManager = null;
        defenseManager = null;
        enemyAnalyzer = null;
        bridgeMovementController = null;
        learningModule = null;

        if (npc != null) {
            if (npc.isSpawned()) {
                npc.despawn();
            }
            npc.destroy();
            npc = null;
        }

        Bukkit.getPluginManager().callEvent(
                new org.twightlight.skywarstrainer.api.events.BotDespawnEvent(this));

        if (plugin.getConfigManager().isDebugMode()) {
            plugin.getLogger().info("[DEBUG] Bot destroyed: " + skin.getDisplayName());
        }
    }

    /**
     * Returns true if this bot is alive.
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

        // ── 13. Positional Strategy (every 50 ticks) — NEW ──
        if (positionalTimer != null && positionalTimer.tick()) {
            tickSafe("positional", () -> {
                if (positionalManager != null) positionalManager.tick();
            });
        }

        // ── 14. Enemy Behavior Analysis (every 20 ticks) — NEW ──
        if (enemyAnalyzerTimer != null && enemyAnalyzerTimer.tick()) {
            tickSafe("enemyAnalysis", () -> {
                if (enemyAnalyzer != null) enemyAnalyzer.tick();
            });
        }

        // ── 14b. Learning Module (every 10 ticks) ──
        if (learningModuleTimer != null && learningModuleTimer.tick()) {
            tickSafe("learning", () -> {
                if (learningModule != null) learningModule.tick();
            });
        }

        // ── 15. Defense Manager interrupt check (every tick) — NEW ──
        // Check if enemy is bridging toward us — trigger defensive action
        tickSafe("defenseInterrupt", () -> {
            if (defenseManager != null && threatMap != null
                    && stateMachine != null
                    && stateMachine.getCurrentState() != BotState.FIGHTING
                    && stateMachine.getCurrentState() != BotState.FLEEING) {
                // Check if any enemy is bridging toward us (velocity + direction check)
                if (threatMap.getVisibleEnemyCount() > 0) {
                    ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                    if (nearest != null && nearest.currentPosition != null) {
                        Location botLoc = getLocation();
                        if (botLoc != null) {
                            double dist = botLoc.distance(nearest.currentPosition);
                            // If enemy is approaching on a bridge (10-25 block range, moving toward us)
                            if (dist > 8 && dist < 25 && nearest.getHorizontalSpeed() > 0.15) {
                                defenseManager.forceEvaluate(TrainerBot.this);
                            }
                        }
                    }
                }
            }
        });

        // ── 16. Mistake injection ──
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
    @Nonnull public SkyWarsTrainer getPlugin() { return plugin; }
    public boolean isInitialized() { return initialized; }
    public boolean isDestroyed() { return destroyed; }
    public long getLocalTickCount() { return localTickCount; }
    public int getStaggerOffset() { return staggerOffset; }
    public void setStaggerOffset(int offset) { this.staggerOffset = offset; }

    @Nonnull
    public String getName() { return skin.getDisplayName(); }

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
    @Nullable public ApproachEngine getApproachManager() { return approachManager; }
    @Nullable public PositionalEngine getPositionalManager() { return positionalManager; }
    @Nullable public DefensiveActionEngine getDefenseManager() { return defenseManager; }
    @Nullable public EnemyBehaviorAnalyzer getEnemyAnalyzer() { return enemyAnalyzer; }
    @Nullable public BridgeMovementController getBridgeMovementController() { return bridgeMovementController; }
    @Nullable public LearningModule getLearningModule() { return learningModule; }

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
