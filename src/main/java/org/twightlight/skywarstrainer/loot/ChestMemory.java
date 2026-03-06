package org.twightlight.skywarstrainer.loot;

import org.bukkit.Location;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-bot memory of chest locations and their looted state.
 *
 * <p>Tracks which chests the bot has discovered, whether they've been looted,
 * and the quality of items found. This enables intelligent loot routing —
 * the bot can skip chests it already looted and prioritize unvisited ones.</p>
 */
public class ChestMemory {

    /** Chest records keyed by block position encoded as a long. */
    private final Map<Long, ChestRecord> records;

    public ChestMemory() {
        this.records = new HashMap<>();
    }

    /**
     * Records a chest at the given location.
     *
     * @param location the chest block location
     */
    public void recordChest(@Nonnull Location location) {
        long key = encodeLocation(location);
        if (!records.containsKey(key)) {
            records.put(key, new ChestRecord(location.clone()));
        }
    }

    /**
     * Marks a chest as looted by this bot.
     *
     * @param location the chest location
     * @param quality  the item quality rating (0-10)
     */
    public void markLooted(@Nonnull Location location, double quality) {
        long key = encodeLocation(location);
        ChestRecord record = records.get(key);
        if (record == null) {
            record = new ChestRecord(location.clone());
            records.put(key, record);
        }
        record.looted = true;
        record.itemQuality = quality;
    }

    /**
     * Marks a chest as looted by an enemy (observed).
     *
     * @param location the chest location
     */
    public void markLootedByEnemy(@Nonnull Location location) {
        long key = encodeLocation(location);
        ChestRecord record = records.get(key);
        if (record == null) {
            record = new ChestRecord(location.clone());
            records.put(key, record);
        }
        record.lootedByEnemy = true;
    }

    /**
     * Returns the chest record for a location, or null if unknown.
     *
     * @param location the chest location
     * @return the record, or null
     */
    @Nullable
    public ChestRecord getRecord(@Nonnull Location location) {
        return records.get(encodeLocation(location));
    }

    /**
     * Returns true if the chest at the given location has been looted.
     *
     * @param location the chest location
     * @return true if looted by this bot
     */
    public boolean isLooted(@Nonnull Location location) {
        ChestRecord record = records.get(encodeLocation(location));
        return record != null && record.looted;
    }

    /**
     * Returns the number of unlooted chests in memory.
     *
     * @return unlooted chest count
     */
    public int getUnlootedCount() {
        int count = 0;
        for (ChestRecord r : records.values()) {
            if (!r.looted && !r.lootedByEnemy) count++;
        }
        return count;
    }

    /** Clears all memory. */
    public void clear() {
        records.clear();
    }

    private static long encodeLocation(@Nonnull Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * Record of a single chest's state in memory.
     */
    public static class ChestRecord {
        /** The chest block position. */
        public final Location position;
        /** Whether this bot has looted this chest. */
        public boolean looted;
        /** Whether an enemy was observed looting this chest. */
        public boolean lootedByEnemy;
        /** Quality rating of items found (0-10), or -1 if unknown. */
        public double itemQuality;

        public ChestRecord(@Nonnull Location position) {
            this.position = position;
            this.looted = false;
            this.lootedByEnemy = false;
            this.itemQuality = -1;
        }
    }
}
