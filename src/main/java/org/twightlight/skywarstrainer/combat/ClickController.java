package org.twightlight.skywarstrainer.combat;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Simulates left-click attacks at realistic CPS (clicks per second).
 *
 * <p>Features:
 * <ul>
 *   <li>Configurable CPS with variance per engagement</li>
 *   <li>Click patterns: BUTTERFLY, JITTER, NORMAL, DRAG</li>
 *   <li>Click jitter (±15% timing variation)</li>
 *   <li>Miss simulation based on aim accuracy and range</li>
 *   <li>Arm swing animation via NMS packets</li>
 * </ul></p>
 */
public class ClickController {

    private final TrainerBot bot;
    private final AimController aimController;

    /** Target CPS for the current engagement (re-rolled per engagement). */
    private double targetCPS;

    /** Milliseconds until the next click is allowed. */
    private long nextClickTimeMs;

    /** Current click pattern. */
    private ClickPattern pattern;

    /** Clicks performed in the current engagement (for pattern variation). */
    private int clickCount;

    /** Whether the last click "connected" (was on target and in range). */
    private boolean lastClickHit;

    /**
     * Creates a ClickController.
     *
     * @param bot           the bot
     * @param aimController the aim controller (for hit detection)
     */
    public ClickController(@Nonnull TrainerBot bot, @Nonnull AimController aimController) {
        this.bot = bot;
        this.aimController = aimController;
        this.nextClickTimeMs = 0;
        this.clickCount = 0;
        this.lastClickHit = false;
        rollNewEngagement();
    }

    /**
     * Rolls a new target CPS and click pattern for a new combat engagement.
     * Called when a new fight starts or the target changes.
     */
    public void rollNewEngagement() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double variance = diff.getCpsVariance();
        double min = Math.max(1, diff.getMaxCPS() - variance);
        double max = diff.getMaxCPS();
        targetCPS = RandomUtil.nextDouble(min, max);
        clickCount = 0;

        // Pattern selection based on difficulty
        double accuracy = diff.getAimAccuracy();
        if (accuracy >= 0.85) {
            // Expert players use jitter or butterfly
            pattern = RandomUtil.chance(0.5) ? ClickPattern.BUTTERFLY : ClickPattern.JITTER;
        } else if (accuracy >= 0.6) {
            pattern = RandomUtil.chance(0.6) ? ClickPattern.NORMAL : ClickPattern.BUTTERFLY;
        } else {
            pattern = RandomUtil.chance(0.7) ? ClickPattern.NORMAL : ClickPattern.DRAG;
        }
    }

    /**
     * Attempts to perform a click/attack this tick. Returns true if a click
     * occurred and connected with the target.
     *
     * @return true if a hit was landed this tick
     */
    public boolean tryClick() {
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs < nextClickTimeMs) {
            return false; // Still on cooldown from last click
        }

        LivingEntity botEntity = bot.getLivingEntity();
        LivingEntity target = aimController.getTarget();
        if (botEntity == null || target == null || target.isDead()) {
            return false;
        }

        // Calculate click interval based on CPS and pattern
        double effectiveCPS = getPatternAdjustedCPS();
        double baseIntervalMs = 1000.0 / effectiveCPS;

        // Add ±15% jitter
        double jitter = baseIntervalMs * 0.15;
        double intervalMs = baseIntervalMs + RandomUtil.nextDouble(-jitter, jitter);
        intervalMs = Math.max(50, intervalMs); // Floor at 50ms (20 CPS hard cap)

        nextClickTimeMs = currentTimeMs + (long) intervalMs;
        clickCount++;

        // Determine if the click hits
        boolean hit = checkHit(botEntity, target);
        lastClickHit = hit;

        // Perform the attack (swing arm animation and damage)
        if (hit) {
            // Use NMS to damage the target as if the bot attacked
            NMSHelper.attackEntity(botEntity, target);
        }

        // Always play arm swing animation (even on miss, like a real player)
        NMSHelper.playArmSwing(botEntity);

        return hit;
    }

    /**
     * Returns the CPS adjusted by the current click pattern.
     * Patterns create realistic rhythm variation.
     */
    private double getPatternAdjustedCPS() {
        switch (pattern) {
            case BUTTERFLY:
                // Alternating fast-slow: odd clicks are faster
                return (clickCount % 2 == 0)
                        ? targetCPS * 1.15
                        : targetCPS * 0.85;

            case JITTER:
                // Consistent high-speed with tiny variance
                return targetCPS + RandomUtil.gaussian(0, 0.3);

            case NORMAL:
                // Regular rhythm
                return targetCPS;

            case DRAG:
                // Occasionally holds click longer (reduces effective CPS)
                if (RandomUtil.chance(0.15)) {
                    return targetCPS * 0.5; // Drag click: slower
                }
                return targetCPS;

            default:
                return targetCPS;
        }
    }

    /**
     * Checks whether a click would hit the target based on aim accuracy, range,
     * and random miss chance.
     */
    private boolean checkHit(@Nonnull LivingEntity botEntity, @Nonnull LivingEntity target) {
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Check range (3.0 blocks in 1.8)
        double distance = botEntity.getLocation().distance(target.getLocation());
        if (distance > 3.0) {
            return false; // Out of melee range
        }

        // Check aim error
        double aimError = aimController.getAimError();
        // Threshold scales with distance: closer = easier to hit even with bad aim
        double hitThreshold = 5.0 + (3.0 - distance) * 3.0;
        if (aimError > hitThreshold) {
            return false; // Aim too far off target
        }

        // Random miss chance based on inverse accuracy
        double missChance = (1.0 - diff.getAimAccuracy()) * 0.3;
        if (RandomUtil.chance(missChance)) {
            return false; // Random miss
        }

        return true;
    }

    /** @return true if the last click connected */
    public boolean didLastClickHit() { return lastClickHit; }

    /** @return the current target CPS */
    public double getTargetCPS() { return targetCPS; }

    /** @return the current click pattern */
    @Nonnull
    public ClickPattern getPattern() { return pattern; }

    /** @return total clicks in current engagement */
    public int getClickCount() { return clickCount; }

    /**
     * Click rhythm patterns that create realistic CPS feel.
     */
    public enum ClickPattern {
        /** Alternating fast-slow intervals (8-14 CPS feel). */
        BUTTERFLY,
        /** Consistent high-speed with tiny variance (10-16 CPS). */
        JITTER,
        /** Regular rhythm (6-9 CPS). */
        NORMAL,
        /** Occasionally holds click longer (1-3 CPS reduction). */
        DRAG
    }
}

