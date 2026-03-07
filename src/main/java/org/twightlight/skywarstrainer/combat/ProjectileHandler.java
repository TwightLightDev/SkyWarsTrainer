package org.twightlight.skywarstrainer.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

/**
 * Handles all projectile interactions: bow shooting, fishing rod casting,
 * egg/snowball throwing, and ender pearl teleportation.
 *
 * <p>Each projectile type has:
 * <ul>
 *   <li>An accuracy model based on {@code projectileAccuracy} difficulty parameter</li>
 *   <li>Predictive lead for moving targets (HARD+ difficulty)</li>
 *   <li>Realistic draw/charge time for bows</li>
 *   <li>Cooldown management to prevent spam at low difficulty</li>
 * </ul></p>
 */
public class ProjectileHandler {

    private final TrainerBot bot;
    private final AimController aimController;

    /** Whether the bot is currently drawing a bow (charging). */
    private boolean bowDrawing;

    /** Ticks the bow has been charged. Full charge is 20 ticks in vanilla. */
    private int bowChargeTicks;

    /** Cooldown in ticks before the bot can use another projectile. */
    private int projectileCooldown;

    /** Cooldown for fishing rod specifically (separate from other projectiles). */
    private int rodCooldown;

    /** Cooldown for ender pearl usage. */
    private int pearlCooldown;

    /** Maximum bow charge ticks for a full-power shot. */
    private static final int MAX_BOW_CHARGE = 20;

    /** Base cooldown between projectile uses in ticks. */
    private static final int BASE_PROJECTILE_COOLDOWN = 15;

    /** Minimum cooldown between fishing rod uses. */
    private static final int ROD_COOLDOWN_TICKS = 20;

    /** Minimum cooldown between ender pearl uses. */
    private static final int PEARL_COOLDOWN_TICKS = 40;

    /**
     * Creates a ProjectileHandler for the given bot.
     *
     * @param bot           the owning bot
     * @param aimController the aim controller for targeting direction
     */
    public ProjectileHandler(@Nonnull TrainerBot bot, @Nonnull AimController aimController) {
        this.bot = bot;
        this.aimController = aimController;
        this.bowDrawing = false;
        this.bowChargeTicks = 0;
        this.projectileCooldown = 0;
        this.rodCooldown = 0;
        this.pearlCooldown = 0;
    }

    /**
     * Ticks projectile cooldowns. Should be called each tick while
     * the combat engine is active.
     */
    public void tick() {
        if (projectileCooldown > 0) projectileCooldown--;
        if (rodCooldown > 0) rodCooldown--;
        if (pearlCooldown > 0) pearlCooldown--;

        // Continue bow charge if drawing
        if (bowDrawing) {
            bowChargeTicks++;
        }
    }

    /**
     * Attempts to shoot a bow at the current aim target. Handles the
     * draw → charge → release → arrow launch cycle.
     *
     * @return true if an arrow was released this tick
     */
    public boolean tryBowShot() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        // Check if player has a bow and arrows
        if (!hasItemInInventory(player, Material.BOW)) return false;
        if (!hasItemInInventory(player, Material.ARROW)) return false;

        LivingEntity target = aimController.getTarget();
        if (target == null || target.isDead()) {
            cancelBowDraw();
            return false;
        }

        DifficultyProfile diff = bot.getDifficultyProfile();

        if (!bowDrawing) {
            // Start drawing the bow
            bowDrawing = true;
            bowChargeTicks = 0;
            return false;
        }

        // Determine optimal release point based on difficulty
        int targetCharge = calculateOptimalBowCharge(diff);
        if (bowChargeTicks >= targetCharge) {
            // Release the bow
            fireBow(player, target, diff);
            cancelBowDraw();
            projectileCooldown = BASE_PROJECTILE_COOLDOWN;
            return true;
        }

