package org.twightlight.skywarstrainer.bot;

import org.twightlight.skywarstrainer.ai.personality.PersonalityProfile;
import org.twightlight.skywarstrainer.config.DifficultyConfig.Difficulty;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stores the complete profile for a single trainer bot: its difficulty level,
 * personality set, skin, statistics, and runtime state flags.
 */
public class BotProfile {

    private Difficulty difficulty;
    private DifficultyProfile difficultyProfile;
    private PersonalityProfile personalityProfile;
    private final List<String> personalityNames;

    // ── Statistics ──
    private int kills;
    private int deaths;
    private int gamesPlayed;
    private int gamesWon;

    // ── Runtime Flags ──
    private boolean paused;
    private boolean debugMode;
    private int totalGamesForLearning;

    /**
     * Optional back-reference to the owning bot, used for unified debug checks.
     * Set after construction by TrainerBot.
     */
    @Nullable
    private TrainerBot ownerBot;

    public BotProfile(@Nonnull Difficulty difficulty, @Nonnull DifficultyProfile difficultyProfile) {
        this.difficulty = difficulty;
        this.difficultyProfile = difficultyProfile;
        this.personalityNames = new ArrayList<>();
        this.personalityProfile = null;
        this.kills = 0;
        this.deaths = 0;
        this.gamesPlayed = 0;
        this.gamesWon = 0;
        this.paused = false;
        this.debugMode = false;
        this.totalGamesForLearning = 0;
        this.ownerBot = null;
    }

    /**
     * Sets the owner bot reference for unified debug checks.
     *
     * @param bot the owning bot
     */
    public void setOwnerBot(@Nullable TrainerBot bot) {
        this.ownerBot = bot;
    }

    // ─── Difficulty ─────────────────────────────────────────────

    @Nonnull
    public Difficulty getDifficulty() {
        return difficulty;
    }

    @Nonnull
    public DifficultyProfile getDifficultyProfile() {
        return difficultyProfile;
    }

    public void setDifficulty(@Nonnull Difficulty difficulty, @Nonnull DifficultyProfile profile) {
        this.difficulty = difficulty;
        this.difficultyProfile = profile;
    }

    // ─── Personalities ──────────────────────────────────────────

    @Nonnull
    public List<String> getPersonalityNames() {
        return Collections.unmodifiableList(personalityNames);
    }

    public void addPersonality(@Nonnull String personalityName) {
        if (!personalityNames.contains(personalityName)) {
            personalityNames.add(personalityName);
            invalidateProfile();
        }
    }

    public boolean removePersonality(@Nonnull String personalityName) {
        boolean removed = personalityNames.remove(personalityName);
        if (removed) {
            invalidateProfile();
        }
        return removed;
    }

    public void setPersonalities(@Nonnull List<String> personalities) {
        personalityNames.clear();
        personalityNames.addAll(personalities);
        invalidateProfile();
    }

    public boolean hasPersonality(@Nonnull String personalityName) {
        return personalityNames.contains(personalityName);
    }

    @Nonnull
    public PersonalityProfile getPersonalityProfile() {
        if (personalityProfile == null) {
            personalityProfile = PersonalityProfile.fromNames(personalityNames);
        }
        return personalityProfile;
    }

    private void invalidateProfile() {
        this.personalityProfile = null;
    }

    // ─── Statistics ─────────────────────────────────────────────

    public int getKills() { return kills; }
    public int getDeaths() { return deaths; }
    public int getGamesPlayed() { return gamesPlayed; }
    public int getGamesWon() { return gamesWon; }

    public double getWinRate() {
        if (gamesPlayed == 0) return 0.0;
        return (gamesWon / (double) gamesPlayed) * 100.0;
    }

    public double getKDRatio() {
        if (deaths == 0) return kills;
        return kills / (double) deaths;
    }

    public void addKill() { kills++; }
    public void addDeath() { deaths++; }
    public void addGamePlayed() { gamesPlayed++; }
    public void addGameWon() { gamesWon++; }

    // ─── Runtime Flags ──────────────────────────────────────────

    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }

    /**
     * Returns true if debug mode is active for this bot.
     *
     * <p>[FIX 6.4] Unified debug check: returns true if EITHER the per-bot debug
     * flag is set OR {@link DebugLogger#isDebugEnabled(TrainerBot)} returns true
     * (which checks global debug and per-bot UUID toggles).</p>
     *
     * @return true if debug output is enabled for this bot
     */
    public boolean isDebugMode() {
        if (debugMode) return true;
        // [FIX 6.4] Also check DebugLogger for global/per-bot-UUID debug
        return DebugLogger.isDebugEnabled(ownerBot);
    }

    public void setDebugMode(boolean debugMode) { this.debugMode = debugMode; }

    public int getTotalGamesForLearning() { return totalGamesForLearning; }
    public void addGameForLearning() { totalGamesForLearning++; }

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
