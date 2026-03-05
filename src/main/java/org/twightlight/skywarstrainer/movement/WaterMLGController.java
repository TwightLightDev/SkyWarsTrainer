package org.twightlight.skywarstrainer.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Controls water bucket MLG (Major League Gaming) plays — using a water bucket
 * while falling to negate fall damage.
 *
 * <p>The water bucket MLG is one of the most skillful mechanics in Minecraft PvP.
 * When a player is falling from a lethal height, they aim straight down and place
 * a water bucket 1-3 blocks before hitting the ground, then pick it up immediately
 * after landing. The success rate is controlled by {@code waterBucketMLG} difficulty
 * parameter.</p>
 *
 * <p>This controller also handles block-clutching: placing a block on an adjacent
 * surface while falling to save yourself from void death.</p>
 */
public class WaterMLGController {

    private final TrainerBot bot;

    /** Whether an MLG attempt is currently in progress. */
    private boolean mlgInProgress;

    /** The Y-level at which the bot started falling. Used to detect significant falls. */
    private double fallStartY;

    /** Whether the bot was airborne last tick. */
    private boolean wasFalling;

    /** Ticks the bot has been continuously falling. */
    private int fallingTicks;

    /** Minimum fall distance (blocks) before MLG is considered. */
    private static final double MIN_MLG_FALL = 10.0;

    /** Blocks above ground at which to start the MLG sequence. */
    private static final double MLG_TRIGGER_HEIGHT = 3.0;

    /**
     * Creates a new WaterMLGController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public WaterMLGController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.mlgInProgress = false;
        this.fallStartY = 0;
        this.wasFalling = false;
        this.fallingTicks = 0;
    }

    /**
     * Tick method called every server tick. Monitors fall state and
     * triggers MLG or block-clutch when appropriate.
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        Vector velocity = NMSHelper.getVelocityDirect(entity);
        boolean currentlyFalling = velocity.getY() < -0.1 && !NMSHelper.isOnGround(entity);

        if (currentlyFalling) {
            if (!wasFalling) {
                // Just started falling
                fallStartY = entity.getLocation().getY();
                fallingTicks = 0;
            }
            fallingTicks++;

            double fallDistance = fallStartY - entity.getLocation().getY();

            // Check if we should attempt MLG
            if (fallDistance >= MIN_MLG_FALL && !mlgInProgress) {
                attemptMLG(entity);
            }

            // Check for block-clutch opportunity (falling toward void)
            if (isAboveVoid(entity) && fallingTicks > 5) {
                attemptBlockClutch(entity);
            }

        } else {
            if (wasFalling && mlgInProgress) {
                // Just landed after MLG — pick up water
                pickUpWater(entity);
                mlgInProgress = false;
            }
            fallingTicks = 0;
        }

        wasFalling = currentlyFalling;
    }

    /**
     * Attempts a water bucket MLG. Checks inventory for water bucket,
     * aims straight down, and places water at the right moment.
     *
     * @param entity the bot entity
     */
    private void attemptMLG(@Nonnull LivingEntity entity) {
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Check if the bot has the skill for this
        if (!RandomUtil.chance(diff.getWaterBucketMLG())) {
            return; // Skill check failed — bot doesn't attempt MLG
        }

        // Check for water bucket in inventory
        if (!(entity instanceof Player)) return;
        Player player = (Player) entity;
        int waterSlot = findWaterBucket(player);
        if (waterSlot < 0) return;

        // Calculate distance to ground
        double groundY = findGroundBelow(entity.getLocation());
        double currentY = entity.getLocation().getY();
        double distanceToGround = currentY - groundY;

        // Trigger MLG when close enough to the ground
        if (distanceToGround <= MLG_TRIGGER_HEIGHT && distanceToGround > 0.5) {
            mlgInProgress = true;

            // Switch to water bucket hotbar slot
            player.getInventory().setHeldItemSlot(waterSlot);

            // Place water at the block below
            Location targetBlock = entity.getLocation().clone();
            targetBlock.setY(groundY);
            Block block = targetBlock.getBlock();

            if (block.getType() == Material.AIR || block.isLiquid()) {
                // Place water on the solid block below
                Block solidBelow = block.getRelative(0, -1, 0);
                if (solidBelow.getType().isSolid()) {
                    block.setType(Material.WATER);
                }
            }
        }
    }

