package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.ProjectileHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Defensive behavior that uses projectiles (snowballs, eggs, bow) to zone
 * enemies who are approaching at medium range.
 *
 * <p>When an enemy is at 10-25 block range and approaching (e.g., on a bridge),
 * the bot positions at the edge of its island and throws projectiles to
 * either knock the enemy off their bridge or force them to slow down.</p>
 *
 * <p>Particularly effective against enemies bridging over void — a single
 * snowball hit can result in a void kill. Uses the existing
 * {@link ProjectileHandler} for the actual throws.</p>
 */
public class ProjectileZoner implements DefensiveBehavior {

    private boolean complete;
    private int ticksActive;
    private int projectilesThrown;

    /** Maximum ticks before auto-completing (stop zoning). */
    private static final int MAX_TICKS = 100; // 5 seconds of zoning

    /** Maximum projectiles per zoning session. */
    private static final int MAX_PROJECTILES = 8;

    /** Minimum ticks between projectile throws. */
    private static final int THROW_COOLDOWN = 8; // 0.4 seconds between throws
    private int throwCooldownRemaining;

    public ProjectileZoner() {
        reset();
    }

    @Nonnull @Override
    public String getName() { return "ProjectileZoner"; }

    @Nonnull @Override
    public DefensiveAction getActionType() { return DefensiveAction.PROJECTILE_ZONE; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        double tendency = diff.getProjectileZoningTendency();

        if (!RandomUtil.chance(tendency)) return false;

        // Need projectiles in inventory
        ProjectileHandler projHandler = getProjectileHandler(bot);
        if (projHandler == null || !projHandler.hasProjectiles()) return false;

        // Need an approaching enemy at medium range
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;
        Location botLoc = botEntity.getLocation();

        for (ThreatMap.ThreatEntry threat : threatMap.getVisibleThreats()) {
            if (threat.currentPosition == null) continue;

            double dist = botLoc.distance(threat.currentPosition);
            // Medium range: 10-25 blocks
            if (dist >= 10.0 && dist <= 25.0) {
                // Check if approaching (positive velocity toward bot)
                if (threat.velocity != null) {
                    double dx = botLoc.getX() - threat.currentPosition.getX();
                    double dz = botLoc.getZ() - threat.currentPosition.getZ();
                    double dot = dx * threat.velocity.getX() + dz * threat.velocity.getZ();
                    if (dot > 0.01) {
                        return true; // Enemy approaching at medium range
                    }
                }
            }
        }
        return false;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double tendency = bot.getDifficultyProfile().getProjectileZoningTendency();
        double personalityMult = 1.0;

        if (bot.getProfile().hasPersonality("SNIPER")) personalityMult *= 2.0;
        if (bot.getProfile().hasPersonality("CAMPER")) personalityMult *= 1.5;
        if (bot.getProfile().hasPersonality("TRICKSTER")) personalityMult *= 1.3;

        return 2.5 * tendency * personalityMult;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        if (ticksActive > MAX_TICKS || projectilesThrown >= MAX_PROJECTILES) {
            complete = true;
            return;
        }

        // Decrement throw cooldown
        if (throwCooldownRemaining > 0) {
            throwCooldownRemaining--;
        }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            complete = true;
            return;
        }

        // Find nearest approaching enemy
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) {
            complete = true;
            return;
        }

        ThreatMap.ThreatEntry target = threatMap.getNearestThreat();
        if (target == null || target.currentPosition == null) {
            complete = true;
            return;
        }

        Location botLoc = botEntity.getLocation();
        Location targetLoc = target.currentPosition;
        double dist = botLoc.distance(targetLoc);

        // If enemy got too close, stop zoning and let combat handle it
        if (dist < 5.0) {
            complete = true;
            return;
        }

        // Look at target
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setLookTarget(targetLoc.clone().add(0, 1.0, 0));
        }

        // Throw projectile when cooldown is ready
        if (throwCooldownRemaining <= 0) {
            ProjectileHandler projHandler = getProjectileHandler(bot);
            if (projHandler != null && projHandler.hasProjectiles()) {
                // Use the projectile handler to throw at the target position
                // Lead the target slightly if they're moving
                Location aimPoint = targetLoc.clone();
                if (target.velocity != null) {
                    // Lead by ~0.5 seconds worth of velocity
                    aimPoint.add(
                            target.velocity.getX() * 10,
                            target.velocity.getY() * 10 + 0.3, // Slight upward arc
                            target.velocity.getZ() * 10
                    );
                }

                projHandler.throwProjectileAt(aimPoint);
                projectilesThrown++;
                throwCooldownRemaining = THROW_COOLDOWN;

                DebugLogger.log(bot, "ProjectileZoner: threw projectile #%d at dist=%.1f",
                        projectilesThrown, dist);
            } else {
                // Out of projectiles
                complete = true;
            }
        }
    }

    /**
     * Safely gets the ProjectileHandler from the bot's CombatEngine.
     */
    @javax.annotation.Nullable
    private ProjectileHandler getProjectileHandler(@Nonnull TrainerBot bot) {
        if (bot.getCombatEngine() == null) return null;
        return bot.getCombatEngine().getProjectileHandler();
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        complete = false;
        ticksActive = 0;
        projectilesThrown = 0;
        throwCooldownRemaining = 0;
    }
}
