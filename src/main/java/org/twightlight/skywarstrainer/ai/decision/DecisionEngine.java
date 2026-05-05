package org.twightlight.skywarstrainer.ai.decision;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.considerations.*;
import org.twightlight.skywarstrainer.ai.learning.LearningEngine;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.api.events.BotDecisionEvent;
import org.twightlight.skywarstrainer.awareness.IslandGraph;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;
import org.twightlight.skywarstrainer.ai.strategy.StrategyPlanner;
import org.twightlight.skywarstrainer.ai.decision.considerations.ThreatPredictionConsideration;
import org.twightlight.skywarstrainer.ai.decision.considerations.StrategyAlignmentConsideration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * The "brain" of the bot: a Utility AI system that evaluates all possible high-level
 * actions, scores them, and selects the best one.
 *
 * <p>Every N ticks (configurable, default 10), the engine:
 * <ol>
 *   <li>Captures a {@link DecisionContext} snapshot of the world</li>
 *   <li>For each possible {@link BotAction}, computes a utility score by combining
 *       weighted {@link UtilityScorer} considerations</li>
 *   <li>Applies personality multipliers to the scores via {@link org.twightlight.skywarstrainer.ai.personality.PersonalityProfile}</li>
 *   <li>Applies decision quality noise (lower quality = more random)</li>
 *   <li>Picks the highest-scoring action and transitions the {@link BotStateMachine}</li>
 * </ol></p>
 *
 * <h3>Interrupt System</h3>
 * <p>Certain events trigger immediate re-evaluation:
 * taking damage, new enemy enters awareness radius, health drops below threshold,
 * player count changes. Interrupts set a dirty flag that causes evaluation on the
 * next tick regardless of the timer.</p>
 *
 * <h3>Personality Integration</h3>
 * <p>Personality modifiers multiply specific action scores. For example, an AGGRESSIVE
 * bot multiplies FIGHT utility by 1.8. The {@link #getPersonalityMultiplier(BotAction)}
 * method resolves modifiers from the bot's {@link org.twightlight.skywarstrainer.ai.personality.PersonalityProfile},
 * which multiplicatively combines modifiers from all assigned personalities.</p>
 */
public class DecisionEngine {

    private final TrainerBot bot;
    private final BotStateMachine stateMachine;

    /** Reusable context object for evaluations (object pooling). */
    private final DecisionContext context;

    /** The interval in ticks between evaluations. */
    private final int evaluationInterval;

    /** Tick counter since last evaluation. */
    private int ticksSinceLastEval;

    /** Whether an interrupt has been triggered, forcing immediate re-evaluation. */
    private boolean interruptFlag;

    /** The action chosen in the most recent evaluation. */
    private BotAction lastChosenAction;

    /** Score map from the last evaluation, for debug output. */
    private final Map<BotAction, Double> lastScores;

    /**
     * Consideration weights for each action. The key structure is:
     * actionWeights[action] = list of (consideration, weight) pairs.
     */
    private final Map<BotAction, List<WeightedConsideration>> actionWeights;

    /** Custom considerations registered via API. */
    private final List<UtilityScorer> customConsiderations;

    // ── Shared consideration instances (stateless, reusable) ──
    private final HealthConsideration healthConsideration = new HealthConsideration();
    private final ThreatConsideration threatConsideration = new ThreatConsideration();
    private final LootValueConsideration lootValueConsideration = new LootValueConsideration();
    private final EquipmentGapConsideration equipmentGapConsideration = new EquipmentGapConsideration();
    private final ZoneControlConsideration zoneControlConsideration = new ZoneControlConsideration();
    private final TimePressureConsideration timePressureConsideration = new TimePressureConsideration();
    private final ProjectileOpportunityConsideration projectileConsideration = new ProjectileOpportunityConsideration();
    private final GamePhaseConsideration gamePhaseConsideration = new GamePhaseConsideration();
    private final PlayerCountConsideration playerCountConsideration = new PlayerCountConsideration();
    private final ResourceConsideration resourceConsideration = new ResourceConsideration();
    private final CounterPlayConsideration counterPlayConsideration = new CounterPlayConsideration();
    private final PositionalConsideration positionalConsideration = new PositionalConsideration();

    private final ThreatPredictionConsideration threatPredCombat = new ThreatPredictionConsideration(ThreatPredictionConsideration.ResponseType.COMBAT_URGENCY);
    private final ThreatPredictionConsideration threatPredDefense = new ThreatPredictionConsideration(ThreatPredictionConsideration.ResponseType.DEFENSIVE_URGENCY);
    private final ThreatPredictionConsideration threatPredBridgeCut = new ThreatPredictionConsideration(ThreatPredictionConsideration.ResponseType.BRIDGE_CUT_OPPORTUNITY);
    private final ThreatPredictionConsideration threatPredPursuit = new ThreatPredictionConsideration(ThreatPredictionConsideration.ResponseType.PURSUIT_OPPORTUNITY);
    private final StrategyAlignmentConsideration strategyAlignmentConsideration = new StrategyAlignmentConsideration();

    /**
     * Creates a new DecisionEngine for the given bot.
     *
     * @param bot          the owning trainer bot
     * @param stateMachine the bot's state machine (receives transitions)
     */
    public DecisionEngine(@Nonnull TrainerBot bot, @Nonnull BotStateMachine stateMachine) {
        this.bot = bot;
        this.stateMachine = stateMachine;
        this.context = new DecisionContext();
        this.evaluationInterval = bot.getPlugin().getConfigManager().getDecisionInterval();
        this.ticksSinceLastEval = 0;
        this.interruptFlag = false;
        this.lastChosenAction = BotAction.LOOT_OWN_ISLAND;
        this.lastScores = new EnumMap<>(BotAction.class);
        this.actionWeights = new EnumMap<>(BotAction.class);
        this.customConsiderations = new ArrayList<>();

        buildActionWeights();
    }

    // ─── Tick ───────────────────────────────────────────────────

    /**
     * Ticks the decision engine. Should be called every tick from the bot's main loop.
     * Actual evaluation only occurs every N ticks or when interrupted.
     */
    public void tick() {
        ticksSinceLastEval++;

        if (interruptFlag || ticksSinceLastEval >= evaluationInterval) {
            evaluate();
            ticksSinceLastEval = 0;
            interruptFlag = false;
        }
    }

    /**
     * Triggers an immediate re-evaluation on the next tick. Call this when
     * significant events occur (taking damage, enemy spotted, etc.).
     */
    public void triggerInterrupt() {
        interruptFlag = true;
    }

    // ─── Evaluation ─────────────────────────────────────────────

    /**
     * Performs a full utility evaluation: scores all actions, applies positional
     * strategy bonuses, counter-play modifiers, personality multipliers, and
     * decision noise, then selects the best action and transitions the state machine.
     * These are applied as post-scoring multipliers before personality and noise.</p>
     */
    private void evaluate() {
        // 1. Populate context snapshot
        context.reset();
        context.populate(bot);

        // 2. Grace period handling
        if (context.isGracePeriod) {
            handleGracePeriod();
            return;
        }

        // 3. Score all actions
        lastScores.clear();
        DifficultyProfile diff = bot.getDifficultyProfile();
        double decisionQuality = diff.getDecisionQuality();

        double qualityMultiplier = bot.getProfile().getPersonalityProfile()
                .getModifier("decisionQuality");
        decisionQuality = MathUtil.clamp(decisionQuality * qualityMultiplier, 0.0, 1.0);

        for (BotAction action : BotAction.values()) {
            double score = scoreAction(action);

            // ═══ Step 3b: Apply PositionalEngine utility bonuses ═══
            score = applyPositionalBonuses(action, score);

            // ═══ Step 3c: Apply CounterModifiers utility multipliers ═══
            score = applyCounterModifiers(action, score);

            // ═══ Step 3d: Apply learning-based weight adjustments ═══
            LearningEngine lm = bot.getLearningEngine();
            if (lm != null) {
                Map<BotAction, Double> learnedAdj = lm.getWeightAdjustments();
                Double learnedMult = learnedAdj.get(action);
                if (learnedMult != null) {
                    score *= learnedMult;
                }
            }

            // ═══ Step 3e: Apply strategy plan multipliers ═══
            score = applyStrategyMultipliers(action, score);


            // Step 4: Apply personality multipliers
            score *= getPersonalityMultiplier(action);

            // Step 5: Apply decision quality noise
            double noiseRange = (1.0 - decisionQuality) * 0.3;
            double noise = RandomUtil.nextDouble(-noiseRange, noiseRange);
            score += noise;

            score = MathUtil.clamp(score, 0.0, 5.0);
            lastScores.put(action, score);
        }

        // 6. Select the best action
        BotAction bestAction = selectBestAction(decisionQuality);

        // 7. Fire BotDecisionEvent
        if (bestAction != null) {
            BotDecisionEvent decisionEvent = new BotDecisionEvent(
                    bot, bestAction, new HashMap<>(lastScores));
            org.bukkit.Bukkit.getPluginManager().callEvent(decisionEvent);
            if (decisionEvent.isCancelled()) return;
        }

        // 8. Map action to state and transition
        if (bestAction != null) {
            boolean shouldTransition = (bestAction != lastChosenAction);

            if (!shouldTransition && isFightAction(bestAction)) {
                if (bot.getCombatEngine() != null) {
                    LivingEntity currentTarget = bot.getCombatEngine().getCurrentTarget();
                    if (currentTarget == null || currentTarget.isDead()) {

                        shouldTransition = true;
                        if (stateMachine != null) {
                            stateMachine.forceTransition(
                                    actionToState(bestAction),
                                    "Target dead, re-engaging: " + bestAction.name());
                        }
                        lastChosenAction = bestAction;
                        LearningEngine lm2 = bot.getLearningEngine();
                        if (lm2 != null) {
                            lm2.onDecisionMade(bestAction, new HashMap<>(lastScores));
                        }
                        if (bot.getProfile().isDebugMode()) {
                            logEvaluation();
                        }
                        return;
                    }
                }
            }

            if (shouldTransition) {
                BotState newState = actionToState(bestAction);
                if (newState != null) {
                    stateMachine.transitionTo(newState, "Utility: " + bestAction.name()
                            + String.format(" (%.3f)", lastScores.getOrDefault(bestAction, 0.0)));
                } else {
                    executeImmediateAction(bestAction);
                }
                lastChosenAction = bestAction;
            }
        }

        // ═══ Step 8b: Notify learning module of decision ═══
        if (bestAction != null) {
            LearningEngine lm2 = bot.getLearningEngine();
            if (lm2 != null) {
                lm2.onDecisionMade(bestAction, new HashMap<>(lastScores));
            }
        }


        // 9. Debug output
        if (bot.getProfile().isDebugMode()) {
            logEvaluation();
        }
    }

    /**
     * Returns true if the given action is a fight-related action that
     * requires an active combat target.
     *
     * @param action the action to check
     * @return true if fight-related
     */
    // [FIX-B1] Helper for same-action target staleness detection
    private boolean isFightAction(@Nonnull BotAction action) {
        return action == BotAction.FIGHT_NEAREST
                || action == BotAction.FIGHT_WEAKEST
                || action == BotAction.FIGHT_TARGETED;
    }


    /**
     * Applies utility score bonuses from the active PositionalEngine strategy.
     *
     * <p>When a positional strategy is active, it provides a map of action key →
     * multiplier. For example, IslandRotation provides {"LOOT": 2.0, "FIGHT": 0.3}.
     * We match action keys to BotActions and multiply the score.</p>
     *
     * @param action the action being scored
     * @param score  the current score
     * @return the modified score
     */
    private double applyPositionalBonuses(@Nonnull BotAction action, double score) {
        org.twightlight.skywarstrainer.movement.positional.PositionalEngine pm =
                bot.getPositionalEngine();
        if (pm == null || !pm.hasActiveStrategy()) return score;

        Map<String, Double> bonuses = pm.getActiveUtilityBonuses();
        if (bonuses.isEmpty()) return score;

        // Try matching on the generic key for this action
        String genericKey = actionToGenericKey(action);
        Double multiplier = bonuses.get(genericKey);
        if (multiplier != null) {
            score *= multiplier;
        }

        // Also try specific key
        String specificKey = actionToSpecificKey(action);
        if (!specificKey.equals(genericKey)) {
            Double specificMult = bonuses.get(specificKey);
            if (specificMult != null) {
                score *= specificMult;
            }
        }

        return score;
    }

    /**
     * Applies CounterModifier utility multipliers from the EnemyBehaviorAnalyzer.
     *
     * <p>When the bot's primary threat has been analyzed, the counter modifiers
     * may adjust FIGHT, FLEE, and CAMP utility scores. For example, if the enemy
     * is a rusher, campUtilityMultiplier increases to encourage camping (stay
     * defensive and counter-punch).</p>
     *
     * @param action the action being scored
     * @param score  the current score
     * @return the modified score
     */
    private double applyCounterModifiers(@Nonnull BotAction action, double score) {
        org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer analyzer =
                bot.getEnemyAnalyzer();
        if (analyzer == null) return score;

        // Get primary threat's counter modifiers
        org.twightlight.skywarstrainer.awareness.ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null) return score;

        org.twightlight.skywarstrainer.awareness.ThreatMap.ThreatEntry nearest =
                threatMap.getNearestThreat();
        if (nearest == null || nearest.playerId == null) return score;

        org.twightlight.skywarstrainer.combat.counter.CounterModifiers mods =
                analyzer.getCounterModifiers(nearest.playerId);

        switch (action) {
            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED:
            case HUNT_PLAYER:
                score *= mods.fightUtilityMultiplier;
                break;
            case FLEE:
                score *= mods.fleeUtilityMultiplier;
                break;
            case CAMP_POSITION:
            case BUILD_FORTIFICATION:
                score *= mods.campUtilityMultiplier;
                break;
            case BREAK_ENEMY_BRIDGE:
                if (mods.bridgeCut) score *= 1.5;
                break;
            default:
                break;
        }

        return score;
    }


    /**
     * Handles decision-making during grace period. Only looting and idle are allowed.
     */
    private void handleGracePeriod() {
        // Check if RUSHER personality — should bridge to mid even during grace
        boolean isRusher = bot.getProfile().hasPersonality("RUSHER");

        if (isRusher && context.blockCount > 0) {
            stateMachine.transitionTo(BotState.BRIDGING, "Grace period: rusher bridging to mid");
            lastChosenAction = BotAction.BRIDGE_TO_MID;
        } else if (context.unlootedChestCount > 0) {
            stateMachine.transitionTo(BotState.LOOTING, "Grace period: looting");
            lastChosenAction = BotAction.LOOT_OWN_ISLAND;
        } else {
            stateMachine.transitionTo(BotState.IDLE, "Grace period: idle");
            lastChosenAction = BotAction.ORGANIZE_INVENTORY;
        }
    }

    /**
     * Scores a single action by evaluating all its weighted considerations.
     *
     * @param action the action to score
     * @return the raw utility score (before personality and noise)
     */
    private double scoreAction(@Nonnull BotAction action) {
        List<WeightedConsideration> considerations = actionWeights.get(action);
        if (considerations == null || considerations.isEmpty()) {
            return 0.0;
        }

        double totalScore = 0.0;
        double totalWeight = 0.0;

        for (WeightedConsideration wc : considerations) {
            double considerationScore = wc.scorer.score(context);
            totalScore += considerationScore * wc.weight;
            totalWeight += Math.abs(wc.weight);
        }

        // Normalize by total weight so all actions are on the same scale
        if (totalWeight > 0) {
            totalScore /= totalWeight;
        }

        return MathUtil.clamp(totalScore, 0.0, 1.0);
    }

    /**
     * Selects the best action from the scored map.
     *
     */
    @Nullable
    private BotAction selectBestAction(double decisionQuality) {
        if (lastScores.isEmpty()) return null;

        List<Map.Entry<BotAction, Double>> sorted = new ArrayList<>(lastScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        if (decisionQuality < 0.3 && sorted.size() > 1 && RandomUtil.chance(0.3)) {
            // [FIX 5.4] Upper bound is sorted.size()-1 (inclusive), minimum index 1
            int maxIdx = Math.min(3, sorted.size() - 1);
            if (maxIdx >= 1) {
                int pick = RandomUtil.nextInt(1, maxIdx);
                return sorted.get(pick).getKey();
            }
        }

        if (decisionQuality < 0.5 && sorted.size() > 1 && RandomUtil.chance(0.1)) {
            return sorted.get(1).getKey();
        }

        return sorted.get(0).getKey();
    }



    /**
     * Returns the personality multiplier for a given action based on the bot's
     * PersonalityProfile. Uses the resolved modifiers from all assigned personalities.
     *
     * <p>The mapping from BotAction to modifier key accounts for the fact that
     * multiple actions may share a modifier key (e.g., all FIGHT variants use "FIGHT"),
     * and some personalities define specific keys (e.g., RUSHER defines "LOOT_OWN_ISLAND"
     * separately from "LOOT").</p>
     *
     * @param action the action to get the multiplier for
     * @return the combined personality multiplier, clamped to [0.01, 5.0]
     */
    private double getPersonalityMultiplier(@Nonnull BotAction action) {
        org.twightlight.skywarstrainer.ai.personality.PersonalityProfile profile =
                bot.getProfile().getPersonalityProfile();
        if (profile.isEmpty()) return 1.0;

        // Try specific key first, then generic key
        String specificKey = actionToSpecificKey(action);
        String genericKey = actionToGenericKey(action);

        double multiplier = profile.getModifier(specificKey);

        // If the specific key returned 1.0 (default / not defined), also check generic
        if (Math.abs(multiplier - 1.0) < 0.001 && !specificKey.equals(genericKey)) {
            double genericMult = profile.getModifier(genericKey);
            if (Math.abs(genericMult - 1.0) > 0.001) {
                multiplier = genericMult;
            }
        }

        // Clamp to prevent extreme values
        return MathUtil.clamp(multiplier, 0.01, 5.0);
    }

    /**
     * Applies score multipliers from the active strategy plan.
     *
     * @param action the action being scored
     * @param score  the current score
     * @return the modified score
     */
    private double applyStrategyMultipliers(@Nonnull BotAction action, double score) {
        StrategyPlanner planner = bot.getStrategyPlanner();
        if (planner == null || !planner.hasActivePlan()) return score;

        Map<BotAction, Double> multipliers = planner.getActiveMultipliers();
        if (multipliers.isEmpty()) return score;

        Double multiplier = multipliers.get(action);
        if (multiplier != null) {
            score *= multiplier;
        }

        return score;
    }


    /**
     * Maps a BotAction to a specific personality modifier key.
     * These are the exact keys as defined in Personality enum modifiers.
     *
     * @param action the action
     * @return the specific modifier key
     */
    @Nonnull
    private String actionToSpecificKey(@Nonnull BotAction action) {
        switch (action) {
            case LOOT_OWN_ISLAND: return "LOOT_OWN_ISLAND";
            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED: return "FIGHT";
            case HUNT_PLAYER: return "HUNT";
            case LOOT_MID:
            case LOOT_OTHER_ISLAND: return "LOOT";
            case FLEE: return "FLEE";
            case ENCHANT: return "ENCHANT";
            case CAMP_POSITION: return "CAMP";
            case BRIDGE_TO_MID: return "BRIDGE_TO_MID";
            case BRIDGE_TO_PLAYER: return "BRIDGE_TO_PLAYER";
            case BREAK_ENEMY_BRIDGE: return "BREAK_ENEMY_BRIDGE";
            case USE_ENDER_PEARL: return "USE_ENDER_PEARL";
            case ORGANIZE_INVENTORY: return "EQUIP";
            default: return action.name();
        }
    }

    /**
     * Maps a BotAction to a generic/fallback modifier key.
     * Used when the specific key has no modifier defined.
     *
     * @param action the action
     * @return the generic modifier key
     */
    @Nonnull
    private String actionToGenericKey(@Nonnull BotAction action) {
        switch (action) {
            case LOOT_OWN_ISLAND:
            case LOOT_MID:
            case LOOT_OTHER_ISLAND: return "LOOT";
            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED: return "FIGHT";
            default: return actionToSpecificKey(action);
        }
    }

    /**
     * Maps a utility action to the corresponding BotState for state machine transition.
     *
     * FIX for issues #10-#13:
     * - HEAL, EAT_FOOD, DRINK_POTION now map to CONSUMING instead of IDLE
     * - ORGANIZE_INVENTORY now maps to ORGANIZING instead of IDLE
     * - USE_ENDER_PEARL still returns null but is handled by executeImmediateAction()
     * - ENCHANTING already mapped correctly; the BT itself was the problem (fixed in TrainerBot)
     */
    @Nullable
    private BotState actionToState(@Nonnull BotAction action) {
        switch (action) {
            case LOOT_OWN_ISLAND:
            case LOOT_MID:
            case LOOT_OTHER_ISLAND:
                return BotState.LOOTING;

            case BRIDGE_TO_MID:
            case BRIDGE_TO_PLAYER:
                return BotState.BRIDGING;

            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED:
                return BotState.FIGHTING;

            case FLEE:
                return BotState.FLEEING;

            case HEAL:
            case EAT_FOOD:
            case DRINK_POTION:
                return BotState.CONSUMING;

            case ENCHANT:
                return BotState.ENCHANTING;

            case CAMP_POSITION:
            case BUILD_FORTIFICATION:
                return BotState.CAMPING;

            case HUNT_PLAYER:
                return BotState.HUNTING;

            case ORGANIZE_INVENTORY:
                return BotState.ORGANIZING;

            case USE_ENDER_PEARL:
                return null;

            case BREAK_ENEMY_BRIDGE:
                return BotState.HUNTING;

            default:
                return BotState.IDLE;
        }
    }

    /**
     * Executes actions that are immediate (single-tick) and don't need a state transition.
     * Called from evaluate() when actionToState() returns null.
     *
     * FIX for issue #12: USE_ENDER_PEARL was a dead action — selected by the utility
     * system, returned null from actionToState(), and then nothing happened. Now
     * the pearl is actually thrown here.
     *
     * @param action the immediate action to execute
     */
    private void executeImmediateAction(@Nonnull BotAction action) {
        switch (action) {
            case USE_ENDER_PEARL:
                executeEnderPearlThrow();
                break;
            default:
                // No other immediate actions currently
                break;
        }
    }

    /**
     * Actually throws an ender pearl toward the best target location.
     * Targets the nearest threat's position, or mid island if no threat is visible.
     */
    private void executeEnderPearlThrow() {
        org.bukkit.entity.Player player = bot.getPlayerEntity();
        if (player == null) return;

        int pearlSlot = -1;
        for (int i = 0; i < 36; i++) {
            org.bukkit.inventory.ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == org.bukkit.Material.ENDER_PEARL) {
                pearlSlot = i;
                break;
            }
        }
        if (pearlSlot < 0) return;

        Location target = null;

        ThreatMap tm = bot.getThreatMap();
        if (tm != null) {
            ThreatMap.ThreatEntry nearest = tm.getNearestThreat();
            if (nearest != null && nearest.currentPosition != null) {
                LivingEntity entity = bot.getLivingEntity();
                if (entity != null) {
                    double dist = entity.getLocation().distance(nearest.currentPosition);
                    double healthFrac = entity.getHealth() / entity.getMaxHealth();
                    if (healthFrac < bot.getDifficultyProfile().getFleeHealthThreshold()) {
                        Location botLoc = entity.getLocation();
                        double dx = botLoc.getX() - nearest.currentPosition.getX();
                        double dz = botLoc.getZ() - nearest.currentPosition.getZ();
                        double len = Math.sqrt(dx * dx + dz * dz);
                        if (len > 0.01) {
                            dx /= len; dz /= len;
                            target = botLoc.clone().add(dx * 20, 2, dz * 20);
                        }
                    } else if (dist > 8) {
                        target = nearest.currentPosition.clone().add(0, 1, 0);
                    }
                }
            }
        }

        if (target == null && bot.getIslandGraph() != null) {
            IslandGraph.Island mid = bot.getIslandGraph().getMidIsland();
            if (mid != null && mid.center != null) {
                target = mid.center.clone().add(0, 1, 0);
            }
        }

        if (target == null) return;

        int previousSlot = player.getInventory().getHeldItemSlot();

        // If pearl is in hotbar, just switch to it
        if (pearlSlot < 9) {
            player.getInventory().setHeldItemSlot(pearlSlot);
        } else {
            // Pearl is in main inventory — swap it into the current hotbar slot
            org.bukkit.inventory.ItemStack pearl = player.getInventory().getItem(pearlSlot);
            org.bukkit.inventory.ItemStack swap = player.getInventory().getItem(previousSlot);
            player.getInventory().setItem(previousSlot, pearl);
            player.getInventory().setItem(pearlSlot, swap);
        }

        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(target);
        }

        org.bukkit.entity.EnderPearl pearl = player.launchProjectile(
                org.bukkit.entity.EnderPearl.class,
                target.toVector().subtract(player.getEyeLocation().toVector()).normalize().multiply(1.5));

        // [FIX 5.5] Consume the pearl by slot index instead of relying on getItemInHand()
        // which may not be the pearl after slot switching shenanigans.
        int consumeSlot = (pearlSlot < 9) ? pearlSlot : previousSlot;
        org.bukkit.inventory.ItemStack pearlItem = player.getInventory().getItem(consumeSlot);
        if (pearlItem != null && pearlItem.getType() == org.bukkit.Material.ENDER_PEARL) {
            if (pearlItem.getAmount() > 1) {
                pearlItem.setAmount(pearlItem.getAmount() - 1);
            } else {
                player.getInventory().setItem(consumeSlot, null);
            }
        }

        // [FIX 5.6] Use bot's stored plugin reference instead of SkyWarsTrainer.getInstance()
        if (bot.getProfile().isDebugMode()) {
            bot.getPlugin().getLogger().info(
                    "[DEBUG] " + bot.getName() + " threw ender pearl toward " +
                            String.format("(%.1f, %.1f, %.1f)", target.getX(), target.getY(), target.getZ()));
        }
    }


    // ─── Action Weight Configuration ────────────────────────────

    /**
     * Builds the consideration-weight tables for each possible action.
     * This defines how each consideration influences each action's utility score.
     */
    private void buildActionWeights() {
        // ── LOOT_OWN_ISLAND ──
        addWeight(BotAction.LOOT_OWN_ISLAND,
                lootValueConsideration, 1.5,
                equipmentGapConsideration, -0.8,
                timePressureConsideration, -1.0,
                threatConsideration, -0.6,
                playerCountConsideration, 0.3);

        // ── LOOT_MID ──
        addWeight(BotAction.LOOT_MID,
                lootValueConsideration, 1.2,
                equipmentGapConsideration, -0.6,
                timePressureConsideration, -0.5,
                zoneControlConsideration, 0.5,
                threatConsideration, -0.8);

        // ── LOOT_OTHER_ISLAND ──
        addWeight(BotAction.LOOT_OTHER_ISLAND,
                lootValueConsideration, 1.0,
                timePressureConsideration, -1.2,
                threatConsideration, -0.5,
                playerCountConsideration, 0.4);

        // ── BRIDGE_TO_MID ──
        addWeight(BotAction.BRIDGE_TO_MID,
                lootValueConsideration, 0.8,
                zoneControlConsideration, -0.5,
                gamePhaseConsideration, -0.3,
                resourceConsideration, -0.3,
                positionalConsideration, 0.5);

        // ── BRIDGE_TO_PLAYER ──
        addWeight(BotAction.BRIDGE_TO_PLAYER,
                threatConsideration, 0.6,
                equipmentGapConsideration, 0.8,
                timePressureConsideration, 0.7,
                resourceConsideration, -0.2);

        // ── FIGHT_NEAREST ──
        addWeight(BotAction.FIGHT_NEAREST,
                threatConsideration, 1.2,
                equipmentGapConsideration, 0.8,
                healthConsideration, -1.0,
                timePressureConsideration, 0.5,
                gamePhaseConsideration, 0.4,
                counterPlayConsideration, 0.6,
                positionalConsideration, 0.4);

        // Add threat prediction to fight actions
        List<WeightedConsideration> fightNearestList = actionWeights.get(BotAction.FIGHT_NEAREST);
        if (fightNearestList != null) {
            fightNearestList.add(new WeightedConsideration(threatPredCombat, 0.5));
            fightNearestList.add(new WeightedConsideration(strategyAlignmentConsideration, 0.3));
        }

        List<WeightedConsideration> fleeList = actionWeights.get(BotAction.FLEE);
        if (fleeList != null) {
            fleeList.add(new WeightedConsideration(threatPredDefense, 0.6));
        }

        List<WeightedConsideration> huntList = actionWeights.get(BotAction.HUNT_PLAYER);
        if (huntList != null) {
            huntList.add(new WeightedConsideration(threatPredPursuit, 0.5));
            huntList.add(new WeightedConsideration(strategyAlignmentConsideration, 0.3));
        }

        List<WeightedConsideration> bridgeCutList = actionWeights.get(BotAction.BREAK_ENEMY_BRIDGE);
        if (bridgeCutList != null) {
            bridgeCutList.add(new WeightedConsideration(threatPredBridgeCut, 0.6));
        }


        // ── FIGHT_WEAKEST ──
        addWeight(BotAction.FIGHT_WEAKEST,
                threatConsideration, 0.8,
                equipmentGapConsideration, 0.6,
                healthConsideration, -0.7,
                timePressureConsideration, 0.6,
                counterPlayConsideration, 0.5);  // [FIX B2]

        // ── FIGHT_TARGETED ──
        addWeight(BotAction.FIGHT_TARGETED,
                threatConsideration, 1.0,
                equipmentGapConsideration, 0.7,
                healthConsideration, -0.9,
                timePressureConsideration, 0.5);

        // ── FLEE ──
        addWeight(BotAction.FLEE,
                healthConsideration, 2.0,
                threatConsideration, 1.0,
                equipmentGapConsideration, -1.2,
                resourceConsideration, -0.3,
                counterPlayConsideration, 0.4);  // [FIX B2]

        // ── HEAL ──
        addWeight(BotAction.HEAL,
                healthConsideration, 1.8,
                threatConsideration, -0.5);

        // ── EAT_FOOD ──
        addWeight(BotAction.EAT_FOOD,
                healthConsideration, 0.8,
                threatConsideration, -0.8);

        // ── DRINK_POTION ──
        addWeight(BotAction.DRINK_POTION,
                threatConsideration, 0.5,
                equipmentGapConsideration, 0.3,
                timePressureConsideration, 0.4);

        // ── ENCHANT ──
        addWeight(BotAction.ENCHANT,
                lootValueConsideration, 0.5,
                threatConsideration, -1.5,
                timePressureConsideration, -0.5,
                zoneControlConsideration, 0.3);

        // ── ORGANIZE_INVENTORY ──
        addWeight(BotAction.ORGANIZE_INVENTORY,
                threatConsideration, -1.5,
                resourceConsideration, 0.3);

        // ── CAMP_POSITION ──
        addWeight(BotAction.CAMP_POSITION,
                zoneControlConsideration, 1.2,
                resourceConsideration, 0.4,
                threatConsideration, -0.3,
                playerCountConsideration, 0.5,
                counterPlayConsideration, 0.5,   // [FIX B2]
                positionalConsideration, 0.6);

        // ── HUNT_PLAYER ──
        addWeight(BotAction.HUNT_PLAYER,
                equipmentGapConsideration, 1.0,
                timePressureConsideration, 1.2,
                healthConsideration, -0.8,
                gamePhaseConsideration, 0.8,
                playerCountConsideration, -0.6,
                positionalConsideration, 0.5);

        // ── USE_ENDER_PEARL ──
        addWeight(BotAction.USE_ENDER_PEARL,
                healthConsideration, 0.8,
                threatConsideration, 0.6,
                projectileConsideration, 0.3);

        // ── BUILD_FORTIFICATION ──
        addWeight(BotAction.BUILD_FORTIFICATION,
                zoneControlConsideration, 0.8,
                resourceConsideration, 0.5,
                threatConsideration, -0.3);

        // ── BREAK_ENEMY_BRIDGE ──
        addWeight(BotAction.BREAK_ENEMY_BRIDGE,
                threatConsideration, 0.4,
                zoneControlConsideration, 0.6,
                projectileConsideration, 0.3);
    }

    /**
     * Helper to add weighted considerations for an action.
     */
    private void addWeight(BotAction action, Object... args) {
        List<WeightedConsideration> list = new ArrayList<>();
        for (int i = 0; i < args.length; i += 2) {
            UtilityScorer scorer = (UtilityScorer) args[i];
            double weight = (Double) args[i + 1];
            list.add(new WeightedConsideration(scorer, weight));
        }
        actionWeights.put(action, list);
    }

    // ─── Custom Consideration Registration ──────────────────────

    /**
     * Registers a custom utility consideration via the API. Custom considerations
     * are evaluated alongside built-in ones during each evaluation cycle.
     *
     * @param consideration the custom consideration to register
     */
    public void registerCustomConsideration(@Nonnull UtilityScorer consideration) {
        customConsiderations.add(consideration);
    }

    // ─── Debug ──────────────────────────────────────────────────

    /**
     * Logs the evaluation results for debug output.
     */
    private void logEvaluation() {
        StringBuilder sb = new StringBuilder();
        sb.append("[DECISION] ").append(bot.getName()).append(" scores: ");

        List<Map.Entry<BotAction, Double>> sorted = new ArrayList<>(lastScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        int shown = 0;
        for (Map.Entry<BotAction, Double> entry : sorted) {
            if (shown >= 5) break;
            if (shown > 0) sb.append(", ");
            sb.append(entry.getKey().name())
                    .append(String.format("=%.3f", entry.getValue()));
            if (entry.getKey() == lastChosenAction) sb.append("*");
            shown++;
        }

        SkyWarsTrainer.getInstance().getLogger().info(sb.toString());
    }

    // ─── Queries ────────────────────────────────────────────────

    /** @return the action chosen in the most recent evaluation */
    @Nonnull
    public BotAction getLastChosenAction() {
        return lastChosenAction;
    }

    /** @return unmodifiable map of action scores from last evaluation */
    @Nonnull
    public Map<BotAction, Double> getLastScores() {
        return Collections.unmodifiableMap(lastScores);
    }

    /** @return the current decision context (snapshot from last evaluation) */
    @Nonnull
    public DecisionContext getContext() {
        return context;
    }

    // ─── Inner Classes ──────────────────────────────────────────

    /**
     * Pairs a utility scorer with its weight for a specific action.
     */
    private static final class WeightedConsideration {
        final UtilityScorer scorer;
        final double weight;

        WeightedConsideration(@Nonnull UtilityScorer scorer, double weight) {
            this.scorer = scorer;
            this.weight = weight;
        }
    }

    /**
     * All possible high-level actions the bot can take.
     * Each maps to one or more BotStates.
     */
    public enum BotAction {
        LOOT_OWN_ISLAND,
        LOOT_MID,
        LOOT_OTHER_ISLAND,
        BRIDGE_TO_MID,
        BRIDGE_TO_PLAYER,
        FIGHT_NEAREST,
        FIGHT_WEAKEST,
        FIGHT_TARGETED,
        FLEE,
        HEAL,
        ENCHANT,
        ORGANIZE_INVENTORY,
        CAMP_POSITION,
        HUNT_PLAYER,
        USE_ENDER_PEARL,
        BUILD_FORTIFICATION,
        BREAK_ENEMY_BRIDGE,
        EAT_FOOD,
        DRINK_POTION
    }
}
