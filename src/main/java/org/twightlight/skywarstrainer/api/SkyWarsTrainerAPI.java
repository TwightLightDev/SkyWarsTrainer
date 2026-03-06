package org.twightlight.skywarstrainer.api;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.decision.UtilityScorer;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.bridging.strategies.BridgeStrategy;
import org.twightlight.skywarstrainer.combat.strategies.CombatStrategy;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.loot.strategies.LootStrategy;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Public API for the SkyWarsTrainer plugin. External plugins can use this
 * to programmatically spawn, remove, query bots, and register custom
 * strategies, considerations, and personalities.
 *
 * <p>Access via {@code SkyWarsTrainerAPI.getInstance()}.</p>
 *
 * <p>Example usage:
 * <pre>
 * SkyWarsTrainerAPI api = SkyWarsTrainerAPI.getInstance();
 * if (api != null) {
 *     TrainerBot bot = api.spawnBot(arena, location, Difficulty.HARD, personalityProfile);
 *     // Register custom combat strategy
 *     api.registerCustomCombatStrategy(new MyCustomStrategy());
 * }
 * </pre></p>
 */
public class SkyWarsTrainerAPI {

    private static SkyWarsTrainerAPI instance;

    private final SkyWarsTrainerPlugin plugin;

    /**
     * Creates a new API instance. Should only be called by the plugin.
     *
     * @param plugin the owning plugin
     */
    public SkyWarsTrainerAPI(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    /**
     * Returns the singleton API instance, or null if the plugin is not loaded.
     *
     * @return the API instance
     */
    @Nullable
    public static SkyWarsTrainerAPI getInstance() {
        return instance;
    }

    /**
     * Clears the singleton instance. Called on plugin disable.
     */
    public static void clearInstance() {
        instance = null;
    }

    // ─── Bot Lifecycle ──────────────────────────────────────────

    /**
     * Spawns a bot in a specific arena at the given location.
     *
     * @param arena      the LostSkyWars arena
     * @param location   the spawn location
     * @param difficulty the difficulty level
     * @param profile    the personality profile
     * @return the spawned bot, or null if spawning failed
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull org.twightlight.skywars.arena.Arena<?> arena,
                               @Nonnull Location location,
                               @Nonnull Difficulty difficulty,
                               @Nonnull PersonalityProfile profile) {
        return plugin.getBotManager().spawnBot(
                arena, location, difficulty, profile.toNameList(), null);
    }

    /**
     * Spawns a bot with a specific difficulty profile and personality profile.
     * This overload allows specifying a custom name.
     *
     * @param arena      the arena
     * @param location   the spawn location
     * @param difficulty the difficulty level
     * @param profile    the personality profile
     * @param name       the custom bot name, or null for random
     * @return the spawned bot, or null if spawning failed
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull org.twightlight.skywars.arena.Arena<?> arena,
                               @Nonnull Location location,
                               @Nonnull Difficulty difficulty,
                               @Nonnull PersonalityProfile profile,
                               @Nullable String name) {
        return plugin.getBotManager().spawnBot(
                arena, location, difficulty, profile.toNameList(), name);
    }

    /**
     * Removes a bot.
     *
     * @param bot the bot to remove
     */
    public void removeBot(@Nonnull TrainerBot bot) {
        plugin.getBotManager().removeBot(bot);
    }

    /**
     * Returns a bot by its display name.
     *
     * @param name the bot name (case-insensitive)
     * @return the bot, or null
     */
    @Nullable
    public TrainerBot getBotByName(@Nonnull String name) {
        return plugin.getBotManager().getBotByName(name);
    }

    /**
     * Returns all active bots.
     *
     * @return unmodifiable list of all bots
     */
    @Nonnull
    public List<TrainerBot> getAllBots() {
        return plugin.getBotManager().getAllBots();
    }

    /**
     * Returns the number of active bots.
     *
     * @return active bot count
     */
    public int getBotCount() {
        return plugin.getBotManager().getActiveBotCount();
    }

    /**
     * Checks if an entity UUID belongs to a trainer bot.
     *
     * @param entityUuid the entity UUID
     * @return true if it's a bot
     */
    public boolean isBot(@Nonnull UUID entityUuid) {
        return plugin.getBotManager().isBot(entityUuid);
    }

    // ─── Custom Strategy Registration ───────────────────────────

    /**
     * Registers a custom combat strategy that will be available to all bots.
     * The strategy will be evaluated alongside built-in strategies during combat.
     *
     * @param strategy the custom combat strategy to register
     */
    public void registerCustomCombatStrategy(@Nonnull CombatStrategy strategy) {
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getCombatEngine() != null) {
                bot.getCombatEngine().getStrategies().add(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom combat strategy: " + strategy.getName());
    }

    /**
     * Registers a custom bridge strategy that will be available to all bots.
     * The strategy will be considered during bridge type selection.
     *
     * @param strategy the custom bridge strategy to register
     */
    public void registerCustomBridgeStrategy(@Nonnull BridgeStrategy strategy) {
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getBridgeEngine() != null) {
                bot.getBridgeEngine().registerStrategy(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom bridge strategy: " + strategy.getName());
    }

    /**
     * Registers a custom loot strategy that will be available to all bots.
     * The strategy will be considered during loot strategy selection.
     *
     * @param strategy the custom loot strategy to register
     */
    public void registerCustomLootStrategy(@Nonnull LootStrategy strategy) {
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getLootEngine() != null) {
                bot.getLootEngine().registerStrategy(strategy);
            }
        }
        plugin.getLogger().info("[API] Registered custom loot strategy: " + strategy.getName());
    }

    /**
     * Registers a custom utility consideration that will be used in decision
     * making for all bots. Custom considerations are evaluated alongside
     * built-in ones during each utility evaluation cycle.
     *
     * @param consideration the custom consideration to register
     */
    public void registerCustomConsideration(@Nonnull UtilityScorer consideration) {
        for (TrainerBot bot : plugin.getBotManager().getAllBots()) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().registerCustomConsideration(consideration);
            }
        }
        plugin.getLogger().info("[API] Registered custom consideration: " + consideration.getName());
    }

    /**
     * Returns the plugin instance. Useful for external plugins that need
     * to access configuration or other plugin services.
     *
     * @return the plugin instance
     */
    @Nonnull
    public SkyWarsTrainerPlugin getPlugin() {
        return plugin;
    }
}
