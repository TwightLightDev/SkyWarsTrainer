package org.twightlight.skywarstrainer.util;

import javax.annotation.Nonnull;

/**
 * A lightweight tick-based timer for scheduling periodic actions within
 * the bot's tick loop without creating separate Bukkit tasks.
 *
 * <p>Instead of relying on {@link org.bukkit.scheduler.BukkitScheduler} for
 * every periodic action, subsystems use TickTimer instances that are checked
 * each tick. This avoids the overhead of many scheduled tasks and keeps all
 * bot logic within the single staggered tick loop.</p>
 *
 * <p>Usage example:
 * <pre>{@code
 *   TickTimer mapScanTimer = new TickTimer(50); // every 50 ticks
 *   // Inside tick loop:
 *   if (mapScanTimer.tick()) {
 *       performMapScan();
 *   }
 * }</pre></p>
 *
 * <p>Supports dynamic interval changes, manual resets, forced triggers,
 * and variance injection for staggering.</p>
 */
public class TickTimer {

    /** The number of ticks between each trigger. */
    private int interval;

    /** The current tick counter. Incremented each call to {@link #tick()}. */
    private int counter;

    /** Whether this timer is currently active. Paused timers never trigger. */
    private boolean active;

    /**
     * Optional variance applied to the interval on each reset. This prevents
     * multiple timers with the same interval from synchronizing, which would
     * cause tick spikes. The actual interval after reset is:
     * {@code interval + random(-variance, +variance)}.
     */
    private int variance;

    /**
     * The effective interval for the current cycle, which may differ from
     * {@link #interval} if variance is applied.
     */
    private int currentEffectiveInterval;

    /**
     * Creates a new TickTimer with the given interval and no variance.
     *
     * @param interval the number of ticks between triggers (must be >= 1)
     */
    public TickTimer(int interval) {
        this(interval, 0);
    }

    /**
     * Creates a new TickTimer with the given interval and variance.
     *
     * @param interval the base number of ticks between triggers (must be >= 1)
     * @param variance the maximum random deviation applied each cycle (>= 0)
     */
    public TickTimer(int interval, int variance) {
        if (interval < 1) {
            throw new IllegalArgumentException("Timer interval must be >= 1, got: " + interval);
        }
        if (variance < 0) {
            throw new IllegalArgumentException("Timer variance must be >= 0, got: " + variance);
        }
        this.interval = interval;
        this.variance = variance;
        this.active = true;
        this.counter = 0;
        this.currentEffectiveInterval = calculateEffectiveInterval();
    }

    /**
     * Creates a new TickTimer with the given interval and an initial offset.
     * The offset staggers this timer relative to others with the same interval.
     *
     * @param interval      the base interval in ticks
     * @param variance      the random variance
     * @param initialOffset the starting counter value (0 to interval-1)
     * @return a new TickTimer with the specified offset
     */
    @Nonnull
    public static TickTimer withOffset(int interval, int variance, int initialOffset) {
        TickTimer timer = new TickTimer(interval, variance);
        timer.counter = MathUtil.clamp(initialOffset, 0, interval - 1);
        return timer;
    }

    /**
     * Advances the timer by one tick and returns whether the timer has triggered.
     *
     * <p>Call this method exactly once per tick in the bot's tick loop. When the
     * counter reaches the effective interval, the timer triggers (returns true),
     * resets the counter, and recalculates the effective interval if variance
     * is enabled.</p>
     *
     * @return true if the timer triggered this tick, false otherwise
     */
    public boolean tick() {
        if (!active) {
            return false;
        }

        counter++;
        if (counter >= currentEffectiveInterval) {
            counter = 0;
            currentEffectiveInterval = calculateEffectiveInterval();
            return true;
        }
        return false;
    }

