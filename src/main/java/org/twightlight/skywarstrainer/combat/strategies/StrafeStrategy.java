package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.StrafeController;

import javax.annotation.Nonnull;

/**
 * A/D strafing while attacking — the foundation of all 1.8 PvP movement.
 *
 * <p>Strafing makes the bot harder to hit by moving laterally during combat.
 * The quality of strafing is controlled by two parameters:
 * <ul>
 *   <li>{@code strafeIntensity}: How fast the bot strafes (lateral speed)</li>
 *   <li>{@code strafeUnpredictability}: How random the pattern is (0 = ABABAB, 1 = fully random)</li>
 * </ul></p>
 *
 * <p>This strategy is almost always active during melee combat. It operates in
 * parallel with click attacks and other strategies. At HARD+ difficulty, it
 * combines lateral strafing with forward W-movement for diagonal approaches.</p>
 */
public class StrafeStrategy implements CombatStrategy {

    /** Whether strafing has been activated this engagement. */
    private boolean activated;

    @Nonnull
    @Override
    public String getName() {
        return "Strafe";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        // Only strafe if intensity is above minimum threshold
        if (diff.getStrafeIntensity() < 0.05) return false;

        // Must be in melee range or approaching for melee
        MovementController mc = bot.getMovementController();
        return mc != null;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        StrafeController strafe = mc.getStrafeController();

        if (!activated) {
            strafe.startStrafing();
            activated = true;
        }

        // The StrafeController handles direction changes and velocity
        // via its own tick() method. The StrafeStrategy just ensures
        // it stays active during combat and the movement controller
        // applies the strafe velocity each tick.
        strafe.tick();
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Strafing is a baseline combat behavior — always medium-high priority
        // during melee. The actual intensity scales with difficulty.
        return 5.0 + diff.getStrafeIntensity() * 3.0;
    }

    @Override
    public void reset() {
        activated = false;
    }
}
