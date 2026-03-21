package org.twightlight.skywarstrainer.ai.decision;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.twightlight.skywarstrainer.awareness.*;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * An immutable snapshot of the world state relevant to decision-making.
 *
 * <p>DecisionContext is created at the start of each Utility AI evaluation cycle.
 * It captures all the data that Considerations need so they don't reach back into
 * the live bot during scoring (which could cause inconsistencies if data changes
 * mid-evaluation). This also allows object pooling — the context can be reset and
 * reused rather than creating a new object each evaluation cycle.</p>
 *
 * <p>Fields are package-private for direct access by Consideration classes within
 * the same package, avoiding getter overhead in hot-path scoring.</p>
 */
public class DecisionContext {

    // ── Bot Reference ──
    /** The bot this context belongs to. Considerations that need subsystem access can use this. */
    TrainerBot bot;

    // ── Health ──
    /** Current health (0-20). */
    double currentHealth;
    /** Maximum health (typically 20). */
    double maxHealth;
    /** Health fraction [0.0, 1.0]. */
    public double healthFraction;
    /** Current food level (0-20). */
    int foodLevel;
    /** Whether the bot has absorption hearts (golden apple effect). */
    public boolean hasAbsorption;

    // ── Position ──
    /** Bot's current location. */
    Location botLocation;
    /** Whether the bot is near a void edge. */
    public boolean nearVoidEdge;
    /** Distance to the nearest void edge. */
    public double voidEdgeDistance;
    /** Whether the bot is near lava. */
    public boolean nearLava;

    // ── Threats ──
    /** Number of visible enemies. */
    public int visibleEnemyCount;
    /** Distance to the nearest enemy. */
    public double nearestEnemyDistance;
    /** The nearest visible threat entry. */
    ThreatMap.ThreatEntry nearestThreat;
    /** All visible threats. */
    List<ThreatMap.ThreatEntry> visibleThreats;
    /** Whether an enemy is on a bridge (projectile opportunity). */
    public boolean enemyOnBridge;
    /** Whether an enemy is near a void edge (knockback opportunity). */
    public boolean enemyNearVoid;

    // ── Equipment ──
    /** Whether the bot has any sword. */
    public boolean hasSword;
    /** Whether the bot has a bow. */
    public boolean hasBow;
    /** Arrow count. */
    public int arrowCount;
    /** Whether the bot has a fishing rod. */
    public boolean hasFishingRod;
    /** Whether the bot has an ender pearl. */
    public boolean hasEnderPearl;
    /** Number of building blocks. */
    public int blockCount;
    /** Whether the bot has a golden apple. */
    public boolean hasGoldenApple;
    /** Whether the bot has any food. */
    public boolean hasFood;
    /** Whether the bot has potions. */
    public boolean hasPotions;
    /** Whether the bot has a water bucket. */
    boolean hasWaterBucket;
    /** Whether the bot has projectiles (eggs, snowballs). */
    public boolean hasProjectiles;

    /**
     * Equipment quality score [0.0, 1.0] estimating overall gear level.
     * 0.0 = naked, 1.0 = full diamond enchanted.
     */
    public double equipmentScore;

    /**
     * Estimated enemy equipment score [0.0, 1.0] based on visible armor/weapon.
     * -1 if no enemy is visible or gear is unknown.
     */
    public double estimatedEnemyEquipmentScore;

    // ── Game Phase ──
    /** Current game phase (EARLY, MID, LATE). */
    public GamePhaseTracker.GamePhase gamePhase;
    /** Continuous game progress [0.0, 1.0]. */
    public double gameProgress;
    /** Time pressure [0.0, 1.0] — rises as game time increases. */
    public double timePressure;
    /** Combat urgency [0.0, 1.0] — rises in mid-late game. */
    double combatUrgency;
    /** Whether currently in grace period. */
    boolean isGracePeriod;

    // ── Loot ──
    /** Number of unlooted chests visible. */
    public int unlootedChestCount;
    /** Distance to the nearest unlooted chest. */
    public double nearestChestDistance;
    /** Whether there are chests on the bot's own island. */
    boolean hasOwnIslandChests;
    /** Whether mid island has known unlooted chests. */
    boolean hasMidChests;

    // ── Map / Island ──
    /** The number of alive players in the game. */
    public int alivePlayerCount;
    /** Whether the bot is on the mid island. */
    public boolean onMidIsland;
    /** Whether a bridge to mid exists. */
    public boolean bridgeToMidExists;
    /** Whether the bot has access to an enchanting table. */
    public boolean enchantingTableAccessible;

    // ── Difficulty ──
    /** The bot's difficulty profile. */
    public DifficultyProfile difficultyProfile;

