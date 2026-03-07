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
 * Combo lock pattern: maintain continuous combo hits without getting hit back.
 * Maintains ~3.0 block distance, sprint-resets on every hit, and strafes
 * unpredictably during the combo chain.
 *
 * <p>Activates when the bot has landed 2+ consecutive hits.</p>
 */
public class ComboLockPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private int comboLength;
    private boolean strafeRight;
    private int strafeSwitchTimer;

    @Nonnull @Override
    public String getName() { return "ComboLock"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        double skill = bot.getDifficultyProfile().getComboLockSkill();
        if (!RandomUtil.chance(skill)) return false;

        ComboTracker tracker = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getComboTracker() : null;
        if (tracker == null) return false;

        // Activate when we've landed 2+ consecutive hits
        return tracker.getHitsLanded() >= 2;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;

        // Check if combo broke (we got hit)
        ComboTracker tracker = bot.getCombatEngine() != null
                ? bot.getCombatEngine().getComboTracker() : null;
        if (tracker != null && tracker.getHitsReceived() > 0) {
            DebugLogger.log(bot, "ComboLock: combo broken after %d hits", comboLength);
            complete = true;
            return;
        }

        // Check if we've exceeded max combo length for this difficulty
        int maxCombo = bot.getDifficultyProfile().getComboLength();
        comboLength = tracker != null ? tracker.getHitsReceived() : 0;
        if (comboLength >= maxCombo) {
            DebugLogger.log(bot, "ComboLock: max combo length %d reached", maxCombo);
            complete = true;
            return;
        }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) {
            complete = true;
            return;
        }

        double dist = botLoc.distance(targetLoc);
        double preferredDist = bot.getDifficultyProfile().getPreferredEngageDistance();

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        // Maintain preferred distance (~3.0 blocks)
        double skill = bot.getDifficultyProfile().getComboLockSkill();
        double distError = (1.0 - skill) * 0.5; // Lower skill = more distance errors

        if (dist > preferredDist + 0.5 + distError) {
            // Too far — sprint toward
            mc.getSprintController().startSprinting();
            mc.setMoveTarget(targetLoc);
        } else if (dist < preferredDist - 0.5 - distError) {
            // Too close — back up slightly
            Location backTarget = botLoc.clone().add(
                    (botLoc.getX() - targetLoc.getX()) * 0.5, 0,
                    (botLoc.getZ() - targetLoc.getZ()) * 0.5);
            mc.setMoveTarget(backTarget);
        }

        // Unpredictable strafing during combo
        strafeSwitchTimer++;
        if (strafeSwitchTimer > RandomUtil.nextInt(5, 15)) {
            strafeRight = !strafeRight;
            strafeSwitchTimer = 0;
        }

        // Look at target
        mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));

        // Sprint-reset logic: the CombatEngine's normal strategies handle the
        // actual attack timing. This pattern handles positioning.

        // Timeout
        if (ticksActive > 200) {
            complete = true;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getComboLockSkill();
        double mult = 1.0;
        if (bot.getProfile().hasPersonality("AGGRESSIVE")) mult *= 1.3;
        if (bot.getProfile().hasPersonality("BERSERKER")) mult *= 1.1;
        return 1.5 * skill * mult;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
        comboLength = 0;
        strafeRight = false;
        strafeSwitchTimer = 0;
    }
}
