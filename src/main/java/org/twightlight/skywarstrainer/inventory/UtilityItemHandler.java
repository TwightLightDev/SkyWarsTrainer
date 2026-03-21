package org.twightlight.skywarstrainer.inventory;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Centralized handler for all utility items that a SkyWars player can use:
 * water bucket, lava bucket, flint & steel, TNT, cobweb, ender chest,
 * and compass-like informational items.
 *
 * <p>Each utility item has:
 * <ul>
 *   <li>An individual cooldown to prevent spam</li>
 *   <li>A skill/IQ gate based on difficulty parameters</li>
 *   <li>Contextual awareness (e.g., only place lava offensively, only MLG
 *       water when falling)</li>
 * </ul></p>
 *
 * <p>This handler is ticked by the {@link InventoryEngine} and also called
 * directly by combat/defense systems for reactive utility usage.</p>
 */
public class UtilityItemHandler {

    private final TrainerBot bot;

    // ─── Cooldowns (in ticks) ───────────────────────────────────
    private int waterBucketCooldown;
    private int lavaBucketCooldown;
    private int flintSteelCooldown;
    private int tntCooldown;
    private int cobwebCooldown;

    // ─── Water MLG State ────────────────────────────────────────
    /** Whether the bot is currently in an MLG water bucket sequence. */
    private boolean mlgActive;
    /** Location where water was placed for MLG (so we can pick it back up). */
    private Location mlgWaterLocation;
    /** Ticks since water was placed during MLG. */
    private int mlgPickupTimer;

    // ─── Lava Placement Tracking ────────────────────────────────
    /** Location of last lava placed, for potential pickup. */
    private Location lastLavaPlacement;
    /** Ticks since lava was placed. */
    private int lavaPickupTimer;

    // ─── Constants ──────────────────────────────────────────────
    private static final int WATER_BUCKET_COOLDOWN = 30;
    private static final int LAVA_BUCKET_COOLDOWN = 60;
    private static final int FLINT_STEEL_COOLDOWN = 40;
    private static final int TNT_COOLDOWN = 80;
    private static final int COBWEB_COOLDOWN = 30;

    /** Ticks to wait after MLG water place before picking it up. */
    private static final int MLG_PICKUP_DELAY = 6;

    /** Max ticks to leave lava on the ground before giving up on pickup. */
    private static final int LAVA_MAX_LINGER = 60;

    /** Minimum fall distance (in blocks) before attempting MLG water. */
    private static final double MLG_MIN_FALL_DISTANCE = 6.0;

    public UtilityItemHandler(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.waterBucketCooldown = 0;
        this.lavaBucketCooldown = 0;
        this.flintSteelCooldown = 0;
        this.tntCooldown = 0;
        this.cobwebCooldown = 0;
        this.mlgActive = false;
        this.mlgWaterLocation = null;
        this.mlgPickupTimer = 0;
        this.lastLavaPlacement = null;
        this.lavaPickupTimer = 0;
    }

    // ═════════════════════════════════════════════════════════════
    //  TICK
    // ═════════════════════════════════════════════════════════════

    /**
     * Ticks all utility item cooldowns and ongoing sequences (MLG pickup, etc.).
     * Called every tick by the InventoryManager's quick-check cycle.
     */
    public void tick() {
        if (waterBucketCooldown > 0) waterBucketCooldown--;
        if (lavaBucketCooldown > 0) lavaBucketCooldown--;
        if (flintSteelCooldown > 0) flintSteelCooldown--;
        if (tntCooldown > 0) tntCooldown--;
        if (cobwebCooldown > 0) cobwebCooldown--;

        // Handle MLG water pickup
        tickMLGPickup();

        // Handle lava pickup
        tickLavaPickup();
    }

    // ═════════════════════════════════════════════════════════════
    //  WATER BUCKET
    // ═════════════════════════════════════════════════════════════

