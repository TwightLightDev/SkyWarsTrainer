package org.twightlight.skywarstrainer.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.awareness.FallDamageEstimator;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * A generic skill for handling height differences when the bot needs to reach
 * a point that is higher or lower than its current position.
 *
 * <p>Replaces the need for case-by-case "if bridge is higher, jump to reach it" logic.
 * All behavior is driven by difficulty parameters:</p>
 * <ul>
 *   <li>{@code stairBridgeSkill} — success rate for towering (placing blocks under self)</li>
 *   <li>{@code blockPlaceChance} — ability to place blocks under pressure</li>
 *   <li>{@code waterBucketMLG} — ability to handle dangerous drops</li>
 * </ul>
 *
 * <p>The ElevationHandler is called from behavior trees (BRIDGING, HUNTING) when
 * the bot detects a height difference to its target.</p>
 */
public class ElevationHandler {

    private final TrainerBot bot;

    /** Number of tower-up steps remaining in the current sequence. */
    private int towerStepsRemaining;

    /** Cooldown between tower attempts to avoid rapid spam. */
    private int towerCooldown;

    /**
     * Creates a new ElevationHandler for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ElevationHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.towerStepsRemaining = 0;
        this.towerCooldown = 0;
    }

    /**
     * Handles elevation differences between the bot's current position and a target.
     *
     * <p>This is the main entry point. It decides what action to take based on
     * the height difference and the bot's capabilities:</p>
     * <ul>
     *   <li>Target 1 block higher: simple jump when close</li>
     *   <li>Target 2-3 blocks higher: tower up (jump + place block under self)</li>
     *   <li>Target 1-2 blocks lower: walk normally (safe fall)</li>
     *   <li>Target 3+ blocks lower: check fall damage safety</li>
     * </ul>
     *
     * @param current the bot's current location
     * @param target  the desired target location
     * @return true if the elevation handler took an action, false if no action needed
     */
    public boolean handleElevation(@Nonnull Location current, @Nonnull Location target) {
        if (towerCooldown > 0) {
            towerCooldown--;
        }

        int heightDiff = target.getBlockY() - current.getBlockY();

        // No significant height difference
        if (heightDiff == 0) return false;

        double horizontalDist = MathUtil.horizontalDistance(current, target);

        if (heightDiff > 0) {
            // Target is HIGHER
            return handleUpwardElevation(current, target, heightDiff, horizontalDist);
        } else {
            // Target is LOWER
            return handleDownwardElevation(current, target, -heightDiff, horizontalDist);
        }
    }

    /**
     * Called every tick while a tower-up sequence is in progress.
     * Returns true if still towering, false when complete.
     *
     * @return true if tower sequence is still active
     */
    public boolean tickTower() {
        if (towerStepsRemaining <= 0) return false;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) {
            towerStepsRemaining = 0;
            return false;
        }

        if (!(entity instanceof Player)) {
            towerStepsRemaining = 0;
            return false;
        }

        Player player = (Player) entity;
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Only place when on ground (after landing from the previous jump)
        if (!NMSHelper.isOnGround(entity)) return true;

        // Skill check
        if (!RandomUtil.chance(diff.getStairBridgeSkill())) {
            // Failed this attempt — try again next tick
            return true;
        }

