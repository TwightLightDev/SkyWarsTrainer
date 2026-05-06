package org.twightlight.skywarstrainer.ai.llm;

import org.twightlight.skywarstrainer.ai.decision.DecisionContext;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine.BotAction;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.ai.decision.considerations.*;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Validates and scores LLM advice against the current game context before
 * it is delivered to the {@link org.twightlight.skywarstrainer.ai.strategy.StrategyPlanner}.
 *
 * <p>This validator hooks into the existing {@link LLMManager#processPendingResponses()}
 * pipeline, adding a validation step between parsing and delivery. It does NOT create
 * a separate system — it is called as a static utility method.</p>
 *
 * <p>Validation checks include:</p>
 * <ul>
 *   <li>Coherence: does the LLM's high multiplier for an action match what the
 *       existing consideration scores suggest for the current context?</li>
 *   <li>Contradiction: are mutually exclusive actions (e.g., FLEE + FIGHT) both boosted?</li>
 *   <li>Information content: does the advice provide enough action multipliers?</li>
 *   <li>Trust weighting: scaled by the difficulty profile's LLM consult chance.</li>
 * </ul>
 *
 * <p>Low-confidence advice has its multipliers attenuated toward neutral (1.0)
 * before delivery. Advice below the 0.2 threshold is rejected entirely.</p>
 */
public final class LLMAdviceValidator {

    /** Minimum final confidence to accept advice. Below this, advice is discarded. */
    private static final double REJECTION_THRESHOLD = 0.2;

    /** Multiplier threshold above which an LLM action is considered "boosted". */
    private static final double HIGH_MULTIPLIER_THRESHOLD = 1.5;

    /** Consideration score below which a boosted action is considered incoherent. */
    private static final double LOW_UTILITY_THRESHOLD = 0.15;

    /** Confidence penalty per incoherent action. */
    private static final double INCOHERENCE_PENALTY = 0.15;

    // ── Shared stateless consideration instances for coherence checking ──
    private static final HealthConsideration HEALTH = new HealthConsideration();
    private static final ThreatConsideration THREAT = new ThreatConsideration();
    private static final LootValueConsideration LOOT = new LootValueConsideration();
    private static final EquipmentGapConsideration EQUIPMENT = new EquipmentGapConsideration();
    private static final TimePressureConsideration TIME = new TimePressureConsideration();
    private static final ZoneControlConsideration ZONE = new ZoneControlConsideration();
    private static final GamePhaseConsideration GAME_PHASE = new GamePhaseConsideration();
    private static final ResourceConsideration RESOURCE = new ResourceConsideration();

    private LLMAdviceValidator() {
        // Static utility class — no instantiation
    }

    /**
     * Validates LLM advice against the current game context, computes a confidence
     * score, attenuates multipliers based on confidence, and returns the modified
     * advice — or null if confidence is too low.
     *
     * <p>This method is the single entry point for LLM advice validation. It is
     * called from {@link LLMManager#processPendingResponses()} between parsing
     * and delivery to the StrategyPlanner.</p>
     *
     * @param rawAdvice the parsed LLM advice (unvalidated)
     * @param context   the current decision context snapshot
     * @param diff      the bot's difficulty profile
     * @return the validated and attenuated advice, or null if rejected
     */
    @Nullable
    public static LLMResponseParser.ParsedAdvice validateAndScore(
            @Nonnull LLMResponseParser.ParsedAdvice rawAdvice,
            @Nonnull DecisionContext context,
            @Nonnull DifficultyProfile diff) {

        double confidence = 1.0;

        // ═══ 1. Coherence check: each high-multiplier action vs. context utility ═══
        int incoherentCount = 0;
        for (Map.Entry<BotAction, Double> entry : rawAdvice.actionMultipliers.entrySet()) {
            BotAction action = entry.getKey();
            double multiplier = entry.getValue();

            if (multiplier > HIGH_MULTIPLIER_THRESHOLD) {
                double contextUtility = computeContextUtility(action, context);
                if (contextUtility < LOW_UTILITY_THRESHOLD) {
                    incoherentCount++;
                }
            }
        }
        confidence -= incoherentCount * INCOHERENCE_PENALTY;

        // ═══ 2. Contradiction check: FLEE + FIGHT both boosted ═══
        boolean fleeBoosted = isBoosted(rawAdvice, BotAction.FLEE);
        boolean fightBoosted = isBoosted(rawAdvice, BotAction.FIGHT_NEAREST)
                || isBoosted(rawAdvice, BotAction.FIGHT_WEAKEST)
                || isBoosted(rawAdvice, BotAction.FIGHT_TARGETED);
        if (fleeBoosted && fightBoosted) {
            confidence *= 0.5;
        }

        // Also check CAMP + HUNT contradiction
        boolean campBoosted = isBoosted(rawAdvice, BotAction.CAMP_POSITION);
        boolean huntBoosted = isBoosted(rawAdvice, BotAction.HUNT_PLAYER);
        if (campBoosted && huntBoosted) {
            confidence *= 0.7;
        }

        // ═══ 3. Low-information check ═══
        if (rawAdvice.actionMultipliers.isEmpty()) {
            confidence *= 0.5;
        } else if (rawAdvice.actionMultipliers.size() <= 1) {
            confidence *= 0.7;
        }

        // ═══ 4. Trust weighting from difficulty profile ═══
        double trustWeight = diff.getPlanLLMConsultChance();
        confidence = Math.max(0.0, confidence) * trustWeight;

        // ═══ 5. Rejection check ═══
        if (confidence < REJECTION_THRESHOLD) {
            return null;
        }

        // ═══ 6. Attenuate multipliers toward neutral based on confidence ═══
        Map<BotAction, Double> attenuated = new EnumMap<>(BotAction.class);
        for (Map.Entry<BotAction, Double> entry : rawAdvice.actionMultipliers.entrySet()) {
            double original = entry.getValue();
            // Blend toward 1.0: at confidence=1.0, full original; at confidence=0.0, neutral
            double blended = 1.0 + confidence * (original - 1.0);
            attenuated.put(entry.getKey(), blended);
        }
        rawAdvice.actionMultipliers.clear();
        rawAdvice.actionMultipliers.putAll(attenuated);

        // ═══ 7. Set confidence on the advice object ═══
        rawAdvice.confidence = confidence;

        return rawAdvice;
    }

    /**
     * Computes an approximate context-based utility for a given action by querying
     * the same consideration scorers the DecisionEngine uses. This provides a
     * "sanity check" score: if the context suggests an action is very bad, but
     * the LLM boosted it, that's incoherent.
     *
     * <p>Uses a simplified subset of considerations per action — not the full
     * weighted evaluation (that would duplicate DecisionEngine logic). Instead,
     * we pick the 1-2 most relevant considerations and average their scores.</p>
     *
     * @param action  the action to check
     * @param context the current context
     * @return approximate utility in [0.0, 1.0]
     */
    private static double computeContextUtility(@Nonnull BotAction action,
                                                @Nonnull DecisionContext context) {
        switch (action) {
            case FIGHT_NEAREST:
            case FIGHT_WEAKEST:
            case FIGHT_TARGETED:
                // Fight makes sense when threats are near and health is OK
                double threatScore = THREAT.score(context);
                double healthPenalty = HEALTH.score(context); // High when health is LOW
                return MathUtil.clamp(threatScore * 0.6 + (1.0 - healthPenalty) * 0.4, 0.0, 1.0);

            case FLEE:
                // Flee makes sense when health is low and threats are high
                return MathUtil.clamp(HEALTH.score(context) * 0.7 + THREAT.score(context) * 0.3, 0.0, 1.0);

            case LOOT_OWN_ISLAND:
            case LOOT_MID:
            case LOOT_OTHER_ISLAND:
                return MathUtil.clamp(LOOT.score(context), 0.0, 1.0);

            case BRIDGE_TO_MID:
            case BRIDGE_TO_PLAYER:
                return MathUtil.clamp(RESOURCE.score(context) * 0.4 + ZONE.score(context) * 0.3
                        + TIME.score(context) * 0.3, 0.0, 1.0);

            case HUNT_PLAYER:
                return MathUtil.clamp(EQUIPMENT.score(context) * 0.4 + TIME.score(context) * 0.3
                        + GAME_PHASE.score(context) * 0.3, 0.0, 1.0);

            case HEAL:
            case EAT_FOOD:
                return MathUtil.clamp(HEALTH.score(context), 0.0, 1.0);

            case ENCHANT:
                return MathUtil.clamp(LOOT.score(context) * 0.3 + (1.0 - THREAT.score(context)) * 0.4
                        + (1.0 - TIME.score(context)) * 0.3, 0.0, 1.0);

            case CAMP_POSITION:
            case BUILD_FORTIFICATION:
                return MathUtil.clamp(ZONE.score(context) * 0.5 + RESOURCE.score(context) * 0.3
                        + (1.0 - THREAT.score(context)) * 0.2, 0.0, 1.0);

            case ORGANIZE_INVENTORY:
                return MathUtil.clamp((1.0 - THREAT.score(context)) * 0.7
                        + RESOURCE.score(context) * 0.3, 0.0, 1.0);

            case USE_ENDER_PEARL:
                return MathUtil.clamp(THREAT.score(context) * 0.5 + HEALTH.score(context) * 0.5, 0.0, 1.0);

            case BREAK_ENEMY_BRIDGE:
                return MathUtil.clamp(ZONE.score(context) * 0.6 + THREAT.score(context) * 0.4, 0.0, 1.0);

            case DRINK_POTION:
                return MathUtil.clamp(THREAT.score(context) * 0.4 + EQUIPMENT.score(context) * 0.3
                        + TIME.score(context) * 0.3, 0.0, 1.0);

            default:
                return 0.5; // Unknown action — neutral confidence
        }
    }

    /**
     * Checks whether an action has a high multiplier (>1.5) in the advice.
     *
     * @param advice the advice to check
     * @param action the action to look for
     * @return true if the action's multiplier exceeds the threshold
     */
    private static boolean isBoosted(@Nonnull LLMResponseParser.ParsedAdvice advice,
                                     @Nonnull BotAction action) {
        Double mult = advice.actionMultipliers.get(action);
        return mult != null && mult > HIGH_MULTIPLIER_THRESHOLD;
    }
}
