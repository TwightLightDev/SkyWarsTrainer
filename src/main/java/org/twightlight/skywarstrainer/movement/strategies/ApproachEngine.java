package org.twightlight.skywarstrainer.movement.strategies;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.counter.CounterModifiers;
import org.twightlight.skywarstrainer.combat.counter.EnemyProfile;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.strategies.approaches.*;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Central manager that owns all approach strategies and orchestrates
 * the approach lifecycle: evaluate → select → execute → arrive/fail.
 *
 * <p>Called by the HUNTING state behavior tree when the target is on
 * a different island from the bot.</p>
 */
public class ApproachEngine {

    private final TrainerBot bot;

    /** All registered approach strategies. */
    private final List<ApproachStrategy> strategies;

    /** The currently active approach strategy. */
    private ApproachStrategy activeStrategy;

    /** The current target being approached. */
    private LivingEntity currentTarget;

    /** Whether an approach is currently in progress. */
    private boolean active;

    /** Reusable context for evaluation. */
    private final ApproachContext context;

    /**
     * Creates a new ApproachManager for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ApproachEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.strategies = new ArrayList<>();
        this.context = new ApproachContext();
        this.active = false;

        registerStrategies();
    }

    /**
     * Registers all approach strategy implementations.
     */
    private void registerStrategies() {
        strategies.add(new DirectRushApproach());
        strategies.add(new DiagonalApproach());
        strategies.add(new VerticalApproach());
        strategies.add(new SplitPathApproach());
        strategies.add(new FlankingApproach());
        strategies.add(new PearlApproach());
    }

    /**
     * Begins an approach toward the given target. Evaluates all strategies,
     * selects the best one, calculates a path, and starts execution.
     *
     * @param target the target to approach
     * @return true if an approach strategy was selected and started
     */
    public boolean startApproach(@Nonnull LivingEntity target) {
        this.currentTarget = target;
        context.populate(bot, target);

        // Evaluate all strategies
        ApproachStrategy best = selectBestStrategy(target);
        if (best == null) {
            DebugLogger.log(bot, "ApproachManager: no viable approach strategy found");
            return false;
        }

        // Initialize and start
        best.reset();
        best.calculateApproachPath(bot, target);
        this.activeStrategy = best;
        this.active = true;

        DebugLogger.log(bot, "ApproachManager: starting %s approach to %s (dist=%.1f)",
                best.getName(), target.getName(), context.distanceToTarget);

        return true;
    }

    /**
     * Ticks the active approach strategy.
     *
     * @return the approach result, or FAILED if no approach is active
     */
    @Nonnull
    public ApproachTickResult tick() {
        if (!active || activeStrategy == null) {
            return ApproachTickResult.FAILED;
        }

        // Re-validate target
        if (currentTarget == null || currentTarget.isDead()) {
            cancelApproach();
            return ApproachTickResult.FAILED;
        }

        ApproachTickResult result = activeStrategy.tick(bot);

        switch (result) {
            case ARRIVED:
            case FAILED:
            case INTERRUPTED:
                active = false;
                DebugLogger.log(bot, "ApproachManager: approach %s with result %s",
                        activeStrategy.getName(), result.name());
                break;
            case IN_PROGRESS:
                // Continue
                break;
        }

        return result;
    }

    /**
     * Cancels the current approach.
     */
    public void cancelApproach() {
        if (activeStrategy != null) {
            activeStrategy.reset();
        }
        activeStrategy = null;
        currentTarget = null;
        active = false;
    }

    /**
     * Selects the best approach strategy using weighted priority scoring.
     *
     * <p>For each viable strategy:
     * <br>1. Check shouldUse() — is it viable?
     * <br>2. Get base priority
     * <br>3. Apply personality multipliers
     * <br>4. Apply situation multipliers
     * <br>5. Apply decision quality noise
     * <br>6. Now applies counter-play-aware situation
     * <br>7. Pick highest</p>
     *
     * <ul>
     *   <li>If the enemy is a known camper (counter recommends bridgeCut), prefer
     *       DiagonalApproach or SplitPath to avoid getting zoned.</li>
     *   <li>If the enemy uses projectiles heavily, boost DiagonalApproach (harder to hit).</li>
     *   <li>If counter modifiers recommend use projectiles first, boost approaches that
     *       keep distance initially (Vertical, Flanking).</li>
     * </ul></p>
     */
    @Nullable
    private ApproachStrategy selectBestStrategy(@Nonnull LivingEntity target) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double decisionQuality = diff.getDecisionQuality();

