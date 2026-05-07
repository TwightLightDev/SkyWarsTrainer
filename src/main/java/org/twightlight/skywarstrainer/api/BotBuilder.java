package org.twightlight.skywarstrainer.api;

import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.personality.Personality;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent API for spawning trainer bots programmatically.
 *
 * <p>Example:
 * <pre>
 * TrainerBot bot = new BotBuilder()
 *     .arena(myArena)
 *     .difficulty(Difficulty.HARD)
 *     .personality(Personality.AGGRESSIVE, Personality.STRATEGIC)
 *     .skin("Technoblade")
 *     .name("PracticeBot_1")
 *     .build();
 * </pre></p>
 *
 * <p>The {@code spawnLocation} setter has been removed: there is no
 * caller-controlled location anymore. Bots join the arena via
 * {@code Arena.connect}, which places them in a free team's cage. The arena
 * is required; if it isn't a 1-per-team mode (Solo / 1v1), {@code build()}
 * returns null.</p>
 */
public class BotBuilder {

    private String name;
    private Difficulty difficulty = Difficulty.MEDIUM;
    private final List<String> personalities = new ArrayList<>();
    private String skin;
    private org.twightlight.skywars.arena.Arena<?> arena;

    public BotBuilder() {
    }

    @Nonnull
    public BotBuilder arena(@Nonnull org.twightlight.skywars.arena.Arena<?> arena) {
        this.arena = arena;
        return this;
    }

    @Nonnull
    public BotBuilder name(@Nonnull String name) {
        this.name = name;
        return this;
    }

    @Nonnull
    public BotBuilder difficulty(@Nonnull Difficulty difficulty) {
        this.difficulty = difficulty;
        return this;
    }

    @Nonnull
    public BotBuilder personality(@Nonnull Personality... personalities) {
        for (Personality p : personalities) this.personalities.add(p.name());
        return this;
    }

    @Nonnull
    public BotBuilder personality(@Nonnull String... personalityNames) {
        this.personalities.addAll(Arrays.asList(personalityNames));
        return this;
    }

    @Nonnull
    public BotBuilder skin(@Nonnull String skinUsername) {
        this.skin = skinUsername;
        return this;
    }

    /**
     * Builds and spawns the bot.
     *
     * @return the spawned TrainerBot, or null if:
     *         the arena isn't a 1-per-team mode, the arena has no free team
     *         seats, or the spawn failed for any other reason
     * @throws IllegalStateException if arena was never set
     */
    @Nullable
    public TrainerBot build() {
        if (arena == null) {
            throw new IllegalStateException("Arena must be set before building a bot. Use .arena(myArena).");
        }

        SkyWarsTrainer plugin = SkyWarsTrainer.getInstance();
        if (plugin == null) {
            throw new IllegalStateException("SkyWarsTrainer plugin is not enabled.");
        }

        if (!BotManager.isSoloMode(arena)) {
            plugin.getLogger().warning("BotBuilder: refused to spawn — arena '"
                    + arena.getServerName() + "' is not a 1-per-team mode.");
            return null;
        }
        if (BotManager.countAvailableSeats(arena) <= 0) {
            plugin.getLogger().warning("BotBuilder: refused to spawn — arena '"
                    + arena.getServerName() + "' has no free team seats.");
            return null;
        }

        // Note: skin is currently informational — BotSkin generation in
        // BotManager keys off difficulty + personalities. If you need a
        // specific skin, set name() to that username (BotSkin.withName uses it).
        return plugin.getBotManager().spawnBot(arena, difficulty, personalities, name);
    }
}