    /**
     * Creates an empty context. Call {@link #populate(TrainerBot)} to fill it.
     */
    public DecisionContext() {
        // Fields default to 0/false/null
    }

    /**
     * Populates this context by reading the bot's current state and all subsystems.
     * This is the main data-gathering method called at the start of each evaluation.
     *
     * @param bot the bot to snapshot
     * @return this context for chaining
     */
    @Nonnull
    public DecisionContext populate(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.difficultyProfile = bot.getDifficultyProfile();

        populateHealth(bot);
        populatePosition(bot);
        populateThreats(bot);
        populateEquipment(bot);
        populateGamePhase(bot);
        populateLoot(bot);
        populateMapInfo(bot);

        return this;
    }

    /**
     * Captures health-related data from the bot's entity.
     */
    private void populateHealth(@Nonnull TrainerBot bot) {
        LivingEntity entity = bot.getLivingEntity();
        if (entity != null) {
            currentHealth = entity.getHealth();
            maxHealth = entity.getMaxHealth();
            healthFraction = (maxHealth > 0) ? (currentHealth / maxHealth) : 0.0;
            // Check for absorption hearts (from golden apple)
            hasAbsorption = false;
        } else {
            currentHealth = 0;
            maxHealth = 20;
            healthFraction = 0;
            hasAbsorption = false;
        }

        Player player = bot.getPlayerEntity();
        foodLevel = (player != null) ? player.getFoodLevel() : 20;
    }

    /**
     * Captures positional data: location, void edges, lava proximity.
     */
    private void populatePosition(@Nonnull TrainerBot bot) {
        botLocation = bot.getLocation();

        VoidDetector voidDetector = bot.getVoidDetector();
        if (voidDetector != null) {
            nearVoidEdge = voidDetector.isNearVoidEdge();
            voidEdgeDistance = voidDetector.getDistanceToVoidEdge();
        } else {
            nearVoidEdge = false;
            voidEdgeDistance = Double.MAX_VALUE;
        }

        nearLava = (bot.getLavaDetector() != null) && bot.getLavaDetector().isLavaDetected();
    }

    /**
     * Captures threat data from the ThreatMap.
     */
    private void populateThreats(@Nonnull TrainerBot bot) {
        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap != null) {
            visibleEnemyCount = threatMap.getVisibleEnemyCount();
            nearestThreat = threatMap.getNearestThreat();
            visibleThreats = threatMap.getVisibleThreats();
            nearestEnemyDistance = (nearestThreat != null && botLocation != null)
                    ? nearestThreat.distanceTo(botLocation) : Double.MAX_VALUE;
        } else {
            visibleEnemyCount = 0;
            nearestThreat = null;
            visibleThreats = java.util.Collections.emptyList();
            nearestEnemyDistance = Double.MAX_VALUE;
        }

