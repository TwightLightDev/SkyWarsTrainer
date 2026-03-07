package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.inventory.InventoryManager;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Defensive behavior that retreats from combat, heals, then re-engages.
 *
 * <p>Key difference from the FLEEING state: retreat-heal is TEMPORARY. The bot
 * sprints away, heals to 70%+ HP, then turns back around and re-engages. FLEE
 * state does not come back.</p>
 *
 * <p>Execution sequence:
 * <ol>
 *   <li>Sprint AWAY from the nearest threat (intelligent direction, not random)</li>
 *   <li>Place blocks behind self to slow pursuer (if skill allows)</li>
 *   <li>Once 8+ blocks away, eat golden apple or drink health potion</li>
 *   <li>If enemy follows closely, throw projectiles backward</li>
 *   <li>Once healed to 70%+ HP, switch back to engagement</li>
 * </ol></p>
 *
 * <p>BERSERKER personality overrides this: eats golden apple DURING fight
 * instead of retreating. This is handled by shouldActivate returning false
 * for BERSERKER bots.</p>
 */
public class RetreatHealer implements DefensiveBehavior {

    private enum Phase {
        RETREATING,        // Running away from enemy
        HEALING,           // Eating golden apple / drinking potion
        RE_ENGAGING        // Turning back to fight
    }

    private Phase phase;
    private boolean complete;
    private int ticksActive;
    private int ticksInPhase;

    /** Target HP fraction to heal to before re-engaging. */
    private static final double RE_ENGAGE_HP = 0.70;

    /** Minimum distance to retreat before healing. */
    private static final double MIN_RETREAT_DISTANCE = 8.0;

    /** Maximum ticks for the entire retreat-heal cycle. */
    private static final int MAX_TICKS = 200; // 10 seconds

    /** Maximum ticks to spend healing before giving up. */
    private static final int MAX_HEAL_TICKS = 60; // 3 seconds

    public RetreatHealer() {
        reset();
    }

    @Nonnull @Override
    public String getName() { return "RetreatHealer"; }

    @Nonnull @Override
    public DefensiveAction getActionType() { return DefensiveAction.RETREAT_HEAL; }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        // BERSERKER never retreats — they eat gapple during combat
        if (bot.getProfile().hasPersonality("BERSERKER")) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double skill = diff.getRetreatHealSkill();

        if (!RandomUtil.chance(skill)) return false;

        // Check if HP is below 50%
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;
        double hpFrac = botEntity.getHealth() / botEntity.getMaxHealth();
        if (hpFrac >= 0.50) return false;

        // Check if we have healing items
        InventoryManager invManager = bot.getInventoryManager();
        if (invManager == null) return false;
        if (!invManager.getFoodHandler().hasGoldenApple()
                && !invManager.getFoodHandler().hasFood()) {
            return false;
        }