    /**
     * Checks if the timer would trigger this tick WITHOUT advancing it.
     * Useful for preview/debug purposes.
     *
     * @return true if the next call to {@link #tick()} would trigger
     */
    public boolean wouldTrigger() {
        return active && (counter + 1) >= currentEffectiveInterval;
    }

    /**
     * Returns the progress toward the next trigger as a fraction [0.0, 1.0].
     *
     * @return the progress fraction
     */
    public double getProgress() {
        if (currentEffectiveInterval <= 0) return 1.0;
        return (double) counter / currentEffectiveInterval;
    }

    /**
     * Resets the counter to zero, effectively restarting the current cycle.
     * The effective interval is recalculated with variance.
     */
    public void reset() {
        counter = 0;
        currentEffectiveInterval = calculateEffectiveInterval();
    }

    /**
     * Forces the timer to trigger on the next call to {@link #tick()}.
     * This is used by the interrupt system to force immediate re-evaluation.
     */
    public void forceNext() {
        counter = currentEffectiveInterval - 1;
    }

    /**
     * Forces an immediate trigger: resets the counter and returns true.
     * Use this for interrupt-driven events that need immediate action.
     *
     * @return always true
     */
    public boolean forceImmediate() {
        counter = 0;
        currentEffectiveInterval = calculateEffectiveInterval();
        return true;
    }

    // ─── Interval Management ────────────────────────────────────

    /**
     * Returns the base interval in ticks.
     *
     * @return the interval
     */
    public int getInterval() {
        return interval;
    }

    /**
     * Sets a new base interval. Resets the counter if the new interval
     * is shorter than the current counter position.
     *
     * @param interval the new interval (must be >= 1)
     */
    public void setInterval(int interval) {
        if (interval < 1) {
            throw new IllegalArgumentException("Timer interval must be >= 1, got: " + interval);
        }
        this.interval = interval;
        this.currentEffectiveInterval = calculateEffectiveInterval();
        if (counter >= currentEffectiveInterval) {
            counter = 0;
        }
    }

    /**
     * Returns the variance value.
     *
     * @return the variance in ticks
     */
    public int getVariance() {
        return variance;
    }

    /**
     * Sets the variance. Does not reset the counter.
     *
     * @param variance the new variance (>= 0)
     */
    public void setVariance(int variance) {
        if (variance < 0) {
            throw new IllegalArgumentException("Timer variance must be >= 0, got: " + variance);
        }
        this.variance = variance;
    }

    /**
     * Returns the effective interval for the current cycle (base + variance).
     *
     * @return the current effective interval
     */
    public int getCurrentEffectiveInterval() {
        return currentEffectiveInterval;
    }

    /**
     * Returns the current counter value (ticks since last trigger).
     *
     * @return the counter
     */
    public int getCounter() {
        return counter;
    }

    // ─── Active State ───────────────────────────────────────────

    /**
     * Returns whether this timer is active.
     *
     * @return true if active, false if paused
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Sets whether this timer is active. Paused timers never trigger
     * and do not advance their counter.
     *
     * @param active true to activate, false to pause
     */
    public void setActive(boolean active) {
        this.active = active;
    }

    /**
     * Pauses the timer. Equivalent to {@code setActive(false)}.
     */
    public void pause() {
        this.active = false;
    }

    /**
     * Resumes the timer. Equivalent to {@code setActive(true)}.
     */
    public void resume() {
        this.active = true;
    }

    // ─── Internal ───────────────────────────────────────────────

    /**
     * Calculates the effective interval for the next cycle by applying variance.
     *
     * @return the effective interval, always >= 1
     */
    private int calculateEffectiveInterval() {
        if (variance <= 0) {
            return interval;
        }
        int offset = RandomUtil.nextInt(-variance, variance);
        return Math.max(1, interval + offset);
    }

    @Override
    public String toString() {
        return "TickTimer{interval=" + interval +
                ", counter=" + counter +
                ", effective=" + currentEffectiveInterval +
                ", active=" + active +
                ", variance=" + variance + "}";
    }
}

