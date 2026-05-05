package org.twightlight.skywarstrainer.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityConflictTable;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Manages the lifecycle of all trainer bots: creation, spawning, despawning,
 * tick distribution, and cleanup.
 *
 * <p>BotManager is the single entry point for bot operations. External systems
 * (commands, API, game hooks) call BotManager to spawn and remove bots.</p>
 */
public class BotManager {

    private final SkyWarsTrainer plugin;

    /** All active bots, keyed by their unique bot ID. */
    private final Map<UUID, TrainerBot> activeBots;

    /** Index of bots by display name for quick lookups via commands. */
    private final Map<String, TrainerBot> botsByName;

    /** Counter for assigning stagger offsets to new bots. */
    private int staggerCounter;

    /**
     * Creates a new BotManager.
     *
     * @param plugin the owning plugin instance
     */
    public BotManager(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
        this.activeBots = new ConcurrentHashMap<>();
        this.botsByName = new ConcurrentHashMap<>();
        this.staggerCounter = 0;
    }

    // ─── Spawning ───────────────────────────────────────────────

    /**
     * Spawns a new trainer bot at the given location with the specified settings.
     *
     * @param arena         the arena this bot belongs to
     * @param location      the spawn location
     * @param difficulty    the difficulty level
     * @param personalities optional personality names (may be empty)
     * @param name          the bot display name, or null for random generation
     * @return the spawned TrainerBot, or null if spawning failed
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull Arena<?> arena, @Nonnull Location location,
                               @Nonnull Difficulty difficulty,
                               @Nonnull List<String> personalities, @Nullable String name) {
        int maxBots = plugin.getConfigManager().getMaxBotsPerGame();
        if (activeBots.size() >= maxBots) {
            plugin.getLogger().warning("Cannot spawn bot: maximum bot limit reached (" + maxBots + ").");
            return null;
        }

        DifficultyProfile difficultyProfile = plugin.getDifficultyConfig().getProfile(difficulty);

        BotProfile profile = new BotProfile(difficulty, difficultyProfile);

        // [FIX 1.5] If no personalities were specified, generate random ones.
        // This makes every bot unique by default while still allowing explicit specification.
        List<String> resolvedPersonalities = personalities;
        if (resolvedPersonalities == null || resolvedPersonalities.isEmpty()) {
            resolvedPersonalities = generateRandomPersonalities();
        }

        for (String personality : resolvedPersonalities) {
            profile.addPersonality(personality);
        }

        BotSkin skin;
        if (name != null && !name.isEmpty()) {
            skin = BotSkin.withName(plugin, name, difficulty, resolvedPersonalities);
        } else {
            skin = BotSkin.generateRandom(plugin, difficulty, resolvedPersonalities);
        }

        String displayName = skin.getDisplayName();
        if (botsByName.containsKey(displayName.toLowerCase())) {
            displayName = displayName + RandomUtil.nextInt(10, 99);
            skin = new BotSkin(skin.getSkinName(), displayName);
        }

        TrainerBot bot = new TrainerBot(plugin, arena, profile, skin);
        bot.setStaggerOffset(staggerCounter);
        staggerCounter++;

        // [FIX 6.7] Reset staggerCounter to prevent negative modulo from int overflow
        if (staggerCounter >= Integer.MAX_VALUE / 2) {
            staggerCounter = 0;
        }

        if (!bot.spawn(location)) {
            plugin.getLogger().warning("Failed to spawn bot: " + displayName);
            return null;
        }

        // Fire BotSpawnEvent
        org.twightlight.skywarstrainer.api.events.BotSpawnEvent spawnEvent =
                new org.twightlight.skywarstrainer.api.events.BotSpawnEvent(bot, location);
        Bukkit.getPluginManager().callEvent(spawnEvent);
        if (spawnEvent.isCancelled()) {
            bot.destroy();
            return null;
        }

        SkyWarsTrainer pluginRef = SkyWarsTrainer.getInstance();
        if (pluginRef != null && pluginRef.getApi() != null) {
            org.twightlight.skywarstrainer.api.SkyWarsTrainerAPI api = pluginRef.getApi();
            api.applyPendingRegistrations(bot);
        }

        activeBots.put(bot.getBotId(), bot);
        botsByName.put(displayName.toLowerCase(), bot);

        plugin.getLogger().info("Spawned bot: " + displayName
                + " [" + difficulty.name() + "] "
                + (resolvedPersonalities.isEmpty() ? "(no personalities)" : resolvedPersonalities));

        return bot;
    }

    /**
     * Convenience method: spawns a bot with the default difficulty.
     *
     * @param arena    the arena
     * @param location the spawn location
     * @return the spawned bot, or null on failure
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull Arena<?> arena, @Nonnull Location location) {
        String defaultDiff = plugin.getConfigManager().getDefaultDifficulty();
        Difficulty difficulty = Difficulty.fromString(defaultDiff);
        if (difficulty == null) difficulty = Difficulty.MEDIUM;
        return spawnBot(arena, location, difficulty, Collections.emptyList(), null);
    }

    /**
     * Periodic maintenance: cleans up dead bots. Called by a lightweight
     * scheduler in the main plugin (NOT for ticking bots — that is handled
     * by the Citizens Trait).
     */
    public void maintenance() {
        cleanupDeadBots();
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
     * Ticks all active bots within the given time budget.
     *
     * <p><strong>WARNING:</strong> Do NOT call this method when Citizens Traits are active.
     * The Citizens Trait ({@code SkyWarsTrainerTrait.run()}) already calls {@code bot.tick()}
     * every server tick. Calling this method would double-tick all bots, causing erratic
     * behavior, doubled combat speed, and doubled movement speed.</p>
     *
     * <p>This method exists only for potential future use in non-Citizens tick modes.
     * It is currently dead code and intentionally never scheduled.</p>
     *
     * @param startNanos   System.nanoTime() when this tick started
     * @param maxMsPerTick maximum milliseconds allowed for bot processing
     * @deprecated Citizens Traits handle bot ticking. Do not call this method.
     */
    @Deprecated
    public void tickAll(long startNanos, long maxMsPerTick) {
        if (activeBots.isEmpty()) return;

        long budgetNanos = maxMsPerTick * 1_000_000L;

        cleanupDeadBots();

        for (TrainerBot bot : activeBots.values()) {
            if (System.nanoTime() - startNanos > budgetNanos) {
                DebugLogger.logSystem("Tick budget exceeded, deferring remaining bots.");
                break;
            }

            if (bot.isDestroyed() || !bot.isInitialized()) continue;

            try {
                bot.tick();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error ticking bot " + bot.getName(), e);
            }
        }
    }

    /**
     * Removes bots whose NPC entity has died or is no longer valid.
     *
     * <p>[FIX 3.4] Removed the addDeath() call — the authoritative source for death
     * counting is GameEventListener.onPlayerDeath(). cleanupDeadBots() only handles
     * removing from maps and destroying the NPC.</p>
     *
     * <p>[FIX 6.8] Fire BotDespawnEvent for bots cleaned up this way, since they
     * don't go through removeBot() which calls destroy() (which fires the event).
     * We collect dead bots in a separate list, then delegate to removeBot().</p>
     */
    private void cleanupDeadBots() {
        // [FIX 6.8] Collect dead bots first, then delegate to removeBot() which fires events
        List<TrainerBot> deadBots = new ArrayList<>();

        for (Map.Entry<UUID, TrainerBot> entry : activeBots.entrySet()) {
            TrainerBot bot = entry.getValue();

            if (bot.isDestroyed()) {
                deadBots.add(bot);
                continue;
            }

            if (bot.isInitialized() && !bot.isAlive()) {
                // [FIX 3.4] Do NOT call bot.getProfile().addDeath() here —
                // GameEventListener.onPlayerDeath() is the authoritative source.
                plugin.getLogger().info("Bot died: " + bot.getName());
                deadBots.add(bot);
            }
        }

        for (TrainerBot deadBot : deadBots) {
            // removeBot() handles: activeBots.remove, botsByName.remove, bot.destroy() (fires BotDespawnEvent)
            activeBots.remove(deadBot.getBotId());
            botsByName.remove(deadBot.getName().toLowerCase());
            if (!deadBot.isDestroyed()) {
                deadBot.destroy();
            }
        }
    }

    // ─── Queries ────────────────────────────────────────────────

    /**
     * Returns all bots in a specific arena.
     *
     * @param arena the arena to search
     * @return list of bots in that arena (may be empty, never null)
     */
    @Nonnull
    public List<TrainerBot> getBotsInArena(@Nonnull Arena<?> arena) {
        return activeBots.values().stream()
                .filter(bot -> !bot.isDestroyed() && bot.getArena() != null && bot.getArena().equals(arena))
                .collect(Collectors.toList());
    }

    /**
     * Returns all bots in the arena identified by its server name.
     *
     * @param arenaServerName the arena server name
     * @return list of bots in that arena
     */
    @Nonnull
    public List<TrainerBot> getBotsInArena(@Nonnull String arenaServerName) {
        return activeBots.values().stream()
                .filter(bot -> !bot.isDestroyed()
                        && bot.getArena() != null
                        && bot.getArena().getServerName() != null
                        && bot.getArena().getServerName().equals(arenaServerName))
                .collect(Collectors.toList());
    }

    @Nullable
    public TrainerBot getBotByName(@Nonnull String name) {
        return botsByName.get(name.toLowerCase());
    }

    @Nullable
    public TrainerBot getBotById(@Nonnull UUID id) {
        return activeBots.get(id);
    }

    /**
     * Returns a bot whose NPC entity matches the given entity UUID.
     *
     * @param entityUuid the entity UUID
     * @return the bot, or null if the entity is not a bot
     */
    @Nullable
    public TrainerBot getBotByEntityUUID(@Nonnull UUID entityUuid) {
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
        return getBotByEntityUUID(entityUuid) != null;
    }

    @Nonnull
    public List<TrainerBot> getAllBots() {
        return Collections.unmodifiableList(new ArrayList<>(activeBots.values()));
    }

    public int getActiveBotCount() {
        return activeBots.size();
    }

    @Nonnull
    public List<String> getBotNames() {
        return new ArrayList<>(botsByName.keySet());
    }

    /**
     * Fills a game with the specified number of bots.
     *
     * @param arena        the arena
     * @param location     the spawn location (ideally different per bot)
     * @param count        number of bots to spawn
     * @param difficulty   the difficulty level
     * @param personalities optional personality names
     * @return the number of bots successfully spawned
     */
    public int fillWithBots(@Nonnull Arena<?> arena, @Nonnull Location location, int count,
                            @Nonnull Difficulty difficulty,
                            @Nonnull List<String> personalities) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            TrainerBot bot = spawnBot(arena, location, difficulty, personalities, null);
            if (bot != null) spawned++;
        }
        plugin.getLogger().info("Filled game with " + spawned + "/" + count + " bots.");
        return spawned;
    }

    /**
     * Generates a random personality list for a bot with conflict checking.
     *
     * @return a list of 1-3 non-conflicting random personality names
     */
    @Nonnull
    public List<String> generateRandomPersonalities() {
        String[] allPersonalities = {
                "AGGRESSIVE", "PASSIVE", "RUSHER", "CAMPER", "STRATEGIC",
                "COLLECTOR", "BERSERKER", "SNIPER", "TRICKSTER", "CAUTIOUS",
                "CLUTCH_MASTER", "TEAMWORK"
        };

        int count = plugin.getConfigManager().getDefaultPersonalityCount();
        count = Math.max(1, Math.min(3, count));

        List<String> result = new ArrayList<>();
        int attempts = 0;
        while (result.size() < count && attempts < 50) {
            attempts++;
            String candidate = RandomUtil.randomElement(allPersonalities);
            if (result.contains(candidate)) continue;

            // Check conflicts using PersonalityConflictTable
            boolean conflicts = false;
            for (String existing : result) {
                if (PersonalityConflictTable
                        .conflicts(Personality.valueOf(existing) , Personality.valueOf(candidate))) {
                    conflicts = true;
                    break;
                }
            }
            if (!conflicts) {
                result.add(candidate);
            }
        }

        if (result.isEmpty()) {
            result.add("STRATEGIC");
        }

        return result;
    }
}
