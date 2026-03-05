package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Estimates fall damage for a bot based on its current height, velocity,
 * and the terrain below.
 *
 * <p>In Minecraft 1.8, fall damage is calculated as:
 * {@code damage = fallDistance - 3.0} (i.e., the first 3 blocks of falling are free).
 * Fall damage is negated by landing in water, on slime blocks, using a hay bale,
 * or via water bucket MLG.</p>
 *
 * <p>The estimator is used by:
 * <ul>
 *   <li>Movement system: avoid walking off ledges where the fall would be fatal.</li>
 *   <li>Combat system: evaluate knockback risk near edges — will KB cause lethal fall?</li>
 *   <li>WaterMLGController: determine if an MLG attempt should be triggered.</li>
 *   <li>Decision engine: factor fall risk into utility scores for positioning.</li>
 *   <li>Bridge engine: assess safety of descending bridges.</li>
 * </ul></p>
 *
 * <p>The estimator can evaluate both the bot's current position and hypothetical
 * positions (e.g., "what if I'm knocked 3 blocks in this direction?"), making it
 * useful for proactive decision making.</p>
 */
public class FallDamageEstimator {

    private final TrainerBot bot;

    /**
     * Maximum number of blocks to scan downward when looking for ground.
     * Beyond this depth, we assume void (instant death).
     */
    private static final int MAX_SCAN_DEPTH = 128;

    /**
     * The number of free fall blocks before damage starts in vanilla 1.8.
     * Players take (fallDistance - FREE_FALL_BLOCKS) hearts of damage.
     */
    private static final double FREE_FALL_BLOCKS = 3.0;

    /**
     * Fall distance that is guaranteed to kill a player at 20 HP (full health).
     * damage = fallDist - 3 >= 20 → fallDist >= 23. Adding a small buffer.
     */
    private static final double LETHAL_FALL_DISTANCE = 23.5;

    /** Cached result from the last estimate for the bot's current position. */
    private FallEstimate lastEstimate;

    /** Tick on which the last estimate was computed, to avoid redundant work. */
    private long lastEstimateTick;

    /**
     * Creates a new FallDamageEstimator for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public FallDamageEstimator(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.lastEstimate = null;
        this.lastEstimateTick = -1;
    }

    // ─── Estimation ─────────────────────────────────────────────

    /**
     * Estimates fall damage if the bot were to fall straight down from its
     * current position. Results are cached for the current tick.
     *
     * @return the fall estimate, or null if the bot's position is unavailable
     */
    @Nullable
    public FallEstimate estimateCurrentPosition() {
        long currentTick = bot.getLocalTickCount();
        if (lastEstimate != null && lastEstimateTick == currentTick) {
            return lastEstimate;
        }

        Location botLoc = bot.getLocation();
        if (botLoc == null || botLoc.getWorld() == null) {
            return null;
        }

        lastEstimate = estimateAt(botLoc);
        lastEstimateTick = currentTick;
        return lastEstimate;
    }

    /**
     * Estimates fall damage at a specific location. This evaluates what would
     * happen if the bot were standing at that location and fell straight down.
     *
     * <p>The estimator scans downward from the location to find:
     * <ol>
     *   <li>The first solid block (landing point)</li>
     *   <li>Any water along the fall path (negates fall damage)</li>
     *   <li>Any lava along the fall path (additional fire damage)</li>
     *   <li>Slime blocks at the landing point (negates fall damage)</li>
     * </ol></p>
     *
     * @param location the position to evaluate
     * @return the fall estimate (never null; will indicate void if no ground found)
     */
    @Nonnull
    public FallEstimate estimateAt(@Nonnull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return new FallEstimate(0, 0, true, false, false, false, null);
        }

        int startX = location.getBlockX();
        int startY = location.getBlockY();
        int startZ = location.getBlockZ();

        /*
         * Check the block the bot is standing on first. If it's solid, there is
         * no fall at all. We check Y-1 since the bot stands ON TOP of a block.
         */
        Block feetBlock = world.getBlockAt(startX, startY - 1, startZ);
        if (feetBlock.getType().isSolid()) {
            return new FallEstimate(0, 0, false, false, false,
                    feetBlock.getType() == Material.SLIME_BLOCK, feetBlock.getLocation());
        }

        // Scan downward to find the landing surface
        boolean waterInPath = false;
        boolean lavaInPath = false;
        int fallBlocks = 0;