    /**
     * Attempts a water bucket MLG save when falling from a lethal height.
     * The bot places water at the block below, lands in it, then picks it up.
     *
     * <p>Success depends on {@code waterBucketMLG} difficulty parameter.
     * BEGINNER/EASY bots almost never attempt this. HARD/EXPERT bots
     * succeed reliably.</p>
     *
     * @return true if the water was placed (bot is now in MLG sequence)
     */
    public boolean tryWaterBucketMLG() {
        if (waterBucketCooldown > 0) return false;
        if (mlgActive) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();

        // Skill gate: lower difficulty bots don't attempt MLG
        if (!RandomUtil.chance(diff.getWaterBucketMLG())) return false;

        if (!hasItem(player, Material.WATER_BUCKET)) return false;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;

        // Check if actually falling
        Vector velocity = entity.getVelocity();
        if (velocity.getY() >= -0.3) return false; // Not falling fast enough

        // Estimate fall distance — find ground below
        Location loc = entity.getLocation();
        double groundY = findGroundY(loc);
        double fallDistance = loc.getY() - groundY;

        if (fallDistance < MLG_MIN_FALL_DISTANCE) return false;

        // Calculate when to place water based on reaction time
        // At high skill, place water 2-3 blocks above ground
        // At low skill, timing is worse (might be too early or too late)
        double ticksToGround = estimateTicksToGround(velocity.getY(), fallDistance);

        // Place water when close to the ground (2-4 blocks away)
        if (fallDistance > 4.0) return false; // Too high — wait

        // Place water at the ground block
        Location groundLoc = new Location(loc.getWorld(),
                Math.floor(loc.getX()), groundY, Math.floor(loc.getZ()));
        Block groundBlock = groundLoc.getBlock();
        Block aboveGround = groundBlock.getRelative(BlockFace.UP);

        if (aboveGround.getType() != Material.AIR
                && aboveGround.getType() != Material.LONG_GRASS
                && !aboveGround.isLiquid()) {
            return false; // Can't place water here
        }

        // Accuracy check — at low skill, might place water in the wrong spot
        double accuracyError = (1.0 - diff.getWaterBucketMLG()) * 2.0;
        if (accuracyError > 0 && RandomUtil.chance(accuracyError * 0.3)) {
            // Missed the placement — fail the MLG
            waterBucketCooldown = WATER_BUCKET_COOLDOWN;
            return false;
        }

        // Switch to water bucket and place it
        int bucketSlot = findItemSlot(player, Material.WATER_BUCKET);
        if (bucketSlot < 0) return false;

        switchToSlot(player, bucketSlot);
        aboveGround.setType(Material.WATER);
        replaceItem(player, bucketSlot, Material.WATER_BUCKET, Material.BUCKET);
        PacketUtil.playArmSwing(player);

        mlgActive = true;
        mlgWaterLocation = aboveGround.getLocation();
        mlgPickupTimer = 0;
        waterBucketCooldown = WATER_BUCKET_COOLDOWN;

        return true;
    }

    /**
     * Places water offensively to push enemies (e.g., toward void edges).
     *
     * @param targetLocation where to place the water
     * @return true if water was placed
     */
    public boolean tryPlaceWaterOffensive(@Nonnull Location targetLocation) {
        if (waterBucketCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.WATER_BUCKET)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        if (!RandomUtil.chance(diff.getWaterBucketMLG() * 0.7)) return false;

        // Target block must be air and within reach (5 blocks)
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;

        double distance = entity.getLocation().distance(targetLocation);
        if (distance > 5.0) return false;

        Block target = targetLocation.getBlock();
        if (target.getType() != Material.AIR) return false;

        int bucketSlot = findItemSlot(player, Material.WATER_BUCKET);
        if (bucketSlot < 0) return false;

        switchToSlot(player, bucketSlot);
        target.setType(Material.WATER);
        replaceItem(player, bucketSlot, Material.WATER_BUCKET, Material.BUCKET);
        PacketUtil.playArmSwing(player);

        // Schedule water pickup later (don't want permanent water)
        mlgActive = true;
        mlgWaterLocation = target.getLocation();
        mlgPickupTimer = 0;
        waterBucketCooldown = WATER_BUCKET_COOLDOWN;

        return true;
    }

