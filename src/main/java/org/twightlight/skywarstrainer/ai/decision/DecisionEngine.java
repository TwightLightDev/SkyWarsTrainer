package org.twightlight.skywarstrainer.ai.decision;

import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.decision.considerations.*;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.api.events.BotDecisionEvent;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

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
 *   <li>Applies personality multipliers to the scores</li>
 *   <li>Applies decision quality noise (lower quality = more random)</li>
 *   <li>Picks the highest-scoring action and transitions the {@link BotStateMachine}</li>
 * </ol></p>
 *
 * <h3>Interrupt System</h3>
 * <p>Certain events trigger immediate re-evaluation:
 * <ul>
 *   <li>Taking damage</li>
 *   <li>New enemy enters awareness radius</li>
 *   <li>Health drops below threshold</li>
 *   <li>Player count changes (someone dies)</li>
 * </ul>
 * Interrupts set a dirty flag that causes evaluation on the next tick regardless
 * of the timer.</p>
 *
 * <h3>Personality Integration</h3>
 * <p>Personality modifiers multiply specific action scores. For example, an AGGRESSIVE
 * bot multiplies FIGHT utility by 1.8. These multipliers are passed in via the bot's
 * profile. Phase 6 provides the full PersonalityProfile; in Phase 3 we use the
 * raw personality names from BotProfile to apply known multipliers.</p>
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
     * Performs a full utility evaluation: scores all actions, applies personality
     * modifiers and decision noise, selects the best action, and transitions
     * the state machine.
     */
    private void evaluate() {
        // 1. Populate context snapshot
        context.reset();
        context.populate(bot);

        // 2. Grace period handling: only allow IDLE and LOOT actions
        if (context.isGracePeriod) {
            handleGracePeriod();
            return;
        }

        // 3. Score all actions
        lastScores.clear();
        DifficultyProfile diff = bot.getDifficultyProfile();
        double decisionQuality = diff.getDecisionQuality();

        for (BotAction action : BotAction.values()) {
            double score = scoreAction(action);

            // Apply personality multipliers from bot profile
            score *= getPersonalityMultiplier(action);

            // Apply decision quality noise: lower quality = more random noise
            double noiseRange = (1.0 - decisionQuality) * 0.3;
            double noise = RandomUtil.nextDouble(-noiseRange, noiseRange);
            score += noise;

            // Clamp to valid range
            score = MathUtil.clamp(score, 0.0, 5.0);
            lastScores.put(action, score);
        }

        // 4. Select the best action
        BotAction bestAction = selectBestAction(decisionQuality);

        // 5. Map action to state and transition
        if (bestAction != null && bestAction != lastChosenAction) {
            BotState newState = actionToState(bestAction);
            if (newState != null) {
                stateMachine.transitionTo(newState, "Utility: " + bestAction.name()
                        + String.format(" (%.3f)", lastScores.getOrDefault(bestAction, 0.0)));
            }
            lastChosenAction = bestAction;
            // In evaluate(), after selecting bestAction and before transitioning state:

            // Fire BotDecisionEvent (cancellable)
            BotDecisionEvent decisionEvent = new BotDecisionEvent(
                    bot, bestAction, new java.util.HashMap<>(lastScores));
            org.bukkit.Bukkit.getPluginManager().callEvent(decisionEvent);

            if (decisionEvent.isCancelled()) {
                return; // Another plugin cancelled this decision
            }

        }

        // 6. Debug output
        if (bot.getProfile().isDebugMode()) {
            logEvaluation();
        }
    }

    /**
     * Handles decision-making during grace period. Only looting and idle are allowed.
     */
    private void handleGracePeriod() {
        if (context.unlootedChestCount > 0) {
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
     * Selects the best action from the scored map. At high decision quality,
     * always picks the top scorer. At low quality, sometimes picks the 2nd or 3rd best.
     */
    @Nullable
    private BotAction selectBestAction(double decisionQuality) {
        if (lastScores.isEmpty()) return null;

        // Sort actions by score descending
        List<Map.Entry<BotAction, Double>> sorted = new ArrayList<>(lastScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // At quality < 0.3, occasionally pick a suboptimal action
        if (decisionQuality < 0.3 && sorted.size() > 1 && RandomUtil.chance(0.3)) {
            int pick = RandomUtil.nextInt(1, Math.min(3, sorted.size()) - 1);
            return sorted.get(pick).getKey();
        }

        // At quality < 0.5, small chance to pick 2nd best
        if (decisionQuality < 0.5 && sorted.size() > 1 && RandomUtil.chance(0.1)) {
            return sorted.get(1).getKey();
        }

        return sorted.get(0).getKey();
    }



    /**
     * Returns the multiplier a specific personality applies to a specific action.
     * Based on Section 4 of the specification.
     */
    private double getPersonalityActionMultiplier(@Nonnull String personality, @Nonnull BotAction action) {
        switch (personality) {
            case "AGGRESSIVE":
                switch (action) {
                    case FIGHT_NEAREST: case FIGHT_WEAKEST: case FIGHT_TARGETED: return 1.8;
                    case HUNT_PLAYER: return 2.0;
                    case LOOT_OWN_ISLAND: case LOOT_MID: case LOOT_OTHER_ISLAND: return 0.5;
                    case FLEE: return 0.4;
                    default: return 1.0;
                }
            case "PASSIVE":
                switch (action) {
                    case FIGHT_NEAREST: case FIGHT_WEAKEST: case FIGHT_TARGETED: return 0.4;
                    case FLEE: return 2.0;
                    case LOOT_OWN_ISLAND: case LOOT_MID: case LOOT_OTHER_ISLAND: return 1.5;
                    case ENCHANT: return 1.8;
                    default: return 1.0;
                }
            case "RUSHER":
                switch (action) {
                    case BRIDGE_TO_MID: return 3.0;
                    case LOOT_OWN_ISLAND: return 0.2;
                    default: return 1.0;
                }
            case "CAMPER":
                switch (action) {
                    case CAMP_POSITION: return 2.5;
                    case HUNT_PLAYER: return 0.3;
                    case BRIDGE_TO_PLAYER: return 0.3;
                    default: return 1.0;
                }
            case "STRATEGIC":
                // Strategic doesn't change action weights; it boosts decision quality
                return 1.0;
            case "COLLECTOR":
                switch (action) {
                    case LOOT_OWN_ISLAND: case LOOT_MID: case LOOT_OTHER_ISLAND: return 2.5;
                    case ENCHANT: case ORGANIZE_INVENTORY: return 1.5;
                    case FIGHT_NEAREST: case FIGHT_WEAKEST: case FIGHT_TARGETED: return 0.6;
                    default: return 1.0;
                }
            case "BERSERKER":
                switch (action) {
                    case FLEE: return 0.05;
                    case FIGHT_NEAREST: case FIGHT_WEAKEST: case FIGHT_TARGETED: return 1.5;
                    default: return 1.0;
                }
            case "SNIPER":
                // Sniper affects combat strategy selection, not action-level decisions
                return 1.0;
            case "TRICKSTER":
                switch (action) {
                    case BREAK_ENEMY_BRIDGE: return 1.8;
                    case USE_ENDER_PEARL: return 1.5;
                    default: return 1.0;
                }
            case "CAUTIOUS":
                switch (action) {
                    case FLEE: return 1.3;
                    default: return 1.0;
                }
            case "CLUTCH_MASTER":
                // Clutch affects combat execution, not high-level decisions
                return 1.0;
            case "TEAMWORK":
                return 1.0; // Phase 6: team mode integration
            default:
                return 1.0;
        }
    }

    /**
     * Maps a utility action to the corresponding BotState for state machine transition.
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
                return BotState.IDLE; // Healing is handled within states

            case ENCHANT:
                return BotState.ENCHANTING;

            case ORGANIZE_INVENTORY:
                return BotState.IDLE;

            case CAMP_POSITION:
            case BUILD_FORTIFICATION:
                return BotState.CAMPING;

            case HUNT_PLAYER:
                return BotState.HUNTING;

            case USE_ENDER_PEARL:
                return null; // Pearl is an immediate action, not a state

            case BREAK_ENEMY_BRIDGE:
                return BotState.HUNTING; // Bridge breaking is done while hunting

            default:
                return BotState.IDLE;
        }
    }

    // ─── Action Weight Configuration ────────────────────────────

    /**
     * Builds the consideration-weight tables for each possible action.
     * This defines how each consideration influences each action's utility score.
     *
     * <p>Positive weight: higher consideration score → higher action score.
     * Negative weight: higher consideration score → lower action score.</p>
     */
    private void buildActionWeights() {
        // ── LOOT_OWN_ISLAND ──
        addWeight(BotAction.LOOT_OWN_ISLAND,
                lootValueConsideration, 1.5,         // High when inventory is empty
                equipmentGapConsideration, -0.8,     // More desire to loot when outgeared (inverted)
                timePressureConsideration, -1.0,     // Less desire late game
                threatConsideration, -0.6,            // Don't loot when enemies are close
                playerCountConsideration, 0.3);       // More players = more reason to gear up first

        // ── LOOT_MID ──
        addWeight(BotAction.LOOT_MID,
                lootValueConsideration, 1.2,
                equipmentGapConsideration, -0.6,
                timePressureConsideration, -0.5,      // Mid loot stays relevant longer
                zoneControlConsideration, 0.5,        // Mid has best loot
                threatConsideration, -0.8);

        // ── LOOT_OTHER_ISLAND ──
        addWeight(BotAction.LOOT_OTHER_ISLAND,
                lootValueConsideration, 1.0,
                timePressureConsideration, -1.2,
                threatConsideration, -0.5,
                playerCountConsideration, 0.4);

        // ── BRIDGE_TO_MID ──
        addWeight(BotAction.BRIDGE_TO_MID,
                lootValueConsideration, 0.8,          // Mid has best loot
                zoneControlConsideration, -0.5,       // If already in good position, less need
                gamePhaseConsideration, -0.3,         // More attractive early/mid
                resourceConsideration, -0.3);         // Need blocks to bridge

        // ── BRIDGE_TO_PLAYER ──
        addWeight(BotAction.BRIDGE_TO_PLAYER,
                threatConsideration, 0.6,              // Need a target to bridge toward
                equipmentGapConsideration, 0.8,       // Only bridge to fight if well-equipped
                timePressureConsideration, 0.7,       // More attractive late game
                resourceConsideration, -0.2);         // Need blocks

        // ── FIGHT_NEAREST ──
        addWeight(BotAction.FIGHT_NEAREST,
                threatConsideration, 1.2,              // Enemy present and close
                equipmentGapConsideration, 0.8,       // Fight when gear advantage
                healthConsideration, -1.0,             // Don't fight at low HP
                timePressureConsideration, 0.5,       // More fighting late game
                gamePhaseConsideration, 0.4);

        // ── FIGHT_WEAKEST ──
        addWeight(BotAction.FIGHT_WEAKEST,
                threatConsideration, 0.8,
                equipmentGapConsideration, 0.6,
                healthConsideration, -0.7,
                timePressureConsideration, 0.6);

        // ── FIGHT_TARGETED ──
        addWeight(BotAction.FIGHT_TARGETED,
                threatConsideration, 1.0,
                equipmentGapConsideration, 0.7,
                healthConsideration, -0.9,
                timePressureConsideration, 0.5);

        // ── FLEE ──
        addWeight(BotAction.FLEE,
                healthConsideration, 2.0,              // High urgency when health is low
                threatConsideration, 1.0,              // High threat → flee
                equipmentGapConsideration, -1.2,      // Outgeared → flee (inverted: low score = flee)
                resourceConsideration, -0.3);

        // ── HEAL ──
        addWeight(BotAction.HEAL,
                healthConsideration, 1.8,
                threatConsideration, -0.5);            // Hard to heal when enemy is close

        // ── EAT_FOOD ──
        addWeight(BotAction.EAT_FOOD,
                healthConsideration, 0.8,
                threatConsideration, -0.8);

        // ── DRINK_POTION ──
        addWeight(BotAction.DRINK_POTION,
                threatConsideration, 0.5,              // Drink speed/strength before fights
                equipmentGapConsideration, 0.3,
                timePressureConsideration, 0.4);

        // ── ENCHANT ──
        addWeight(BotAction.ENCHANT,
                lootValueConsideration, 0.5,           // Still want to improve gear
                threatConsideration, -1.5,             // Never enchant near enemies
                timePressureConsideration, -0.5,
                zoneControlConsideration, 0.3);

        // ── ORGANIZE_INVENTORY ──
        addWeight(BotAction.ORGANIZE_INVENTORY,
                threatConsideration, -1.5,             // Never organize near enemies
                resourceConsideration, 0.3);

        // ── CAMP_POSITION ──
        addWeight(BotAction.CAMP_POSITION,
                zoneControlConsideration, 1.2,
                resourceConsideration, 0.4,            // Need supplies to camp
                threatConsideration, -0.3,             // Camp when not under immediate pressure
                playerCountConsideration, 0.5);

        // ── HUNT_PLAYER ──
        addWeight(BotAction.HUNT_PLAYER,
                equipmentGapConsideration, 1.0,       // Hunt when well-equipped
                timePressureConsideration, 1.2,       // Hunt more in late game
                healthConsideration, -0.8,             // Don't hunt at low HP
                gamePhaseConsideration, 0.8,          // Hunt in mid-late game
                playerCountConsideration, -0.6);      // Fewer players → more hunting

        // ── USE_ENDER_PEARL ──
        addWeight(BotAction.USE_ENDER_PEARL,
                healthConsideration, 0.8,              // Pearl to escape when low HP
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
            if (shown >= 5) break; // Show top 5
            if (shown > 0) sb.append(", ");
            sb.append(entry.getKey().name())
                    .append(String.format("=%.3f", entry.getValue()));
            if (entry.getKey() == lastChosenAction) sb.append("*");
            shown++;
        }

        SkyWarsTrainerPlugin.getInstance().getLogger().info(sb.toString());
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the action chosen in the most recent evaluation.
     *
     * @return the last chosen action
     */
    @Nonnull
    public BotAction getLastChosenAction() {
        return lastChosenAction;
    }

    /**
     * Returns the score map from the most recent evaluation.
     *
     * @return unmodifiable map of action scores
     */
    @Nonnull
    public Map<BotAction, Double> getLastScores() {
        return Collections.unmodifiableMap(lastScores);
    }

    /**
     * Returns the current decision context (snapshot from last evaluation).
     *
     * @return the context
     */
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
     * Returns the personality multiplier for a given action based on the bot's
     * PersonalityProfile. Uses the resolved modifiers from all assigned personalities.
     */
    private double getPersonalityMultiplier(@Nonnull BotAction action) {
        org.twightlight.skywarstrainer.ai.personality.PersonalityProfile profile =
                bot.getProfile().getPersonalityProfile();
        if (profile.isEmpty()) return 1.0;

        // Map BotAction to the modifier key used in Personality enum
        String key = actionToModifierKey(action);
        double multiplier = profile.getModifier(key);

        // Clamp to prevent extreme values
        return MathUtil.clamp(multiplier, 0.01, 5.0);
    }

    /**
     * Maps a BotAction to the personality modifier key.
     */
    @Nonnull
    private String actionToModifierKey(@Nonnull BotAction action) {
        switch (action) {
            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED:
                return "FIGHT";
            case HUNT_PLAYER:
                return "HUNT";
            case LOOT_OWN_ISLAND:
                return "LOOT_OWN_ISLAND";
            case LOOT_MID:
            case LOOT_OTHER_ISLAND:
                return "LOOT";
            case FLEE:
                return "FLEE";
            case ENCHANT:
                return "ENCHANT";
            case CAMP_POSITION:
                return "CAMP";
            case BRIDGE_TO_MID:
                return "BRIDGE_TO_MID";
            case BRIDGE_TO_PLAYER:
                return "BRIDGE_TO_PLAYER";
            case BREAK_ENEMY_BRIDGE:
                return "BREAK_ENEMY_BRIDGE";
            case USE_ENDER_PEARL:
                return "USE_ENDER_PEARL";
            case ORGANIZE_INVENTORY:
                return "EQUIP";
            default:
                return action.name();
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

