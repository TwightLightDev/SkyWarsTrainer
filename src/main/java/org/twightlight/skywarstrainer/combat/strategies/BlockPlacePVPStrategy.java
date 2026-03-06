package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.CombatEngine;
import org.twightlight.skywarstrainer.combat.ComboTracker;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.awareness.VoidDetector;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Places blocks during PvP to break combos, create obstacles, gain height
 * advantage, or clutch near void edges.
 *
 * <p>This strategy covers several sub-behaviors:
 * <ul>
 *   <li><b>Combo break:</b> Place a block between bot and enemy when being comboed</li>
 *   <li><b>Height advantage:</b> Jump → place block under feet → attack from above</li>
 *   <li><b>Defensive barrier:</b> Place 1-2 blocks to create cover when under pressure</li>
 *   <li><b>Void safety:</b> Place blocks when knocked near edges</li>
 *   <li><b>Random tactical:</b> Occasionally place blocks to disrupt enemy movement</li>
 * </ul></p>
 *
 * <p>The {@code blockPlaceChance} parameter controls overall frequency, and
 * {@code comboBreakPriority} controls how aggressively it responds to being comboed.</p>
 */
public class BlockPlacePVPStrategy implements CombatStrategy {

    /**
     * The type of block placement being attempted.
     */
    private enum PlaceMode {
        COMBO_BREAK,
        HEIGHT_ADVANTAGE,
        DEFENSIVE_BARRIER,
        VOID_SAFETY,
        RANDOM_TACTICAL
    }

    /** Current placement mode. */
    private PlaceMode activeMode;

    /** Cooldown in ticks before the next block placement attempt. */
    private int placeCooldown;

    /** Number of jump-placements chained in sequence. */
    private int jumpPlaceChain;

    /** Minimum ticks between block placement attempts. */
    private static final int MIN_PLACE_COOLDOWN = 8;

    /** Materials considered as building blocks, in priority order. */
    private static final Material[] BLOCK_MATERIALS = {
            Material.COBBLESTONE, Material.STONE, Material.WOOL,
            Material.WOOD, Material.SANDSTONE, Material.DIRT,
            Material.SAND, Material.NETHERRACK
    };

    @Nonnull
    @Override
    public String getName() {
        return "BlockPlacePVP";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getBlockPlaceChance() <= 0.0) return false;
        if (placeCooldown > 0) {
            placeCooldown--;
            return false;
        }

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        // Must have blocks to place
        if (!hasPlaceableBlocks(player)) return false;

        return true;
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        // Determine which placement mode to use based on context
        activeMode = selectPlaceMode(bot, diff);
        if (activeMode == null) return;

