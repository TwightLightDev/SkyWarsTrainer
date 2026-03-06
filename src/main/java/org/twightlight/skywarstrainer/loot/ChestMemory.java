// ═══════════════════════════════════════════════════════════════════
// File: src/main/java/org/twightlight/skywarstrainer/loot/ChestMemory.java
// ═══════════════════════════════════════════════════════════════════
package org.twightlight.skywarstrainer.loot;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.awareness.ChestLocator;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Per-bot memory of chest locations and their looted states.
 *
 * <p>ChestMemory extends beyond ChestLocator by storing the bot's subjective
 * knowledge about chests: what items were found, which chests enemies looted,
 * and inferences about enemy gear based on observations.</p>
 *
 * <p>This allows the bot to make intelligent looting decisions:
 * <ul>
 *   <li>Skip chests it already looted</li>
 *   <li>Skip chests it saw enemies loot</li>
 *   <li>Infer enemy chest quality from observed enemy gear</li>
 *   <li>Prioritize chests that likely haven't been looted</li>
 * </ul></p>
 */
public class ChestMemory {

    private final TrainerBot bot;

    /** Extended chest data keyed by block position string. */
    private final Map<String, ChestRecord> records;

    /** Islands inferred to have been looted by enemies. */
    private final Set<String> enemyLootedIslands;

    /**
     * Creates a new ChestMemory for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ChestMemory(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.records = new HashMap<>();
        this.enemyLootedIslands = new HashSet<>();
    }

    /**
     * Records the bot's observation of a chest.
     *
     * @param location the chest location
     * @param looted   whether the chest was looted
     * @param quality  quality rating of items found (0-10, or -1 if unknown)
     * @param items    items found in the chest (can be empty)
     */
    public void recordChest(@Nonnull Location location, boolean looted, int quality,
                            @Nonnull List<ItemStack> items) {
        String key = locationKey(location);
        ChestRecord record = records.computeIfAbsent(key, k -> new ChestRecord(location.clone()));
        record.isLooted = looted;
        record.lootedByBot = looted;
        record.qualityRating = quality;
        record.lastVisitTick = bot.getLocalTickCount();
        record.itemsFound.clear();
        record.itemsFound.addAll(items);
    }

    /**
     * Records that an enemy was seen looting a chest at this location.
     *
     * @param location the chest location
     */
    public void recordEnemyLooted(@Nonnull Location location) {
        String key = locationKey(location);
        ChestRecord record = records.computeIfAbsent(key, k -> new ChestRecord(location.clone()));
        record.isLooted = true;
        record.lootedByEnemy = true;
    }

    /**
     * Infers that an island has been looted based on observing an enemy's gear.
     * If we see an enemy with diamond gear, their island's chests likely had
     * good loot and have been emptied.
     *
     * @param enemyPlayer the observed enemy
     */
    public void inferEnemyIslandLooted(@Nonnull Player enemyPlayer) {
        // Check enemy equipment quality
        int gearScore = 0;
        if (enemyPlayer.getInventory().getHelmet() != null) {
            gearScore += getArmorTierScore(enemyPlayer.getInventory().getHelmet().getType());
        }
        if (enemyPlayer.getInventory().getChestplate() != null) {
            gearScore += getArmorTierScore(enemyPlayer.getInventory().getChestplate().getType());
        }
        if (enemyPlayer.getInventory().getItemInHand() != null
                && LootPriorityTable.isSword(enemyPlayer.getInventory().getItemInHand().getType())) {
            gearScore += 3;
        }

        // If enemy has decent gear, assume their island's chests are looted
        if (gearScore >= 5) {
            String islandKey = "enemy_island_" + enemyPlayer.getName();
            enemyLootedIslands.add(islandKey);
        }
    }

    /**
     * Returns the record for a chest at the given location.
     *
     * @param location the chest location
     * @return the record, or null if unknown
     */
    @Nullable
    public ChestRecord getRecord(@Nonnull Location location) {
        return records.get(locationKey(location));
    }

    /**
     * Returns whether the bot knows this chest has been looted.
     *
     * @param location the chest location
     * @return true if known to be looted
     */
    public boolean isKnownLooted(@Nonnull Location location) {
        ChestRecord record = records.get(locationKey(location));
        return record != null && record.isLooted;
    }

    /**
     * Returns all unlooted chests from memory, sorted by distance.
     *
     * @return list of unlooted chest records, nearest first
     */
    @Nonnull
    public List<ChestRecord> getUnlootedChests() {
        Location botLoc = bot.getLocation();
        List<ChestRecord> unlooted = new ArrayList<>();
        for (ChestRecord record : records.values()) {
            if (!record.isLooted) {
                unlooted.add(record);
            }
        }
        if (botLoc != null) {
            unlooted.sort(Comparator.comparingDouble(
                    r -> MathUtil.horizontalDistance(botLoc, r.location)));
        }
        return unlooted;
    }

    /**
     * Syncs chest data from the ChestLocator awareness system.
     *
     * @param locator the chest locator
     */
    public void syncFromLocator(@Nonnull ChestLocator locator) {
        for (ChestLocator.ChestInfo info : locator.getAllChests()) {
            String key = locationKey(info.location);
            ChestRecord record = records.computeIfAbsent(key,
                    k -> new ChestRecord(info.location.clone()));
            if (info.isLooted && !record.isLooted) {
                record.isLooted = true;
                record.lootedByEnemy = info.lootedByEnemy;
                record.lootedByBot = info.lootedByBot;
            }
        }
    }

    /** Clears all memory. */
    public void clear() {
        records.clear();
        enemyLootedIslands.clear();
    }

    /** @return total number of remembered chests */
    public int size() { return records.size(); }

    private int getArmorTierScore(@Nonnull Material mat) {
        String name = mat.name();
        if (name.startsWith("DIAMOND")) return 4;
        if (name.startsWith("IRON")) return 3;
        if (name.startsWith("CHAINMAIL")) return 2;
        if (name.startsWith("GOLD")) return 1;
        return 0;
    }

    @Nonnull
    private static String locationKey(@Nonnull Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Inner: ChestRecord
    // ═══════════════════════════════════════════════════════════════

    /** Extended chest data with bot-specific observations. */
    public static class ChestRecord {
        public final Location location;
        public boolean isLooted;
        public boolean lootedByBot;
        public boolean lootedByEnemy;
        public int qualityRating; // 0-10, -1 if unknown
        public long lastVisitTick;
        public final List<ItemStack> itemsFound;

        public ChestRecord(@Nonnull Location location) {
            this.location = location;
            this.isLooted = false;
            this.lootedByBot = false;
            this.lootedByEnemy = false;
            this.qualityRating = -1;
            this.lastVisitTick = 0;
            this.itemsFound = new ArrayList<>();
        }
    }
}
