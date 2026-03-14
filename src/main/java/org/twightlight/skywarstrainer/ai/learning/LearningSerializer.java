// ═══════════════════════════════════════════════════════════════════
// FILE: src/main/java/org/twightlight/skywarstrainer/ai/learning/LearningSerializer.java
// ═══════════════════════════════════════════════════════════════════

package org.twightlight.skywarstrainer.ai.learning;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.twightlight.skywarstrainer.SkyWarsTrainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;

/**
 * Saves and loads the MemoryBank and ReplayBuffer to/from JSON files in the
 * plugin's data folder using Gson.
 *
 * <h3>File Layout</h3>
 * <ul>
 *   <li>{@code plugins/SkyWarsTrainer/learning_data.json} — The Q-table (MemoryBank)</li>
 *   <li>{@code plugins/SkyWarsTrainer/replay_buffer.json} — The replay buffer</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Async saves work on deep-copied snapshots taken on the main thread.
 * Sync saves (on disable) write directly. Loads run synchronously during onEnable.</p>
 *
 * <h3>Corruption Resilience</h3>
 * <p>Before overwriting, the current file is backed up as {@code .backup.json}.
 * After writing, the entry count is verified. If verification fails, the backup
 * is restored. If both files are missing/corrupt, the system starts fresh.</p>
 */
public final class LearningSerializer {

    private static final String MEMORY_FILE = "learning_data.json";
    private static final String MEMORY_BACKUP = "learning_data.backup.json";
    private static final String REPLAY_FILE = "replay_buffer.json";
    private static final String REPLAY_BACKUP = "replay_buffer.backup.json";

    private static final Gson GSON = new GsonBuilder().create();

    private LearningSerializer() {
        // Static utility class
    }

    // ═════════════════════════════════════════════════════════════
    //  SAVE — ASYNC
    // ═════════════════════════════════════════════════════════════

