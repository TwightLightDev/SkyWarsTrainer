package org.twightlight.skywarstrainer.api;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Public API for the SkyWarsTrainer plugin. External plugins can use this
 * to programmatically spawn, remove, and query bots.
 *
 * <p>Access via {@code SkyWarsTrainerAPI.getInstance()}.</p>
 *
 * <p>Example usage:
 * <pre>
 * SkyWarsTrainerAPI api = SkyWarsTrainerAPI.getInstance();
 * if (api != null) {
 *     TrainerBot bot = api.spawnBot(location, Difficulty.HARD, personalityProfile);
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
     * Spawns a bot at the given location with the specified settings.
     *
     * @param location   the spawn location
     * @param difficulty the difficulty profile
     * @param profile    the personality profile
     * @return the spawned bot, or null if spawning failed
     */
    @Nullable
    public TrainerBot spawnBot(@Nonnull Location location,
                               @Nonnull DifficultyProfile difficulty,
                               @Nonnull PersonalityProfile profile) {
        return plugin.getBotManager().spawnBot(
                null, location, difficulty.getDifficulty(),
                profile.toNameList(), null);
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
    public boolean isBot(@Nonnull java.util.UUID entityUuid) {
        return plugin.getBotManager().isBot(entityUuid);
    }
}
