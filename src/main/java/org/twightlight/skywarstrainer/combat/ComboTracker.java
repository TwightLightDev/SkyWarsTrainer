package org.twightlight.skywarstrainer.combat;

import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;

import javax.annotation.Nonnull;

/**
 * Tracks consecutive hits landed and received during combat.
 *
 * <p>The combo tracker is essential for:
 * <ul>
 *   <li>Knowing when the bot is "comboing" an enemy (consecutive hits landed)</li>
 *   <li>Knowing when the bot is being comboed (consecutive hits received)</li>
 *   <li>Triggering combo-break behaviors (placing blocks, trading hits)</li>
 *   <li>Limiting combo length based on difficulty (lower skill = shorter combos)</li>
 * </ul></p>
 *
 * <p>A "combo" is defined as consecutive hits without the opponent hitting back.
 * The combo resets when the other side lands a hit, or after a timeout of 40
 * ticks (2 seconds) without any hit from either side.</p>
 */
public class ComboTracker {

    private final TrainerBot bot;

    /** Consecutive hits the bot has landed without being hit back. */
    private int hitsLanded;

    /** Consecutive hits the bot has received without hitting back. */
    private int hitsReceived;

    /** Total hits landed in the current engagement. */
    private int totalHitsLanded;

    /** Total hits received in the current engagement. */
    private int totalHitsReceived;

    /** Ticks since the last hit was landed by the bot. */
    private int ticksSinceLastHitLanded;

    /** Ticks since the last hit was received by the bot. */
    private int ticksSinceLastHitReceived;

    /** The timeout in ticks after which a combo resets. */
    private static final int COMBO_TIMEOUT_TICKS = 40;

    /**
     * Creates a new ComboTracker for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public ComboTracker(@Nonnull TrainerBot bot) {
        this.bot = bot;
        reset();
    }

    /**
     * Resets all combo tracking data. Called when a new engagement starts
     * or combat ends.
     */
    public void reset() {
        this.hitsLanded = 0;
        this.hitsReceived = 0;
        this.totalHitsLanded = 0;
        this.totalHitsReceived = 0;
        this.ticksSinceLastHitLanded = COMBO_TIMEOUT_TICKS;
        this.ticksSinceLastHitReceived = COMBO_TIMEOUT_TICKS;
    }

    /**
     * Called each tick to update combo timers. If too long passes without
     * a hit from either side, combos reset.
     */
    public void tick() {
        ticksSinceLastHitLanded++;
        ticksSinceLastHitReceived++;

        // Reset our combo if we haven't hit in a while
        if (ticksSinceLastHitLanded > COMBO_TIMEOUT_TICKS) {
            hitsLanded = 0;
        }

        // Reset received combo if they haven't hit us in a while
        if (ticksSinceLastHitReceived > COMBO_TIMEOUT_TICKS) {
            hitsReceived = 0;
        }
    }

    /**
     * Records that the bot successfully landed a hit on the target.
     * Resets the received-combo counter and increments the landed-combo.
     */
    public void onHitLanded() {
        hitsLanded++;
        totalHitsLanded++;
        ticksSinceLastHitLanded = 0;

        // Landing a hit breaks the enemy's combo on us
        hitsReceived = 0;
    }

    /**
     * Records that the bot was hit by the target.
     * Resets the landed-combo counter and increments the received-combo.
     */
    public void onHitReceived() {
        hitsReceived++;
        totalHitsReceived++;
        ticksSinceLastHitReceived = 0;

        // Being hit breaks our combo on the enemy
        hitsLanded = 0;
    }

    /**
     * Returns whether the bot is currently in a combo (landing consecutive hits).
     *
     * @return true if the bot has landed 2+ consecutive hits recently
     */
    public boolean isInCombo() {
        return hitsLanded >= 2 && ticksSinceLastHitLanded < COMBO_TIMEOUT_TICKS;
    }

    /**
     * Returns whether the bot is being comboed (receiving consecutive hits).
     *
     * @return true if the bot has received 2+ consecutive hits recently
     */
    public boolean isBeingComboed() {
        return hitsReceived >= 2 && ticksSinceLastHitReceived < COMBO_TIMEOUT_TICKS;
    }

    /**
     * Returns whether the bot has reached its maximum combo length for its
     * difficulty level. At max combo, the bot's combo effectiveness degrades
     * (simulating imperfect human play).
     *
     * @return true if at or above the difficulty's combo length limit
     */
    public boolean isAtMaxCombo() {
        DifficultyProfile diff = bot.getDifficultyProfile();
        return hitsLanded >= diff.getComboLength();
    }

    /**
     * Returns how urgently the bot should try to break the enemy's combo.
     * Based on the number of consecutive hits received and the
     * comboBreakPriority difficulty parameter.
     *
     * @return a priority value from 0.0 (no urgency) to 1.0 (critical)
     */
    public double getComboBreakUrgency() {
        if (hitsReceived < 2) return 0.0;

        DifficultyProfile diff = bot.getDifficultyProfile();
        double basePriority = diff.getComboBreakPriority();

        // Urgency scales with consecutive hits received
        double hitScale = Math.min(1.0, hitsReceived / 5.0);
        return basePriority * hitScale;
    }

    // ─── Accessors ──────────────────────────────────────────────

    /** @return consecutive hits landed without being hit */
    public int getHitsLanded() { return hitsLanded; }

    /** @return consecutive hits received without landing a hit */
    public int getHitsReceived() { return hitsReceived; }

    /** @return total hits landed this engagement */
    public int getTotalHitsLanded() { return totalHitsLanded; }

    /** @return total hits received this engagement */
    public int getTotalHitsReceived() { return totalHitsReceived; }

    /** @return ticks since the bot last landed a hit */
    public int getTicksSinceLastHitLanded() { return ticksSinceLastHitLanded; }

    /** @return ticks since the bot last received a hit */
    public int getTicksSinceLastHitReceived() { return ticksSinceLastHitReceived; }
}