    /**
     * Ticks the MLG water pickup sequence. After placing water, the bot waits
     * a few ticks (to land), then picks the water back up.
     */
    private void tickMLGPickup() {
        if (!mlgActive || mlgWaterLocation == null) return;

        mlgPickupTimer++;

        if (mlgPickupTimer >= MLG_PICKUP_DELAY) {
            // Pick up the water
            Player player = bot.getPlayerEntity();
            if (player != null) {
                Block waterBlock = mlgWaterLocation.getBlock();
                if (waterBlock.getType() == Material.WATER
                        || waterBlock.getType() == Material.STATIONARY_WATER) {
                    waterBlock.setType(Material.AIR);

                    // Convert empty bucket back to water bucket
                    int emptySlot = findItemSlot(player, Material.BUCKET);
                    if (emptySlot >= 0) {
                        replaceItem(player, emptySlot, Material.BUCKET, Material.WATER_BUCKET);
                    }
                    PacketUtil.playArmSwing(player);
                }
            }

            mlgActive = false;
            mlgWaterLocation = null;
            mlgPickupTimer = 0;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  LAVA BUCKET
    // ═════════════════════════════════════════════════════════════

    /**
     * Places lava offensively near a target to deal damage and zone them.
     * After a delay, the bot picks the lava back up (to retain the bucket).
     *
     * @param target the enemy to place lava near
     * @return true if lava was placed
     */
    public boolean tryPlaceLavaOffensive(@Nonnull LivingEntity target) {
        if (lavaBucketCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.LAVA_BUCKET)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        // Lava placement IQ scales with general decision quality and potion usage IQ
        double lavaIQ = (diff.getDecisionQuality() + diff.getPotionUsageIQ()) / 2.0;
        if (!RandomUtil.chance(lavaIQ * 0.5)) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double distance = botEntity.getLocation().distance(target.getLocation());
        if (distance > 5.0 || distance < 1.5) return false;

        // Place lava at the target's feet or slightly ahead of them
        Location targetLoc = target.getLocation().clone();
        Vector targetVelocity = target.getVelocity();

        // Predict where the target will be in a few ticks
        if (lavaIQ >= 0.5 && targetVelocity.lengthSquared() > 0.01) {
            targetLoc.add(targetVelocity.clone().multiply(3.0));
        }

        Block placeBlock = targetLoc.getBlock();
        if (placeBlock.getType() != Material.AIR) {
            placeBlock = placeBlock.getRelative(BlockFace.UP);
        }
        if (placeBlock.getType() != Material.AIR) return false;

        int bucketSlot = findItemSlot(player, Material.LAVA_BUCKET);
        if (bucketSlot < 0) return false;

        switchToSlot(player, bucketSlot);
        placeBlock.setType(Material.LAVA);
        replaceItem(player, bucketSlot, Material.LAVA_BUCKET, Material.BUCKET);
        PacketUtil.playArmSwing(player);

        lastLavaPlacement = placeBlock.getLocation();
        lavaPickupTimer = 0;
        lavaBucketCooldown = LAVA_BUCKET_COOLDOWN;

        return true;
    }

    /**
     * Ticks lava pickup. After placing lava offensively, the bot tries to
     * pick it back up to retain the bucket for future use.
     */
    private void tickLavaPickup() {
        if (lastLavaPlacement == null) return;

        lavaPickupTimer++;

        // Pick up lava after it has been on the ground for a while
        // or if the bot is close enough
        boolean shouldPickup = lavaPickupTimer >= LAVA_MAX_LINGER;

        if (!shouldPickup) {
            LivingEntity entity = bot.getLivingEntity();
            if (entity != null) {
                double dist = entity.getLocation().distance(lastLavaPlacement);
                // Pick up if close and lava has been out for at least 20 ticks
                if (dist <= 4.0 && lavaPickupTimer >= 20) {
                    shouldPickup = true;
                }
            }
        }

        if (shouldPickup) {
            Player player = bot.getPlayerEntity();
            if (player != null) {
                Block lavaBlock = lastLavaPlacement.getBlock();
                if (lavaBlock.getType() == Material.LAVA
                        || lavaBlock.getType() == Material.STATIONARY_LAVA) {
                    lavaBlock.setType(Material.AIR);

                    int emptySlot = findItemSlot(player, Material.BUCKET);
                    if (emptySlot >= 0) {
                        replaceItem(player, emptySlot, Material.BUCKET, Material.LAVA_BUCKET);
                    }
                    PacketUtil.playArmSwing(player);
                }
            }
            lastLavaPlacement = null;
            lavaPickupTimer = 0;
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  FLINT & STEEL
    // ═════════════════════════════════════════════════════════════

    /**
     * Uses flint & steel to ignite the block near a target, setting them on fire.
     * Can also be used defensively to block a path.
     *
     * @param target the enemy to ignite near
     * @return true if fire was placed
     */
    public boolean tryFlintAndSteel(@Nonnull LivingEntity target) {
        if (flintSteelCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.FLINT_AND_STEEL)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        // Flint & steel skill scales with general combat IQ
        double flintIQ = diff.getDecisionQuality();
        if (!RandomUtil.chance(flintIQ * 0.4)) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double distance = botEntity.getLocation().distance(target.getLocation());
        // Flint & steel has very short range (must be adjacent)
        if (distance > 4.0) return false;

        // Place fire at the target's feet
        Location targetLoc = target.getLocation().clone();
        Block feetBlock = targetLoc.getBlock();

        // Find a suitable block to place fire on top of
        Block below = feetBlock.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) return false;
        if (feetBlock.getType() != Material.AIR) return false;

        int flintSlot = findItemSlot(player, Material.FLINT_AND_STEEL);
        if (flintSlot < 0) return false;

        switchToSlot(player, flintSlot);
        feetBlock.setType(Material.FIRE);
        PacketUtil.playArmSwing(player);

        // Damage the flint & steel (durability 64)
        ItemStack flint = player.getInventory().getItem(flintSlot);
        if (flint != null) {
            short newDurability = (short) (flint.getDurability() + 1);
            if (newDurability >= flint.getType().getMaxDurability()) {
                player.getInventory().setItem(flintSlot, null);
            } else {
                flint.setDurability(newDurability);
            }
        }

        flintSteelCooldown = FLINT_STEEL_COOLDOWN;
        return true;
    }

    /**
     * Uses flint & steel to block a path with fire (defensive/zoning).
     *
     * @param location where to place the fire line
     * @return true if fire was placed
     */
    public boolean tryFlintAndSteelZoning(@Nonnull Location location) {
        if (flintSteelCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.FLINT_AND_STEEL)) return false;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;

        double distance = entity.getLocation().distance(location);
        if (distance > 5.0) return false;

        Block target = location.getBlock();
        Block below = target.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid() || target.getType() != Material.AIR) return false;

        int flintSlot = findItemSlot(player, Material.FLINT_AND_STEEL);
        if (flintSlot < 0) return false;

        switchToSlot(player, flintSlot);
        target.setType(Material.FIRE);
        PacketUtil.playArmSwing(player);

        ItemStack flint = player.getInventory().getItem(flintSlot);
        if (flint != null) {
            short newDurability = (short) (flint.getDurability() + 1);
            if (newDurability >= flint.getType().getMaxDurability()) {
                player.getInventory().setItem(flintSlot, null);
            } else {
                flint.setDurability(newDurability);
            }
        }

        flintSteelCooldown = FLINT_STEEL_COOLDOWN;
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    //  TNT
    // ═════════════════════════════════════════════════════════════

    /**
     * Places and ignites TNT near a target for area damage. The bot places
     * TNT at a calculated position and it auto-primes.
     *
     * @param target the enemy to TNT near
     * @return true if TNT was placed and ignited
     */
    public boolean tryPlaceTNT(@Nonnull LivingEntity target) {
        if (tntCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.TNT)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        // TNT usage requires moderate IQ — it can self-damage
        double tntIQ = diff.getDecisionQuality() * 0.8 + diff.getProjectileAccuracy() * 0.2;
        if (!RandomUtil.chance(tntIQ * 0.3)) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double distance = botEntity.getLocation().distance(target.getLocation());
        // TNT should be placed at medium range to avoid self-damage
        if (distance < 4.0 || distance > 8.0) return false;

        // Calculate placement position — between bot and target, closer to target
        Location botLoc = botEntity.getLocation();
        Location targetLoc = target.getLocation();
        Vector direction = MathUtil.directionTo(botLoc, targetLoc);

        Location placeLoc = botLoc.clone().add(direction.multiply(distance * 0.7));
        Block placeBlock = placeLoc.getBlock();

        // TNT needs to be placed on top of a solid block
        Block below = placeBlock.getRelative(BlockFace.DOWN);
        if (!below.getType().isSolid()) {
            // Try the ground level
            placeBlock = below;
            below = placeBlock.getRelative(BlockFace.DOWN);
            if (!below.getType().isSolid()) return false;
        }

        if (placeBlock.getType() != Material.AIR) return false;

        int tntSlot = findItemSlot(player, Material.TNT);
        if (tntSlot < 0) return false;

        switchToSlot(player, tntSlot);
        removeItem(player, Material.TNT, 1);
        PacketUtil.playArmSwing(player);

        // Spawn primed TNT at the location
        World world = placeBlock.getWorld();
        TNTPrimed tnt = world.spawn(placeBlock.getLocation().add(0.5, 0.0, 0.5), TNTPrimed.class);
        tnt.setFuseTicks(40); // 2 seconds fuse

        tntCooldown = TNT_COOLDOWN;
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    //  COBWEB
    // ═════════════════════════════════════════════════════════════

    /**
     * Places a cobweb at the target's location to slow them down.
     * Extremely effective for preventing enemies from chasing or escaping.
     *
     * @param target the enemy to cobweb
     * @return true if cobweb was placed
     */
    public boolean tryPlaceCobweb(@Nonnull LivingEntity target) {
        if (cobwebCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.WEB)) return false;

        DifficultyProfile diff = bot.getDifficultyProfile();
        if (!RandomUtil.chance(diff.getDecisionQuality() * 0.5)) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null) return false;

        double distance = botEntity.getLocation().distance(target.getLocation());
        if (distance > 5.0) return false;

        Location targetLoc = target.getLocation();
        Block placeBlock = targetLoc.getBlock();

        if (placeBlock.getType() != Material.AIR) return false;

        int webSlot = findItemSlot(player, Material.WEB);
        if (webSlot < 0) return false;

        switchToSlot(player, webSlot);
        placeBlock.setType(Material.WEB);
        removeItem(player, Material.WEB, 1);
        PacketUtil.playArmSwing(player);

        cobwebCooldown = COBWEB_COOLDOWN;
        return true;
    }

    /**
     * Places cobweb defensively (e.g., while fleeing, behind the bot).
     *
     * @return true if cobweb was placed
     */
    public boolean tryPlaceCobwebBehind() {
        if (cobwebCooldown > 0) return false;

        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        if (!hasItem(player, Material.WEB)) return false;

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;

        // Place behind the bot
        Vector behind = entity.getLocation().getDirection().multiply(-1);
        behind.setY(0).normalize();
        Location behindLoc = entity.getLocation().clone().add(behind.multiply(1.5));
        Block placeBlock = behindLoc.getBlock();

        if (placeBlock.getType() != Material.AIR) return false;

        int webSlot = findItemSlot(player, Material.WEB);
        if (webSlot < 0) return false;

        switchToSlot(player, webSlot);
        placeBlock.setType(Material.WEB);
        removeItem(player, Material.WEB, 1);
        PacketUtil.playArmSwing(player);

        cobwebCooldown = COBWEB_COOLDOWN;
        return true;
    }

    // ═════════════════════════════════════════════════════════════
    //  QUERY METHODS
    // ═════════════════════════════════════════════════════════════

    /** @return true if the bot has a water bucket */
    public boolean hasWaterBucket() {
        Player p = bot.getPlayerEntity();
        return p != null && hasItem(p, Material.WATER_BUCKET);
    }

    /** @return true if the bot has a lava bucket */
    public boolean hasLavaBucket() {
        Player p = bot.getPlayerEntity();
        return p != null && hasItem(p, Material.LAVA_BUCKET);
    }

    /** @return true if the bot has flint & steel */
    public boolean hasFlintAndSteel() {
        Player p = bot.getPlayerEntity();
        return p != null && hasItem(p, Material.FLINT_AND_STEEL);
    }

    /** @return true if the bot has TNT */
    public boolean hasTNT() {
        Player p = bot.getPlayerEntity();
        return p != null && hasItem(p, Material.TNT);
    }

    /** @return true if the bot has cobwebs */
    public boolean hasCobweb() {
        Player p = bot.getPlayerEntity();
        return p != null && hasItem(p, Material.WEB);
    }

    /** @return true if the bot has ANY utility item */
    public boolean hasAnyUtilityItem() {
        return hasWaterBucket() || hasLavaBucket() || hasFlintAndSteel()
                || hasTNT() || hasCobweb();
    }

    /** @return true if the MLG water sequence is in progress */
    public boolean isMLGActive() {
        return mlgActive;
    }

    /** @return true if water bucket is off cooldown */
    public boolean isWaterReady() {
        return waterBucketCooldown <= 0;
    }

    /** @return true if lava bucket is off cooldown */
    public boolean isLavaReady() {
        return lavaBucketCooldown <= 0;
    }

    /** @return true if flint & steel is off cooldown */
    public boolean isFlintReady() {
        return flintSteelCooldown <= 0;
    }

    /** @return true if TNT is off cooldown */
    public boolean isTNTReady() {
        return tntCooldown <= 0;
    }

    /** @return true if cobweb placement is off cooldown */
    public boolean isCobwebReady() {
        return cobwebCooldown <= 0;
    }

    /**
     * Returns a list of all utility item types currently in the bot's inventory.
     *
     * @return list of available utility item materials
     */
    @Nonnull
    public List<Material> getAvailableUtilityItems() {
        List<Material> items = new ArrayList<>();
        Player p = bot.getPlayerEntity();
        if (p == null) return items;

        if (hasItem(p, Material.WATER_BUCKET)) items.add(Material.WATER_BUCKET);
        if (hasItem(p, Material.LAVA_BUCKET)) items.add(Material.LAVA_BUCKET);
        if (hasItem(p, Material.FLINT_AND_STEEL)) items.add(Material.FLINT_AND_STEEL);
        if (hasItem(p, Material.TNT)) items.add(Material.TNT);
        if (hasItem(p, Material.WEB)) items.add(Material.WEB);
        return items;
    }

    // ═════════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════════

    private boolean hasItem(@Nonnull Player player, @Nonnull Material material) {
        return player.getInventory().contains(material);
    }

    private int findItemSlot(@Nonnull Player player, @Nonnull Material material) {
        PlayerInventory inv = player.getInventory();
        // Check hotbar first for fast access
        for (int i = 0; i < 9; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == material) return i;
        }
        // Then main inventory
        for (int i = 9; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == material) return i;
        }
        return -1;
    }

    private void switchToSlot(@Nonnull Player player, int slot) {
        if (slot >= 0 && slot < 9) {
            player.getInventory().setHeldItemSlot(slot);
        } else if (slot >= 9 && slot < 36) {
            // Move item to hotbar slot 8 (misc slot) then select it
            ItemStack item = player.getInventory().getItem(slot);
            ItemStack swap = player.getInventory().getItem(8);
            player.getInventory().setItem(8, item);
            player.getInventory().setItem(slot, swap);
            player.getInventory().setHeldItemSlot(8);
        }
    }

    private void replaceItem(@Nonnull Player player, int slot,
                             @Nonnull Material from, @Nonnull Material to) {
        // Find the slot with 'from' material and replace with 'to'
        int actualSlot = slot;
        ItemStack item = player.getInventory().getItem(actualSlot);
        if (item != null && item.getType() == from) {
            player.getInventory().setItem(actualSlot, new ItemStack(to, 1));
            return;
        }
        // Search inventory as fallback
        for (int i = 0; i < 36; i++) {
            item = player.getInventory().getItem(i);
            if (item != null && item.getType() == from) {
                player.getInventory().setItem(i, new ItemStack(to, 1));
                return;
            }
        }
    }

    private void removeItem(@Nonnull Player player, @Nonnull Material material, int amount) {
        player.getInventory().removeItem(new ItemStack(material, amount));
    }

    /**
     * Finds the Y coordinate of the nearest solid ground below a location.
     *
     * @param location the starting location
     * @return the Y coordinate of the ground, or 0 if void
     */
    private double findGroundY(@Nonnull Location location) {
        for (int y = location.getBlockY() - 1; y >= 0; y--) {
            Block block = location.getWorld().getBlockAt(
                    location.getBlockX(), y, location.getBlockZ());
            if (block.getType().isSolid()) {
                return y + 1;
            }
        }
        return 0; // Void
    }

    /**
     * Estimates ticks until the entity hits the ground given current vertical velocity.
     *
     * @param velocityY      current vertical velocity (negative = falling)
     * @param fallDistBlocks  remaining fall distance in blocks
     * @return estimated ticks to impact
     */
    private double estimateTicksToGround(double velocityY, double fallDistBlocks) {
        // Minecraft gravity: ~0.08 blocks/tick²
        // Using kinematic equation: d = v*t + 0.5*g*t²
        // Solve for t using quadratic formula
        double g = 0.08;
        double v = Math.abs(velocityY);
        double d = fallDistBlocks;

        double discriminant = v * v + 2 * g * d;
        if (discriminant < 0) return 20; // fallback

        return (-v + Math.sqrt(discriminant)) / g;
    }
}
