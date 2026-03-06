package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.SprintController;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Sprint-reset strategy: Toggle sprint off/on between hits for maximum knockback.
 *
 * <p>Similar to W-tapping but uses the sprint key toggle instead of the forward
 * key. The bot sprints → hits → toggles sprint off → toggles sprint on → hits.
 * Each hit after resetting is a new sprint-hit with full KB.</p>
 *
 * <p>The {@code sprintResetChance} parameter determines if the bot performs a
 * sprint reset on each hit. At HARD+ (0.8), most hits are sprint-reset hits.
 * At BEGINNER (0.0), the bot never attempts it.</p>
 */
public class SprintResetStrategy implements CombatStrategy {

    /** Whether the sprint reset was triggered this tick. */
    private boolean resetTriggered;

    /** Ticks since last sprint reset — prevents double-reset. */
    private int cooldownTicks;

    /** Minimum ticks between sprint resets. */
    private static final int RESET_COOLDOWN = 5;

    @Nonnull
    @Override
    public String getName() {
        return "SprintReset";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getSprintResetChance() <= 0.0) return false;
        if (cooldownTicks > 0) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        MovementController mc = bot.getMovementController();
        if (mc == null) return false;

        // Must be sprinting to reset sprint
        return mc.getSprintController().isSprinting();
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        SprintController sprint = mc.getSprintController();

        // Roll for sprint reset on this tick
        if (RandomUtil.chance(diff.getSprintResetChance() * 0.3)) {
            // The SprintController handles the actual toggle off/on timing
            sprint.performSprintReset();
            resetTriggered = true;
            cooldownTicks = RESET_COOLDOWN;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Sprint reset is a core combo mechanic, high priority when available
        return 6.0 * diff.getSprintResetChance();
    }

    @Override
    public void reset() {
        resetTriggered = false;
        cooldownTicks = 0;
    }
}