        for (int y = startY - 1; y >= Math.max(0, startY - MAX_SCAN_DEPTH); y--) {
            Block block = world.getBlockAt(startX, y, startZ);
            Material type = block.getType();

            if (type == Material.WATER || type == Material.STATIONARY_WATER) {
                waterInPath = true;
            }
            if (type == Material.LAVA || type == Material.STATIONARY_LAVA) {
                lavaInPath = true;
            }

            if (type.isSolid()) {
                // Found the landing block
                fallBlocks = startY - y - 1; // distance in blocks
                double damage = calculateDamage(fallBlocks, waterInPath,
                        type == Material.SLIME_BLOCK, type == Material.HAY_BLOCK);
                boolean isSlime = type == Material.SLIME_BLOCK;
                return new FallEstimate(fallBlocks, damage, false, waterInPath,
                        lavaInPath, isSlime, block.getLocation());
            }

            fallBlocks++;
        }

        // No solid block found within scan depth — this is void
        return new FallEstimate(fallBlocks, Double.MAX_VALUE, true, waterInPath,
                lavaInPath, false, null);
    }

    /**
     * Estimates fall damage at a position offset from the bot's current location.
     * Used for evaluating knockback risk: "what if I'm knocked 3 blocks east?"
     *
     * @param offsetX X offset from bot's current position
     * @param offsetY Y offset from bot's current position
     * @param offsetZ Z offset from bot's current position
     * @return the fall estimate at the offset position
     */
    @Nullable
    public FallEstimate estimateAtOffset(double offsetX, double offsetY, double offsetZ) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return null;

        Location offsetLoc = botLoc.clone().add(offsetX, offsetY, offsetZ);
        return estimateAt(offsetLoc);
    }

    // ─── Convenience Queries ────────────────────────────────────

    /**
     * Returns whether the bot's current position would result in a fatal fall.
     * A fall is fatal if the estimated damage >= the bot's current health.
     *
     * @return true if the fall from the current position would be lethal
     */
    public boolean isCurrentPositionLethal() {
        FallEstimate estimate = estimateCurrentPosition();
        if (estimate == null) return false;
        if (estimate.isVoid) return true;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;

        return estimate.estimatedDamage >= entity.getHealth();
    }

    /**
     * Returns whether a fall from the current position would deal any damage.
     *
     * @return true if the bot would take fall damage from the current position
     */
    public boolean wouldTakeFallDamage() {
        FallEstimate estimate = estimateCurrentPosition();
        if (estimate == null) return false;
        return estimate.estimatedDamage > 0;
    }

    /**
     * Evaluates the fall risk in a given direction from the bot's position.
     * Checks 1-3 blocks ahead in the direction and estimates the worst-case
     * fall damage.
     *
     * @param yawDirection the horizontal direction to check (degrees)
     * @param checkDistance how many blocks ahead to check (1-5)
     * @return the worst-case fall estimate in that direction, or null if unavailable
     */
    @Nullable
    public FallEstimate evaluateDirectionRisk(float yawDirection, int checkDistance) {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return null;

        double yawRad = Math.toRadians(yawDirection);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        FallEstimate worstCase = null;
        double worstDamage = -1;

        checkDistance = (int) MathUtil.clamp(checkDistance, 1, 5);

        for (int dist = 1; dist <= checkDistance; dist++) {
            Location checkLoc = botLoc.clone().add(dirX * dist, 0, dirZ * dist);
            FallEstimate estimate = estimateAt(checkLoc);

            if (estimate.estimatedDamage > worstDamage || estimate.isVoid) {
                worstDamage = estimate.estimatedDamage;
                worstCase = estimate;
                if (estimate.isVoid) break; // Can't get worse than void
            }
        }

        return worstCase;
    }

    /**
     * Returns a safety score for the bot's current position, where 1.0 means
     * completely safe (solid ground, no fall risk) and 0.0 means extremely
     * dangerous (void or lethal fall).
     *
     * <p>Used by the decision engine as a positioning factor in utility scoring.</p>
     *
     * @return a safety score in [0.0, 1.0]
     */
    public double getPositionSafetyScore() {
        FallEstimate estimate = estimateCurrentPosition();
        if (estimate == null) return 0.5; // Unknown → neutral

        if (estimate.isVoid) return 0.0;
        if (estimate.waterInPath) return 0.9; // Water negates most fall damage
        if (estimate.fallDistance <= 3) return 1.0; // No damage zone

        LivingEntity entity = bot.getLivingEntity();
        double currentHealth = entity != null ? entity.getHealth() : 20.0;

        // Score inversely proportional to how close the fall damage is to being lethal
        double damageRatio = estimate.estimatedDamage / currentHealth;
        return MathUtil.clamp(1.0 - damageRatio, 0.0, 1.0);
    }

    // ─── Damage Calculation ─────────────────────────────────────

    /**
     * Calculates the expected fall damage based on vanilla 1.8 mechanics.
     *
     * <p>Base formula: {@code damage = max(0, fallDistance - 3)}.</p>
     * <p>Modifiers:
     * <ul>
     *   <li>Water in path: damage = 0 (fall damage negated)</li>
     *   <li>Slime block landing: damage = 0 (bounce, no damage)</li>
     *   <li>Hay bale landing: damage *= 0.2 (80% reduction)</li>
     * </ul></p>
     *
     * @param fallDistance    the fall distance in blocks
     * @param waterInPath    whether water is in the fall path
     * @param slimeBlock     whether the landing block is a slime block
     * @param hayBale        whether the landing block is a hay bale
     * @return the estimated damage in half-hearts
     */
    public static double calculateDamage(int fallDistance, boolean waterInPath,
                                         boolean slimeBlock, boolean hayBale) {
        if (waterInPath || slimeBlock) {
            return 0.0;
        }

        double baseDamage = Math.max(0.0, fallDistance - FREE_FALL_BLOCKS);

        if (hayBale) {
            baseDamage *= 0.2;
        }

        return baseDamage;
    }

    /**
     * Returns whether a given fall distance is potentially lethal for a player
     * at full health (20 HP).
     *
     * @param fallDistance the fall distance in blocks
     * @return true if the fall would kill a full-health player
     */
    public static boolean isLethalFall(int fallDistance) {
        return (fallDistance - FREE_FALL_BLOCKS) >= 20.0;
    }

    // ─── Cached Data Access ─────────────────────────────────────

    /**
     * Returns the last computed fall estimate for the bot's current position.
     * May be stale if not updated this tick. Prefer {@link #estimateCurrentPosition()}.
     *
     * @return the last estimate, or null if none computed yet
     */
    @Nullable
    public FallEstimate getLastEstimate() {
        return lastEstimate;
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: FallEstimate
    // ═════════════════════════════════════════════════════════════

    /**
     * Immutable data object containing the results of a fall damage estimation.
     *
     * <p>Provides all information needed for the AI to make positioning decisions:
     * how far the fall is, how much damage it would deal, whether water or lava
     * is involved, and where the landing point is.</p>
     */
    public static final class FallEstimate {

        /** The number of blocks the bot would fall. */
        public final int fallDistance;

        /**
         * The estimated damage in half-hearts. 0 if no damage, Double.MAX_VALUE
         * if falling into void (instant death).
         */
        public final double estimatedDamage;

        /** Whether the fall leads into void (no solid ground found). */
        public final boolean isVoid;

        /** Whether water was found in the fall path (negates damage). */
        public final boolean waterInPath;

        /** Whether lava was found in the fall path (adds fire damage). */
        public final boolean lavaInPath;

        /** Whether the landing block is a slime block (negates damage with bounce). */
        public final boolean slimeBlockLanding;

        /** The location of the landing block, or null if void. */
        @Nullable
        public final Location landingLocation;

        /**
         * Creates a new FallEstimate.
         *
         * @param fallDistance       the fall distance in blocks
         * @param estimatedDamage   the estimated damage
         * @param isVoid            whether this is a void fall
         * @param waterInPath       whether water is in the path
         * @param lavaInPath        whether lava is in the path
         * @param slimeBlockLanding whether landing on a slime block
         * @param landingLocation   the landing block location
         */
        public FallEstimate(int fallDistance, double estimatedDamage, boolean isVoid,
                            boolean waterInPath, boolean lavaInPath,
                            boolean slimeBlockLanding, @Nullable Location landingLocation) {
            this.fallDistance = fallDistance;
            this.estimatedDamage = estimatedDamage;
            this.isVoid = isVoid;
            this.waterInPath = waterInPath;
            this.lavaInPath = lavaInPath;
            this.slimeBlockLanding = slimeBlockLanding;
            this.landingLocation = landingLocation;
        }

        /**
         * Returns true if this fall would deal no damage.
         *
         * @return true if the fall is safe
         */
        public boolean isSafe() {
            return !isVoid && estimatedDamage <= 0;
        }

        /**
         * Returns true if this fall would be lethal to a player with the given HP.
         *
         * @param currentHealth the player's current health in half-hearts
         * @return true if the fall would kill
         */
        public boolean isLethalFor(double currentHealth) {
            return isVoid || estimatedDamage >= currentHealth;
        }

        /**
         * Returns the fraction of health that would be lost from this fall.
         *
         * @param maxHealth the player's maximum health (typically 20)
         * @return the health fraction lost [0.0, 1.0+] (can exceed 1.0 for overkill)
         */
        public double healthFractionLost(double maxHealth) {
            if (isVoid) return 1.0;
            if (maxHealth <= 0) return 1.0;
            return estimatedDamage / maxHealth;
        }

        @Override
        public String toString() {
            if (isVoid) {
                return "FallEstimate{VOID, dist=" + fallDistance + "}";
            }
            return "FallEstimate{dist=" + fallDistance
                    + ", dmg=" + String.format("%.1f", estimatedDamage)
                    + ", water=" + waterInPath
                    + ", lava=" + lavaInPath
                    + ", safe=" + isSafe() + "}";
        }
    }
}
