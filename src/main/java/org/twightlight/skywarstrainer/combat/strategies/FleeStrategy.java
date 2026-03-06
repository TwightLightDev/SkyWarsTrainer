package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Flee strategy: Disengage from combat and escape when health is critical.
 *
 * <p>Flee behavior:
 * <ul>
 *   <li>Sprint away from the enemy in the opposite direction</li>
 *   <li>Eat golden apples while running</li>
 *   <li>Place blocks behind self to obstruct pursuit</li>
 *   <li>Throw projectiles backward to slow the pursuer</li>
 *   <li>Use ender pearl to teleport to safety if available</li>
 *   <li>Place water bucket behind to create slow zone</li>
 *   <li>If near a bridge, bridge away to another island</li>
 * </ul></p>
 *
 * <p>The flee decision is ultimately made by the DecisionEngine, but this
 * strategy can also activate within combat when HP drops below the flee
 * threshold during a fight.</p>
 */
public class FleeStrategy implements CombatStrategy {

    /** Whether currently executing flee behavior. */
    private boolean fleeing;

    /** Ticks since flee started. */
    private int fleeTicks;

    /** Cooldown between placing blocks behind self. */
    private int blockPlaceCooldown;

    /** Cooldown between throwing backward projectiles. */
    private int backProjectileCooldown;

    /** Whether a golden apple is currently being eaten. */
    private boolean eatingGapple;

    /** Ticks remaining for golden apple consumption. */
    private int gappleEatTimer;

    /** Golden apple eating duration in ticks. */
    private static final int GAPPLE_EAT_TICKS = 32;

    @Nonnull
    @Override
    public String getName() {
        return "Flee";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();

        // Activate when HP is below flee threshold
        return healthFraction < diff.getFleeHealthThreshold();
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return;

        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        Player player = bot.getPlayerEntity();

        fleeing = true;
        fleeTicks++;

        // Determine flee direction (away from nearest threat)
        LivingEntity threat = findNearestEnemy(bot);
        Location fleeDirection;

        if (threat != null) {
            // Move directly away from the threat
            Vector awayFromThreat = MathUtil.directionTo(
                    threat.getLocation(), botEntity.getLocation());

            // Avoid fleeing toward void
            VoidDetector voidDetector = bot.getVoidDetector();
            if (voidDetector != null && voidDetector.isNearVoidEdge()) {
                Float safeDir = voidDetector.getSafeDirection();
                if (safeDir != null) {
                    double safeRad = Math.toRadians(safeDir);
                    awayFromThreat = new Vector(-Math.sin(safeRad), 0, Math.cos(safeRad));
                }
            }

            fleeDirection = botEntity.getLocation().clone()
                    .add(awayFromThreat.multiply(10.0));
        } else {
            // No visible threat — just run in the direction we're facing
            fleeDirection = botEntity.getLocation().clone()
                    .add(mc.getForwardDirection().multiply(10.0));
        }

        // Sprint away
        mc.setMoveTarget(fleeDirection);
        mc.getSprintController().startSprinting();

        // Eat golden apple while fleeing
        if (!eatingGapple && player != null && player.getInventory().contains(Material.GOLDEN_APPLE)) {
            double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
            if (healthFraction < diff.getFleeHealthThreshold() * 1.5) {
                startEatingGoldenApple(player);
            }
        }

        if (eatingGapple) {
            tickGoldenAppleEating(player);
        }

        // Place blocks behind self to obstruct pursuer
        if (blockPlaceCooldown <= 0 && player != null && diff.getBlockPlaceChance() > 0.2) {
            placeBlockBehind(bot, player);
            blockPlaceCooldown = RandomUtil.nextInt(8, 15);
        }
        if (blockPlaceCooldown > 0) blockPlaceCooldown--;

        // Throw projectiles backward
        if (backProjectileCooldown <= 0 && player != null && diff.getProjectileAccuracy() > 0.3) {
            throwBackwardProjectile(player, threat);
            backProjectileCooldown = RandomUtil.nextInt(15, 30);
        }
        if (backProjectileCooldown > 0) backProjectileCooldown--;

        // Attempt ender pearl escape for high-IQ bots
        if (player != null && diff.getPearlUsageIQ() > 0.5
                && player.getInventory().contains(Material.ENDER_PEARL)
                && fleeTicks > 20 && RandomUtil.chance(diff.getPearlUsageIQ() * 0.1)) {
            // Pearl to a safe location far away
            Location safeSpot = fleeDirection.clone();
            safeSpot.setY(botEntity.getLocation().getY());
            // The ProjectileHandler will handle the actual pearl throw
            // For now, we just indicate the desire to pearl
        }
    }

