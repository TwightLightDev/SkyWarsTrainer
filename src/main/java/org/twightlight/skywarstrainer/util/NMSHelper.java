package org.twightlight.skywarstrainer.util;

import net.minecraft.server.v1_8_R3.EntityLiving;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import net.minecraft.server.v1_8_R3.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_8_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized helper for all NMS (net.minecraft.server) operations targeting
 * Spigot 1.8.8 (v1_8_R3).
 *
 * <p>This class abstracts NMS calls so that if the server version ever changes,
 * only this file and {@link PacketUtil} need to be updated. All other subsystems
 * interact with NMS exclusively through these helpers.</p>
 *
 * <p>NMS operations provided:
 * <ul>
 *   <li>Entity handle retrieval (Bukkit → NMS)</li>
 *   <li>Precise knockback application following vanilla 1.8 mechanics</li>
 *   <li>Velocity manipulation bypassing Bukkit's safety clamping</li>
 *   <li>Entity attribute access (movement speed, attack damage)</li>
 *   <li>Sneaking state manipulation</li>
 *   <li>Ground state detection</li>
 * </ul></p>
 */
public final class NMSHelper {

    private static final Logger LOGGER = Bukkit.getLogger();

    /**
     * Vanilla base knockback strength in 1.8 combat.
     * This is the value used in EntityLiving.attack() before modifiers.
     */
    public static final double BASE_KNOCKBACK = 0.4;

    /**
     * Additional knockback multiplier applied when the attacker is sprinting.
     * In 1.8, sprint-hits deal significantly more KB, which is the core of
     * combo-based PvP.
     */
    public static final double SPRINT_KNOCKBACK_MULTIPLIER = 1.0;

    /**
     * Vertical KB component added on hit. This lifts the target slightly,
     * enabling combos by keeping them airborne.
     */
    public static final double VERTICAL_KNOCKBACK = 0.4;

    private NMSHelper() {
        // Static utility class — no instantiation
    }

    // ─── Entity Handle Retrieval ────────────────────────────────

    /**
     * Gets the NMS Entity handle from a Bukkit Entity.
     *
     * @param entity the Bukkit entity
     * @return the NMS entity, or null if conversion fails
     */
    @Nullable
    public static net.minecraft.server.v1_8_R3.Entity getNMSEntity(@Nonnull Entity entity) {
        try {
            return ((CraftEntity) entity).getHandle();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to get NMS handle for entity", e);
            return null;
        }
    }

    /**
     * Gets the NMS EntityLiving handle from a Bukkit LivingEntity.
     *
     * @param entity the Bukkit living entity
     * @return the NMS entity, or null if conversion fails
     */
    @Nullable
    public static EntityLiving getNMSLivingEntity(@Nonnull LivingEntity entity) {
        try {
            return ((CraftLivingEntity) entity).getHandle();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to get NMS LivingEntity handle", e);
            return null;
        }
    }

    /**
     * Gets the NMS EntityPlayer handle from a Bukkit Player.
     *
     * @param player the Bukkit player
     * @return the NMS EntityPlayer, or null if conversion fails
     */
    @Nullable
    public static EntityPlayer getNMSPlayer(@Nonnull Player player) {
        try {
            return ((CraftPlayer) player).getHandle();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to get NMS EntityPlayer handle", e);
            return null;
        }
    }

    // ─── Knockback ──────────────────────────────────────────────

