package org.twightlight.skywarstrainer.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityConflictTable;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
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
        for (String personality : personalities) {
            profile.addPersonality(personality);
        }

        BotSkin skin;
        if (name != null && !name.isEmpty()) {
            skin = BotSkin.withName(plugin, name, difficulty, personalities);
        } else {
            skin = BotSkin.generateRandom(plugin, difficulty, personalities);
        }

        String displayName = skin.getDisplayName();
        if (botsByName.containsKey(displayName.toLowerCase())) {
            displayName = displayName + RandomUtil.nextInt(10, 99);
            skin = new BotSkin(skin.getSkinName(), displayName);
        }

        TrainerBot bot = new TrainerBot(plugin, arena, profile, skin);
        bot.setStaggerOffset(staggerCounter);
        staggerCounter++;


        if (!bot.spawn(location)) {
            plugin.getLogger().warning("Failed to spawn bot: " + displayName);
            return null;
        }

        // In spawnBot() method, after bot.spawn(location) succeeds and before return:

        // Fire BotSpawnEvent
        org.twightlight.skywarstrainer.api.events.BotSpawnEvent spawnEvent =
                new org.twightlight.skywarstrainer.api.events.BotSpawnEvent(bot, location);
        Bukkit.getPluginManager().callEvent(spawnEvent);
        if (spawnEvent.isCancelled()) {
            bot.destroy();
            return null;
        }

        activeBots.put(bot.getBotId(), bot);
        botsByName.put(displayName.toLowerCase(), bot);

        plugin.getLogger().info("Spawned bot: " + displayName
                + " [" + difficulty.name() + "] "
                + (personalities.isEmpty() ? "(no personalities)" : personalities));

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
     * Called once per server tick by the main plugin tick loop.
     *
     * @param startNanos   System.nanoTime() when this tick started
     * @param maxMsPerTick maximum milliseconds allowed for bot processing
     */
    public void tickAll(long startNanos, long maxMsPerTick) {
        if (activeBots.isEmpty()) return;

        long budgetNanos = maxMsPerTick * 1_000_000L;

        cleanupDeadBots();

        for (TrainerBot bot : activeBots.values()) {
            if (System.nanoTime() - startNanos > budgetNanos) {
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[DEBUG] Tick budget exceeded, deferring remaining bots.");
                }
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

            if (bot.isInitialized() && !bot.isAlive()) {
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
     * Returns all bots in a specific arena.
     * This is the key method that GameEventListener and other game integration
     * classes need to find bots participating in a specific SkyWars game.
     *
     * @param arena the arena to search
     * @return list of bots in that arena (may be empty, never null)
     */
    @Nonnull
    public List<TrainerBot> getBotsInArena(@Nonnull Arena<?> arena) {
        return activeBots.values().stream()
                .filter(bot -> !bot.isDestroyed() && bot.getArena().equals(arena))
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