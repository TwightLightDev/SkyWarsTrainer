package org.twightlight.skywarstrainer.bot;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;

import org.bukkit.Location;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.config.DifficultyConfig;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages the lifecycle of all trainer bots: creation, spawning, despawning,
 * tick distribution, and cleanup.
 *
 * <p>BotManager is the single entry point for bot operations. External systems
 * (commands, API, game hooks) call BotManager to spawn and remove bots.
 * Internally, it maintains a registry of all active bots and distributes
 * their tick processing across server ticks to minimize lag.</p>
 *
 * <p>Tick staggering: When multiple bots are active, running all of them on
 * every tick would cause lag spikes. Instead, BotManager assigns each bot a
 * stagger offset. On tick N, only bots whose offset matches (N % groupSize)
 * run their expensive logic. Movement (which must be smooth) still runs
 * every tick for all bots.</p>
 */
public class BotManager {

    private final SkyWarsTrainerPlugin plugin;

    /**
     * All active bots, keyed by their unique bot ID.
     * ConcurrentHashMap because bots may be accessed from async contexts
     * (e.g., map scanning callbacks).
     */
    private final Map<UUID, TrainerBot> activeBots;

    /**
     * Index of bots by display name for quick lookups via commands.
     */
    private final Map<String, TrainerBot> botsByName;

    /**
     * Counter for assigning stagger offsets to new bots.
     * Wraps around to distribute bots evenly across ticks.
     */
    private int staggerCounter;

    /**
     * Creates a new BotManager.
     *
     * @param plugin the owning plugin instance
     */
    public BotManager(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        this.activeBots = new ConcurrentHashMap<>();
        this.botsByName = new ConcurrentHashMap<>();
        this.staggerCounter = 0;
    }

    // ─── Spawning ───────────────────────────────────────────────

    /**
     * Spawns a new trainer bot at the given location with the specified settings.
     *
     * <p>This is the primary bot creation method. It:
     * <ol>
     *   <li>Validates the bot count limit.</li>
     *   <li>Creates a BotProfile with the specified difficulty.</li>
     *   <li>Generates or applies the given skin.</li>
     *   <li>Creates and spawns a TrainerBot.</li>
     *   <li>Registers the bot in all tracking structures.</li>
     * </ol></p>
     *
     * @param location    the spawn location
     * @param difficulty  the difficulty level
     * @param personalities optional personality names (may be empty)
     * @param name        the bot display name, or null for random generation
     * @return the spawned TrainerBot, or null if spawning failed
     */
    @Nullable
    public TrainerBot spawnBot(Arena<?> arena, @Nonnull Location location, @Nonnull Difficulty difficulty,
                               @Nonnull List<String> personalities, @Nullable String name) {
        // Check bot count limit
        int maxBots = plugin.getConfigManager().getMaxBotsPerGame();
        if (activeBots.size() >= maxBots) {
            plugin.getLogger().warning("Cannot spawn bot: maximum bot limit reached (" + maxBots + ").");
            return null;
        }

        // Resolve difficulty profile
        DifficultyProfile difficultyProfile = plugin.getDifficultyConfig().getProfile(difficulty);

        // Create bot profile
        BotProfile profile = new BotProfile(difficulty, difficultyProfile);
        for (String personality : personalities) {
            profile.addPersonality(personality);
        }

        // Create skin
        BotSkin skin;
        if (name != null && !name.isEmpty()) {
            skin = BotSkin.withName(plugin, name);
        } else {
            skin = BotSkin.generateRandom(plugin);
        }

        // Ensure no name collision
        String displayName = skin.getDisplayName();
        if (botsByName.containsKey(displayName.toLowerCase())) {
            // Append random number to make unique
            displayName = displayName + RandomUtil.nextInt(10, 99);
            skin = new BotSkin(skin.getSkinName(), displayName);
        }

        // Create and spawn the bot
        TrainerBot bot = new TrainerBot(plugin, arena, profile, skin);
        bot.setStaggerOffset(staggerCounter % Math.max(1, activeBots.size() + 1));
        staggerCounter++;

        if (!bot.spawn(location)) {
            plugin.getLogger().warning("Failed to spawn bot: " + displayName);
            return null;
        }

        // Register in tracking structures
        activeBots.put(bot.getBotId(), bot);
        botsByName.put(displayName.toLowerCase(), bot);

        // Register with player tracker so the game tracks this bot as a "player"
        if (bot.getLivingEntity() != null) {

        }

        plugin.getLogger().info("Spawned bot: " + displayName
                + " [" + difficulty.name() + "] "
                + (personalities.isEmpty() ? "(no personalities)" : personalities));

        return bot;
    }