        // Need an enemy nearby to retreat FROM
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) return false;

        return true;
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        double skill = bot.getDifficultyProfile().getRetreatHealSkill();
        double personalityMult = 1.0;

        if (bot.getProfile().hasPersonality("STRATEGIC")) personalityMult *= 1.5;
        if (bot.getProfile().hasPersonality("CAUTIOUS")) personalityMult *= 1.5;

        // Priority scales with how low HP is
        LivingEntity botEntity = bot.getLivingEntity();
        double hpMultiplier = 1.0;
        if (botEntity != null) {
            double hpFrac = botEntity.getHealth() / botEntity.getMaxHealth();
            hpMultiplier = 2.0 - hpFrac * 2.0; // Lower HP = higher priority
        }

        return 2.0 * skill * personalityMult * hpMultiplier;
    }

    @Override
    public void tick(@Nonnull TrainerBot bot) {
        ticksActive++;
        ticksInPhase++;

        if (ticksActive > MAX_TICKS) {
            complete = true;
            return;
        }

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) {
            complete = true;
            return;
        }

        switch (phase) {
            case RETREATING:
                tickRetreat(bot, botEntity);
                break;
            case HEALING:
                tickHealing(bot, botEntity);
                break;
            case RE_ENGAGING:
                tickReEngage(bot, botEntity);
                break;
        }
    }

    /**
     * Sprint away from the nearest enemy. Place blocks behind if skill allows.
     */
    private void tickRetreat(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap == null || threatMap.getVisibleThreats().isEmpty()) {
            // No threats — skip to healing
            transitionToHealing();
            return;
        }

        ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
        if (nearest == null || nearest.currentPosition == null) {
            transitionToHealing();
            return;
        }

        Location botLoc = botEntity.getLocation();
        Location threatLoc = nearest.currentPosition;
        double dist = botLoc.distance(threatLoc);

        MovementController mc = bot.getMovementController();
        if (mc == null) {
            complete = true;
            return;
        }

        // Sprint away from threat
        double dx = botLoc.getX() - threatLoc.getX();
        double dz = botLoc.getZ() - threatLoc.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.01) {
            dx /= len;
            dz /= len;
            Location fleeTarget = botLoc.clone().add(dx * 10, 0, dz * 10);
            mc.setMoveTarget(fleeTarget);
            mc.getSprintController().startSprinting();
        }

        // Check if we've retreated far enough
        if (dist >= MIN_RETREAT_DISTANCE) {
            transitionToHealing();
            return;
        }

        // Place blocks behind to slow pursuer (skill-based)
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getRetreatHealSkill() > 0.5 && ticksInPhase % 10 == 0) {
            // Place a block behind the bot (between bot and enemy)
            Location behindBot = botLoc.clone().add(-dx * 1.5, 0, -dz * 1.5);
            if (behindBot.getBlock().getType() == org.bukkit.Material.AIR) {
                InventoryManager inv = bot.getInventoryManager();
                if (inv != null && inv.getBlockCounter().getTotalBlocks() > 5) {
                    behindBot.getBlock().setType(org.bukkit.Material.COBBLESTONE);
                    DebugLogger.log(bot, "RetreatHealer: placed block behind at dist=%.1f", dist);
                }
            }
        }
    }

    /**
     * Eat golden apple or use healing while stationary.
     */
    private void tickHealing(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        if (ticksInPhase > MAX_HEAL_TICKS) {
            // Timeout — re-engage anyway
            transitionToReEngage();
            return;
        }

        // Try to eat golden apple / food
        InventoryManager invManager = bot.getInventoryManager();
        if (invManager != null) {
            invManager.getFoodHandler().tick();
        }

        // Check if healed enough
        double hpFrac = botEntity.getHealth() / botEntity.getMaxHealth();
        if (hpFrac >= RE_ENGAGE_HP) {
            DebugLogger.log(bot, "RetreatHealer: healed to %.0f%%, re-engaging", hpFrac * 100);
            transitionToReEngage();
            return;
        }

        // If enemy closes distance while healing, throw projectiles
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap != null) {
            ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
            if (nearest != null && nearest.currentPosition != null) {
                double dist = botEntity.getLocation().distance(nearest.currentPosition);
                if (dist < 5.0) {
                    // Enemy too close — abort healing, re-engage
                    DebugLogger.log(bot, "RetreatHealer: enemy closed in (dist=%.1f), forced re-engage", dist);
                    transitionToReEngage();
                }
            }
        }
    }

    /**
     * Turn back and move toward the enemy for re-engagement.
     */
    private void tickReEngage(@Nonnull TrainerBot bot, @Nonnull LivingEntity botEntity) {
        // Trigger decision engine re-eval — it should pick FIGHT
        if (bot.getDecisionEngine() != null) {
            bot.getDecisionEngine().triggerInterrupt();
        }
        complete = true;
        DebugLogger.log(bot, "RetreatHealer: re-engaging after heal");
    }

    private void transitionToHealing() {
        phase = Phase.HEALING;
        ticksInPhase = 0;
        DebugLogger.log(null, "RetreatHealer: transitioned to HEALING phase");
    }

    private void transitionToReEngage() {
        phase = Phase.RE_ENGAGING;
        ticksInPhase = 0;
    }

    @Override
    public boolean isComplete() { return complete; }

    @Override
    public void reset() {
        phase = Phase.RETREATING;
        complete = false;
        ticksActive = 0;
        ticksInPhase = 0;
    }
}