    /**
     * Applies knockback to a target entity following vanilla 1.8 mechanics.
     *
     * <p>The 1.8 knockback formula:
     * <ol>
     *   <li>Calculate horizontal direction from attacker to target.</li>
     *   <li>Apply base horizontal KB ({@value #BASE_KNOCKBACK}) in that direction.</li>
     *   <li>Apply vertical KB ({@value #VERTICAL_KNOCKBACK}) upward.</li>
     *   <li>If attacker is sprinting, add sprint bonus.</li>
     *   <li>If weapon has Knockback enchantment, add enchantment bonus.</li>
     * </ol></p>
     *
     * <p>This method directly sets the entity's NMS velocity to match vanilla
     * behavior. Bukkit's setVelocity() clamps values and doesn't perfectly
     * replicate 1.8 combat feel.</p>
     *
     * @param target           the entity receiving knockback
     * @param attacker         the entity dealing the hit
     * @param isSprinting      whether the attacker is sprinting (sprint-hit KB)
     * @param knockbackLevel   the Knockback enchantment level (0 for none)
     * @param kbReduction      the fraction of KB to reduce (0.0 = full KB, 0.45 = 45% reduced)
     */
    public static void applyKnockback(@Nonnull LivingEntity target, @Nonnull Entity attacker,
                                      boolean isSprinting, int knockbackLevel, double kbReduction) {
        try {
            EntityLiving nmsTarget = getNMSLivingEntity(target);
            if (nmsTarget == null) return;

            // Direction vector from attacker to target (horizontal only)
            double dx = target.getLocation().getX() - attacker.getLocation().getX();
            double dz = target.getLocation().getZ() - attacker.getLocation().getZ();

            // Normalize horizontal direction
            double horizontalDist = Math.sqrt(dx * dx + dz * dz);
            if (horizontalDist < 0.001) {
                // Entities are on top of each other; use attacker's facing direction
                Location attackerLoc = attacker.getLocation();
                double yawRad = Math.toRadians(attackerLoc.getYaw());
                dx = -Math.sin(yawRad);
                dz = Math.cos(yawRad);
                horizontalDist = 1.0;
            }

            dx /= horizontalDist;
            dz /= horizontalDist;

            // Base knockback
            double kbX = dx * BASE_KNOCKBACK;
            double kbY = VERTICAL_KNOCKBACK;
            double kbZ = dz * BASE_KNOCKBACK;

            // Sprint bonus: adds additional horizontal KB
            if (isSprinting) {
                kbX += dx * SPRINT_KNOCKBACK_MULTIPLIER;
                kbZ += dz * SPRINT_KNOCKBACK_MULTIPLIER;
            }

            // Knockback enchantment bonus
            if (knockbackLevel > 0) {
                double enchantBonus = knockbackLevel * 0.5;
                kbX += dx * enchantBonus;
                kbZ += dz * enchantBonus;
            }

            // Apply KB reduction (simulates player movement countering)
            double reductionMultiplier = MathUtil.clamp(1.0 - kbReduction, 0.0, 1.0);
            kbX *= reductionMultiplier;
            kbY *= reductionMultiplier;
            kbZ *= reductionMultiplier;

            /*
             * Set velocity via NMS to bypass Bukkit's velocity clamping.
             * In vanilla 1.8, the hit entity's existing velocity is partially
             * preserved (motX *= 0.5, etc.) before adding the KB. We replicate this.
             */
            nmsTarget.motX = nmsTarget.motX * 0.5 + kbX;
            nmsTarget.motY = Math.min(nmsTarget.motY * 0.5 + kbY, 0.4); // Cap vertical
            nmsTarget.motZ = nmsTarget.motZ * 0.5 + kbZ;

            // Mark velocity as changed so the server sends the update to clients
            nmsTarget.velocityChanged = true;

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to apply knockback", e);
            // Fallback to Bukkit velocity if NMS fails
            applyKnockbackFallback(target, attacker, isSprinting, knockbackLevel, kbReduction);
        }
    }

    /**
     * Fallback knockback using Bukkit API if NMS fails.
     * Less accurate than NMS but functional.
     */
    private static void applyKnockbackFallback(@Nonnull LivingEntity target, @Nonnull Entity attacker,
                                               boolean isSprinting, int knockbackLevel, double kbReduction) {
        Vector direction = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        if (direction.lengthSquared() < 0.001) {
            // Use attacker's facing direction
            direction = attacker.getLocation().getDirection();
        }
        direction.setY(0).normalize();

        double strength = BASE_KNOCKBACK;
        if (isSprinting) strength += SPRINT_KNOCKBACK_MULTIPLIER;
        if (knockbackLevel > 0) strength += knockbackLevel * 0.5;
        strength *= MathUtil.clamp(1.0 - kbReduction, 0.0, 1.0);

        Vector kb = direction.multiply(strength);
        kb.setY(VERTICAL_KNOCKBACK * MathUtil.clamp(1.0 - kbReduction, 0.0, 1.0));

        target.setVelocity(target.getVelocity().multiply(0.5).add(kb));
    }

    // ─── Entity State ───────────────────────────────────────────

    /**
     * Sets the sneaking state of a living entity via NMS.
     *
     * <p>For NPCs, Bukkit's setSneaking may not always work correctly.
     * This directly sets the NMS sneaking flag.</p>
     *
     * @param entity   the entity
     * @param sneaking whether the entity should be sneaking
     */
    public static void setSneaking(@Nonnull LivingEntity entity, boolean sneaking) {
        try {
            EntityLiving nmsEntity = getNMSLivingEntity(entity);
            if (nmsEntity == null) return;
            nmsEntity.setSneaking(sneaking);
        } catch (Exception e) {
            // Fallback to Bukkit
            if (entity instanceof Player) {
                ((Player) entity).setSneaking(sneaking);
            }
        }
    }