    /**
     * Convenience method: spawns a bot with the default difficulty and random personalities.
     *
     * @param location the spawn location
     * @return the spawned bot, or null on failure
     */
    @Nullable
    public TrainerBot spawnBot(Arena<?> arena, @Nonnull Location location) {
        String defaultDiff = plugin.getConfigManager().getDefaultDifficulty();
        Difficulty difficulty = Difficulty.fromString(defaultDiff);
        if (difficulty == null) {
            difficulty = Difficulty.MEDIUM;
        }
        return spawnBot(arena, location, difficulty, Collections.emptyList(), null);
    }

    // ─── Removal ────────────────────────────────────────────────

    /**
     * Removes a specific bot by its instance.
     *
     * @param bot the bot to remove
     * @return true if the bot was found and removed
     */
    public boolean removeBot(@Nonnull TrainerBot bot) {
        TrainerBot removed = activeBots.remove(bot.getBotId());
        if (removed != null) {
            botsByName.remove(removed.getName().toLowerCase());
            if (removed.getLivingEntity() != null) {

            }
            removed.destroy();
            return true;
        }
        return false;
    }

    /**
     * Removes a bot by its display name (case-insensitive).
     *
     * @param name the bot name
     * @return true if found and removed
     */
    public boolean removeBot(@Nonnull String name) {
        TrainerBot bot = botsByName.get(name.toLowerCase());
        if (bot != null) {
            return removeBot(bot);
        }
        return false;
    }

    /**
     * Removes all active bots.
     *
     * @return the number of bots removed
     */
    public int removeAllBots() {
        int count = activeBots.size();
        for (TrainerBot bot : new ArrayList<>(activeBots.values())) {
            removeBot(bot);
        }
        return count;
    }

    // ─── Tick Distribution ──────────────────────────────────────

    /**
     * Ticks all active bots, distributing processing within the given time budget.
     *
     * <p>Called once per server tick by the main plugin tick loop. If staggering
     * is enabled, each bot only runs expensive logic on its assigned tick slot.
     * Movement (Phase 2+) always runs every tick for smoothness.</p>
     *
     * <p>If the time budget is exceeded, remaining bots are deferred to the next tick.</p>
     *
     * @param startNanos     the System.nanoTime() when this tick started
     * @param maxMsPerTick   the maximum milliseconds allowed for bot processing
     */
    public void tickAll(long startNanos, long maxMsPerTick) {
        if (activeBots.isEmpty()) return;

        long budgetNanos = maxMsPerTick * 1_000_000L;

        // Clean up any dead bots
        cleanupDeadBots();

        // Tick each bot
        for (TrainerBot bot : activeBots.values()) {
            // Check time budget
            if (System.nanoTime() - startNanos > budgetNanos) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[DEBUG] Tick budget exceeded, deferring remaining bots.");
                }
                break;
            }

            if (bot.isDestroyed() || !bot.isInitialized()) {
                continue;
            }