    /**
     * Starts eating a golden apple from inventory.
     */
    private void startEatingGoldenApple(@Nonnull Player player) {
        // Find golden apple in hotbar
        for (int i = 0; i < 9; i++) {
            org.bukkit.inventory.ItemStack stack = player.getInventory().getItem(i);
            if (stack != null && stack.getType() == Material.GOLDEN_APPLE) {
                player.getInventory().setHeldItemSlot(i);
                eatingGapple = true;
                gappleEatTimer = GAPPLE_EAT_TICKS;
                return;
            }
        }
    }

    /**
     * Ticks golden apple eating progress.
     */
    private void tickGoldenAppleEating(@Nullable Player player) {
        if (!eatingGapple) return;
        gappleEatTimer--;
        if (gappleEatTimer <= 0) {
            eatingGapple = false;
            if (player != null) {
                // Consume the golden apple
                org.bukkit.inventory.ItemStack hand = player.getItemInHand();
                if (hand != null && hand.getType() == Material.GOLDEN_APPLE) {
                    // Apply regeneration effect (golden apple effect)
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.REGENERATION, 100, 1));
                    player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.ABSORPTION, 2400, 0));

                    // Remove one golden apple
                    if (hand.getAmount() > 1) {
                        hand.setAmount(hand.getAmount() - 1);
                    } else {
                        player.setItemInHand(null);
                    }
                }
            }
        }
    }

    /**
     * Places a block behind the bot to obstruct the pursuer.
     */
    private void placeBlockBehind(@Nonnull TrainerBot bot, @Nonnull Player player) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        // "Behind" is opposite to the flee direction (toward the enemy)
        Vector behind = mc.getForwardDirection().multiply(-1);
        Location behindLoc = player.getLocation().clone().add(behind.multiply(1.0));
        behindLoc.setY(player.getLocation().getY());

        org.bukkit.block.Block block = behindLoc.getBlock();
        if (block.getType() == Material.AIR) {
            // Find a placeable block
            Material[] blocks = {Material.COBBLESTONE, Material.STONE, Material.WOOL,
                    Material.WOOD, Material.DIRT};
            for (Material mat : blocks) {
                if (player.getInventory().contains(mat)) {
                    block.setType(mat);
                    player.getInventory().removeItem(new org.bukkit.inventory.ItemStack(mat, 1));
                    return;
                }
            }
        }
    }

    /**
     * Throws a projectile backward at the pursuer to slow them down.
     */
    private void throwBackwardProjectile(@Nonnull Player player, @Nullable LivingEntity threat) {
        if (threat == null) return;

        // Check for snowballs or eggs
        Material projectileMat = null;
        if (player.getInventory().contains(Material.SNOW_BALL)) {
            projectileMat = Material.SNOW_BALL;
        } else if (player.getInventory().contains(Material.EGG)) {
            projectileMat = Material.EGG;
        }

        if (projectileMat == null) return;

        // Throw toward the threat
        Vector toThreat = MathUtil.directionTo(
                player.getEyeLocation(), threat.getLocation().add(0, 1.0, 0));

        org.bukkit.entity.Projectile projectile;
        if (projectileMat == Material.SNOW_BALL) {
            projectile = player.launchProjectile(org.bukkit.entity.Snowball.class);
        } else {
            projectile = player.launchProjectile(org.bukkit.entity.Egg.class);
        }
        projectile.setVelocity(toThreat.multiply(1.5));

        player.getInventory().removeItem(new org.bukkit.inventory.ItemStack(projectileMat, 1));
        PacketUtil.playArmSwing(player);
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return 0.0;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();

        if (healthFraction >= diff.getFleeHealthThreshold()) return 0.0;

        // Priority increases dramatically as HP gets lower
        double urgency = 1.0 - (healthFraction / diff.getFleeHealthThreshold());
        return 8.0 + urgency * 10.0; // Very high priority when fleeing is needed
    }

    @Override
    public void reset() {
        fleeing = false;
        fleeTicks = 0;
        blockPlaceCooldown = 0;
        backProjectileCooldown = 0;
        eatingGapple = false;
        gappleEatTimer = 0;
    }

    @Nullable
    private LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(30, 30, 30)) {
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