    /**
     * Attempts to place a block on an adjacent surface while falling.
     * Used to save the bot from falling into the void.
     *
     * @param entity the bot entity
     */
    private void attemptBlockClutch(@Nonnull LivingEntity entity) {
        DifficultyProfile diff = bot.getDifficultyProfile();

        // Block clutch skill is related to waterBucketMLG and difficulty
        double clutchChance = diff.getWaterBucketMLG() * 0.8;
        if (!RandomUtil.chance(clutchChance)) return;

        if (!(entity instanceof Player)) return;
        Player player = (Player) entity;

        // Find a block in the inventory to place
        int blockSlot = findPlaceableBlock(player);
        if (blockSlot < 0) return;

        // Look for an adjacent solid surface to place against
        Location loc = entity.getLocation();
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] offset : offsets) {
            Block adjacent = loc.getWorld().getBlockAt(
                    loc.getBlockX() + offset[0],
                    loc.getBlockY(),
                    loc.getBlockZ() + offset[2]);
            if (adjacent.getType().isSolid()) {
                // Place block at the bot's position
                Block targetBlock = loc.getBlock();
                if (targetBlock.getType() == Material.AIR) {
                    // Switch to block slot and place
                    player.getInventory().setHeldItemSlot(blockSlot);
                    ItemStack blockItem = player.getInventory().getItem(blockSlot);
                    if (blockItem != null && blockItem.getType().isBlock()) {
                        targetBlock.setType(blockItem.getType());
                        // Consume one block from the stack
                        if (blockItem.getAmount() > 1) {
                            blockItem.setAmount(blockItem.getAmount() - 1);
                        } else {
                            player.getInventory().setItem(blockSlot, null);
                        }
                    }
                    return; // Successfully clutched
                }
            }
        }
    }

    /**
     * Picks up the water block placed during MLG after landing.
     *
     * @param entity the bot entity
     */
    private void pickUpWater(@Nonnull LivingEntity entity) {
        // Look for water at/below the bot's feet
        Location loc = entity.getLocation();
        for (int dy = 0; dy >= -2; dy--) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() + dy, loc.getBlockZ());
            if (block.getType() == Material.WATER || block.getType() == Material.STATIONARY_WATER) {
                block.setType(Material.AIR);
                // The water bucket is "collected" — we don't need to actually modify inventory
                // since this is a simplified simulation
                return;
            }
        }
    }

    /**
     * Checks if the bot is above the void (no solid ground in a reasonable depth).
     *
     * @param entity the entity to check
     * @return true if the bot is above void
     */
    private boolean isAboveVoid(@Nonnull LivingEntity entity) {
        Location loc = entity.getLocation();
        int startY = loc.getBlockY();
        int minY = Math.max(0, startY - 64);

        for (int y = startY; y >= minY; y--) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid()) {
                return false; // There is ground below
            }
        }
        return true; // No ground found — above void
    }

    /**
     * Finds the Y-level of the first solid block directly below the location.
     *
     * @param loc the starting location
     * @return the Y-level of the ground, or 0 if no ground found
     */
    private double findGroundBelow(@Nonnull Location loc) {
        int startY = loc.getBlockY();
        int minY = Math.max(0, startY - 64);

        for (int y = startY - 1; y >= minY; y--) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid()) {
                return y + 1; // Top of the solid block
            }
        }
        return 0;
    }

    /**
     * Searches the player's hotbar for a water bucket.
     *
     * @param player the player
     * @return the hotbar slot index (0-8), or -1 if not found
     */
    private int findWaterBucket(@Nonnull Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.WATER_BUCKET) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Searches the player's hotbar for a placeable block.
     *
     * @param player the player
     * @return the hotbar slot index (0-8), or -1 if not found
     */
    private int findPlaceableBlock(@Nonnull Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType().isBlock() && item.getType().isSolid()) {
                return i;
            }
        }
        return -1;
    }

    /** @return true if an MLG attempt is currently in progress */
    public boolean isMlgInProgress() { return mlgInProgress; }

    /** @return true if the bot is currently falling */
    public boolean isFalling() { return wasFalling; }

    /** @return ticks the bot has been falling continuously */
    public int getFallingTicks() { return fallingTicks; }

    /**
     * Returns the total distance the bot has fallen so far.
     *
     * @return fall distance in blocks, or 0 if not falling
     */
    public double getCurrentFallDistance() {
        if (!wasFalling) return 0;
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return 0;
        return Math.max(0, fallStartY - entity.getLocation().getY());
    }
}

