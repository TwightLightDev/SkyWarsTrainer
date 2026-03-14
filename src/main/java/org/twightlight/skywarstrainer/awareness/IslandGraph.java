package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.World;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;

/**
 * Represents the SkyWars map as a graph of islands connected by bridges.
 *
 * <p>In SkyWars, the map consists of:
 * <ul>
 *   <li><strong>Islands</strong>: spawn islands (one per player), a central "mid" island,
 *       and possibly smaller mini-islands.</li>
 *   <li><strong>Bridges</strong>: player-built or pre-built connections between islands.</li>
 * </ul></p>
 *
 * <p>The graph is built by analyzing the map scanner's data. Islands are detected
 * as connected clusters of solid blocks separated by void/air. Bridges are detected
 * when new blocks are placed connecting two island clusters.</p>
 *
 * <p>This graph is used by:
 * <ul>
 *   <li>BridgePathPlanner: to decide where to build bridges</li>
 *   <li>DecisionEngine: to evaluate zone control and island access</li>
 *   <li>HuntingAI: to find paths to enemy islands</li>
 * </ul></p>
 */
public class IslandGraph {

    private final TrainerBot bot;

    /** All detected islands. */
    private final List<Island> islands;

    /** All detected bridges (connections between islands). */
    private final List<Bridge> bridges;

    /** The island the bot is currently on. */
    private Island currentIsland;

    /** The island identified as "mid" (center of map, usually the largest). */
    private Island midIsland;

    /** The island identified as the bot's spawn island. */
    private Island spawnIsland;

    /**
     * Creates a new IslandGraph for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public IslandGraph(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.islands = new ArrayList<>();
        this.bridges = new ArrayList<>();
        this.currentIsland = null;
        this.midIsland = null;
        this.spawnIsland = null;
    }

    /**
     * Rebuilds the island graph from the map scanner's data. This is an
     * expensive operation and should be called infrequently (every 100+ ticks
     * or when significant map changes are detected).
     *
     * @param scanner the map scanner with cached block data
     */
    public void rebuild(@Nonnull MapScanner scanner) {
        Location botLoc = bot.getLocation();
        if (botLoc == null || botLoc.getWorld() == null) return;

        islands.clear();
        bridges.clear();

        // Use the scanner's cached data to identify platform clusters
        // Simplified approach: flood-fill from known solid positions
        World world = botLoc.getWorld();
        int centerX = botLoc.getBlockX();
        int centerZ = botLoc.getBlockZ();
        int radius = bot.getDifficultyProfile().getAwarenessRadius();

        Set<Long> visited = new HashSet<>();
        int baseY = botLoc.getBlockY();

        // Scan for platform clusters at the bot's Y level (±5 blocks)
        for (int x = centerX - radius; x <= centerX + radius; x += 3) {
            for (int z = centerZ - radius; z <= centerZ + radius; z += 3) {
                for (int y = baseY - 5; y <= baseY + 5; y++) {
                    long key = encodePos(x, y, z);
                    if (visited.contains(key)) continue;

                    MapScanner.BlockCategory cat = scanner.getBlockAt(x, y, z);
                    if (cat == MapScanner.BlockCategory.SOLID || cat == MapScanner.BlockCategory.SPECIAL_SOLID) {
                        // Found an unvisited solid block — flood-fill to find the island
                        Island island = floodFillIsland(scanner, x, y, z, visited, radius);
                        if (island != null && island.blockCount >= 4) {
                            islands.add(island);
                        }
                    }
                }
            }
        }

        // Identify mid island (largest island near map center) and spawn island (closest to bot)
        identifySpecialIslands(botLoc);

        // Detect bridges between islands
        detectBridges(scanner);

        // Update current island
        updateCurrentIsland(botLoc);
    }

