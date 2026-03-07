package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ComboTracker;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * KB cancel pattern: reduce incoming knockback when being comboed.
 * Moves TOWARD the attacker, sprints into their next hit, and attempts
 * to land a trade hit to break their combo.
 *
 * <p>Activates when the bot has received 2+ consecutive hits.</p>
 */
public class KBCancelPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;

    @Nonnull @Override
    public String getName() { return "KBCancel"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        double skill = bot.getDifficultyProfile().getKbCancelSkill();
        if (!RandomUtil.chance(skill)) return false;

        ComboTracker tracker = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getComboTracker() : null;
        if (tracker == null) return false;

        // Activate when we've been hit 2+ times consecutively
        return tracker.getHitsLanded() >= 2;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;

        ComboTracker tracker = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getComboTracker() : null;

        // Check if we broke their combo (we landed a hit)
        if (tracker != null && tracker.getHitsReceived() > 0) {
            DebugLogger.log(bot, "KBCancel: combo broken, landed counter-hit");
            complete = true;
            return;
        }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) {
            complete = true;
            return;
        }

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        // Core KB cancel: move TOWARD the attacker to reduce knockback
        mc.setMoveTarget(targetLoc);
        mc.getSprintController().startSprinting();
        mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));

        // Jump + hit to break their combo
        if (ticksActive % 4 == 0) {
            mc.getJumpController().jump();
        }

        // Reduce current velocity toward attacker (KB cancel mechanic)
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity != null) {
            double kbSkill = bot.getDifficultyProfile().getKbCancelSkill();
            org.bukkit.util.Vector vel = botEntity.getVelocity();
            // Reduce horizontal velocity proportional to skill
            vel.setX(vel.getX() * (1.0 - kbSkill * 0.6));
            vel.setZ(vel.getZ() * (1.0 - kbSkill * 0.6));
            botEntity.setVelocity(vel);
        }

        if (ticksActive > 60) {
            complete = true;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        // High priority — survival skill
        return 2.5 * bot.getDifficultyProfile().getKbCancelSkill();
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
    }
}
