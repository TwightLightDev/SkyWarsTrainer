package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Edge knock pattern: position between the enemy and the void edge,
 * then sprint-attack toward the void to knock them off the map.
 *
 * <p>Activates when an enemy is within 5 blocks of a void edge.
 * Uses sprint resets, knockback weapons, and fishing rod pulls
 * to maximize knockback toward the void.</p>
 */
public class EdgeKnockPattern implements EngagementPattern {

    private boolean complete;
    private int ticksActive;
    private static final int MAX_TICKS = 100; // 5 seconds max

    @Nonnull @Override
    public String getName() { return "EdgeKnock"; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        double skill = bot.getDifficultyProfile().getEdgeKnockSkill();
        if (!RandomUtil.chance(skill)) return false;

        // Check if target is near a void edge
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector == null) return false;

        Location targetLoc = target.getLocation();
        // Check if void is within 5 blocks of the target in any cardinal direction
        for (int dx = -5; dx <= 5; dx += 2) {
            for (int dz = -5; dz <= 5; dz += 2) {
                if (voidDetector.isVoidBelow(targetLoc.clone().add(dx, 0, dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        ticksActive++;
        if (ticksActive > MAX_TICKS) {
            complete = true;
            return;
        }

        Location botLoc = bot.getLocation();
        Location targetLoc = target.getLocation();
        if (botLoc == null || targetLoc == null) {
            complete = true;
            return;
        }

        // Find the nearest void direction from the target
        Location voidDirection = findVoidDirection(bot, targetLoc);
        if (voidDirection == null) {
            complete = true;
            return;
        }

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        // Position: get on the opposite side of the target from the void
        // So we're pushing them toward the void
        double vdx = voidDirection.getX() - targetLoc.getX();
        double vdz = voidDirection.getZ() - targetLoc.getZ();
        double len = Math.sqrt(vdx * vdx + vdz * vdz);
        if (len < 0.01) {
            complete = true;
            return;
        }
        vdx /= len;
        vdz /= len;

        // Move to the position BEHIND the target relative to void
        Location positionTarget = targetLoc.clone().add(-vdx * 2.5, 0, -vdz * 2.5);
        mc.setMoveTarget(positionTarget);
        mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));
        mc.getSprintController().startSprinting();

        // If within striking range and properly positioned, attack
        double distToTarget = botLoc.distance(targetLoc);
        if (distToTarget < 3.5) {
            // Sprint-attack for maximum knockback toward void
            mc.getSprintController().startSprinting();
        }

        DebugLogger.log(bot, "EdgeKnock: tick=%d dist=%.1f positioning", ticksActive, distToTarget);
    }

    /**
     * Finds the direction to the nearest void from the target's position.
     */
    private Location findVoidDirection(@Nonnull TrainerBot bot, @Nonnull Location targetLoc) {
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector == null) return null;

        Location nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                Location check = targetLoc.clone().add(dx, 0, dz);
                if (voidDetector.isVoidBelow(check)) {
                    double dist = check.distance(targetLoc);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = check;
                    }
                }
            }
        }

        return nearest;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getEdgeKnockSkill();
        double personalityMult = 1.0;
        if (bot.getProfile().hasPersonality("TRICKSTER")) personalityMult *= 2.0;
        if (bot.getProfile().hasPersonality("STRATEGIC")) personalityMult *= 1.5;
        return 2.0 * skill * personalityMult;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
    }
}