    /**
     * Flood-fills from a starting solid block to identify all connected solid blocks
     * forming an island. Uses breadth-first search limited by the awareness radius.
     *
     * @param scanner the map scanner
     * @param startX  starting block X
     * @param startY  starting block Y
     * @param startZ  starting block Z
     * @param visited global visited set (shared across all flood-fills)
     * @param maxRadius maximum search radius
     * @return the discovered island, or null if too small
     */
    @Nullable
    private Island floodFillIsland(@Nonnull MapScanner scanner, int startX, int startY, int startZ,
                                   @Nonnull Set<Long> visited, int maxRadius) {
        Queue<long[]> queue = new LinkedList<>();
        queue.add(new long[]{startX, startY, startZ});

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int blockCount = 0;
        double sumX = 0, sumY = 0, sumZ = 0;

        int maxBlocks = 2000; // Prevent runaway for huge structures

        while (!queue.isEmpty() && blockCount < maxBlocks) {
            long[] pos = queue.poll();
            int x = (int) pos[0], y = (int) pos[1], z = (int) pos[2];
            long key = encodePos(x, y, z);

            if (visited.contains(key)) continue;
            visited.add(key);

            MapScanner.BlockCategory cat = scanner.getBlockAt(x, y, z);
            if (cat != MapScanner.BlockCategory.SOLID && cat != MapScanner.BlockCategory.SPECIAL_SOLID) {
                continue;
            }

            blockCount++;
            sumX += x;
            sumY += y;
            sumZ += z;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);

            // Expand to adjacent blocks (6-connected: NSEW + up/down)
            int[][] neighbors = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
            for (int[] n : neighbors) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                long nKey = encodePos(nx, ny, nz);
                if (!visited.contains(nKey)) {
                    queue.add(new long[]{nx, ny, nz});
                }
            }
        }

        if (blockCount < 4) return null;

        Location center = new Location(
                bot.getLocation().getWorld(),
                sumX / blockCount, sumY / blockCount, sumZ / blockCount);

        Island island = new Island(islands.size(), center, blockCount);
        island.minX = minX;
        island.maxX = maxX;
        island.minZ = minZ;
        island.maxZ = maxZ;
        return island;
    }

    /**
     * Identifies the mid island and spawn island from the detected islands.
     *
     * @param botLoc the bot's current location
     */
    private void identifySpecialIslands(@Nonnull Location botLoc) {
        if (islands.isEmpty()) return;

        // Spawn island: closest to bot's location
        double nearestDist = Double.MAX_VALUE;
        for (Island island : islands) {
            double dist = MathUtil.horizontalDistance(botLoc, island.center);
            if (dist < nearestDist) {
                nearestDist = dist;
                spawnIsland = island;
            }
        }

        // Mid island: largest island (or closest to map center)
        // Heuristic: the largest island that isn't the spawn island
        int maxBlocks = 0;
        for (Island island : islands) {
            if (island != spawnIsland && island.blockCount > maxBlocks) {
                maxBlocks = island.blockCount;
                midIsland = island;
            }
        }

        // If only one island found, it's both spawn and mid
        if (midIsland == null) {
            midIsland = spawnIsland;
        }
    }

    private List<Location> findBridgeBlocks(MapScanner scanner, Island a, Island b) {
        List<Location> blocks = new ArrayList<>();

        double dx = b.center.getX() - a.center.getX();
        double dz = b.center.getZ() - a.center.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        dx /= distance;
        dz /= distance;

        int y = (int)((a.center.getY() + b.center.getY()) / 2.0);

        for (double d = 0; d < distance; d += 1.0) {
            int x = (int)(a.center.getX() + dx * d);
            int z = (int)(a.center.getZ() + dz * d);

            for (int dy = -1; dy <= 1; dy++) {
                if (scanner.getBlockAt(x, y + dy, z) == MapScanner.BlockCategory.SOLID) {
                    blocks.add(new Location(bot.getLocation().getWorld(), x, y + dy, z));
                    break;
                }
            }
        }

        return blocks.size() > 3 ? blocks : null;
    }

    /**
     * Detects bridges between islands. A bridge is a narrow strip of blocks
     * connecting two island bounding boxes.
     *
     * @param scanner the map scanner
     */
    private void detectBridges(@Nonnull MapScanner scanner) {
        // Simplified bridge detection: check if any two islands have solid blocks
        // between their edges (within a narrow corridor)
        for (int i = 0; i < islands.size(); i++) {
            for (int j = i + 1; j < islands.size(); j++) {
                Island a = islands.get(i);
                Island b = islands.get(j);
                double dist = MathUtil.horizontalDistance(a.center, b.center);

                // Only check islands within reasonable bridging distance
                if (dist > 80) continue;

                // Check if there are solid blocks along the line between island centers
                if (hasBridgeBetween(scanner, a, b)) {
                    List<Location> blocks = findBridgeBlocks(scanner, a, b);

                    if (blocks != null) {
                        bridges.add(new Bridge(a, b, blocks));
                    }
                    bridges.add(new Bridge(a, b, blocks));
                }
            }
        }
    }

    /**
     * Checks if there is a continuous path of solid blocks between two islands.
     *
     * @param scanner the map scanner
     * @param a       first island
     * @param b       second island
     * @return true if a bridge-like structure connects the two
     */
    private boolean hasBridgeBetween(@Nonnull MapScanner scanner, @Nonnull Island a, @Nonnull Island b) {
        // Trace a line from center of A to center of B
        double dx = b.center.getX() - a.center.getX();
        double dz = b.center.getZ() - a.center.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1) return false;

        dx /= distance;
        dz /= distance;

        int y = (int) ((a.center.getY() + b.center.getY()) / 2.0);
        int solidCount = 0;
        int totalChecks = 0;

        for (double d = 0; d < distance; d += 1.0) {
            int x = (int) (a.center.getX() + dx * d);
            int z = (int) (a.center.getZ() + dz * d);

            // Check a few Y levels
            for (int dy = -1; dy <= 1; dy++) {
                MapScanner.BlockCategory cat = scanner.getBlockAt(x, y + dy, z);
                if (cat == MapScanner.BlockCategory.SOLID || cat == MapScanner.BlockCategory.SPECIAL_SOLID) {
                    solidCount++;
                    break;
                }
            }
            totalChecks++;
        }

        // Bridge exists if >60% of the path has solid blocks
        return totalChecks > 0 && (double) solidCount / totalChecks > 0.6;
    }

    /**
     * Updates the currentIsland field based on the bot's position.
     *
     * @param botLoc the bot's current location
     */
    public void updateCurrentIsland(@Nonnull Location botLoc) {
        double nearestDist = Double.MAX_VALUE;
        currentIsland = null;
        for (Island island : islands) {
            double dist = MathUtil.horizontalDistance(botLoc, island.center);
            if (dist < nearestDist && dist < 30) { // Must be within 30 blocks of island center
                nearestDist = dist;
                currentIsland = island;
            }
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    /** @return all detected islands */
    @Nonnull
    public List<Island> getIslands() { return Collections.unmodifiableList(islands); }

    /** @return all detected bridges */
    @Nonnull
    public List<Bridge> getBridges() { return Collections.unmodifiableList(bridges); }

    /** @return the island the bot is currently on, or null */
    @Nullable
    public Island getCurrentIsland() { return currentIsland; }

    /** @return the mid (center) island, or null if not identified */
    @Nullable
    public Island getMidIsland() { return midIsland; }

    /** @return the bot's spawn island, or null if not identified */
    @Nullable
    public Island getSpawnIsland() { return spawnIsland; }

    /**
     * Returns whether the bot has a bridge connection to the mid island.
     *
     * @return true if a bridge connects the bot's current island to mid
     */
    public boolean hasBridgeToMid() {
        if (currentIsland == null || midIsland == null) return false;
        if (currentIsland == midIsland) return true;
        for (Bridge bridge : bridges) {
            if ((bridge.islandA == currentIsland && bridge.islandB == midIsland)
                    || (bridge.islandA == midIsland && bridge.islandB == currentIsland)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns islands that are connected to the given island via bridges.
     *
     * @param island the island to check
     * @return list of connected islands
     */
    @Nonnull
    public List<Island> getConnectedIslands(@Nonnull Island island) {
        List<Island> connected = new ArrayList<>();
        for (Bridge bridge : bridges) {
            if (bridge.islandA == island) connected.add(bridge.islandB);
            else if (bridge.islandB == island) connected.add(bridge.islandA);
        }
        return connected;
    }

    private static long encodePos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 30) | ((long) (y & 0x1FF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Checks if a given location is on the mid island.
     *
     * @param loc the location to check
     * @return true if the location is on the mid island
     */
    public boolean isOnMidIsland(@Nonnull Location loc) {
        if (midIsland == null) return false;

        double dist = MathUtil.horizontalDistance(loc, midIsland.center);
        return dist < 30; // same heuristic used for island detection
    }

    /**
     * Checks if there is a bridge from the island containing the given location
     * to the mid-island.
     *
     * @param loc the location to evaluate from
     * @return true if a bridge exists to mid
     */
    public boolean hasBridgeToMid(@Nonnull Location loc) {
        if (midIsland == null) return false;

        Island fromIsland = null;
        double nearestDist = Double.MAX_VALUE;

        for (Island island : islands) {
            double dist = MathUtil.horizontalDistance(loc, island.center);
            if (dist < nearestDist && dist < 30) {
                nearestDist = dist;
                fromIsland = island;
            }
        }

        if (fromIsland == null) return false;
        if (fromIsland == midIsland) return true;

        for (Bridge bridge : bridges) {
            if ((bridge.islandA == fromIsland && bridge.islandB == midIsland) ||
                    (bridge.islandB == fromIsland && bridge.islandA == midIsland)) {
                return true;
            }
        }

        return false;
    }

    public List<Bridge> getBridgesFrom(Island island) {
        List<Bridge> result = new ArrayList<>();

        for (Bridge b : bridges) {
            if (b.getFrom() == island || b.getTo() == island) {
                result.add(b);
            }
        }

        return result;
    }

    /**
     * Returns the island that contains or is closest to the given location.
     *
     * <p>The method uses the same heuristic as island detection:
     * if the horizontal distance to an island center is < 30 blocks,
     * the location is considered part of that island.</p>
     *
     * @param loc location to check
     * @return the island at the location, or null if none found
     */
    @Nullable
    public Island getIslandAt(@Nonnull Location loc) {
        double nearestDist = Double.MAX_VALUE;
        Island nearest = null;

        for (Island island : islands) {
            double dist = MathUtil.horizontalDistance(loc, island.center);

            if (dist < nearestDist && dist < 30) {
                nearestDist = dist;
                nearest = island;
            }
        }

        return nearest;
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: Island
    // ═════════════════════════════════════════════════════════════

    /** Represents a single island (platform cluster) in the SkyWars map. */
    public static class Island {
        public final int id;
        public final Location center;
        public final int blockCount;
        public int minX, maxX, minZ, maxZ;

        public Island(int id, @Nonnull Location center, int blockCount) {
            this.id = id;
            this.center = center;
            this.blockCount = blockCount;
        }

        @Override
        public String toString() {
            return "Island{id=" + id + ", blocks=" + blockCount
                    + ", center=(" + (int) center.getX() + "," + (int) center.getZ() + ")}";
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  INNER: Bridge
    // ═════════════════════════════════════════════════════════════

    public static class Bridge {

        private final Island islandA;
        private final Island islandB;
        private final List<Location> blockLocations;

        public Bridge(Island from, Island to, List<Location> blocks) {
            this.islandA = from;
            this.islandB = to;
            this.blockLocations = blocks;
        }

        public Island getFrom() {
            return islandA;
        }

        public Island getTo() {
            return islandB;
        }

        public List<Location> getBlockLocations() {
            return blockLocations;
        }
    }
}