        // Detect if an enemy is on a bridge or near void (projectile/KB opportunity)
        enemyOnBridge = false;
        enemyNearVoid = false;
        if (visibleThreats != null && bot.getVoidDetector() != null) {
            for (ThreatMap.ThreatEntry threat : visibleThreats) {
                if (threat.currentPosition != null) {
                    // Simple heuristic: if enemy is on a 1-wide structure, they may be on a bridge
                    if (threat.getHorizontalSpeed() > 0.05 && threat.currentPosition.getBlockY() > 0) {
                        // Check if air is on both sides of the enemy (bridge indicator)
                        // Simplified: check the block below for isolation
                        Location tPos = threat.currentPosition;
                        if (bot.getVoidDetector().isVoidBelow(
                                tPos.clone().add(1, 0, 0))
                                || bot.getVoidDetector().isVoidBelow(
                                tPos.clone().add(-1, 0, 0))) {
                            enemyOnBridge = true;
                        }
                    }

                    // Enemy near void edge check
                    if (bot.getVoidDetector().isVoidBelow(
                            threat.currentPosition.clone().add(2, 0, 0))
                            || bot.getVoidDetector().isVoidBelow(
                            threat.currentPosition.clone().add(-2, 0, 0))
                            || bot.getVoidDetector().isVoidBelow(
                            threat.currentPosition.clone().add(0, 0, 2))
                            || bot.getVoidDetector().isVoidBelow(
                            threat.currentPosition.clone().add(0, 0, -2))) {
                        enemyNearVoid = true;
                    }
                }
            }
        }
    }

    /**
     * Captures equipment data from the bot's inventory.
     */
    private void populateEquipment(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        if (player == null) {
            hasSword = false;
            hasBow = false;
            arrowCount = 0;
            hasFishingRod = false;
            hasEnderPearl = false;
            blockCount = 0;
            hasGoldenApple = false;
            hasFood = false;
            hasPotions = false;
            hasWaterBucket = false;
            hasProjectiles = false;
            equipmentScore = 0.0;
            estimatedEnemyEquipmentScore = -1.0;
            return;
        }

        PlayerInventory inv = player.getInventory();
        hasSword = false;
        hasBow = false;
        arrowCount = 0;
        hasFishingRod = false;
        hasEnderPearl = false;
        blockCount = 0;
        hasGoldenApple = false;
        hasFood = false;
        hasPotions = false;
        hasWaterBucket = false;
        hasProjectiles = false;

        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            String typeName = item.getType().name();

            if (typeName.contains("SWORD")) hasSword = true;
            if (typeName.equals("BOW")) hasBow = true;
            if (typeName.equals("ARROW")) arrowCount += item.getAmount();
            if (typeName.equals("FISHING_ROD")) hasFishingRod = true;
            if (typeName.equals("ENDER_PEARL")) hasEnderPearl = true;
            if (typeName.equals("GOLDEN_APPLE")) hasGoldenApple = true;
            if (typeName.equals("WATER_BUCKET")) hasWaterBucket = true;
            if (typeName.equals("SNOW_BALL") || typeName.equals("EGG")) hasProjectiles = true;
            if (typeName.contains("POTION")) hasPotions = true;

            if (item.getType().isEdible()) hasFood = true;

            if (item.getType().isBlock() || typeName.equals("COBBLESTONE")
                    || typeName.equals("STONE") || typeName.equals("DIRT")
                    || typeName.contains("WOOL") || typeName.contains("PLANK")
                    || typeName.contains("SANDSTONE")) {
                blockCount += item.getAmount();
            }
        }

        // Calculate equipment score
        equipmentScore = calculateEquipmentScore(inv);

        // Estimate enemy equipment score from visible gear
        estimatedEnemyEquipmentScore = estimateEnemyEquipment();
    }

    /**
     * Calculates a 0.0–1.0 equipment quality score from the bot's inventory.
     * Full diamond enchanted = 1.0, naked = 0.0.
     */
    private double calculateEquipmentScore(@Nonnull PlayerInventory inv) {
        double score = 0.0;

        // Armor scoring (up to 0.5)
        ItemStack[] armor = inv.getArmorContents();
        if (armor != null) {
            for (ItemStack piece : armor) {
                if (piece == null) continue;
                String name = piece.getType().name();
                if (name.contains("DIAMOND")) score += 0.125;
                else if (name.contains("IRON")) score += 0.09;
                else if (name.contains("CHAIN")) score += 0.065;
                else if (name.contains("GOLD")) score += 0.04;
                else if (name.contains("LEATHER")) score += 0.025;
            }
        }

        // Weapon scoring (up to 0.3)
        for (ItemStack item : inv.getContents()) {
            if (item == null) continue;
            String name = item.getType().name();
            if (name.contains("DIAMOND_SWORD")) { score += 0.3; break; }
            else if (name.contains("IRON_SWORD")) { score += 0.22; break; }
            else if (name.contains("STONE_SWORD")) { score += 0.15; break; }
            else if (name.contains("GOLD_SWORD")) { score += 0.12; break; }
            else if (name.contains("WOOD_SWORD")) { score += 0.08; break; }
        }

        // Utility items scoring (up to 0.2)
        if (hasBow && arrowCount > 0) score += 0.05;
        if (hasGoldenApple) score += 0.05;
        if (hasEnderPearl) score += 0.03;
        if (blockCount >= 32) score += 0.04;
        else if (blockCount >= 10) score += 0.02;
        if (hasPotions) score += 0.03;

        return Math.min(1.0, score);
    }

    /**
     * Estimates enemy equipment score from the nearest visible enemy's held item
     * and armor. Returns -1 if no enemy is visible.
     */
    private double estimateEnemyEquipment() {
        if (nearestThreat == null || nearestThreat.currentPosition == null) {
            return -1.0;
        }

        // We can't directly read another entity's inventory via standard Bukkit
        // unless they're close enough. For now, use a heuristic based on game phase.
        if (bot.getGamePhaseTracker() != null) {
            double progress = bot.getGamePhaseTracker().getGameProgress();
            // As the game progresses, enemies tend to be better equipped
            return 0.2 + (progress * 0.6);
        }
        return 0.3; // Unknown; assume modestly equipped
    }

    /**
     * Captures game phase data.
     */
    private void populateGamePhase(@Nonnull TrainerBot bot) {
        GamePhaseTracker tracker = bot.getGamePhaseTracker();
        if (tracker != null) {
            gamePhase = tracker.getPhase();
            gameProgress = tracker.getGameProgress();
            timePressure = tracker.getTimePressure();
            combatUrgency = tracker.getCombatUrgency();
            isGracePeriod = tracker.isGracePeriod();
        } else {
            gamePhase = GamePhaseTracker.GamePhase.EARLY;
            gameProgress = 0.0;
            timePressure = 0.0;
            combatUrgency = 0.0;
            isGracePeriod = false;
        }
    }

    /**
     * Captures loot-related data.
     */
    private void populateLoot(@Nonnull TrainerBot bot) {
        ChestLocator locator = bot.getChestLocator();
        if (locator != null) {
            unlootedChestCount = locator.getUnlootedCount();
            ChestLocator.ChestInfo nearest = locator.getNearestUnlootedChest();
            nearestChestDistance = (nearest != null && botLocation != null)
                    ? botLocation.distance(nearest.location) : Double.MAX_VALUE;
            // Simplified island check — detailed in Phase 5 with loot engine
            hasOwnIslandChests = unlootedChestCount > 0 && nearestChestDistance < 20;
            hasMidChests = unlootedChestCount > 0 && nearestChestDistance >= 20;
        } else {
            unlootedChestCount = 0;
            nearestChestDistance = Double.MAX_VALUE;
            hasOwnIslandChests = false;
            hasMidChests = false;
        }
    }