        return false;
    }

    /**
     * Cancels the current bow draw.
     */
    public void cancelBowDraw() {
        bowDrawing = false;
        bowChargeTicks = 0;
    }

    /**
     * Fires a bow shot toward the target with accuracy-based spread.
     *
     * @param player the bot's player entity
     * @param target the target entity
     * @param diff   the difficulty profile
     */
    private void fireBow(@Nonnull Player player, @Nonnull LivingEntity target,
                         @Nonnull DifficultyProfile diff) {
        Location eyePos = player.getEyeLocation();
        Location targetPos = target.getLocation().add(0, 1.0, 0);

        // Calculate direction with predictive lead for moving targets
        Vector direction = calculateProjectileDirection(eyePos, target, diff, true);

        // Add accuracy-based spread
        double spread = (1.0 - diff.getProjectileAccuracy()) * 5.0;
        direction.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.02),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.02)
        )).normalize();

        // Calculate bow force from charge time
        double force = Math.min(bowChargeTicks / (double) MAX_BOW_CHARGE, 1.0);

        // Launch the arrow
        Arrow arrow = player.launchProjectile(Arrow.class);
        arrow.setVelocity(direction.multiply(force * 3.0));
        arrow.setShooter(player);

        // Remove an arrow from inventory
        removeItem(player, Material.ARROW, 1);

        // Play arm swing animation
        PacketUtil.playArmSwing(player);
    }

    /**
     * Attempts to cast a fishing rod at the target for the rod-combo technique.
     *
     * @return true if the rod was cast this tick
     */
    public boolean tryFishingRod() {
        if (rodCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        LivingEntity target = aimController.getTarget();
        if (target == null || target.isDead()) return false;

        if (!hasItemInInventory(player, Material.FISHING_ROD)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Fishing rod accuracy check
        double aimError = aimController.getAimError();
        double hitThreshold = 5.0 + diff.getRodUsageSkill() * 5.0;
        if (aimError > hitThreshold) return false;

        // Switch to fishing rod, cast, then schedule switch back
        // For NPCs, we simulate the rod cast by spawning the hook entity
        Location eyePos = player.getEyeLocation();
        Vector direction = calculateProjectileDirection(eyePos, target, diff, false);

        // Add rod-specific accuracy spread
        double spread = (1.0 - diff.getRodUsageSkill()) * 3.0;
        direction.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.015),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.015)
        )).normalize();

        // Launch a fishing hook (simulated as snowball for hit detection, since
        // NPC fishing rod casting is complex — the snowball provides the KB effect)
        // In a production server, this would use NMS to properly cast the rod
        try {
            FishHook hook = player.getWorld().spawn(eyePos, FishHook.class);
            hook.setVelocity(direction.multiply(1.5));
            hook.setShooter(player);
        } catch (Exception e) {
            // Fallback: spawn a snowball for the knockback effect
            Snowball projectile = player.launchProjectile(Snowball.class);
            projectile.setVelocity(direction.multiply(1.5));
        }

        PacketUtil.playArmSwing(player);

        rodCooldown = ROD_COOLDOWN_TICKS;
        return true;
    }

    /**
     * Throws a snowball at the target.
     *
     * @return true if a snowball was thrown
     */
    public boolean trySnowball() {
        if (projectileCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        LivingEntity target = aimController.getTarget();
        if (target == null || target.isDead()) return false;

        if (!hasItemInInventory(player, Material.SNOW_BALL)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        Location eyePos = player.getEyeLocation();
        Vector direction = calculateProjectileDirection(eyePos, target, diff, false);

        // Add spread
        double spread = (1.0 - diff.getProjectileAccuracy()) * 4.0;
        direction.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.015),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.015)
        )).normalize();

        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.setVelocity(direction.multiply(1.5));

        removeItem(player, Material.SNOW_BALL, 1);
        PacketUtil.playArmSwing(player);
        projectileCooldown = BASE_PROJECTILE_COOLDOWN / 2; // Faster than arrows
        return true;
    }

    /**
     * Throws an egg at the target.
     *
     * @return true if an egg was thrown
     */
    public boolean tryEgg() {
        if (projectileCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        LivingEntity target = aimController.getTarget();
        if (target == null || target.isDead()) return false;

        if (!hasItemInInventory(player, Material.EGG)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        Location eyePos = player.getEyeLocation();
        Vector direction = calculateProjectileDirection(eyePos, target, diff, false);

        double spread = (1.0 - diff.getProjectileAccuracy()) * 4.0;
        direction.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.015),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.015)
        )).normalize();

        Egg egg = player.launchProjectile(Egg.class);
        egg.setVelocity(direction.multiply(1.5));

        removeItem(player, Material.EGG, 1);
        PacketUtil.playArmSwing(player);
        projectileCooldown = BASE_PROJECTILE_COOLDOWN / 2;
        return true;
    }

    /**
     * Uses an ender pearl for tactical teleportation. The pearl is aimed at
     * a specific location rather than at an enemy.
     *
     * @param targetLocation the location to pearl toward
     * @return true if a pearl was thrown
     */
    public boolean tryEnderPearl(@Nonnull Location targetLocation) {
        if (pearlCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItemInInventory(player, Material.ENDER_PEARL)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Ender pearl usage IQ check — lower IQ bots don't use pearls well
        if (!RandomUtil.chance(diff.getPearlUsageIQ())) return false;

        Location eyePos = player.getEyeLocation();
        Vector direction = MathUtil.directionTo(eyePos, targetLocation);

        // Calculate proper arc for pearl trajectory (pearls have gravity)
        double distance = eyePos.distance(targetLocation);
        double pitchAdjust = -Math.toRadians(20 + distance * 0.5); // Lob upward
        direction.setY(direction.getY() + Math.sin(pitchAdjust) * 0.5);
        direction.normalize();

        // Add accuracy spread (pearls are harder to aim precisely)
        double spread = (1.0 - diff.getPearlUsageIQ()) * 3.0;
        direction.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.02),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.02)
        )).normalize();

        EnderPearl pearl = player.launchProjectile(EnderPearl.class);
        pearl.setVelocity(direction.multiply(1.5));

        removeItem(player, Material.ENDER_PEARL, 1);
        PacketUtil.playArmSwing(player);
        pearlCooldown = PEARL_COOLDOWN_TICKS;
        return true;
    }

    /**
     * Calculates the direction vector for a projectile, with optional
     * predictive lead for moving targets.
     *
     * @param eyePos the bot's eye position (launch point)
     * @param target the target entity
     * @param diff   the difficulty profile
     * @param leadTarget whether to apply predictive lead
     * @return the normalized direction vector
     */
    @Nonnull
    private Vector calculateProjectileDirection(@Nonnull Location eyePos,
                                                @Nonnull LivingEntity target,
                                                @Nonnull DifficultyProfile diff,
                                                boolean leadTarget) {
        Location targetPos = target.getLocation().add(0, 1.0, 0);

        if (leadTarget && diff.getProjectileAccuracy() >= 0.6) {
            // Predictive aim: lead the target based on their velocity
            Vector targetVelocity = target.getVelocity();
            double distance = eyePos.distance(targetPos);
            // Estimated travel time for projectile (rough approximation)
            double travelTimeTicks = distance / 1.5; // ~1.5 blocks/tick projectile speed
            double leadFactor = travelTimeTicks * diff.getProjectileAccuracy();
            targetPos.add(targetVelocity.clone().multiply(leadFactor));
        }

        return MathUtil.directionTo(eyePos, targetPos);
    }

    /**
     * Calculates the optimal bow charge time based on difficulty.
     * Higher difficulty bots get closer to full charge for maximum damage.
     *
     * @param diff the difficulty profile
     * @return the optimal charge in ticks
     */
    private int calculateOptimalBowCharge(@Nonnull DifficultyProfile diff) {
        // Expert bots always full-charge; beginners release early
        double chargeQuality = diff.getProjectileAccuracy();
        int minCharge = 5; // Minimum for any knockback
        int maxCharge = MAX_BOW_CHARGE;
        return (int) (minCharge + (maxCharge - minCharge) * chargeQuality);
    }

    // ─── Inventory Helpers ──────────────────────────────────────

    /**
     * Checks if the player has at least one of the specified item in inventory.
     *
     * @param player   the player
     * @param material the material to check for
     * @return true if the item exists in inventory
     */
    private boolean hasItemInInventory(@Nonnull Player player, @Nonnull Material material) {
        return player.getInventory().contains(material);
    }

    /**
     * Removes a specified amount of an item from the player's inventory.
     *
     * @param player   the player
     * @param material the material to remove
     * @param amount   how many to remove
     */
    private void removeItem(@Nonnull Player player, @Nonnull Material material, int amount) {
        ItemStack toRemove = new ItemStack(material, amount);
        player.getInventory().removeItem(toRemove);
    }

    // ─── Query Methods ──────────────────────────────────────────

    /**
     * Returns whether the bot has any ranged weapons available.
     *
     * @return true if the bot has a bow+arrows, rod, snowballs, or eggs
     */
    public boolean hasRangedWeapons() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        return (hasItemInInventory(player, Material.BOW) && hasItemInInventory(player, Material.ARROW))
                || hasItemInInventory(player, Material.FISHING_ROD)
                || hasItemInInventory(player, Material.SNOW_BALL)
                || hasItemInInventory(player, Material.EGG);
    }

    /**
     * Called when a projectile fired by this bot hits something.
     * Handles special behavior for rods, snowballs, eggs, etc.
     *
     * @param projectile the projectile that hit
     */
    public void onProjectileHit(@Nonnull Projectile projectile) {

        // Fishing rod hook hit logic (used for rod combo)
        if (projectile instanceof FishHook) {
            List<Entity> entities = projectile.getNearbyEntities(1, 1.5, 1);
            if (entities.isEmpty()) return;
            Entity hooked = entities.get(0);
            if (hooked instanceof LivingEntity) {
                LivingEntity target = (LivingEntity) hooked;

                // Small knockback effect to simulate rod combo
                Vector kb = target.getLocation().toVector()
                        .subtract(projectile.getLocation().toVector())
                        .normalize()
                        .multiply(0.35);

                kb.setY(0.25);
                target.setVelocity(target.getVelocity().add(kb));
            }
            return;
        }

        // Snowball hit behavior
        if (projectile instanceof Snowball) {
            if (projectile.getNearbyEntities(1.5, 1.5, 1.5).isEmpty()) return;

            for (Entity entity : projectile.getNearbyEntities(1.5, 1.5, 1.5)) {
                if (entity instanceof LivingEntity && entity != bot.getPlayerEntity()) {
                    LivingEntity target = (LivingEntity) entity;

                    Vector kb = target.getLocation().toVector()
                            .subtract(projectile.getLocation().toVector())
                            .normalize()
                            .multiply(0.2);

                    kb.setY(0.2);
                    target.setVelocity(target.getVelocity().add(kb));
                }
            }
            return;
        }

        // Egg hit behavior (same as snowball but weaker)
        if (projectile instanceof Egg) {
            for (Entity entity : projectile.getNearbyEntities(1.5, 1.5, 1.5)) {
                if (entity instanceof LivingEntity && entity != bot.getPlayerEntity()) {
                    LivingEntity target = (LivingEntity) entity;

                    Vector kb = target.getLocation().toVector()
                            .subtract(projectile.getLocation().toVector())
                            .normalize()
                            .multiply(0.15);

                    kb.setY(0.15);
                    target.setVelocity(target.getVelocity().add(kb));
                }
            }
            return;
        }

        // Arrow hit (mainly used for tracking hits)
        if (projectile instanceof Arrow) {
            // Could add hit statistics, accuracy tracking, etc.
            // For now we just ensure the arrow is removed after hit
            projectile.remove();
        }
    }

    /**
     * Returns whether the bot has a fishing rod available.
     *
     * @return true if the bot has a fishing rod and the rod cooldown is ready
     */
    public boolean hasFishingRod() {
        Player player = bot.getPlayerEntity();
        return player != null && hasItemInInventory(player, Material.FISHING_ROD) && rodCooldown <= 0;
    }

    /**
     * Returns whether the bot has any throwable projectiles available
     * (snowballs or eggs). Used by zoning logic.
     */
    public boolean hasProjectiles() {
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        return hasItemInInventory(player, Material.SNOW_BALL)
                || hasItemInInventory(player, Material.EGG);
    }

    /**
     * Throws a projectile toward a specific location.
     * Used by zoning and tactical behaviors.
     *
     * Priority order:
     * 1. Snowball
     * 2. Egg
     * 3. Bow (if far away)
     */
    public boolean throwProjectileAt(@Nonnull Location target) {

        if (projectileCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        Location eye = player.getEyeLocation();

        Vector dir = MathUtil.directionTo(eye, target);

        double spread = (1.0 - diff.getProjectileAccuracy()) * 4.0;
        dir.add(new Vector(
                RandomUtil.gaussian(0, spread * 0.015),
                RandomUtil.gaussian(0, spread * 0.01),
                RandomUtil.gaussian(0, spread * 0.015)
        )).normalize();

        // Prefer snowballs
        if (hasItemInInventory(player, Material.SNOW_BALL)) {
            Snowball s = player.launchProjectile(Snowball.class);
            s.setVelocity(dir.multiply(1.5));
            removeItem(player, Material.SNOW_BALL, 1);
            PacketUtil.playArmSwing(player);
            projectileCooldown = BASE_PROJECTILE_COOLDOWN / 2;
            return true;
        }

        // Then eggs
        if (hasItemInInventory(player, Material.EGG)) {
            Egg e = player.launchProjectile(Egg.class);
            e.setVelocity(dir.multiply(1.5));
            removeItem(player, Material.EGG, 1);
            PacketUtil.playArmSwing(player);
            projectileCooldown = BASE_PROJECTILE_COOLDOWN / 2;
            return true;
        }

        // Fallback: bow shot
        LivingEntity target1 = aimController.getTarget();
        if (target1 != null) {
            return tryBowShot();
        }

        return false;
    }

    /**
     * Returns whether the bot has ender pearls available.
     *
     * @return true if pearls are available and off cooldown
     */
    public boolean hasEnderPearl() {
        Player player = bot.getPlayerEntity();
        return player != null && hasItemInInventory(player, Material.ENDER_PEARL) && pearlCooldown <= 0;
    }

    /** @return true if the bot is currently drawing a bow */
    public boolean isBowDrawing() { return bowDrawing; }

    /** @return the current bow charge in ticks */
    public int getBowChargeTicks() { return bowChargeTicks; }

    /** @return true if the projectile cooldown is ready */
    public boolean isProjectileReady() { return projectileCooldown <= 0; }

    /** @return true if the fishing rod cooldown is ready */
    public boolean isRodReady() { return rodCooldown <= 0; }
}

