package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.MathUtil;
import org.twightlight.skywarstrainer.util.TickTimer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Periodically scans the terrain around the bot to build a cached 3D grid
 * of block types. This cache is used by all awareness subsystems to avoid
 * expensive per-tick block lookups.
 *
 * <p>The scanner operates incrementally: it does not rescan the entire radius
 * every cycle. Instead, it scans in concentric rings outward, updating the
 * nearest blocks first (most tactically important) and deferring far blocks.</p>
 *
 * <p>Scanned data includes: solid blocks, air, liquids (water/lava), chests,
 * enchanting tables, crafting tables, void edges, and platforms. This data
 * feeds into IslandGraph, ChestLocator, LavaDetector, and VoidDetector.</p>
 *
 * <p>Scan frequency is configured via {@code general.map-scan-interval} in
 * config.yml (default: every 50 ticks = 2.5 seconds).</p>
 */
public class MapScanner {

    private final TrainerBot bot;
    private final TickTimer scanTimer;

    /**
     * Cached block data. Maps block position key (encoded long) to block type category.
     * We use a category enum rather than storing Material directly to save memory.
     */
    private final Map<Long, BlockCategory> blockCache;

    /** Locations of discovered special blocks (chests, enchanting tables, etc.). */
    private final List<Location> chestLocations;
    private final List<Location> enchantingTableLocations;
    private final List<Location> craftingTableLocations;

    /** The center of the last scan. If the bot has moved significantly, a rescan is triggered. */
    private Location lastScanCenter;

    /** Current scan ring index for incremental scanning. */
    private int currentScanRing;

    /** Maximum number of blocks to scan per tick to stay within budget. */
    private static final int MAX_BLOCKS_PER_TICK = 500;

    /**
     * Creates a new MapScanner for the given bot.
     *
     * @param bot          the owning trainer bot
     * @param scanInterval the number of ticks between full scan cycles
     */
    public MapScanner(@Nonnull TrainerBot bot, int scanInterval) {
        this.bot = bot;
        this.scanTimer = new TickTimer(scanInterval, scanInterval / 5);
        this.blockCache = new HashMap<>();
        this.chestLocations = new ArrayList<>();
        this.enchantingTableLocations = new ArrayList<>();
        this.craftingTableLocations = new ArrayList<>();
        this.lastScanCenter = null;
        this.currentScanRing = 0;
    }

    /**
     * Tick method. Checks if a scan should be performed this tick.
     * The actual scanning is done incrementally across multiple ticks.
     */
    public void tick() {
        if (scanTimer.tick()) {
            startNewScan();
        }

        // Process incremental scan blocks
        processIncrementalScan();
    }

    /**
     * Forces a full rescan on the next tick. Used when the bot is first
     * spawned or teleported to a new location.
     */
    public void forceRescan() {
        lastScanCenter = null;
        currentScanRing = 0;
        blockCache.clear();
        chestLocations.clear();
        enchantingTableLocations.clear();
        craftingTableLocations.clear();
        scanTimer.forceNext();
    }

    /**
     * Initiates a new scan cycle. Clears the scan ring counter so the
     * incremental scanner starts from the center outward.
     */
    private void startNewScan() {
        Location botLoc = bot.getLocation();
        if (botLoc == null) return;

        // Check if the bot has moved significantly since last scan
        if (lastScanCenter != null && botLoc.getWorld().equals(lastScanCenter.getWorld())) {
            double movedDistance = MathUtil.horizontalDistance(botLoc, lastScanCenter);
            if (movedDistance < 5.0) {
                // Haven't moved much — only update far rings
                currentScanRing = Math.max(0, currentScanRing - 2);
                return;
            }
        }



        // Significant movement or first scan — full rescan
        lastScanCenter = botLoc.clone();
        currentScanRing = 0;
        chestLocations.clear();
        enchantingTableLocations.clear();
        craftingTableLocations.clear();
    }

