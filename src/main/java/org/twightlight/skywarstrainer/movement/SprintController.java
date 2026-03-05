package org.twightlight.skywarstrainer.movement;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Controls the bot's sprint state, simulating the double-tap W or toggle-sprint
 * behavior of real players.
 *
 * <p>In Minecraft 1.8, sprinting provides a ~30% speed boost and is essential
 * for PvP (sprint-hitting applies extra knockback). The sprint controller
 * manages sprint activation, deactivation, and the sprint-reset mechanic
 * used in advanced combat.</p>
 *
 * <p>Sprint start has a simulated delay (2 ticks) to mimic the slight lag
 * between pressing the sprint key and actually sprinting. This delay is
 * reduced at higher difficulties.</p>
 */
public class SprintController {

    private final TrainerBot bot;

    /** Whether the bot intends to be sprinting. */
    private boolean wantsSprint;

    /** Whether the bot is actually sprinting (after delay). */
    private boolean isSprinting;

    /**
     * Ticks remaining before sprint activates. Simulates the delay
     * between pressing sprint and the sprint state becoming active.
     */
    private int sprintActivationDelay;

    /**
     * Ticks remaining in a sprint-reset cycle. During a sprint-reset,
     * sprinting is temporarily disabled for 1-3 ticks to allow a new
     * sprint-hit.
     */
    private int sprintResetTicks;

    /** Whether a sprint reset is in progress. */
    private boolean resetting;

    /**
     * Creates a new SprintController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public SprintController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.wantsSprint = false;
        this.isSprinting = false;
        this.sprintActivationDelay = 0;
        this.sprintResetTicks = 0;
        this.resetting = false;
    }

    /**
     * Tick method called every server tick. Manages sprint activation delay
     * and sprint-reset timing.
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        // Handle sprint-reset in progress
        if (resetting) {
            sprintResetTicks--;
            if (sprintResetTicks <= 0) {
                // Reset complete — re-enable sprint
                resetting = false;
                if (wantsSprint) {
                    isSprinting = true;
                    NMSHelper.setSprinting(entity, true);
                }
            }
            return;
        }

        // Handle sprint activation delay
        if (wantsSprint && !isSprinting) {
            sprintActivationDelay--;
            if (sprintActivationDelay <= 0) {
                isSprinting = true;
                NMSHelper.setSprinting(entity, true);
            }
        }

        // Ensure NMS state matches
        if (isSprinting && !resetting) {
            NMSHelper.setSprinting(entity, true);
        }
    }

    /**
     * Commands the bot to start sprinting. Sprint activates after a
     * difficulty-scaled delay.
     */
    public void startSprinting() {
        if (wantsSprint && isSprinting) return; // Already sprinting
        wantsSprint = true;

        // Sprint activation delay: 2 ticks at low difficulty, 0-1 at high
        DifficultyProfile diff = bot.getDifficultyProfile();
        double fraction = diff.getDifficulty().asFraction(); // 0.0=beginner, 1.0=expert
        int maxDelay = (int) Math.round(2.0 * (1.0 - fraction));
        sprintActivationDelay = Math.max(0, maxDelay);

        if (sprintActivationDelay == 0) {
            isSprinting = true;
            LivingEntity entity = bot.getLivingEntity();
            if (entity != null) {
                NMSHelper.setSprinting(entity, true);
            }
        }
    }

    /**
     * Commands the bot to stop sprinting.
     */
    public void stopSprinting() {
        wantsSprint = false;
        isSprinting = false;
        resetting = false;
        sprintActivationDelay = 0;
        sprintResetTicks = 0;

        LivingEntity entity = bot.getLivingEntity();
        if (entity != null) {
            NMSHelper.setSprinting(entity, false);
        }
    }

    /**
     * Performs a sprint-reset: temporarily disables sprint for 1-3 ticks,
     * then re-enables it. This is the core of 1.8 PvP combo mechanics —
     * each subsequent hit after resetting is a new sprint-hit with full KB.
     *
     * <p>The reset duration varies by difficulty: EXPERT bots reset in 1 tick
     * (frame-perfect), while BEGINNER bots take 3 ticks (if they even attempt it).</p>
     */
    public void performSprintReset() {
        if (!isSprinting) return;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        // Disable sprint
        isSprinting = false;
        NMSHelper.setSprinting(entity, false);
        resetting = true;

        // Reset duration based on difficulty
        DifficultyProfile diff = bot.getDifficultyProfile();
        double fraction = diff.getDifficulty().asFraction();
        int minTicks = 1;
        int maxTicks = (int) Math.round(3.0 - 2.0 * fraction); // 3 at beginner, 1 at expert
        sprintResetTicks = RandomUtil.nextInt(minTicks, Math.max(minTicks, maxTicks));
    }

    /**
     * Returns whether the bot is currently sprinting.
     *
     * @return true if actively sprinting
     */
    public boolean isSprinting() {
        return isSprinting && !resetting;
    }

    /**
     * Returns whether the bot wants to be sprinting (may still be in activation delay).
     *
     * @return true if sprint is requested
     */
    public boolean wantsSprint() {
        return wantsSprint;
    }

    /**
     * Returns whether a sprint-reset is currently in progress.
     *
     * @return true if resetting
     */
    public boolean isResetting() {
        return resetting;
    }
}