        // Jump
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.getJumpController().jump();
        }

        // Place block under self (delayed — the block placement happens on the next tick
        // when the entity has jumped and the block below is air)
        bot.getPlugin().getServer().getScheduler().runTaskLater(bot.getPlugin(), () -> {
            if (bot.isDestroyed() || !bot.isAlive()) return;
            LivingEntity e = bot.getLivingEntity();
            if (e == null) return;

            Location belowLoc = e.getLocation().clone().subtract(0, 1, 0);
            Block below = belowLoc.getBlock();
            if (below.getType() == Material.AIR) {
                int blockSlot = findBlockSlot(player);
                if (blockSlot >= 0) {
                    ItemStack blockItem = player.getInventory().getItem(blockSlot);
                    if (blockItem != null && blockItem.getType().isBlock()) {
                        below.setType(blockItem.getType());
                        if (blockItem.getAmount() > 1) {
                            blockItem.setAmount(blockItem.getAmount() - 1);
                        } else {
                            player.getInventory().setItem(blockSlot, null);
                        }
                    }
                }
            }
        }, 3L); // 3 ticks after jump

        towerStepsRemaining--;
        if (towerStepsRemaining <= 0) {
            towerCooldown = 20;
            DebugLogger.log(bot, "ElevationHandler: Tower sequence complete");
        }

        return towerStepsRemaining > 0;
    }

    /**
     * Returns whether a tower-up sequence is currently in progress.
     *
     * @return true if towering
     */
    public boolean isTowering() {
        return towerStepsRemaining > 0;
    }

    /**
     * Handles the case where the target is above the bot.
     */
    private boolean handleUpwardElevation(@Nonnull Location current,
                                          @Nonnull Location target,
                                          int heightDiff,
                                          double horizontalDist) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();

        if (heightDiff == 1) {
            // Target is 1 block higher — jump when close
            if (horizontalDist <= 2.0) {
                mc.getJumpController().jump();
                DebugLogger.log(bot, "ElevationHandler: Jump to reach +1 target");
                return true;
            }
            return false;

        } else if (heightDiff >= 2 && heightDiff <= 3) {
            // Target is 2-3 blocks higher — tower up if bot has blocks and skill
            if (towerCooldown > 0) return false;

            int blockCount = 0;
            if (bot.getInventoryEngine() != null) {
                blockCount = bot.getInventoryEngine().getBlockCounter().getTotalBlocks();
            }

            if (blockCount >= heightDiff && diff.getStairBridgeSkill() > 0.1) {
                // Start tower sequence
                towerStepsRemaining = heightDiff;
                DebugLogger.log(bot, "ElevationHandler: Starting tower-up sequence (%d blocks)", heightDiff);
                return true;
            }

            // Can't tower — just jump and hope for the best
            if (horizontalDist <= 3.0) {
                mc.getJumpController().jump();
                mc.getSprintController().startSprinting();
                return true;
            }
            return false;

        } else {
            // Target is 4+ blocks higher — can't realistically reach without a bridge/staircase
            // Return false and let the bridge system handle it
            return false;
        }
    }

    /**
     * Handles the case where the target is below the bot.
     */
    private boolean handleDownwardElevation(@Nonnull Location current,
                                            @Nonnull Location target,
                                            int dropHeight,
                                            double horizontalDist) {
        if (dropHeight <= 2) {
            // Safe drop — no action needed
            return false;
        }

        if (dropHeight <= 3) {
            // Might take some fall damage but survivable — check health
            LivingEntity entity = bot.getLivingEntity();
            if (entity != null) {
                double healthFrac = entity.getHealth() / entity.getMaxHealth();
                if (healthFrac < 0.3) {
                    // Low health — don't jump down
                    DebugLogger.log(bot, "ElevationHandler: Refusing 3-block drop at low health");
                    return true; // Return true to indicate we handled it (by refusing)
                }
            }
            return false; // Safe enough, proceed normally
        }

        // 4+ block drop — check fall damage
        FallDamageEstimator fde = bot.getFallDamageEstimator();
        if (fde != null) {
            LivingEntity entity = bot.getLivingEntity();
            if (entity != null) {
                double estimatedDamage = dropHeight - 3; // Simplified: 1 damage per block above 3
                if (estimatedDamage >= entity.getHealth()) {
                    // Lethal drop — refuse
                    DebugLogger.log(bot, "ElevationHandler: Refusing lethal %d-block drop", dropHeight);
                    return true;
                }
            }
        }

        return false; // Non-lethal, proceed normally
    }

    /**
     * Finds a placeable block in the player's inventory.
     *
     * @param player the player entity
     * @return the slot index, or -1 if none found
     */
    private int findBlockSlot(@Nonnull Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isBlock() && item.getType().isSolid()) {
                return i;
            }
        }
        for (int i = 9; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isBlock() && item.getType().isSolid()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Cancels any active tower-up sequence immediately.
     * Used when bot state changes (combat, retreat, etc.).
     */
    public void cancelTower() {
        if (towerStepsRemaining > 0) {
            towerStepsRemaining = 0;
            towerCooldown = 10; // small cooldown to prevent instant restart
            DebugLogger.log(bot, "ElevationHandler: Tower sequence cancelled");
        }
    }
}