    /**
     * Sets the sprinting state of a living entity via NMS.
     *
     * @param entity    the entity
     * @param sprinting whether the entity should be sprinting
     */
    public static void setSprinting(@Nonnull LivingEntity entity, boolean sprinting) {
        try {
            EntityLiving nmsEntity = getNMSLivingEntity(entity);
            if (nmsEntity == null) return;
            nmsEntity.setSprinting(sprinting);
        } catch (Exception e) {
            // Fallback to Bukkit
            if (entity instanceof Player) {
                ((Player) entity).setSprinting(sprinting);
            }
        }
    }

    /**
     * Checks if the given entity is on the ground using NMS.
     * More reliable than Bukkit's {@code Entity.isOnGround()} for NPCs.
     *
     * @param entity the entity to check
     * @return true if the entity is on the ground
     */
    public static boolean isOnGround(@Nonnull Entity entity) {
        try {
            net.minecraft.server.v1_8_R3.Entity nmsEntity = getNMSEntity(entity);
            return nmsEntity != null && nmsEntity.onGround;
        } catch (Exception e) {
            return entity.isOnGround();
        }
    }

    /**
     * Gets the NMS world handle from a Bukkit location.
     *
     * @param location the Bukkit location
     * @return the NMS WorldServer, or null if conversion fails
     */
    @Nullable
    public static net.minecraft.server.v1_8_R3.WorldServer getNMSWorld(@Nonnull Location location) {
        try {
            return ((CraftWorld) location.getWorld()).getHandle();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to get NMS world", e);
            return null;
        }
    }

    /**
     * Sets the entity's NMS velocity directly, bypassing Bukkit's clamping.
     *
     * @param entity   the entity
     * @param velocity the desired velocity vector
     */
    public static void setVelocityDirect(@Nonnull Entity entity, @Nonnull Vector velocity) {
        try {
            net.minecraft.server.v1_8_R3.Entity nmsEntity = getNMSEntity(entity);
            if (nmsEntity == null) {
                entity.setVelocity(velocity);
                return;
            }
            nmsEntity.motX = velocity.getX();
            nmsEntity.motY = velocity.getY();
            nmsEntity.motZ = velocity.getZ();
            nmsEntity.velocityChanged = true;
        } catch (Exception e) {
            entity.setVelocity(velocity);
        }
    }

    /**
     * Gets the entity's current velocity from NMS (more precise than Bukkit).
     *
     * @param entity the entity
     * @return the velocity vector
     */
    @Nonnull
    public static Vector getVelocityDirect(@Nonnull Entity entity) {
        try {
            net.minecraft.server.v1_8_R3.Entity nmsEntity = getNMSEntity(entity);
            if (nmsEntity == null) {
                return entity.getVelocity();
            }
            return new Vector(nmsEntity.motX, nmsEntity.motY, nmsEntity.motZ);
        } catch (Exception e) {
            return entity.getVelocity();
        }
    }

    /**
     * Gets the last damage tick for a living entity (used for hit cooldown checks).
     *
     * @param entity the living entity
     * @return the NMS noDamageTicks value, or 0 if unavailable
     */
    public static int getNoDamageTicks(@Nonnull LivingEntity entity) {
        try {
            EntityLiving nmsEntity = getNMSLivingEntity(entity);
            return nmsEntity != null ? nmsEntity.noDamageTicks : entity.getNoDamageTicks();
        } catch (Exception e) {
            return entity.getNoDamageTicks();
        }
    }

    /**
     * Sets the noDamageTicks (invulnerability frames) for a living entity.
     *
     * @param entity the living entity
     * @param ticks  the number of invulnerability ticks
     */
    public static void setNoDamageTicks(@Nonnull LivingEntity entity, int ticks) {
        try {
            EntityLiving nmsEntity = getNMSLivingEntity(entity);
            if (nmsEntity != null) {
                nmsEntity.noDamageTicks = ticks;
            } else {
                entity.setNoDamageTicks(ticks);
            }
        } catch (Exception e) {
            entity.setNoDamageTicks(ticks);
        }
    }

