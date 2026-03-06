package org.twightlight.skywarstrainer.config;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.util.*;

/**
 * Loads and provides map-specific configuration for SkyWars arenas.
 *
 * <p>Map config allows admins to define per-map settings such as:
 * <ul>
 *   <li>Mid island center coordinates</li>
 *   <li>Spawn point locations</li>
 *   <li>Preferred bot strategies for the map</li>
 *   <li>Custom bridge paths</li>
 * </ul></p>
 *
 * <p>Config file: {@code maps.yml} (optional — auto-detection works without it).</p>
 */
public class MapConfig {

    private final SkyWarsTrainerPlugin plugin;

    /** Map configurations keyed by map name (lowercase). */
    private final Map<String, MapData> maps;

    public MapConfig(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        this.maps = new HashMap<>();
    }

    /**
     * Loads map configurations from maps.yml.
     */
    public void load() {
        maps.clear();

        File file = new File(plugin.getDataFolder(), "maps.yml");
        if (!file.exists()) {
            // Maps config is optional — create a template
            plugin.saveResource("maps.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection mapsSection = config.getConfigurationSection("maps");
        if (mapsSection == null) {
            plugin.getLogger().info("No maps configured in maps.yml — using auto-detection.");
            return;
        }

        for (String mapName : mapsSection.getKeys(false)) {
            ConfigurationSection mapSection = mapsSection.getConfigurationSection(mapName);
            if (mapSection == null) continue;

            MapData data = new MapData(mapName);

            // Mid island center
            if (mapSection.contains("mid-center")) {
                double midX = mapSection.getDouble("mid-center.x", 0);
                double midY = mapSection.getDouble("mid-center.y", 64);
                double midZ = mapSection.getDouble("mid-center.z", 0);
                data.midCenter = new double[]{midX, midY, midZ};
            }

            // Preferred difficulty
            data.preferredDifficulty = mapSection.getString("preferred-difficulty", null);

            // Max bridge distance
            data.maxBridgeDistance = mapSection.getInt("max-bridge-distance", 50);

            maps.put(mapName.toLowerCase(), data);
        }

        plugin.getLogger().info("Loaded " + maps.size() + " map configurations.");
    }

    /**
     * Returns the map data for a given map name, or null if not configured.
     *
     * @param mapName the map name (case-insensitive)
     * @return the map data, or null
     */
    @Nullable
    public MapData getMapData(@Nonnull String mapName) {
        return maps.get(mapName.toLowerCase());
    }

    /**
     * Returns true if a map configuration exists.
     *
     * @param mapName the map name
     * @return true if configured
     */
    public boolean hasMapData(@Nonnull String mapName) {
        return maps.containsKey(mapName.toLowerCase());
    }

    /**
     * Data class holding per-map configuration.
     */
    public static class MapData {
        public final String name;
        public double[] midCenter;         // [x, y, z] or null for auto-detect
        public String preferredDifficulty; // null for default
        public int maxBridgeDistance;       // max blocks to bridge

        public MapData(@Nonnull String name) {
            this.name = name;
            this.midCenter = null;
            this.preferredDifficulty = null;
            this.maxBridgeDistance = 50;
        }
    }
}