        switch (activeMode) {
            case COMBO_BREAK:
                executeComboBreak(bot, player, diff);
                break;
            case HEIGHT_ADVANTAGE:
                executeHeightAdvantage(bot, player, diff);
                break;
            case DEFENSIVE_BARRIER:
                executeDefensiveBarrier(bot, player);
                break;
            case VOID_SAFETY:
                executeVoidSafety(bot, player);
                break;
            case RANDOM_TACTICAL:
                executeRandomTactical(bot, player);
                break;
        }
    }

    /**
     * Selects the most appropriate block placement mode based on context.
     */
    @Nullable
    private PlaceMode selectPlaceMode(@Nonnull TrainerBot bot, @Nonnull DifficultyProfile diff) {
        // Priority 1: Void safety (survival)
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null && voidDetector.isOnEdge()) {
            return PlaceMode.VOID_SAFETY;
        }

        // Priority 2: Combo break (being comboed)
        ComboTracker comboTracker = getComboTracker(bot);
        if (comboTracker != null && comboTracker.isBeingComboed()
                && RandomUtil.chance(diff.getComboBreakPriority())) {
            return PlaceMode.COMBO_BREAK;
        }

        // Priority 3: Height advantage (HARD+ difficulty, random chance)
        if (diff.getDifficulty().asFraction() >= 0.5
                && RandomUtil.chance(diff.getBlockPlaceChance() * 0.3)) {
            return PlaceMode.HEIGHT_ADVANTAGE;
        }

        // Priority 4: Defensive barrier when under pressure
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity != null) {
            double healthFraction = botEntity.getHealth() / botEntity.getMaxHealth();
            if (healthFraction < 0.4 && RandomUtil.chance(diff.getBlockPlaceChance())) {
                return PlaceMode.DEFENSIVE_BARRIER;
            }
        }

        // Priority 5: Random tactical placement
        if (RandomUtil.chance(diff.getBlockPlaceChance() * 0.15)) {
            return PlaceMode.RANDOM_TACTICAL;
        }

        return null;
    }

    /**
     * Places a block between the bot and enemy to break a combo.
     */
    private void executeComboBreak(@Nonnull TrainerBot bot, @Nonnull Player player,
                                   @Nonnull DifficultyProfile diff) {
        Location botLoc = player.getLocation();
        // Find the nearest enemy direction
        LivingEntity nearest = findNearestEnemy(bot);
        if (nearest == null) return;

        Vector toEnemy = MathUtil.directionTo(botLoc, nearest.getLocation());
        // Place block 1 block toward the enemy
        Location placePos = botLoc.clone().add(toEnemy.multiply(1.0));
        placePos.setY(botLoc.getY()); // Same Y level

        placeBlockAt(player, placePos);
        placeCooldown = MIN_PLACE_COOLDOWN;

        // Strafe sideways after placing to re-engage
        MovementController mc = bot.getMovementController();
        if (mc != null) {
            Vector right = mc.getRightDirection();
            double strafeDir = RandomUtil.nextBoolean() ? 1.0 : -1.0;
            Location strafeTarget = botLoc.clone().add(right.multiply(strafeDir * 2.0));
            mc.setMoveTarget(strafeTarget);
        }
    }

    /**
     * Jump and place a block under feet for height advantage.
     */
    private void executeHeightAdvantage(@Nonnull TrainerBot bot, @Nonnull Player player,
                                        @Nonnull DifficultyProfile diff) {
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        // Jump
        mc.getJumpController().jump();

        // Schedule block placement below feet (1-2 ticks after jump start)
        // In practice, we place the block at the bot's foot position
        Location belowFeet = player.getLocation().clone();
        belowFeet.setY(belowFeet.getY() - 0.5);

        placeBlockAt(player, belowFeet);

        jumpPlaceChain++;
        // Chain limit based on difficulty
        int maxChain = diff.getDifficulty().asFraction() >= 0.75 ? 3 : 1;
        if (jumpPlaceChain >= maxChain) {
            jumpPlaceChain = 0;
            placeCooldown = MIN_PLACE_COOLDOWN * 2;
        } else {
            placeCooldown = 3; // Short cooldown for chain
        }
    }

    /**
     * Places 1-2 blocks as a defensive barrier.
     */
    private void executeDefensiveBarrier(@Nonnull TrainerBot bot, @Nonnull Player player) {
        LivingEntity nearest = findNearestEnemy(bot);
        if (nearest == null) return;

        Location botLoc = player.getLocation();
        Vector toEnemy = MathUtil.directionTo(botLoc, nearest.getLocation());

        // Place a block between bot and enemy at body height
        Location placePos = botLoc.clone().add(toEnemy.multiply(1.0));
        placePos.setY(botLoc.getY());
        placeBlockAt(player, placePos);

        // Optionally place a second block on top for a taller wall
        if (RandomUtil.chance(0.5)) {
            Location abovePos = placePos.clone().add(0, 1, 0);
            placeBlockAt(player, abovePos);
        }

        placeCooldown = MIN_PLACE_COOLDOWN * 2;
    }

    /**
     * Places a block for void safety when near edges.
     */
    private void executeVoidSafety(@Nonnull TrainerBot bot, @Nonnull Player player) {
        Location botLoc = player.getLocation();

        // Place block directly under feet
        Location belowFeet = botLoc.clone();
        belowFeet.setY(belowFeet.getY() - 1);

        Block belowBlock = belowFeet.getBlock();
        if (belowBlock.getType() == Material.AIR) {
            placeBlockAt(player, belowFeet);
        }

        // Also place block behind (away from void)
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null) {
            Float safeDir = voidDetector.getSafeDirection();
            if (safeDir != null) {
                double rad = Math.toRadians(safeDir);
                Location behindPos = botLoc.clone().add(-Math.sin(rad), 0, Math.cos(rad));
                placeBlockAt(player, behindPos);
            }
        }

        placeCooldown = MIN_PLACE_COOLDOWN;
    }

    /**
     * Random tactical block placement to create unpredictable obstacles.
     */
    private void executeRandomTactical(@Nonnull TrainerBot bot, @Nonnull Player player) {
        Location botLoc = player.getLocation();

        // Pick a random adjacent position
        int dx = RandomUtil.nextInt(-1, 1);
        int dz = RandomUtil.nextInt(-1, 1);
        if (dx == 0 && dz == 0) dz = 1;

        Location placePos = botLoc.clone().add(dx, 0, dz);
        placeBlockAt(player, placePos);
        placeCooldown = MIN_PLACE_COOLDOWN * 3;
    }

    /**
     * Attempts to place a block at the given location from the bot's inventory.
     *
     * @param player   the bot's player entity
     * @param location the target block location
     */
    private void placeBlockAt(@Nonnull Player player, @Nonnull Location location) {
        Block targetBlock = location.getBlock();
        if (targetBlock.getType() != Material.AIR) return; // Already occupied

        // Find a placeable block in inventory
        Material blockType = findPlaceableBlock(player);
        if (blockType == null) return;

        // Check that there's an adjacent solid block to place against
        if (!hasAdjacentSolid(targetBlock)) return;

        // Place the block
        targetBlock.setType(blockType);

        // Remove the block from inventory
        removeOneBlock(player, blockType);
    }

    /**
     * Checks if any face of the target block is adjacent to a solid block.
     */
    private boolean hasAdjacentSolid(@Nonnull Block block) {
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace face : faces) {
            if (block.getRelative(face).getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the first available placeable block material in the player's inventory.
     */
    @Nullable
    private Material findPlaceableBlock(@Nonnull Player player) {
        for (Material mat : BLOCK_MATERIALS) {
            if (player.getInventory().contains(mat)) {
                return mat;
            }
        }
        return null;
    }

    /**
     * Checks if the player has any placeable blocks in inventory.
     */
    private boolean hasPlaceableBlocks(@Nonnull Player player) {
        return findPlaceableBlock(player) != null;
    }

    /**
     * Removes one block of the specified type from inventory.
     */
    private void removeOneBlock(@Nonnull Player player, @Nonnull Material material) {
        ItemStack toRemove = new ItemStack(material, 1);
        player.getInventory().removeItem(toRemove);
    }

    /**
     * Finds the nearest visible enemy to the bot.
     */
    @Nullable
    private LivingEntity findNearestEnemy(@Nonnull TrainerBot bot) {
        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return null;

        double nearestDist = Double.MAX_VALUE;
        LivingEntity nearest = null;
        for (org.bukkit.entity.Entity entity : botEntity.getNearbyEntities(10, 10, 10)) {
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

    /**
     * Gets the combo tracker from the bot's combat engine.
     *
     * @param bot the trainer bot
     * @return the combo tracker, or null if the combat engine is not active
     */
    @Nullable
    private ComboTracker getComboTracker(@Nonnull TrainerBot bot) {
        CombatEngine engine = bot.getCombatEngine();
        if (engine != null) {
            return engine.getComboTracker();
        }
        return null;
    }


    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();

        double basePriority = 3.0 * diff.getBlockPlaceChance();

        // Boost priority when near void
        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null && voidDetector.isOnEdge()) {
            basePriority += 5.0; // Void safety is critical
        }

        return basePriority;
    }

    @Override
    public void reset() {
        activeMode = null;
        placeCooldown = 0;
        jumpPlaceChain = 0;
    }
}

