package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages all engagement patterns for a single bot. Evaluates patterns
 * during combat, activates the best one, and lets it run until completion.
 *
 * <p>An active engagement pattern overrides the normal per-tick strategy
 * selection in CombatEngine for its duration.</p>
 */
public class EngagementPatternManager {

    private final TrainerBot bot;

    /** All registered patterns. */
    private final List<EngagementPattern> patterns;

    /** The currently active pattern, or null. */
    private EngagementPattern activePattern;

    /** Reusable context object. */
    private final EngagementContext context;

    /**
     * Creates a new EngagementPatternManager.
     *
     * @param bot the owning bot
     */
    public EngagementPatternManager(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.patterns = new ArrayList<>();
        this.context = new EngagementContext();
        this.activePattern = null;

        registerPatterns();
    }

    /**
     * Registers all engagement pattern implementations.
     */
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
     * @return true if an active pattern handled this tick (caller should skip normal strategy)
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
     * Evaluates all patterns and returns the best one to activate.
     *
     * @param bot    the bot
     * @param target the combat target
     * @return the best pattern to activate, or null if none should activate
     */
    @Nullable
    public EngagementPattern evaluatePatterns(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        context.populate(bot, target);

        EngagementPattern best = null;
        double bestPriority = Double.NEGATIVE_INFINITY;

        for (EngagementPattern pattern : patterns) {
            if (!pattern.shouldActivate(bot, target, context)) continue;

            double priority = pattern.getPriority(bot);
            if (priority > bestPriority) {
                bestPriority = priority;
                best = pattern;
            }
        }

        return best;
    }

    /**
     * Activates a pattern. The pattern will run on subsequent ticks until complete.
     *
     * @param pattern the pattern to activate
     */
    public void activate(@Nonnull EngagementPattern pattern) {
        if (activePattern != null) {
            activePattern.reset();
        }
        pattern.reset();
        this.activePattern = pattern;
        DebugLogger.log(bot, "Engagement pattern activated: %s", pattern.getName());
    }

    /** @return the active pattern or null */
    @Nullable
    public EngagementPattern getActivePattern() { return activePattern; }

    /** @return true if a pattern is currently active */
    public boolean hasActivePattern() { return activePattern != null; }
}