    /**
     * Simulates the bot attacking a target entity, replicating vanilla 1.8 melee attack.
     *
     * <p>This uses NMS to invoke the attack sequence as if the bot performed a left-click
     * on the target. It handles damage calculation, invulnerability frame checks, and
     * the attack event. Knockback is NOT applied here — it should be applied separately
     * via {@link #applyKnockback} for finer control from the combat system.</p>
     *
     * @param attacker the attacking living entity (the bot)
     * @param target   the entity being attacked
     */
    public static void attackEntity(@Nonnull LivingEntity attacker, @Nonnull LivingEntity target) {
        try {
            EntityLiving nmsAttacker = getNMSLivingEntity(attacker);
            EntityLiving nmsTarget = getNMSLivingEntity(target);
            if (nmsAttacker == null || nmsTarget == null) {
                // Fallback: use Bukkit damage method
                attackEntityFallback(attacker, target);
                return;
            }

            /*
             * Check invulnerability frames. In vanilla 1.8, an entity has
             * noDamageTicks after being hit (default 20 ticks = 1 second,
             * but damage can still be applied if it exceeds the last damage dealt).
             * The hurtTimestamp / noDamageTicks check prevents damage spam.
             */
            if (nmsTarget.noDamageTicks > nmsTarget.maxNoDamageTicks / 2) {
                return; // Target is still in invulnerability frames
            }

            /*
             * Calculate base attack damage from the attacker's held item.
             * In 1.8, there's no attack cooldown — damage is based purely on
             * the weapon's damage attribute.
             */
            float damage = calculateMeleeDamage(attacker);

            // Apply the damage through Bukkit's damage method to ensure events fire
            // (EntityDamageByEntityEvent, etc.) so other plugins can handle it.
            target.damage(damage, attacker);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[SkyWarsTrainer] Failed to attack entity via NMS, using fallback", e);
            attackEntityFallback(attacker, target);
        }
    }

    /**
     * Fallback attack method using pure Bukkit API.
     * Less precise than NMS but functional if NMS fails.
     *
     * @param attacker the attacking entity
     * @param target   the target entity
     */
    private static void attackEntityFallback(@Nonnull LivingEntity attacker, @Nonnull LivingEntity target) {
        float damage = calculateMeleeDamage(attacker);
        target.damage(damage, attacker);
    }

    /**
     * Calculates melee damage for the given attacker based on their held weapon.
     *
     * <p>Vanilla 1.8 base damage values:
     * <ul>
     *   <li>Fist: 1.0</li>
     *   <li>Wood/Gold Sword: 5.0</li>
     *   <li>Stone Sword: 6.0</li>
     *   <li>Iron Sword: 7.0</li>
     *   <li>Diamond Sword: 8.0</li>
     *   <li>Sharpness adds +1.25 per level</li>
     * </ul></p>
     *
     * @param attacker the attacking entity
     * @return the base melee damage
     */
    private static float calculateMeleeDamage(@Nonnull LivingEntity attacker) {
        org.bukkit.inventory.ItemStack weapon = attacker.getEquipment() != null
                ? attacker.getEquipment().getItemInHand() : null;

        if (weapon == null || weapon.getType() == Material.AIR) {
            return 1.0f; // Fist damage
        }

        float baseDamage;
        switch (weapon.getType()) {
            case DIAMOND_SWORD:
                baseDamage = 8.0f;
                break;
            case IRON_SWORD:
                baseDamage = 7.0f;
                break;
            case STONE_SWORD:
                baseDamage = 6.0f;
                break;
            case GOLD_SWORD:
            case WOOD_SWORD:
                baseDamage = 5.0f;
                break;
            case DIAMOND_AXE:
                baseDamage = 7.0f;
                break;
            case IRON_AXE:
                baseDamage = 6.0f;
                break;
            case STONE_AXE:
                baseDamage = 5.0f;
                break;
            case GOLD_AXE:
            case WOOD_AXE:
                baseDamage = 4.0f;
                break;
            default:
                baseDamage = 1.0f;
                break;
        }

        // Add Sharpness enchantment bonus (+1.25 per level in 1.8)
        if (weapon.containsEnchantment(org.bukkit.enchantments.Enchantment.DAMAGE_ALL)) {
            int sharpnessLevel = weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DAMAGE_ALL);
            baseDamage += sharpnessLevel * 1.25f;
        }

        return baseDamage;
    }

    /**
     * Plays the arm swing animation for the given entity, visible to all nearby players.
     * Delegates to {@link PacketUtil#playArmSwing(Entity)} for packet-based animation.
     *
     * @param entity the entity whose arm should swing
     */
    public static void playArmSwing(@Nonnull Entity entity) {
        PacketUtil.playArmSwing(entity);
    }

    public static void useItem(Player player, boolean use) {
        EntityPlayer ep = ((CraftPlayer) player).getHandle();
        ItemStack item = ep.inventory.getItemInHand();

        if (item == null || item.getItem() == null) return;

        if (use) {
            int duration = 72000;
            ep.a(item, duration);                   // start using item
        } else {
            ep.bU();                                // stop using item
        }
    }

    public static boolean isEating(Player player) {
        if (player == null) return false;

        EntityPlayer ep = ((CraftPlayer) player).getHandle();
        return ep.bS(); // true if player is consuming ANY item
    }

}
