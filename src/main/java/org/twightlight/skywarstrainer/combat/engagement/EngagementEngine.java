package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.counter.CounterModifiers;
import org.twightlight.skywarstrainer.combat.counter.EnemyBehaviorAnalyzer;
import org.twightlight.skywarstrainer.combat.engagement.patterns.*;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages all engagement patterns for a single bot. Evaluates patterns
 * during combat, activates the best one, and lets it run until completion.
 *
 * <p><b>UPDATED (Phase 7):</b> Now passes {@link EngagementContext} to
 * pattern evaluation, and factors in {@link CounterModifiers} from the
 * {@link EnemyBehaviorAnalyzer} when selecting patterns. For example,
 * if the enemy is known to bait near void edges, the EdgeKnock pattern
 * priority is reduced.</p>
 */
public class EngagementEngine {

    private final TrainerBot bot;
    private final List<EngagementPattern> patterns;
    private EngagementPattern activePattern;
    private final EngagementContext context;

    /**
     * Creates a new EngagementPatternManager.
     *
     * @param bot the owning bot
     */
    public EngagementEngine(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.patterns = new ArrayList<>();
        this.context = new EngagementContext();
        this.activePattern = null;
        registerPatterns();
    }

    private void registerPatterns() {
        patterns.add(new EdgeKnockPattern());
        patterns.add(new ComboLockPattern());
        patterns.add(new KBCancelPattern());
        patterns.add(new ProjectileOpenerPattern());
        patterns.add(new ThirdPartyPattern());
    }

    /**
     * Ticks the active engagement pattern if one exists.
     *
     * @param bot    the bot
     * @param target the current combat target
     * @return true if an active pattern handled this tick
     */
    public boolean tickActivePattern(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        if (activePattern == null) return false;

        if (activePattern.isComplete()) {
            DebugLogger.log(bot, "Engagement pattern %s completed", activePattern.getName());
            activePattern.reset();
            activePattern = null;
            return false;
        }

        activePattern.tick(bot, target);
        return true;
    }

    /**
     * Evaluates all patterns against the current combat context and returns
     * the best one to activate. Factors in counter modifiers from the
     * enemy behavior analyzer to adjust pattern priorities.
     *
     * @param bot    the bot
     * @param target the combat target
     * @return the best pattern, or null if none should activate
     */
    @Nullable
    public EngagementPattern evaluatePatterns(@Nonnull TrainerBot bot,
                                              @Nonnull LivingEntity target) {
        // Populate the shared context snapshot
        context.populate(bot, target);

        // Fetch counter modifiers for this specific enemy
        CounterModifiers counterMods = getCounterModifiers(target);

        EngagementPattern best = null;
        double bestPriority = Double.NEGATIVE_INFINITY;

        for (EngagementPattern pattern : patterns) {
            // Pass context to shouldActivate
            if (!pattern.shouldActivate(bot, target, context)) continue;

            double priority = pattern.getPriority(bot);

            // Apply counter modifier adjustments to pattern priority
            priority = applyCounterModifiers(pattern, priority, counterMods);

            if (priority > bestPriority) {
                bestPriority = priority;
                best = pattern;
            }
        }

        return best;
    }

    /**
     * Adjusts a pattern's priority based on counter modifiers for the current enemy.
     * For example, if the enemy is known to bait near void, EdgeKnock priority drops.
     * If the enemy uses projectiles heavily, ProjectileOpener is less useful (they
     * counter it), so we close distance instead.
     */
    private double applyCounterModifiers(@Nonnull EngagementPattern pattern,
                                         double basePriority,
                                         @Nonnull CounterModifiers mods) {
        String name = pattern.getName();

        // If playing passive against this enemy, reduce aggressive pattern priorities
        if (mods.playPassive) {
            if ("EdgeKnock".equals(name) || "ComboLock".equals(name)) {
                basePriority *= 0.5;
            }
        }

        // If avoiding void edge, EdgeKnock is risky (we have to get near void too)
        if (mods.avoidVoidEdge && "EdgeKnock".equals(name)) {
            basePriority *= 0.6;
        }

        // If using projectiles first is recommended, boost ProjectileOpener
        if (mods.useProjectilesFirst && "ProjectileOpener".equals(name)) {
            basePriority *= 1.5;
        }

        // If watching for bait (trickster enemy), be cautious with edge plays
        if (mods.watchForBait && "EdgeKnock".equals(name)) {
            basePriority *= 0.4;
        }

        return basePriority;
    }

    /**
     * Retrieves counter modifiers for the target from the enemy behavior analyzer.
     * Returns neutral modifiers if the analyzer is unavailable.
     */
    @Nonnull
    private CounterModifiers getCounterModifiers(@Nonnull LivingEntity target) {
        EnemyBehaviorAnalyzer analyzer = bot.getEnemyAnalyzer();
        if (analyzer == null) return new CounterModifiers();
        return analyzer.getCounterModifiers(target.getUniqueId());
    }

    /**
     * Activates a pattern.
     *
     * @param pattern the pattern to activate
     */
    public void activate(@Nonnull EngagementPattern pattern) {
        if (activePattern != null) activePattern.reset();
        pattern.reset();
        this.activePattern = pattern;
        DebugLogger.log(bot, "Engagement pattern activated: %s", pattern.getName());
    }

    /** @return the active pattern or null */
    @Nullable public EngagementPattern getActivePattern() { return activePattern; }

    /** @return true if a pattern is currently active */
    public boolean hasActivePattern() { return activePattern != null; }

    /** @return the reusable context for external inspection */
    @Nonnull public EngagementContext getContext() { return context; }
}
