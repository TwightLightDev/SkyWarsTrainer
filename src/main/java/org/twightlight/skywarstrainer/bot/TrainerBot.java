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
import org.twightlight.skywarstrainer.ai.learning.LearningEngine;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.awareness.*;
import org.twightlight.skywarstrainer.bridging.BridgeEngine;
import org.twightlight.skywarstrainer.bridging.movement.BridgeMovementController;
import org.twightlight.skywarstrainer.combat.CombatEngine;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;
import org.twightlight.skywarstrainer.combat.defense.DefensiveEngine;
import org.twightlight.skywarstrainer.config.ConfigManager;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.game.BotChatManager;
import org.twightlight.skywarstrainer.inventory.EnchantmentHandler;
import org.twightlight.skywarstrainer.inventory.FoodHandler;
import org.twightlight.skywarstrainer.inventory.InventoryEngine;
import org.twightlight.skywarstrainer.inventory.PotionHandler;
import org.twightlight.skywarstrainer.loot.LootEngine;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.positional.PositionalEngine;
import org.twightlight.skywarstrainer.movement.strategies.ApproachEngine;
import org.twightlight.skywarstrainer.movement.strategies.ApproachTickResult;
import org.twightlight.skywarstrainer.util.RandomUtil;
import org.twightlight.skywarstrainer.util.TickTimer;
import org.twightlight.skywarstrainer.ai.strategy.StrategyPlanner;
import org.twightlight.skywarstrainer.awareness.ThreatPredictor;

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
    private InventoryEngine inventoryEngine;

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

    private ApproachEngine approachEngine;
    private PositionalEngine positionalEngine;
    private DefensiveEngine defenseEngine;
    private EnemyBehaviorAnalyzer enemyAnalyzer;
    private LearningEngine learningEngine;
    private BridgeMovementController bridgeMovementController;

    // ═══════════════════════════════════════════════════════════
    //  STRATEGY & THREAT PREDICTION (NEW)
    // ═══════════════════════════════════════════════════════════
    private StrategyPlanner strategyPlanner;
    private ThreatPredictor threatPredictor;

    // ── New Tick Timers ──
    private TickTimer strategyPlannerTimer;
    private TickTimer threatPredictorTimer;

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
        profile.setOwnerBot(this);
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
        // ── 1-8: UNCHANGED — same as current code ──
        // (all subsystem creation from mapScanner through decisionEngine stays identical)

        int mapScanInterval = plugin.getConfigManager().getMapScanInterval();
        this.mapScanner = new MapScanner(this, mapScanInterval);
        this.threatMap = new ThreatMap(this);
        this.islandGraph = new IslandGraph(this);
        this.chestLocator = new ChestLocator(this);
        this.lavaDetector = new LavaDetector(this);
        this.voidDetector = new VoidDetector(this);
        this.fallDamageEstimator = new FallDamageEstimator(this);
        this.gamePhaseTracker = new GamePhaseTracker(this);
        this.movementController = new MovementController(this);
        this.combatEngine = new CombatEngine(this);
        this.bridgeEngine = new BridgeEngine(this);
        this.bridgeMovementController = new BridgeMovementController(this);
        this.lootEngine = new LootEngine(this);
        this.inventoryEngine = new InventoryEngine(this);
        this.approachEngine = new ApproachEngine(this);
        this.positionalEngine = new PositionalEngine(this);
        this.defenseEngine = new DefensiveEngine(this);
        this.enemyAnalyzer = new EnemyBehaviorAnalyzer(this);

        if (plugin.getLearningManager().getLearningConfig() != null && plugin.getLearningManager().getLearningConfig().isEnabled()
                && plugin.getLearningManager().getSharedMemoryBank() != null && plugin.getLearningManager().getSharedReplayBuffer() != null) {
            this.learningEngine = new LearningEngine(this, plugin.getLearningManager().getSharedMemoryBank(), plugin.getLearningManager().getSharedReplayBuffer());
        }

        // ── Strategy Planner (NEW) ──
        if (profile.getDifficultyProfile().isStrategyPlanningEnabled()) {
            this.strategyPlanner = new StrategyPlanner(this);
        }

        // ── Threat Predictor (NEW) ──
        if (profile.getDifficultyProfile().isThreatPredictionEnabled()) {
            this.threatPredictor = new ThreatPredictor(this);
        }


        this.stateMachine = new BotStateMachine(this);
        this.decisionEngine = new DecisionEngine(this, stateMachine);

        stateMachine.addTransitionListener(this::onStateTransition);

        buildBehaviorTrees();

        // ── 9. Tick Timers — [FIX] Now reads ALL intervals from config.yml timers section ──
        // Previously these were all hardcoded (5, 15, 60, 200, 30, 3, 100, 50, 20).
        // Now they read from timers.* config paths via ConfigManager getters.
        ConfigManager cfg = plugin.getConfigManager();
        int voidInterval = cfg.getTimerVoidDetectInterval();
        int lavaInterval = cfg.getTimerLavaDetectInterval();
        int chestInterval = cfg.getTimerChestUpdateInterval();
        int islandInterval = cfg.getTimerIslandGraphInterval();
        int phaseInterval = cfg.getTimerGamePhaseInterval();
        int btInterval = cfg.getTimerBehaviorTreeInterval();
        int invInterval = cfg.getTimerInventoryAuditInterval();
        int posInterval = cfg.getTimerPositionalInterval();
        int enemyInterval = cfg.getTimerEnemyAnalyzerInterval();
        int strategyInterval = cfg.getTimerStrategyPlannerInterval();
        int threatInterval = cfg.getTimerThreatPredictorInterval();

        this.voidDetectTimer = new TickTimer(voidInterval, 1 + (staggerOffset % Math.max(1, voidInterval)));
        this.lavaDetectTimer = new TickTimer(lavaInterval, 3 + (staggerOffset % Math.max(1, lavaInterval)));
        this.chestUpdateTimer = new TickTimer(chestInterval, 10 + (staggerOffset % Math.max(1, chestInterval)));
        this.islandGraphTimer = new TickTimer(islandInterval, 40 + (staggerOffset % Math.max(1, islandInterval)));
        this.gamePhaseTimer = new TickTimer(phaseInterval, 5 + (staggerOffset % Math.max(1, phaseInterval)));
        this.behaviorTreeTimer = new TickTimer(btInterval, 1 + (staggerOffset % Math.max(1, btInterval)));
        this.inventoryAuditTimer = new TickTimer(invInterval, 20 + (staggerOffset % Math.max(1, invInterval)));
        this.positionalTimer = new TickTimer(posInterval, 10 + (staggerOffset % Math.max(1, posInterval)));
        this.enemyAnalyzerTimer = new TickTimer(enemyInterval, 4 + (staggerOffset % Math.max(1, enemyInterval)));
        this.learningModuleTimer = new TickTimer(10, 2 + (staggerOffset % 10));
        this.strategyPlannerTimer = new TickTimer(strategyInterval, 15 + (staggerOffset % Math.max(1, strategyInterval)));
        this.threatPredictorTimer = new TickTimer(threatInterval, 7 + (staggerOffset % Math.max(1, threatInterval)));

        mapScanner.forceRescan();
    }


    // [FIX-A1/A4/D3/E1] Subsystem cleanup callback on state transitions.
    // This is the central fix for bugs A1, A4, D3, and E1.
    // When the state machine transitions, subsystems that "own" behavior in the
    // old state must release control so they don't conflict with the new state.
    private void onStateTransition(@Nonnull BotState oldState, @Nonnull BotState newState) {
        // [FIX-D3] Disengage combat when leaving FIGHTING state.
        // Without this, CombatEngine remains active=true and keeps ticking
        // in TrainerBot.tick() step 3, causing the bot to fight while looting/bridging.
        if (oldState == BotState.FIGHTING && newState != BotState.FIGHTING) {
            if (combatEngine != null && combatEngine.isActive()) {
                combatEngine.disengage();
            }
        }

        if (movementController != null) {
            movementController.resetAuthority();
        }

        // [FIX-E1] Cancel active defensive behavior on ANY state change.
        // Without this, a BridgeCutter active during CAMPING persists into FIGHTING,
        // and the FIGHTING BT's defensive tick runs the stale BridgeCutter instead
        // of evaluating RetreatHealer.
        // Also fixes A1: RetreatHealer is cancelled when leaving FIGHTING, preventing
        // movement oscillation from a retreating bot that should now be looting.
        if (defenseEngine != null) {
            defenseEngine.cancel();
        }

        // [FIX-A4] Cancel orphaned approach when leaving HUNTING.
        // Without this, ApproachEngine.isActive() returns true when re-entering HUNTING,
        // causing the BT to resume a stale approach with outdated target position.
        if (oldState == BotState.HUNTING && newState != BotState.HUNTING) {
            if (approachEngine != null && approachEngine.isActive()) {
                approachEngine.cancelApproach();
            }
        }

        // [FIX-A2 partial] Stop bridge when leaving BRIDGING state.
        // BridgeEngine.stopBridge() already calls mc.setSneaking(false), mc.setMovingBackward(false),
        // and bridgeMovementController.reset(). This ensures no stale bridge state persists.
        if (oldState == BotState.BRIDGING && newState != BotState.BRIDGING) {
            if (bridgeEngine != null && bridgeEngine.isActive()) {
                bridgeEngine.stopBridge();
            }
        }

        // [FIX-C3 partial] Ensure sneaking is off when entering combat states.
        // If a bridge was interrupted or sneaking was left on by any system,
        // clear it so the bot doesn't fight at 30% speed.
        if (newState == BotState.FIGHTING || newState == BotState.FLEEING || newState == BotState.HUNTING) {
            if (movementController != null && movementController.isSneaking()) {
                movementController.setSneaking(false);
            }
        }
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

                            if (defenseEngine != null) {
                                LivingEntity entity = getLivingEntity();
                                if (entity != null) {
                                    double hp = entity.getHealth() / entity.getMaxHealth();
                                    if (hp < 0.5 && getDifficultyProfile().getRetreatHealSkill() > 0.2) {
                                        defenseEngine.tick(TrainerBot.this);
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
                                inventoryEngine != null
                                        && inventoryEngine.getBlockCounter().getTotalBlocks() > 0),
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
                                        inventoryEngine.getBlockCounter().getTotalBlocks());
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
                                mc.setMoveTarget(fleeTarget, MovementController.MovementAuthority.FLEE);
                            }
                        }
                    }

                    if (inventoryEngine != null) {
                        inventoryEngine.getFoodHandler().tick();
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

        // ══ ENCHANTING — FIXED (issue #10) ══
        // Was: just called inventoryManager.tick() and returned RUNNING forever.
        // Now: pathfinds to enchanting table, interacts with it, applies enchantment,
        //      then completes with SUCCESS so the DecisionEngine can re-evaluate.
        stateMachine.registerTree(BotState.ENCHANTING, new BehaviorTree("ENCHANTING",
                new SequenceNode("enchant-sequence",
                        // Step 1: Verify enchanting is worthwhile
                        new ConditionNode("should-enchant", bot -> {
                            if (inventoryEngine == null) return false;
                            return inventoryEngine.getEnchantmentHandler().shouldEnchant();
                        }),
                        // Step 2: Find and pathfind to enchanting table
                        new ActionNode("pathfind-to-enchant-table", bot -> {
                            LivingEntity entity = getLivingEntity();
                            if (entity == null) return NodeStatus.FAILURE;
                            Location botLoc = entity.getLocation();
                            if (botLoc == null || botLoc.getWorld() == null) return NodeStatus.FAILURE;


                            Location tableLocation = null;
                            MapScanner scanner = getMapScanner();
                            if (scanner != null) {
                                tableLocation = scanner.getNearestEnchantingTable(botLoc);
                            }
                            if (tableLocation == null) {
                                int radius = 6; // Reduced from 10
                                double closestDist = Double.MAX_VALUE;
                                for (int x = -radius; x <= radius; x++) {
                                    for (int y = -3; y <= 3; y++) {
                                        for (int z = -radius; z <= radius; z++) {
                                            Location check = botLoc.clone().add(x, y, z);
                                            if (check.getBlock().getType() == org.bukkit.Material.ENCHANTMENT_TABLE) {
                                                double dist = botLoc.distanceSquared(check);
                                                if (dist < closestDist) {
                                                    closestDist = dist;
                                                    tableLocation = check;
                                                }
                                            }
                                        }
                                    }
                                }
                            }


                            if (tableLocation == null) return NodeStatus.FAILURE;

                            double distance = botLoc.distance(tableLocation);
                            if (distance <= 2.5) {
                                // Close enough to enchant — look at the table
                                if (movementController != null) {
                                    movementController.setLookTarget(tableLocation.clone().add(0.5, 0.75, 0.5));
                                }
                                return NodeStatus.SUCCESS;
                            }

                            // Pathfind toward the table
                            if (movementController != null) {
                                // Target one block adjacent to the table (not inside it)
                                Location walkTarget = tableLocation.clone().add(0.5, 0, 1.5);
                                movementController.setMoveTarget(walkTarget, MovementController.MovementAuthority.AI_GENERAL);
                                movementController.setLookTarget(tableLocation.clone().add(0.5, 0.75, 0.5));
                            }
                            return NodeStatus.RUNNING;
                        }),
                        // Step 3: Apply enchantment (simulated — NPCs can't open GUIs)
                        new ActionNode("apply-enchantment", bot -> {
                            Player player = getPlayerEntity();
                            if (player == null) return NodeStatus.FAILURE;
                            if (inventoryEngine == null) return NodeStatus.FAILURE;

                            EnchantmentHandler handler = inventoryEngine.getEnchantmentHandler();
                            if (!handler.shouldEnchant()) return NodeStatus.SUCCESS; // Nothing to enchant

                            int level = player.getLevel();
                            if (level < 1) return NodeStatus.FAILURE;

                            // Find the item to enchant (sword in slot 0)
                            org.bukkit.inventory.ItemStack weapon = player.getInventory().getItem(0);
                            if (weapon == null || !weapon.getType().name().endsWith("_SWORD")
                                    || !weapon.getEnchantments().isEmpty()) {
                                // Try armor pieces
                                boolean enchantedSomething = false;
                                for (org.bukkit.inventory.ItemStack armor : player.getInventory().getArmorContents()) {
                                    if (armor != null && armor.getEnchantments().isEmpty()) {
                                        inventoryEngine.getEnchantmentHandler().applySimulatedEnchant(armor, level);
                                        enchantedSomething = true;
                                        break;
                                    }
                                }
                                if (!enchantedSomething) return NodeStatus.SUCCESS;
                            } else {
                                inventoryEngine.getEnchantmentHandler().applySimulatedEnchant(weapon, level);
                            }

                            // Consume levels (simplified: 1-3 levels depending on enchant tier)
                            int cost = Math.min(level, RandomUtil.nextInt(1, 3));
                            player.setLevel(level - cost);

                            if (getProfile().isDebugMode()) {
                                plugin.getLogger().info("[DEBUG] " + getName()
                                        + " enchanted an item (cost " + cost + " levels)");
                            }

                            return NodeStatus.SUCCESS;
                        })
                )
        ));

        // ══ CONSUMING — NEW (fixes #13: HEAL/EAT_FOOD/DRINK_POTION) ══
        // These actions were mapped to IDLE which did nothing useful.
        // Now they map to CONSUMING which actively triggers eating/drinking.
        stateMachine.registerTree(BotState.CONSUMING, new BehaviorTree("CONSUMING",
                new ActionNode("consume-tick", bot -> {
                    if (inventoryEngine == null) return NodeStatus.FAILURE;
                    Player player = getPlayerEntity();
                    if (player == null) return NodeStatus.FAILURE;

                    FoodHandler foodHandler = inventoryEngine.getFoodHandler();
                    PotionHandler potionHandler = inventoryEngine.getPotionHandler();

                    // Determine what to consume based on the chosen action
                    DecisionEngine.BotAction lastAction = decisionEngine != null
                            ? decisionEngine.getLastChosenAction() : null;

                    if (lastAction == DecisionEngine.BotAction.DRINK_POTION) {
                        // Force potion tick regardless of cooldown/RNG
                        potionHandler.tick();
                        return NodeStatus.SUCCESS; // One-shot: potion consumption is instant
                    }

                    if (lastAction == DecisionEngine.BotAction.HEAL) {
                        // Prefer golden apple if available, otherwise regular food
                        if (foodHandler.hasGoldenApple()) {
                            // Find and eat golden apple
                            int gaSlot = inventoryEngine.getFoodHandler().findGoldenAppleSlot(player);
                            if (gaSlot >= 0) {
                                player.getInventory().setHeldItemSlot(gaSlot < 9 ? gaSlot : 0);
                                if (gaSlot >= 9) {
                                    // Move to hotbar
                                    org.bukkit.inventory.ItemStack ga = player.getInventory().getItem(gaSlot);
                                    org.bukkit.inventory.ItemStack swap = player.getInventory().getItem(0);
                                    player.getInventory().setItem(0, ga);
                                    player.getInventory().setItem(gaSlot, swap);
                                    player.getInventory().setHeldItemSlot(0);
                                }

                                if (!foodHandler.isEating())
                                    org.twightlight.skywarstrainer.util.NMSHelper.useItem(player, true);
                                // Golden apple eating takes 32 ticks
                                return NodeStatus.RUNNING;
                            }
                        }
                        // Fall through to regular food
                        foodHandler.tick();
                        if (foodHandler.isEating()) {
                            return NodeStatus.RUNNING;
                        }
                        return NodeStatus.SUCCESS;
                    }

                    if (lastAction == DecisionEngine.BotAction.EAT_FOOD) {
                        foodHandler.tick();
                        if (foodHandler.isEating()) {
                            return NodeStatus.RUNNING;
                        }
                        return NodeStatus.SUCCESS;
                    }

                    // Default: try food then potion
                    foodHandler.tick();
                    if (foodHandler.isEating()) {
                        return NodeStatus.RUNNING;
                    }
                    potionHandler.tick();
                    return NodeStatus.SUCCESS;
                })
        ));

        // ══ ORGANIZING — NEW (fixes #11: ORGANIZE_INVENTORY) ══
        // Was mapped to IDLE which just did random head movements.
        // Now actually performs a full inventory audit and completes.
        stateMachine.registerTree(BotState.ORGANIZING, new BehaviorTree("ORGANIZING",
                new ActionNode("organize-tick", bot -> {
                    if (inventoryEngine == null) return NodeStatus.FAILURE;
                    Player player = getPlayerEntity();
                    if (player == null) return NodeStatus.FAILURE;

                    // Perform a full inventory audit: equip best armor, select best
                    // sword, organize hotbar, count blocks
                    inventoryEngine.performFullAudit(player);

                    if (getProfile().isDebugMode()) {
                        plugin.getLogger().info("[DEBUG] " + getName()
                                + " organized inventory (full audit)");
                    }

                    // Organizing is a one-shot action — SUCCESS lets the DecisionEngine
                    // re-evaluate on the next cycle
                    return NodeStatus.SUCCESS;
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
                                                mc.setMoveTarget(target.getLocation(), MovementController.MovementAuthority.HUNTING);
                                                mc.setLookTarget(target.getLocation().add(0, 1.0, 0));
                                            }
                                            return NodeStatus.RUNNING;
                                        })
                                ),
                                // Branch B: Target on different island — use ApproachManager
                                new SequenceNode("hunt-approach",
                                        new ActionNode("approach-tick", bot -> {
                                            if (approachEngine == null) return NodeStatus.FAILURE;

                                            if (!approachEngine.isActive()) {
                                                // Start a new approach
                                                LivingEntity target = findNearestThreat();
                                                if (target == null) return NodeStatus.FAILURE;
                                                boolean started = approachEngine.startApproach(target);
                                                if (!started) {
                                                    // Fallback: use basic bridge
                                                    return NodeStatus.FAILURE;
                                                }
                                            }

                                            ApproachTickResult result = approachEngine.tick();
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
                                    if (defenseEngine != null) {
                                        defenseEngine.tick(TrainerBot.this);
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
        inventoryEngine = null;
        stateMachine = null;
        decisionEngine = null;
        approachEngine = null;
        positionalEngine = null;
        defenseEngine = null;
        enemyAnalyzer = null;
        bridgeMovementController = null;
        learningEngine = null;
        strategyPlanner = null;
        threatPredictor = null;

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

        tickSafe("fallCheck", () -> {
            LivingEntity entity = getLivingEntity();
            if (entity != null && entity.getVelocity().getY() < -0.5) {
                DifficultyProfile diff = getDifficultyProfile();
                if (diff.getWaterBucketMLG() > 0.1 && inventoryEngine != null) {
                    inventoryEngine.getUtilityItemHandler().tryWaterBucketMLG();
                }
            }
        });

        tickSafe("utilityItemTick", () -> {
            if (inventoryEngine != null) {
                inventoryEngine.getUtilityItemHandler().tick();
            }
        });

        // ── 1. Movement (every tick) ──
        tickSafe("movement", () -> {
            if (movementController != null) movementController.tick();
        });

        // ── 2. Threat tracking (every tick) ──
        tickSafe("threatMap", () -> {
            if (threatMap != null) threatMap.tick();
        });

        // ── 3. Combat Engine (every tick when active AND in FIGHTING state) ──
        // [FIX-D3] Guard: only tick combat engine if actually in FIGHTING state.
        // The transition listener (onStateTransition) calls disengage() when leaving
        // FIGHTING, so isActive() should be false. This guard is belt-and-suspenders
        // in case of edge cases where the transition fires but disengage wasn't called.
        tickSafe("combat", () -> {
            if (combatEngine != null && combatEngine.isActive()
                    && stateMachine != null && stateMachine.getCurrentState() == BotState.FIGHTING) {
                combatEngine.tick();
            }
        });

        // ── 4. Decision Engine (every N ticks or on interrupt) ──
        tickSafe("decision", () -> {
            if (decisionEngine != null) decisionEngine.tick();
        });

        // Step 5: Behavior Tree
        if (behaviorTreeTimer != null && behaviorTreeTimer.tick()) {
            tickSafe("behaviorTree", () -> {
                if (stateMachine != null) {
                    NodeStatus status = stateMachine.tick();
                    if (status != NodeStatus.RUNNING) {
                        // [FIX] Stop all movement when BT completes/fails to prevent
                        // micro-lurch from stale move targets during the gap between
                        // BT completion and the next DecisionEngine evaluation.
                        if (movementController != null) {
                            movementController.stopAll();
                            movementController.resetAuthority();
                        }
                        if (decisionEngine != null) {
                            decisionEngine.triggerInterrupt();
                        }
                    }
                }
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
                if (inventoryEngine != null) inventoryEngine.tick();
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
                if (positionalEngine != null) positionalEngine.tick();
            });
        }

        // ── 14. Enemy Behavior Analysis (every 20 ticks) — NEW ──
        if (enemyAnalyzerTimer != null && enemyAnalyzerTimer.tick()) {
            tickSafe("enemyAnalysis", () -> {
                if (enemyAnalyzer != null) enemyAnalyzer.tick();
            });
        }

        // ── 15. Threat Predictor (every ~10 ticks) ──
        if (threatPredictorTimer != null && threatPredictorTimer.tick()) {
            tickSafe("threatPredictor", () -> {
                if (threatPredictor != null) threatPredictor.tick();
            });
        }

        // ── 16. Strategy Planner (every ~100 ticks) ──
        if (strategyPlannerTimer != null && strategyPlannerTimer.tick()) {
            tickSafe("strategyPlanner", () -> {
                if (strategyPlanner != null) strategyPlanner.tick();
            });
        }

        // ── 17. Learning Module (every ~10 ticks) ──
        if (learningModuleTimer != null && learningModuleTimer.tick()) {
            tickSafe("learning", () -> {
                if (learningEngine != null) learningEngine.tick();
            });
        }

        // ── 18. Defense Manager interrupt check (every tick) — NEW ──
        // Check if enemy is bridging toward us — trigger defensive action
        // [FIX-A5/E1] Also exclude CAMPING state to prevent double-ticking DefensiveEngine.
        // CAMPING BT already ticks DefensiveEngine via CooldownDecorator.
        tickSafe("defenseInterrupt", () -> {
            if (defenseEngine != null && threatMap != null
                    && stateMachine != null
                    && stateMachine.getCurrentState() != BotState.FIGHTING
                    && stateMachine.getCurrentState() != BotState.FLEEING
                    && stateMachine.getCurrentState() != BotState.CAMPING) {
                // Check if any enemy is bridging toward us (velocity + direction check)
                if (threatMap.getVisibleEnemyCount() > 0) {
                    ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
                    if (nearest != null && nearest.currentPosition != null) {
                        Location botLoc = getLocation();
                        if (botLoc != null) {
                            double dist = botLoc.distance(nearest.currentPosition);
                            // If enemy is approaching on a bridge (10-25 block range, moving toward us)
                            if (dist > 8 && dist < 25 && nearest.getHorizontalSpeed() > 0.15) {
                                defenseEngine.forceEvaluate(TrainerBot.this);
                            }
                        }
                    }
                }
            }
        });

        // ── 19. Mistake injection ──
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
    @Nullable public InventoryEngine getInventoryEngine() { return inventoryEngine; }
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
    @Nullable public ApproachEngine getApproachEngine() { return approachEngine; }
    @Nullable public PositionalEngine getPositionalEngine() { return positionalEngine; }
    @Nullable public DefensiveEngine getDefenseEngine() { return defenseEngine; }
    @Nullable public EnemyBehaviorAnalyzer getEnemyAnalyzer() { return enemyAnalyzer; }
    @Nullable public BridgeMovementController getBridgeMovementController() { return bridgeMovementController; }
    @Nullable public LearningEngine getLearningEngine() { return learningEngine; }
    /** @return the strategy planner, or null if strategy planning is disabled */
    @Nullable
    public StrategyPlanner getStrategyPlanner() {
        return strategyPlanner;
    }

    /** @return the threat predictor, or null if threat prediction is disabled */
    @Nullable
    public ThreatPredictor getThreatPredictor() {
        return threatPredictor;
    }

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
