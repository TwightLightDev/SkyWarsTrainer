package org.twightlight.skywarstrainer.combat.defense;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * A generic fall-recovery and edge-save system for the bot.
 *
 * <p>The ClutchHandler provides the bot with the ability to save itself when
 * falling toward void or taking lethal fall damage. It uses three generic
 * recovery mechanisms, all driven by difficulty parameters:</p>
 * <ul>
 *   <li><b>Block clutch:</b> Place a block directly below while falling</li>
 *   <li><b>Water bucket MLG:</b> Delegates to WaterMLGController as a fallback</li>
 *   <li><b>Directional recovery:</b> Apply impulse toward nearby solid ground</li>
 * </ul>
 *
 * <p>Clutch skill is derived from existing parameters: {@code waterBucketMLG}
 * (reused for clutch skill), {@code blockPlaceChance} (for block clutch skill),
 * and {@code kbCancelSkill} (for directional recovery precision).</p>
 *
 * <p>This handler is ticked from {@link org.twightlight.skywarstrainer.awareness.SurvivalGuard},
 * not from a separate timer.</p>
 */
public class ClutchHandler {

    private final TrainerBot bot;
    private final double clutchSkill;
    private final double blockClutchSkill;
    private final double recoverySkill;

    /** Cooldown to prevent spamming clutch attempts. */
    private int clutchCooldown;

    /**
     * Creates a new ClutchHandler for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ClutchHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        DifficultyProfile diff = bot.getDifficultyProfile();
        this.clutchSkill = diff.getWaterBucketMLG();
        this.blockClutchSkill = diff.getBlockPlaceChance() * 0.8;
        this.recoverySkill = diff.getKbCancelSkill();
        this.clutchCooldown = 0;
    }

    /**
     * Attempts to save the bot from falling into void or taking lethal fall damage.
     *
     * <p>Called from SurvivalGuard when the bot is in the air with significant
     * downward velocity. The handler tries (in order):</p>
     * <ol>
     *   <li>Block clutch — place a block under self if over void and has blocks</li>
     *   <li>Water bucket MLG — delegate to existing WaterMLGController</li>
     *   <li>Directional recovery — nudge velocity toward nearby solid ground</li>
     * </ol>
     *
     * @return true if a clutch was attempted (regardless of success)
     */
    public boolean attemptClutch() {
        if (clutchCooldown > 0) {
            clutchCooldown--;
            return false;
        }

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return false;

        VoidDetector vd = bot.getVoidDetector();
        if (vd == null) return false;

        Location botLoc = entity.getLocation();
        boolean overVoid = vd.isVoidBelow(botLoc);

        // Only attempt clutch if we're actually falling over void or from great height
        if (!overVoid) return false;

        // Set cooldown to prevent spam
        clutchCooldown = 10;

        // ── Attempt 1: Block clutch ──
        if (attemptBlockClutch(entity, botLoc)) {
            DebugLogger.log(bot, "ClutchHandler: Block clutch attempted");
            return true;
        }

        // ── Attempt 2: Water bucket MLG (delegates to existing system) ──
        if (clutchSkill > 0.1 && RandomUtil.chance(clutchSkill)) {
            if (bot.getInventoryEngine() != null) {
                bot.getInventoryEngine().getUtilityItemHandler().tryWaterBucketMLG();
                DebugLogger.log(bot, "ClutchHandler: Water MLG attempted");
                return true;
            }
        }

        // ── Attempt 3: Directional recovery ──
        if (attemptDirectionalRecovery(entity, botLoc, vd)) {
            DebugLogger.log(bot, "ClutchHandler: Directional recovery attempted");
            return true;
        }

        return false;
    }

    /**
     * Attempts to place a block directly below the bot while falling.
     *
     * @param entity the bot's entity
     * @param botLoc the bot's current location
     * @return true if a block was placed
     */
    private boolean attemptBlockClutch(@Nonnull LivingEntity entity, @Nonnull Location botLoc) {
        if (!RandomUtil.chance(blockClutchSkill)) return false;

        // Check if bot has blocks
        if (bot.getInventoryEngine() == null) return false;
        int blockCount = bot.getInventoryEngine().getBlockCounter().getTotalBlocks();
        if (blockCount <= 0) return false;

        // Check if the block directly below is air
        Location belowLoc = botLoc.clone().subtract(0, 1, 0);
        Block below = belowLoc.getBlock();
        if (below.getType() != Material.AIR) return false;

        // Look straight down
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            mc.setCurrentPitch(90.0f);
        }

        // Find a block in the bot's inventory and place it
        if (entity instanceof Player) {
            Player player = (Player) entity;
            int blockSlot = findBlockSlot(player);
            if (blockSlot >= 0) {
                ItemStack blockItem = player.getInventory().getItem(blockSlot);
                if (blockItem != null && blockItem.getType().isBlock()) {
                    // Place the block
                    below.setType(blockItem.getType());
                    // Consume the block
                    if (blockItem.getAmount() > 1) {
                        blockItem.setAmount(blockItem.getAmount() - 1);
                    } else {
                        player.getInventory().setItem(blockSlot, null);
                    }
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Attempts to nudge the bot's velocity toward nearby solid ground.
     *
     * @param entity the bot's entity
     * @param botLoc the bot's current location
     * @param vd     the void detector
     * @return true if a recovery nudge was applied
     */
    private boolean attemptDirectionalRecovery(@Nonnull LivingEntity entity,
                                               @Nonnull Location botLoc,
                                               @Nonnull VoidDetector vd) {
        if (recoverySkill < 0.1) return false;

        // Check nearby directions for solid ground
        Float safeDir = vd.getSafeDirection();
        if (safeDir == null) {
            // No safe direction from void detector — scan manually
            double nearestSolidDist = Double.MAX_VALUE;
            double bestDx = 0, bestDz = 0;

            int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
            for (int[] dir : directions) {
                for (int dist = 1; dist <= 3; dist++) {
                    Location check = botLoc.clone().add(dir[0] * dist, 0, dir[1] * dist);
                    if (!vd.isVoidBelow(check)) {
                        double d = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]) * dist;
                        if (d < nearestSolidDist) {
                            nearestSolidDist = d;
                            bestDx = dir[0] * dist;
                            bestDz = dir[1] * dist;
                        }
                        break;
                    }
                }
            }

            if (nearestSolidDist > 3.5) return false;

            double len = Math.sqrt(bestDx * bestDx + bestDz * bestDz);
            if (len < 0.01) return false;

            double impulseStrength = 0.15 * recoverySkill;
            Vector impulse = new Vector(
                    (bestDx / len) * impulseStrength,
                    0.05,
                    (bestDz / len) * impulseStrength
            );
            entity.setVelocity(entity.getVelocity().add(impulse));
            return true;

        } else {
            // Use safe direction from void detector
            double impulseStrength = 0.15 * recoverySkill;
            double safeYawRad = Math.toRadians(safeDir);
            Vector impulse = new Vector(
                    -Math.sin(safeYawRad) * impulseStrength,
                    0.05,
                    Math.cos(safeYawRad) * impulseStrength
            );
            entity.setVelocity(entity.getVelocity().add(impulse));
            return true;
        }
    }

    /**
     * Finds the inventory slot containing a placeable block.
     *
     * @param player the player entity
     * @return the slot index, or -1 if none found
     */
    private int findBlockSlot(@Nonnull Player player) {
        // Check hotbar first
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isBlock() && item.getType().isSolid()) {
                return i;
            }
        }
        // Check main inventory
        for (int i = 9; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isBlock() && item.getType().isSolid()) {
                return i;
            }
        }
        return -1;
    }
}
