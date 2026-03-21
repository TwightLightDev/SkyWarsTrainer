package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.CombatUtils;
import org.twightlight.skywarstrainer.combat.ProjectileHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fishing rod combo strategy: Cast rod → wait for hit → switch to sword →
 * sprint in → melee combo while enemy is in rod-pull KB/stun.
 *
 * <p>The fishing rod combo is a powerful 1.8 PvP technique. The rod hit
 * slows the enemy and applies slight knockback, giving the attacker a
 * window to sprint in and land several melee hits before the enemy can
 * respond.</p>
 *
 * <p>Full sequence:
 * <ol>
 *   <li>Switch to fishing rod</li>
 *   <li>Cast rod at enemy</li>
 *   <li>Wait for rod hit confirmation (1-3 ticks travel time)</li>
 *   <li>Switch to sword (1-2 ticks delay based on difficulty)</li>
 *   <li>Sprint toward enemy</li>
 *   <li>Melee attack combo (3-5 hits)</li>
 *   <li>Disengage briefly</li>
 *   <li>Repeat</li>
 * </ol></p>
 *
 * <p>The {@code rodUsageSkill} parameter affects aim accuracy with the rod,
 * switch speed, and timing of the approach.</p>
 */
public class RodComboStrategy implements CombatStrategy {

    /**
     * Phases of the rod combo sequence.
     */
    private enum Phase {
        /** Waiting for opportunity to initiate rod combo. */
        IDLE,
        /** Switching to fishing rod. */
        SWITCHING_TO_ROD,
        /** Aiming and casting the rod. */
        CASTING,
        /** Waiting for the rod projectile to reach the target. */
        WAITING_FOR_HIT,
        /** Switching back to sword after rod hit. */
        SWITCHING_TO_SWORD,
        /** Sprinting toward the enemy for melee followup. */
        APPROACHING,
        /** Landing melee hits during the combo window. */
        COMBOING,
        /** Brief disengage before next rod attempt. */
        COOLDOWN
    }

    private Phase currentPhase;
    private int phaseTimer;
    private int comboHitsLanded;

    /** Number of melee hits to attempt during the combo window. */
    private int targetComboHits;

    /** Cooldown between rod combo attempts. */
    private static final int ROD_COMBO_COOLDOWN = 60;

    @Nonnull
    @Override
    public String getName() {
        return "RodCombo";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getRodUsageSkill() <= 0.0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        // Must have a fishing rod
        if (!player.getInventory().contains(Material.FISHING_ROD)) return false;

        // Must have a sword to switch to
        if (!CombatUtils.hasSword(player)) return false;

        // Target must be in rod range (10-20 blocks optimal)
        LivingEntity target = findNearestEnemy(bot);
        if (target == null) return false;

        double range = bot.getLivingEntity().getLocation().distance(target.getLocation());
        // Rod combo works best at 5-15 blocks
        return range >= 4.0 && range <= 20.0;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        MovementController mc = bot.getMovementController();
        Player player = bot.getPlayerEntity();
        if (mc == null || player == null) return;

        switch (currentPhase) {
            case IDLE:
                // Initiate rod combo if skill roll passes
                if (RandomUtil.chance(diff.getRodUsageSkill() * 0.2)) {
                    currentPhase = Phase.SWITCHING_TO_ROD;
                    // Switch time: faster at higher difficulty
                    phaseTimer = Math.max(1, (int) Math.round(3.0 * (1.0 - diff.getRodUsageSkill())));
                }
                break;

            case SWITCHING_TO_ROD:
                // Simulate hotbar switch to fishing rod
                CombatUtils.switchToItem(player, Material.FISHING_ROD);
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.CASTING;
                    phaseTimer = 1;
                }
                break;
            case WAITING_FOR_HIT:
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.SWITCHING_TO_SWORD;
                    // Sword switch time: 1-2 ticks based on skill
                    phaseTimer = diff.getRodUsageSkill() >= 0.7 ? 1 : 2;
                }
                break;

            case SWITCHING_TO_SWORD:
                CombatUtils.switchToSword(player);
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.APPROACHING;
                    targetComboHits = RandomUtil.nextInt(3, 5);
                    comboHitsLanded = 0;
                    mc.getSprintController().startSprinting();
                    phaseTimer = 20; // Max approach time before giving up
                }
                break;

            case APPROACHING:
                // Sprint toward target
                LivingEntity approachTarget = findNearestEnemy(bot);
                if (approachTarget != null) {
                    // [FIX] Use COMBAT authority
                    mc.setMoveTarget(approachTarget.getLocation(), MovementController.MovementAuthority.COMBAT);
                    mc.setLookTarget(approachTarget.getLocation().add(0, 1.0, 0));

                    double dist = player.getLocation().distance(approachTarget.getLocation());
                    if (dist <= 3.5) {
                        currentPhase = Phase.COMBOING;
                        phaseTimer = 40;
                    }
                }

                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.COOLDOWN;
                    phaseTimer = ROD_COMBO_COOLDOWN;
                }
                break;

            case CASTING:
                // [FIX C2] Actually cast the rod! Previously, the strategy went
                // straight to WAITING_FOR_HIT without ever calling tryFishingRod().
                ProjectileHandler projHandler = bot.getCombatEngine().getProjectileHandler();
                boolean castSuccess = projHandler.tryFishingRod();

                if (!castSuccess) {
                    // Rod couldn't be cast (cooldown, no rod, etc.) — go to cooldown
                    currentPhase = Phase.COOLDOWN;
                    phaseTimer = ROD_COMBO_COOLDOWN / 2;
                    break;
                }

                currentPhase = Phase.WAITING_FOR_HIT;
                LivingEntity target1 = CombatUtils.findNearestEnemy(bot);
                if (target1 != null) {
                    double distance = player.getLocation().distance(target1.getLocation());
                    phaseTimer = (int) Math.ceil(distance / 1.5) + 2;
                } else {
                    phaseTimer = 5;
                }
                break;


            case COMBOING:
                int currentHits = bot.getCombatEngine().getComboTracker().getHitsLanded();
                if (currentHits >= targetComboHits) {
                    currentPhase = Phase.COOLDOWN;
                    phaseTimer = ROD_COMBO_COOLDOWN;
                    break;
                }

                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.COOLDOWN;
                    phaseTimer = ROD_COMBO_COOLDOWN;
                }
                break;

            case COOLDOWN:
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.IDLE;
                }
                break;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Rod combo is a high-skill play — priority scales strongly with skill
        double base = 6.0 * diff.getRodUsageSkill();

        // Bonus priority when target is at optimal rod range (8-15 blocks)
        LivingEntity target = findNearestEnemy(bot);
        if (target != null) {
            LivingEntity botEntity = bot.getLivingEntity();
            if (botEntity != null) {
                double range = botEntity.getLocation().distance(target.getLocation());
                if (range >= 8 && range <= 15) {
                    base += 2.0; // Sweet spot for rod combos
                }
            }
        }

        return base;
    }

    @Override
    public void reset() {
        currentPhase = Phase.IDLE;
        phaseTimer = 0;
        comboHitsLanded = 0;
        targetComboHits = 0;
    }

    // ─── Helpers ────────────────────────────────────────────────

    @Nullable
    private LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(20, 20, 20)) {
            if (entity instanceof Player && !entity.isDead()
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