            try {
                bot.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error ticking bot " + bot.getName(), e);
            }
        }
    }

    /**
     * Removes bots whose NPC entity has died or is no longer valid.
     */
    private void cleanupDeadBots() {
        Iterator<Map.Entry<UUID, TrainerBot>> iterator = activeBots.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TrainerBot> entry = iterator.next();
            TrainerBot bot = entry.getValue();

            if (bot.isDestroyed()) {
                botsByName.remove(bot.getName().toLowerCase());
                iterator.remove();
                continue;
            }

            // Check if the NPC entity is dead (killed in combat)
            if (bot.isInitialized() && !bot.isAlive()) {
                // The NPC died — record the death and clean up
                bot.getProfile().addDeath();
                botsByName.remove(bot.getName().toLowerCase());
                iterator.remove();
                bot.destroy();

                plugin.getLogger().info("Bot died: " + bot.getName());
            }
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns a bot by its display name (case-insensitive).
     *
     * @param name the bot name
     * @return the bot, or null if not found
     */
    @Nullable
    public TrainerBot getBotByName(@Nonnull String name) {
        return botsByName.get(name.toLowerCase());
    }

    /**
     * Returns a bot by its unique ID.
     *
     * @param id the bot UUID
     * @return the bot, or null if not found
     */
    @Nullable
    public TrainerBot getBotById(@Nonnull UUID id) {
        return activeBots.get(id);
    }

    /**
     * Returns a bot whose NPC entity matches the given entity UUID.
     * Used to identify if a Player entity in events is actually a bot.
     *
     * @param entityUuid the entity UUID
     * @return the bot, or null if the entity is not a bot
     */
    @Nullable
    public TrainerBot getBotByEntityUuid(@Nonnull UUID entityUuid) {
        for (TrainerBot bot : activeBots.values()) {
            if (bot.getLivingEntity() != null
                    && bot.getLivingEntity().getUniqueId().equals(entityUuid)) {
                return bot;
            }
        }
        return null;
    }

    /**
     * Checks if a given entity UUID belongs to a trainer bot.
     *
     * @param entityUuid the entity UUID to check
     * @return true if this entity is a trainer bot
     */
    public boolean isBot(@Nonnull UUID entityUuid) {
        return getBotByEntityUuid(entityUuid) != null;
    }

    /**
     * Returns an unmodifiable list of all active bots.
     *
     * @return all active bots
     */
    @Nonnull
    public List<TrainerBot> getAllBots() {
        return Collections.unmodifiableList(new ArrayList<>(activeBots.values()));
    }

    /**
     * Returns the number of currently active bots.
     *
     * @return active bot count
     */
    public int getActiveBotCount() {
        return activeBots.size();
    }

    /**
     * Returns a list of all active bot names.
     *
     * @return list of bot names
     */
    @Nonnull
    public List<String> getBotNames() {
        return new ArrayList<>(botsByName.keySet());
    }

    /**
     * Fills a game with the specified number of bots at a given difficulty.
     * Each bot is spawned at the given location (typically a spawn point).
     *
     * <p>In practice, each bot should be spawned at a different spawn point.
     * The locations should be provided by the SkyWars game hook (Phase 6).
     * This convenience method spawns all bots at the same location.</p>
     *
     * @param location     the spawn location
     * @param count        number of bots to spawn
     * @param difficulty   the difficulty level
     * @param personalities optional personality names
     * @return the number of bots successfully spawned
     */
    public int fillWithBots(Arena<?> arena, @Nonnull Location location, int count, @Nonnull Difficulty difficulty,
                            @Nonnull List<String> personalities) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            TrainerBot bot = spawnBot(arena, location, difficulty, personalities, null);
            if (bot != null) {
                spawned++;
            }
        }
        plugin.getLogger().info("Filled game with " + spawned + "/" + count + " bots.");
        return spawned;
    }

    /**
     * Generates a random personality list for a bot. In Phase 1, this returns
     * placeholder strings. Full personality generation with conflict resolution
     * is implemented in Phase 6.
     *
     * @param difficulty the difficulty level (unused in Phase 1)
     * @return a list of 1-3 random personality names
     */
    @Nonnull
    public List<String> generateRandomPersonalities(@Nonnull Difficulty difficulty) {
        /*
         * Placeholder: returns random personality names from the full list.
         * Phase 6 implements PersonalityConflictTable validation and re-rolling.
         */
        String[] allPersonalities = {
                "AGGRESSIVE", "PASSIVE", "RUSHER", "CAMPER", "STRATEGIC",
                "COLLECTOR", "BERSERKER", "SNIPER", "TRICKSTER", "CAUTIOUS",
                "CLUTCH_MASTER", "TEAMWORK"
        };

        int count = plugin.getConfigManager().getDefaultPersonalityCount();
        count = Math.max(1, Math.min(3, count));

        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String personality = RandomUtil.randomElement(allPersonalities);
            // Simple duplicate check (full conflict check in Phase 6)
            if (!result.contains(personality)) {
                result.add(personality);
            }
        }

        if (result.isEmpty()) {
            result.add("STRATEGIC");
        }

        return result;
    }
}

