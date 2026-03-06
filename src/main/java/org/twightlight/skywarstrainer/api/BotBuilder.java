package org.twightlight.skywarstrainer.api;

import org.bukkit.Location;
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent API for building and spawning trainer bots programmatically.
 *
 * <p>Example:
 * <pre>
 * TrainerBot bot = new BotBuilder()
 *     .name("PracticeBot_1")
 *     .difficulty(Difficulty.HARD)
 *     .personality(Personality.AGGRESSIVE, Personality.STRATEGIC)
 *     .skin("Technoblade")
 *     .spawnLocation(location)
 *     .build();
 * </pre></p>
 */
public class BotBuilder {

    private String name;
    private Difficulty difficulty = Difficulty.MEDIUM;
    private final List<String> personalities = new ArrayList<>();
    private String skin;
    private Location spawnLocation;

    /**
     * Creates a new BotBuilder with default settings.
     */
    public BotBuilder() {
    }

    /**
     * Sets the bot's display name.
     *
     * @param name the display name
     * @return this builder
     */
    @Nonnull
    public BotBuilder name(@Nonnull String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the bot's difficulty level.
     *
     * @param difficulty the difficulty
     * @return this builder
     */
    @Nonnull
    public BotBuilder difficulty(@Nonnull Difficulty difficulty) {
        this.difficulty = difficulty;
        return this;
    }

    /**
     * Adds personalities to the bot.
     *
     * @param personalities the personalities to add
     * @return this builder
     */
    @Nonnull
    public BotBuilder personality(@Nonnull Personality... personalities) {
        for (Personality p : personalities) {
            this.personalities.add(p.name());
        }
        return this;
    }

    /**
     * Adds personality names to the bot.
     *
     * @param personalityNames the personality names
     * @return this builder
     */
    @Nonnull
    public BotBuilder personality(@Nonnull String... personalityNames) {
        this.personalities.addAll(Arrays.asList(personalityNames));
        return this;
    }

    /**
     * Sets the skin username for the bot.
     *
     * @param skinUsername the Minecraft username to use as skin
     * @return this builder
     */
    @Nonnull
    public BotBuilder skin(@Nonnull String skinUsername) {
        this.skin = skinUsername;
        return this;
    }

    /**
     * Sets the spawn location for the bot.
     *
     * @param location the spawn location
     * @return this builder
     */
    @Nonnull
    public BotBuilder spawnLocation(@Nonnull Location location) {
        this.spawnLocation = location;
        return this;
    }

    /**
     * Builds and spawns the bot with the configured settings.
     *
     * @return the spawned TrainerBot, or null if spawning failed
     * @throws IllegalStateException if no spawn location is set
     */
    @Nullable
    public TrainerBot build() {
        if (spawnLocation == null) {
            throw new IllegalStateException("Spawn location must be set before building a bot.");
        }

        SkyWarsTrainerPlugin plugin = SkyWarsTrainerPlugin.getInstance();
        if (plugin == null) {
            throw new IllegalStateException("SkyWarsTrainer plugin is not enabled.");
        }

        return plugin.getBotManager().spawnBot(
                null,
                spawnLocation,
                difficulty,
                personalities,
                name
        );
    }
}