// In DecisionContext.populateMapInfo(), replace the O(n³) enchanting table scan
// with a cached lookup from MapScanner:

    private void populateMapInfo(@Nonnull TrainerBot bot) {
        try {
            alivePlayerCount = bot.getArena().getAlive();
        } catch (Exception e) {
            alivePlayerCount = 8;
        }

        IslandGraph graph = bot.getIslandGraph();
        if (graph != null) {
            onMidIsland = graph.isOnMidIsland(botLocation);
            bridgeToMidExists = graph.hasBridgeToMid(botLocation);
        } else {
            onMidIsland = false;
            bridgeToMidExists = false;
        }

        // [FIX E1/E2] Use MapScanner's cached enchanting table locations instead
        // of scanning a 17×5×17 block cube (1445 blocks!) every evaluation.
        // MapScanner already scans the world incrementally and can track special blocks.
        enchantingTableAccessible = false;
        MapScanner scanner = bot.getMapScanner();
        if (scanner != null && botLocation != null) {
            Location nearestTable = scanner.getNearestEnchantingTable(botLocation);
            if (nearestTable != null) {
                enchantingTableAccessible = botLocation.distance(nearestTable) <= 10.0;
            }
        }
    }


    /**
     * Resets all fields to defaults for object reuse / pooling.
     */
    public void reset() {
        bot = null;
        currentHealth = 0;
        maxHealth = 20;
        healthFraction = 0;
        foodLevel = 20;
        hasAbsorption = false;
        botLocation = null;
        nearVoidEdge = false;
        voidEdgeDistance = Double.MAX_VALUE;
        nearLava = false;
        visibleEnemyCount = 0;
        nearestEnemyDistance = Double.MAX_VALUE;
        nearestThreat = null;
        visibleThreats = null;
        enemyOnBridge = false;
        enemyNearVoid = false;
        hasSword = false;
        hasBow = false;
        arrowCount = 0;
        hasFishingRod = false;
        hasEnderPearl = false;
        blockCount = 0;
        hasGoldenApple = false;
        hasFood = false;
        hasPotions = false;
        hasWaterBucket = false;
        hasProjectiles = false;
        equipmentScore = 0.0;
        estimatedEnemyEquipmentScore = -1.0;
        gamePhase = GamePhaseTracker.GamePhase.EARLY;
        gameProgress = 0.0;
        timePressure = 0.0;
        combatUrgency = 0.0;
        isGracePeriod = false;
        unlootedChestCount = 0;
        nearestChestDistance = Double.MAX_VALUE;
        hasOwnIslandChests = false;
        hasMidChests = false;
        alivePlayerCount = 0;
        onMidIsland = false;
        bridgeToMidExists = false;
        enchantingTableAccessible = false;
        difficultyProfile = null;
    }

    @Override
    public String toString() {
        return String.format("DecisionContext{hp=%.0f/%.0f, enemies=%d, nearestEnemy=%.1f, " +
                        "equip=%.2f, chests=%d, phase=%s, progress=%.2f}",
                currentHealth, maxHealth, visibleEnemyCount, nearestEnemyDistance,
                equipmentScore, unlootedChestCount,
                gamePhase != null ? gamePhase.name() : "null", gameProgress);
    }
}