    /**
     * Processes one ring of the incremental scan per call. Scans blocks
     * in a square ring at the current ring distance, working outward.
     */
    private void processIncrementalScan() {
        if (lastScanCenter == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();
        int maxRadius = diff.getAwarenessRadius();

        if (currentScanRing > maxRadius) return; // Scan complete

        World world = lastScanCenter.getWorld();
        if (world == null) return;

        int centerX = lastScanCenter.getBlockX();
        int centerY = lastScanCenter.getBlockY();
        int centerZ = lastScanCenter.getBlockZ();
        int ring = currentScanRing;

        int blocksScanned = 0;

        // Scan the perimeter of the current ring at multiple Y levels
        int yMin = Math.max(0, centerY - 10);
        int yMax = Math.min(255, centerY + 15);

        for (int y = yMin; y <= yMax && blocksScanned < MAX_BLOCKS_PER_TICK; y++) {
            for (int x = centerX - ring; x <= centerX + ring && blocksScanned < MAX_BLOCKS_PER_TICK; x++) {
                for (int z = centerZ - ring; z <= centerZ + ring; z++) {
                    // Only scan the perimeter of the ring (not interior — already scanned)
                    if (Math.abs(x - centerX) != ring && Math.abs(z - centerZ) != ring) {
                        continue;
                    }

                    if (blocksScanned >= MAX_BLOCKS_PER_TICK) break;

                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    BlockCategory category = categorizeBlock(type);

                    long key = encodePosition(x, y, z);
                    blockCache.put(key, category);

                    // Track special blocks
                    if (type == Material.CHEST || type == Material.TRAPPED_CHEST) {
                        Location loc = block.getLocation();
                        if (!containsLocation(chestLocations, loc)) {
                            chestLocations.add(loc);
                        }
                    } else if (type == Material.ENCHANTMENT_TABLE) {
                        Location loc = block.getLocation();
                        if (!containsLocation(enchantingTableLocations, loc)) {
                            enchantingTableLocations.add(loc);
                        }
                    } else if (type == Material.WORKBENCH) {
                        Location loc = block.getLocation();
                        if (!containsLocation(craftingTableLocations, loc)) {
                            craftingTableLocations.add(loc);
                        }
                    }

                    blocksScanned++;
                }
            }
        }

        currentScanRing++;
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns the cached block category at the given world position.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return the block category, or null if not yet scanned
     */
    @Nullable
    public BlockCategory getBlockAt(int x, int y, int z) {
        return blockCache.get(encodePosition(x, y, z));
    }

    /**
     * Returns the cached block category at the given location.
     *
     * @param loc the location to query
     * @return the block category, or null if not yet scanned
     */
    @Nullable
    public BlockCategory getBlockAt(@Nonnull Location loc) {
        return getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    /**
     * Checks if a block at the given position is solid (based on cache).
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return true if the cached block is solid, false if air/liquid/unknown
     */
    public boolean isSolid(int x, int y, int z) {
        BlockCategory cat = getBlockAt(x, y, z);
        return cat == BlockCategory.SOLID || cat == BlockCategory.SPECIAL_SOLID;
    }

    /** @return all discovered chest locations */
    @Nonnull
    public List<Location> getChestLocations() {
        return chestLocations;
    }

    /** @return all discovered enchanting table locations */
    @Nonnull
    public List<Location> getEnchantingTableLocations() {
        return enchantingTableLocations;
    }

    /** @return all discovered crafting table locations */
    @Nonnull
    public List<Location> getCraftingTableLocations() {
        return craftingTableLocations;
    }

    /** @return the number of cached block entries */
    public int getCacheSize() {
        return blockCache.size();
    }

    /** @return the current scan progress as a ring index */
    public int getCurrentScanRing() {
        return currentScanRing;
    }

    // ─── Internals ──────────────────────────────────────────────

    /**
     * Categorizes a Material into a simplified BlockCategory for memory-efficient
     * caching.
     *
     * @param type the material type
     * @return the block category
     */
    @Nonnull
    private static BlockCategory categorizeBlock(@Nonnull Material type) {
        if (type == Material.AIR) return BlockCategory.AIR;
        if (type == Material.WATER || type == Material.STATIONARY_WATER) return BlockCategory.WATER;
        if (type == Material.LAVA || type == Material.STATIONARY_LAVA) return BlockCategory.LAVA;
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST
                || type == Material.ENCHANTMENT_TABLE || type == Material.WORKBENCH) {
            return BlockCategory.SPECIAL_SOLID;
        }
        if (type.isSolid()) return BlockCategory.SOLID;
        return BlockCategory.AIR; // Non-solid, non-liquid blocks treated as air
    }

    /**
     * Encodes a block position into a single long for use as a hash map key.
     * Packs X (21 bits), Y (9 bits), Z (21 bits) using bit shifting.
     *
     * @param x block X
     * @param y block Y
     * @param z block Z
     * @return the encoded position key
     */
    private static long encodePosition(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 30) | ((long) (y & 0x1FF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Checks if a location list already contains a location at the same block coordinates.
     *
     * @param list the list to check
     * @param loc  the location to find
     * @return true if the list contains a location at the same block position
     */
    private static boolean containsLocation(@Nonnull List<Location> list, @Nonnull Location loc) {
        for (Location existing : list) {
            if (existing.getBlockX() == loc.getBlockX()
                    && existing.getBlockY() == loc.getBlockY()
                    && existing.getBlockZ() == loc.getBlockZ()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public Location getNearestEnchantingTable(@Nonnull Location from) {

        return new ArrayList<>(enchantingTableLocations).stream().min(Comparator.comparing(from::distance)).get();
    }

    /**
     * Simplified block category for memory-efficient caching.
     * Reduces hundreds of Material types to 5 categories.
     */
    public enum BlockCategory {
        /** Air or non-solid, non-liquid block. */
        AIR,
        /** Solid block (stone, wood, dirt, etc.). */
        SOLID,
        /** Water (source or flowing). */
        WATER,
        /** Lava (source or flowing). */
        LAVA,
        /** Special solid block (chest, enchanting table, crafting table). */
        SPECIAL_SOLID
    }
}

