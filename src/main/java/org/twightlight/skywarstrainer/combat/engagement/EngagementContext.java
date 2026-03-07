package org.twightlight.skywarstrainer.combat.engagement;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.CombatEngine;

import javax.annotation.Nonnull;

/**
 * Context snapshot for engagement pattern evaluation.
 */
public class EngagementContext {

    /** Consecutive hits the bot has landed without being hit. */
    public int comboLanded;

    /** Consecutive hits the bot has received without landing a hit. */
    public int comboReceived;

    /** Distance to the current target in blocks. */
    public double rangeToTarget;

    /** Whether the target is near a void edge. */
    public boolean targetNearVoid;

    /** Whether the bot is near a void edge. */
    public boolean botNearVoid;

    /** Distance from target to the nearest void edge. */
    public double targetVoidDistance;

    /** Whether there are other enemies fighting nearby (third-party opportunity). */
    public boolean enemiesFighting;

    /** Number of visible enemies. */
    public int visibleEnemyCount;

    /** Bot's current health fraction [0, 1]. */
    public double botHealthFraction;

    /** Target's current health fraction [0, 1]. */
    public double targetHealthFraction;

    /**
     * Populates this context from the current bot state.
     *
     * @param bot    the bot
     * @param target the combat target
     * @return this context for chaining
     */
    @Nonnull
    public EngagementContext populate(@Nonnull TrainerBot bot, @Nonnull LivingEntity target) {
        CombatEngine combat = bot.getCombatEngine();
        if (combat != null) {
            comboLanded = combat.getComboTracker().getTotalHitsLanded();
            comboReceived = combat.getComboTracker().getHitsReceived();
        }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity != null) {
            rangeToTarget = botEntity.getLocation().distance(target.getLocation());
            botHealthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
        }
        targetHealthFraction = target.getHealth() / target.getMaxHealth();

        // Void checks
        VoidDetector vd = bot.getVoidDetector();
        if (vd != null) {
            botNearVoid = vd.isNearVoidEdge();
            // Check if target is near void
            Location targetLoc = target.getLocation();
            targetNearVoid = vd.isVoidBelow(targetLoc.clone().add(2, 0, 0))
                    || vd.isVoidBelow(targetLoc.clone().add(-2, 0, 0))
                    || vd.isVoidBelow(targetLoc.clone().add(0, 0, 2))
                    || vd.isVoidBelow(targetLoc.clone().add(0, 0, -2));
            targetVoidDistance = targetNearVoid ? 2.0 : 10.0;
        }

        // Third-party detection
        if (bot.getThreatMap() != null) {
            visibleEnemyCount = bot.getThreatMap().getVisibleEnemyCount();
            // Detect two enemies fighting: both took damage recently
            enemiesFighting = visibleEnemyCount >= 2;
        }

        return this;
    }
}