        ApproachStrategy best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (ApproachStrategy strategy : strategies) {
            if (!strategy.shouldUse(bot, target, context)) continue;

            double score = strategy.getPriority(bot);

            // ═══ Phase 7: Counter-play-aware situation multipliers ═══
            score *= getSituationMultiplier(strategy);

            // Apply decision quality noise
            double noiseRange = (1.0 - decisionQuality) * 0.3;
            score += RandomUtil.nextDouble(-noiseRange, noiseRange);

            if (score > bestScore) {
                bestScore = score;
                best = strategy;
            }
        }

        return best;
    }

    /**
     * Computes situation-based multipliers for a strategy's priority.
     * Factors in enemy behavior analysis from the context's counter modifiers.
     *
     * @param strategy the strategy to compute multipliers for
     * @return the combined situation multiplier
     */
    private double getSituationMultiplier(@Nonnull ApproachStrategy strategy) {
        double mult = 1.0;
        String name = strategy.getName();

        // ── General situation multipliers ──
        if ("DirectRush".equals(name)) {
            if (context.targetDistracted) mult *= 1.5;
            if (context.is1v1) mult *= 1.2;
            if (context.targetHasBowAimed) mult *= 0.5;
            if (context.targetHealthEstimate < 0.3) mult *= 1.3; // Low HP — rush!
        } else if ("DiagonalApproach".equals(name)) {
            if (context.targetHasBowAimed) mult *= 1.5;
            if (context.multipleEnemies) mult *= 1.3;
        } else if ("VerticalApproach".equals(name)) {
            if (context.heightDifference < -2) mult *= 1.5; // Target is below — don't tower
            if (context.heightDifference > 2) mult *= 0.7;  // Already need to go up
        } else if ("PearlApproach".equals(name)) {
            // Only pearl if the enemy is dangerous enough to warrant it
            if (context.targetHealthEstimate > 0.7) mult *= 1.3;
        }

        // ── Phase 7: Counter-play-aware multipliers ──
        if (context.counterMods != null) {
            CounterModifiers cm = context.counterMods;

            // If counter recommends projectiles first, prefer approaches that
            // keep distance (Vertical, Flanking) over DirectRush
            if (cm.useProjectilesFirst) {
                if ("DirectRush".equals(name)) mult *= 0.7;
                if ("VerticalApproach".equals(name)) mult *= 1.3;
                if ("FlankingApproach".equals(name)) mult *= 1.2;
            }

            // If counter recommends avoiding void edge (trickster enemy),
            // prefer wider approaches that give more room
            if (cm.avoidVoidEdge) {
                if ("DiagonalApproach".equals(name)) mult *= 1.3;
                if ("DirectRush".equals(name)) mult *= 0.8;
            }

            // If counter recommends bridge cutting (enemy is aggressive bridger),
            // prefer defensive/flanking approaches
            if (cm.bridgeCut) {
                if ("FlankingApproach".equals(name)) mult *= 1.4;
                if ("DiagonalApproach".equals(name)) mult *= 1.2;
            }

            // If enemy uses bait, be careful with direct approaches
            if (cm.watchForBait) {
                if ("DirectRush".equals(name)) mult *= 0.7;
                if ("SplitPathApproach".equals(name)) mult *= 1.3;
            }

            // If playing passive against a strong enemy, prefer non-committal approaches
            if (cm.playPassive) {
                if ("DirectRush".equals(name)) mult *= 0.5;
                if ("FlankingApproach".equals(name)) mult *= 1.5;
                if ("PearlApproach".equals(name)) mult *= 0.5; // Don't waste pearl
            }
        }

        // ── Enemy profile-based multipliers ──
        if (context.enemyProfile != null) {
            EnemyProfile ep = context.enemyProfile;
            switch (ep.observedCombatStyle) {
                case AGGRESSIVE:
                    // Against aggressors, don't rush (they want melee)
                    if ("DirectRush".equals(name)) mult *= 0.8;
                    break;
                case PROJECTILE:
                    // Against snipers, diagonal to dodge
                    if ("DiagonalApproach".equals(name)) mult *= 1.4;
                    if ("DirectRush".equals(name)) mult *= 0.6;
                    break;
                case PASSIVE:
                    // Against passive players, direct rush is fine
                    if ("DirectRush".equals(name)) mult *= 1.3;
                    break;
                case TRICKSTER:
                    // Against tricksters, flanking avoids traps
                    if ("FlankingApproach".equals(name)) mult *= 1.5;
                    break;
                default:
                    break;
            }
        }

        return mult;
    }


    // ─── Accessors ──────────────────────────────────────────────

    /** @return true if an approach is currently in progress */
    public boolean isActive() { return active; }

    /** @return the currently active approach strategy */
    @Nullable
    public ApproachStrategy getActiveStrategy() { return activeStrategy; }

    /** @return the current target being approached */
    @Nullable
    public LivingEntity getCurrentTarget() { return currentTarget; }
}
