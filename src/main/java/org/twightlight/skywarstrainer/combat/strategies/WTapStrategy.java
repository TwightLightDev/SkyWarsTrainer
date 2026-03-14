package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.movement.SprintController;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * W-tap strategy: Sprint → hit → release W → re-sprint → hit.
 *
 * <p>In 1.8 PvP, releasing the forward key (W) for 1-3 ticks between hits
 * causes each subsequent hit to be a fresh sprint-hit, delivering maximum
 * knockback. This is the core mechanic behind combo PvP.</p>
 *
 * <p>The {@code wTapEfficiency} parameter controls how well the bot times
 * the W-release. At EXPERT difficulty (0.92), the timing is near frame-perfect.
 * At BEGINNER (0.0), the bot never attempts W-taps.</p>
 *
 * <p>W-tapping is interleaved with strafing for maximum combo potential.</p>
 */
public class WTapStrategy implements CombatStrategy {

    /**
     * Phase of the W-tap cycle.
     * SPRINTING → hit → RELEASING → RECOVERING → SPRINTING
     */
    private enum Phase {
        /** Moving forward with sprint active, waiting to hit. */
        SPRINTING,
        /** W key released, slowing down briefly after a hit. */
        RELEASING,
        /** Re-engaging sprint after the release gap. */
        RECOVERING
    }

    private Phase currentPhase;

    /** Ticks remaining in the current phase. */
    private int phaseTimer;

    /** Whether we just landed a hit (triggers the W-release). */
    private boolean hitLandedThisCycle;

    @Nonnull
    @Override
    public String getName() {
        return "WTap";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // W-tapping is only attempted if the bot has the skill for it
        if (diff.getWTapEfficiency() <= 0.0) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        // Must be in melee range
        LivingEntity target = getCurrentTarget(bot);
        if (target == null) return false;

        double range = botEntity.getLocation().distance(target.getLocation());
        return range <= 4.0;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        SprintController sprint = mc.getSprintController();
        DifficultyProfile diff = bot.getDifficultyProfile();

        // [FIX] Get the current target so we can use setMoveTarget with authority
        // instead of setMovingForward which bypasses the authority system.
        LivingEntity target = getCurrentTarget(bot);

        switch (currentPhase) {
            case SPRINTING:
                // Sprint toward the enemy
                sprint.startSprinting();
                // [FIX] Use COMBAT authority move target instead of setMovingForward(true).
                // setMovingForward bypasses authority and clears moveTarget, which causes
                // handlePositioning()'s COMBAT-authority target to be overwritten every tick.
                if (target != null) {
                    mc.setMoveTarget(target.getLocation(), MovementController.MovementAuthority.COMBAT);
                }

                if (hitLandedThisCycle && RandomUtil.chance(diff.getWTapEfficiency())) {
                    currentPhase = Phase.RELEASING;
                    int maxRelease = (int) Math.round(3.0 - 2.0 * diff.getDifficulty().asFraction());
                    phaseTimer = RandomUtil.nextInt(1, Math.max(1, maxRelease));
                    // [FIX] Stop movement by clearing the target with COMBAT authority
                    // instead of setMovingForward(false) which bypasses authority.
                    mc.setMoveTarget(null, MovementController.MovementAuthority.COMBAT);
                    sprint.stopSprinting();
                    hitLandedThisCycle = false;
                }
                break;

            case RELEASING:
                // W key released — not moving forward, no sprint.
                // [FIX] Ensure movement stays cleared (don't let handlePositioning overwrite)
                // The null target with COMBAT authority from above persists.
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.RECOVERING;
                    phaseTimer = 1;
                }
                break;

            case RECOVERING:
                // Re-engage sprint and forward movement
                sprint.startSprinting();
                // [FIX] Use authority-aware movement toward target
                if (target != null) {
                    mc.setMoveTarget(target.getLocation(), MovementController.MovementAuthority.COMBAT);
                }
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.SPRINTING;
                }
                break;
        }
    }


    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // W-tapping priority scales with efficiency. It's a core combo mechanic.
        return 7.0 * diff.getWTapEfficiency();
    }

    @Override
    public void reset() {
        currentPhase = Phase.SPRINTING;
        phaseTimer = 0;
        hitLandedThisCycle = false;
    }

    /**
     * Notifies the strategy that the bot landed a hit. This triggers the
     * W-release phase on the next execution tick.
     */
    public void onHitLanded() {
        hitLandedThisCycle = true;
    }

    /**
     * Gets the current combat target from the combat engine, if available.
     */
    @javax.annotation.Nullable
    private LivingEntity getCurrentTarget(@Nonnull TrainerBot bot) {
        // Access the target through the bot's combat engine aim controller
        // Since we can't directly reference CombatEngine from here to avoid
        // circular dependency, we check the aim controller's target
        // through the movement controller's look target or nearby entities
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        // Find the nearest player as a proxy for the combat target
        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(5, 5, 5)) {
            if (entity instanceof org.bukkit.entity.Player && !entity.isDead()
                    && !entity.getUniqueId().equals(botEntity.getUniqueId())) {
                double dist = botEntity.getLocation().distanceSquared(entity.getLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = (LivingEntity) entity;
                }
            }
        }
        return nearest;
    }
}