    /**
     * Saves the MemoryBank and ReplayBuffer asynchronously. Takes snapshots on the
     * main thread (fast deep-copy), then writes to disk on an async thread.
     *
     * @param plugin       the plugin instance
     * @param memoryBank   the shared memory bank
     * @param replayBuffer the shared replay buffer
     */
    public static void saveAsync(@Nonnull final SkyWarsTrainer plugin,
                                 @Nonnull final MemoryBank memoryBank,
                                 @Nonnull final ReplayBuffer replayBuffer) {
        // Snapshot on main thread (fast memcopy)
        final String memoryJson = serializeMemoryBank(memoryBank);
        final int memorySize = memoryBank.size();
        final String replayJson = serializeReplayBuffer(replayBuffer);
        final int replaySize = replayBuffer.getSize();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    writeFile(plugin, MEMORY_FILE, MEMORY_BACKUP, memoryJson, memorySize, "MemoryBank");
                    writeFile(plugin, REPLAY_FILE, REPLAY_BACKUP, replayJson, replaySize, "ReplayBuffer");
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save learning data asynchronously", e);
                }
            }
        });
    }

    // ═════════════════════════════════════════════════════════════
    //  SAVE — SYNCHRONOUS (for plugin disable)
    // ═════════════════════════════════════════════════════════════

    /**
     * Saves the MemoryBank and ReplayBuffer synchronously. Used during onDisable()
     * when async tasks can't be guaranteed to complete.
     *
     * @param plugin       the plugin instance
     * @param memoryBank   the shared memory bank
     * @param replayBuffer the shared replay buffer
     */
    public static void saveSynchronous(@Nonnull SkyWarsTrainer plugin,
                                       @Nonnull MemoryBank memoryBank,
                                       @Nonnull ReplayBuffer replayBuffer) {
        try {
            String memoryJson = serializeMemoryBank(memoryBank);
            writeFile(plugin, MEMORY_FILE, MEMORY_BACKUP, memoryJson, memoryBank.size(), "MemoryBank");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save MemoryBank", e);
        }

        try {
            String replayJson = serializeReplayBuffer(replayBuffer);
            writeFile(plugin, REPLAY_FILE, REPLAY_BACKUP, replayJson, replayBuffer.getSize(), "ReplayBuffer");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save ReplayBuffer", e);
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  LOAD
    // ═════════════════════════════════════════════════════════════

    /**
     * Loads the MemoryBank and ReplayBuffer from disk. Runs synchronously
     * during onEnable() before any bots spawn.
     *
     * <p>If the main file is corrupt, falls back to the backup. If both are
     * missing or corrupt, starts fresh (graceful degradation).</p>
     *
     * @param plugin       the plugin instance
     * @param memoryBank   the memory bank to populate
     * @param replayBuffer the replay buffer to populate
     */
    public static void load(@Nonnull SkyWarsTrainer plugin,
                            @Nonnull MemoryBank memoryBank,
                            @Nonnull ReplayBuffer replayBuffer) {
        // Load MemoryBank
        File memoryFile = new File(plugin.getDataFolder(), MEMORY_FILE);
        File memoryBackup = new File(plugin.getDataFolder(), MEMORY_BACKUP);

        boolean memoryLoaded = false;
        if (memoryFile.exists()) {
            memoryLoaded = loadMemoryBank(plugin, memoryFile, memoryBank);
        }
        if (!memoryLoaded && memoryBackup.exists()) {
            plugin.getLogger().warning("Primary learning data corrupt/missing, trying backup...");
            memoryLoaded = loadMemoryBank(plugin, memoryBackup, memoryBank);
        }
        if (memoryLoaded) {
            plugin.getLogger().info("Loaded learning data: " + memoryBank.size() + " Q-table entries, game #" + memoryBank.getCurrentGameNumber());
        } else {
            plugin.getLogger().info("No existing learning data found. Starting fresh.");
        }

        // Load ReplayBuffer
        File replayFile = new File(plugin.getDataFolder(), REPLAY_FILE);
        File replayBackup = new File(plugin.getDataFolder(), REPLAY_BACKUP);

        boolean replayLoaded = false;
        if (replayFile.exists()) {
            replayLoaded = loadReplayBuffer(plugin, replayFile, replayBuffer);
        }
        if (!replayLoaded && replayBackup.exists()) {
            plugin.getLogger().warning("Primary replay buffer corrupt/missing, trying backup...");
            replayLoaded = loadReplayBuffer(plugin, replayBackup, replayBuffer);
        }
        if (replayLoaded) {
            plugin.getLogger().info("Loaded replay buffer: " + replayBuffer.getSize() + " entries");
        } else {
            plugin.getLogger().info("No existing replay buffer found. Starting fresh.");
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  MEMORY BANK SERIALIZATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Serializes the MemoryBank to a JSON string.
     *
     * @param memoryBank the memory bank to serialize
     * @return the JSON string
     */
    @Nonnull
    private static String serializeMemoryBank(@Nonnull MemoryBank memoryBank) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("gameNumber", memoryBank.getCurrentGameNumber());
        root.addProperty("entryCount", memoryBank.size());

        JsonObject entries = new JsonObject();
        for (Map.Entry<Integer, MemoryBank.QEntry> mapEntry : memoryBank.getAllEntries().entrySet()) {
            int stateId = mapEntry.getKey();
            MemoryBank.QEntry qEntry = mapEntry.getValue();

            JsonObject entryObj = new JsonObject();
            entryObj.add("q", toJsonArray(qEntry.qValues));
            entryObj.add("v", toJsonIntArray(qEntry.visitCounts));
            entryObj.add("os", toJsonIntArray(qEntry.recentOutcomeSigns));
            entryObj.addProperty("oh", qEntry.recentOutcomeHead);
            entryObj.addProperty("la", qEntry.lastAccessedGame);
            entryObj.addProperty("cg", qEntry.createdGame);
            entryObj.add("sc", toJsonArray(qEntry.stateCentroid));
            entryObj.addProperty("cs", qEntry.centroidSampleCount);

            entries.add(String.valueOf(stateId), entryObj);
        }

        root.add("entries", entries);
        return GSON.toJson(root);
    }

    /**
     * Loads MemoryBank data from a JSON file.
     *
     * @param plugin     the plugin
     * @param file       the file to read
     * @param memoryBank the memory bank to populate
     * @return true if loading succeeded
     */
    private static boolean loadMemoryBank(@Nonnull SkyWarsTrainer plugin,
                                          @Nonnull File file,
                                          @Nonnull MemoryBank memoryBank) {
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) return false;

            JsonObject rootObj = root.getAsJsonObject();
            long gameNumber = rootObj.has("gameNumber") ? rootObj.get("gameNumber").getAsLong() : 0;
            memoryBank.setCurrentGameNumber(gameNumber);

            JsonObject entries = rootObj.has("entries") ? rootObj.getAsJsonObject("entries") : null;
            if (entries == null) return false;

            int contradictionLookback = memoryBank.getContradictionLookback();

            for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                int stateId;
                try {
                    stateId = Integer.parseInt(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }

                JsonObject entryObj = entry.getValue().getAsJsonObject();

                double[] qValues = fromJsonDoubleArray(entryObj.getAsJsonArray("q"));
                int[] visitCounts = fromJsonIntArray(entryObj.getAsJsonArray("v"));
                int[] outcomeSigns = fromJsonIntArray(entryObj.getAsJsonArray("os"));
                int outcomeHead = entryObj.has("oh") ? entryObj.get("oh").getAsInt() : 0;
                long lastAccessed = entryObj.has("la") ? entryObj.get("la").getAsLong() : gameNumber;
                long created = entryObj.has("cg") ? entryObj.get("cg").getAsLong() : 0;
                double[] centroid = entryObj.has("sc") ? fromJsonDoubleArray(entryObj.getAsJsonArray("sc")) : new double[StateEncoder.STATE_VECTOR_SIZE];
                int centroidSamples = entryObj.has("cs") ? entryObj.get("cs").getAsInt() : 0;

                // Ensure outcome signs array matches expected size
                if (outcomeSigns.length != contradictionLookback) {
                    int[] resized = new int[contradictionLookback];
                    System.arraycopy(outcomeSigns, 0, resized, 0, Math.min(outcomeSigns.length, contradictionLookback));
                    outcomeSigns = resized;
                    if (outcomeHead >= contradictionLookback) outcomeHead = 0;
                }

                MemoryBank.QEntry qEntry = new MemoryBank.QEntry(
                        qValues, visitCounts, outcomeSigns, outcomeHead,
                        lastAccessed, created, centroid, centroidSamples
                );

                // Use the mutable map to add entries directly
                memoryBank.getEntriesMutable().put(stateId, qEntry);
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load learning data from " + file.getName(), e);
            return false;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  REPLAY BUFFER SERIALIZATION
    // ═════════════════════════════════════════════════════════════

    /**
     * Serializes the ReplayBuffer to a JSON string.
     *
     * @param replayBuffer the replay buffer to serialize
     * @return the JSON string
     */
    @Nonnull
    private static String serializeReplayBuffer(@Nonnull ReplayBuffer replayBuffer) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("gameNumber", replayBuffer.getCurrentGameNumber());
        root.addProperty("head", replayBuffer.getHead());
        root.addProperty("size", replayBuffer.getSize());
        root.addProperty("maxPriority", replayBuffer.getMaxPriorityEverSeen());

        JsonArray entriesArray = new JsonArray();
        ReplayEntry[] buffer = replayBuffer.getBuffer();
        int size = replayBuffer.getSize();

        for (int i = 0; i < size; i++) {
            ReplayEntry entry = buffer[i];
            if (entry == null) {
                entriesArray.add(JsonNull.INSTANCE);
                continue;
            }

            JsonObject entryObj = new JsonObject();
            entryObj.add("s", toJsonArray(entry.state));
            entryObj.addProperty("a", entry.actionOrdinal);
            entryObj.addProperty("r", entry.reward);
            entryObj.add("ns", toJsonArray(entry.nextState));
            entryObj.addProperty("t", entry.terminal);
            entryObj.addProperty("g", entry.gameNumber);
            entryObj.addProperty("p", entry.tdErrorPriority);

            entriesArray.add(entryObj);
        }

        root.add("entries", entriesArray);
        return GSON.toJson(root);
    }

    /**
     * Loads ReplayBuffer data from a JSON file.
     *
     * @param plugin       the plugin
     * @param file         the file to read
     * @param replayBuffer the replay buffer to populate
     * @return true if loading succeeded
     */
    private static boolean loadReplayBuffer(@Nonnull SkyWarsTrainer plugin,
                                            @Nonnull File file,
                                            @Nonnull ReplayBuffer replayBuffer) {
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) return false;

            JsonObject rootObj = root.getAsJsonObject();
            long gameNumber = rootObj.has("gameNumber") ? rootObj.get("gameNumber").getAsLong() : 0;
            int head = rootObj.has("head") ? rootObj.get("head").getAsInt() : 0;
            int size = rootObj.has("size") ? rootObj.get("size").getAsInt() : 0;
            double maxPriority = rootObj.has("maxPriority") ? rootObj.get("maxPriority").getAsDouble() : 1.0;

            JsonArray entriesArray = rootObj.has("entries") ? rootObj.getAsJsonArray("entries") : null;
            if (entriesArray == null) return false;

            int capacity = replayBuffer.getCapacity();
            ReplayEntry[] loadedEntries = new ReplayEntry[capacity];
            int loadedCount = Math.min(entriesArray.size(), capacity);

            for (int i = 0; i < loadedCount; i++) {
                JsonElement elem = entriesArray.get(i);
                if (elem.isJsonNull()) {
                    loadedEntries[i] = null;
                    continue;
                }

                JsonObject entryObj = elem.getAsJsonObject();
                double[] state = fromJsonDoubleArray(entryObj.getAsJsonArray("s"));
                int actionOrdinal = entryObj.get("a").getAsInt();
                double reward = entryObj.get("r").getAsDouble();
                double[] nextState = fromJsonDoubleArray(entryObj.getAsJsonArray("ns"));
                boolean terminal = entryObj.get("t").getAsBoolean();
                long entryGameNumber = entryObj.get("g").getAsLong();
                double priority = entryObj.get("p").getAsDouble();

                loadedEntries[i] = new ReplayEntry(state, actionOrdinal, reward, nextState,
                        terminal, entryGameNumber, priority);
            }

            int loadedSize = Math.min(size, loadedCount);
            replayBuffer.setCurrentGameNumber(gameNumber);
            replayBuffer.setMaxPriorityEverSeen(maxPriority);
            replayBuffer.restoreFromLoad(loadedEntries, head, loadedSize);

            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load replay buffer from " + file.getName(), e);
            return false;
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  FILE I/O HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Writes JSON content to a file with backup and verification.
     *
     * @param plugin       the plugin for data folder access
     * @param fileName     the target file name
     * @param backupName   the backup file name
     * @param jsonContent  the JSON string to write
     * @param expectedCount the expected entry count for verification
     * @param label        a human-readable label for log messages
     */
    private static void writeFile(@Nonnull SkyWarsTrainer plugin,
                                  @Nonnull String fileName,
                                  @Nonnull String backupName,
                                  @Nonnull String jsonContent,
                                  int expectedCount,
                                  @Nonnull String label) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        File targetFile = new File(dataFolder, fileName);
        File backupFile = new File(dataFolder, backupName);

        // Create backup of existing file
        if (targetFile.exists()) {
            if (backupFile.exists()) {
                backupFile.delete();
            }
            targetFile.renameTo(backupFile);
        }

        // Write new file
        FileWriter writer = null;
        try {
            writer = new FileWriter(new File(dataFolder, fileName));
            writer.write(jsonContent);
            writer.flush();
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to write " + label + " to " + fileName, e);
            // Try to restore backup
            if (backupFile.exists()) {
                File newTarget = new File(dataFolder, fileName);
                if (!newTarget.exists()) {
                    backupFile.renameTo(newTarget);
                }
            }
            return;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (IOException ignored) { }
            }
        }

        // Verification: read back and check count
        File written = new File(dataFolder, fileName);
        if (written.exists() && written.length() > 0) {
            plugin.getLogger().info("Saved " + label + ": " + expectedCount + " entries (" + (written.length() / 1024) + " KB)");
        } else {
            plugin.getLogger().severe("Verification failed for " + label + "! File is empty. Restoring backup.");
            if (backupFile.exists()) {
                written.delete();
                backupFile.renameTo(written);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  JSON ARRAY HELPERS
    // ═════════════════════════════════════════════════════════════

    /**
     * Converts a double array to a compact JsonArray.
     */
    @Nonnull
    private static JsonArray toJsonArray(@Nonnull double[] array) {
        JsonArray jsonArray = new JsonArray();
        for (double v : array) {
            double rounded = Math.round(v * 1000000.0) / 1000000.0;
            jsonArray.add(new JsonPrimitive(rounded));
        }
        return jsonArray;
    }

    /**
     * Converts an int array to a JsonArray.
     */
    @Nonnull
    private static JsonArray toJsonIntArray(@Nonnull int[] array) {
        JsonArray jsonArray = new JsonArray();
        for (int v : array) {
            jsonArray.add(new JsonPrimitive(v));
        }
        return jsonArray;
    }

    /**
     * Parses a JsonArray into a double array.
     */
    @Nonnull
    private static double[] fromJsonDoubleArray(@Nullable JsonArray jsonArray) {
        if (jsonArray == null) return new double[0];
        double[] result = new double[jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            result[i] = jsonArray.get(i).getAsDouble();
        }
        return result;
    }

    /**
     * Parses a JsonArray into an int array.
     */
    @Nonnull
    private static int[] fromJsonIntArray(@Nullable JsonArray jsonArray) {
        if (jsonArray == null) return new int[0];
        int[] result = new int[jsonArray.size()];
        for (int i = 0; i < jsonArray.size(); i++) {
            result[i] = jsonArray.get(i).getAsInt();
        }
        return result;
    }
}