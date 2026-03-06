package org.twightlight.skywarstrainer.bot;

import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores the complete profile for a single trainer bot: its difficulty level,
 * personality set, skin, statistics, and runtime state flags.
 *
 * <p>BotProfile is the central data object that defines what a bot IS. It is
 * created when a bot is spawned and persists for the bot's lifetime. Other
 * subsystems (combat, movement, decisions) read from this profile to
 * determine behavior parameters.</p>
 *
 * <p>In Phase 1, the personality system is not yet implemented. The profile
 * stores the difficulty and placeholder personality names. The personality
 * weight modifiers and conflict resolution are added in Phase 6.</p>
 */
public class BotProfile {

    /** The difficulty level of this bot. */
    private Difficulty difficulty;

    /** The resolved difficulty profile with all numeric parameters. */
    private DifficultyProfile difficultyProfile;
    private PersonalityProfile personalityProfile;
    /**
     * Personality names assigned to this bot (1-3). These are stored as
     * strings in Phase 1 and resolved to full Personality enums in Phase 6.
     */
    private final List<String> personalityNames;

    // ── Statistics ──
    private int kills;
    private int deaths;
    private int gamesPlayed;
    private int gamesWon;

    // ── Runtime Flags ──
    private boolean paused;
    private boolean debugMode;

    /**
     * Creates a new BotProfile with the specified difficulty.
     *
     * @param difficulty        the difficulty level
     * @param difficultyProfile the resolved difficulty profile
     */
    public BotProfile(@Nonnull Difficulty difficulty, @Nonnull DifficultyProfile difficultyProfile) {
        this.difficulty = difficulty;
        this.difficultyProfile = difficultyProfile;
        this.personalityNames = new ArrayList<>();
        this.kills = 0;
        this.deaths = 0;
        this.gamesPlayed = 0;
        this.gamesWon = 0;
        this.paused = false;
        this.debugMode = false;
    }

    // ─── Difficulty ─────────────────────────────────────────────

    /**
     * Returns the difficulty level.
     *
     * @return the difficulty
     */
    @Nonnull
    public Difficulty getDifficulty() {
        return difficulty;
    }

    /**
     * Returns the resolved difficulty profile containing all numeric parameters.
     * This is the primary object that subsystems read from.
     *
     * @return the difficulty profile
     */
    @Nonnull
    public DifficultyProfile getDifficultyProfile() {
        return difficultyProfile;
    }

    /**
     * Sets a new difficulty level and profile. Used by the /swt difficulty command.
     *
     * @param difficulty the new difficulty
     * @param profile    the new profile
     */
    public void setDifficulty(@Nonnull Difficulty difficulty, @Nonnull DifficultyProfile profile) {
        this.difficulty = difficulty;
        this.difficultyProfile = profile;
    }

    // ─── Personalities ──────────────────────────────────────────

    /**
     * Returns the list of personality names assigned to this bot.
     *
     * @return unmodifiable list of personality names
     */
    @Nonnull
    public List<String> getPersonalityNames() {
        return Collections.unmodifiableList(personalityNames);
    }

    /**
     * Adds a personality name to this bot's profile.
     *
     * @param personalityName the personality name (e.g., "AGGRESSIVE")
     */
    public void addPersonality(@Nonnull String personalityName) {
        if (!personalityNames.contains(personalityName)) {
            personalityNames.add(personalityName);
        }
    }

    /**
     * Removes a personality name from this bot's profile.
     *
     * @param personalityName the personality name to remove
     * @return true if it was present and removed
     */
    public boolean removePersonality(@Nonnull String personalityName) {
        return personalityNames.remove(personalityName);
    }

    /**
     * Replaces all personality names with the given list.
     *
     * @param personalities the new personality names
     */
    public void setPersonalities(@Nonnull List<String> personalities) {
        personalityNames.clear();
        personalityNames.addAll(personalities);
    }

    /**
     * Returns true if this bot has the specified personality.
     *
     * @param personalityName the personality to check
     * @return true if present
     */
    public boolean hasPersonality(@Nonnull String personalityName) {
        return personalityNames.contains(personalityName);
    }

    // ─── Statistics ─────────────────────────────────────────────

    /** @return total kills across all games */
    public int getKills() { return kills; }

    /** @return total deaths across all games */
    public int getDeaths() { return deaths; }

    /** @return number of games played */
    public int getGamesPlayed() { return gamesPlayed; }

    /** @return number of games won */
    public int getGamesWon() { return gamesWon; }

    /**
     * Returns the win rate as a percentage (0-100).
     *
     * @return the win rate, or 0 if no games played
     */
    public double getWinRate() {
        if (gamesPlayed == 0) return 0.0;
        return (gamesWon / (double) gamesPlayed) * 100.0;
    }

    /**
     * Returns the K/D ratio.
     *
     * @return the kill/death ratio, or kills if no deaths
     */
    public double getKDRatio() {
        if (deaths == 0) return kills;
        return kills / (double) deaths;
    }

    /** Increments the kill counter. */
    public void addKill() { kills++; }

    /** Increments the death counter. */
    public void addDeath() { deaths++; }

    /** Increments the games played counter. */
    public void addGamePlayed() { gamesPlayed++; }

    /** Increments the games won counter. */
    public void addGameWon() { gamesWon++; }

    // ─── Runtime Flags ──────────────────────────────────────────

    /** @return true if the bot's AI is paused */
    public boolean isPaused() { return paused; }

    /** @param paused whether the bot AI should be paused */
    public void setPaused(boolean paused) { this.paused = paused; }

    /** @return true if debug mode is active for this bot */
    public boolean isDebugMode() { return debugMode; }

    /** @param debugMode whether debug output is shown for this bot */
    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }


    /**
     * Returns the resolved PersonalityProfile. Lazily builds from stored names.
     *
     * @return the personality profile (never null; may be empty)
     */
    @Nonnull
    public PersonalityProfile getPersonalityProfile() {
        if (personalityProfile == null) {
            personalityProfile = PersonalityProfile.fromNames(personalityNames);
        }
        return personalityProfile;
    }

    /**
     * Invalidates the cached personality profile. Must be called when
     * personalities are added/removed.
     */
    private void invalidateProfile() {
        this.personalityProfile = null;
    }

    @Override
    public String toString() {
        return "BotProfile{difficulty=" + difficulty.name()
                + ", personalities=" + personalityNames
                + ", kills=" + kills
                + ", deaths=" + deaths
                + ", games=" + gamesPlayed
                + ", wins=" + gamesWon + "}";
    }
}

