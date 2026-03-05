package org.twightlight.skywarstrainer.util;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A generic cooldown tracker that maps keys to expiration timestamps.
 *
 * <p>Uses {@link System#currentTimeMillis()} for timing, which makes it
 * independent of server tick rate and safe across async contexts.
 * Supports automatic cleanup of expired entries.</p>
 *
 * <p>Common use cases in the bot AI:
 * <ul>
 *   <li>Attack cooldowns per target</li>
 *   <li>Chat message cooldowns per event type</li>
 *   <li>Strategy activation cooldowns</li>
 *   <li>Decision re-evaluation throttling</li>
 *   <li>Ability usage cooldowns (pearl, potion, golden apple)</li>
 * </ul></p>
 *
 * @param <K> the key type (e.g., String for action names, UUID for players)
 */
public class CooldownMap<K> {

    /**
     * Maps each key to the timestamp (millis) when its cooldown expires.
     * If the current time is past this value, the cooldown has expired.
     */
    private final Map<K, Long> cooldowns;

    /**
     * How many {@link #isOnCooldown(Object)} or {@link #put(Object, long)} calls
     * between automatic cleanup sweeps. Prevents unbounded map growth from
     * keys that are never checked again after their cooldown expires.
     */
    private static final int CLEANUP_INTERVAL = 100;

    /** Counter for tracking when to perform cleanup. */
    private int operationCounter;

    /**
     * Creates a new empty CooldownMap.
     */
    public CooldownMap() {
        this.cooldowns = new HashMap<>();
        this.operationCounter = 0;
    }

    /**
     * Puts a key on cooldown for the specified duration in milliseconds.
     *
     * @param key        the key to place on cooldown
     * @param durationMs the cooldown duration in milliseconds
     */
    public void put(@Nonnull K key, long durationMs) {
        cooldowns.put(key, System.currentTimeMillis() + durationMs);
        maybeCleanup();
    }

    /**
     * Puts a key on cooldown for the specified duration in server ticks.
     * Converts ticks to milliseconds assuming 20 TPS (1 tick = 50ms).
     *
     * @param key           the key to place on cooldown
     * @param durationTicks the cooldown duration in ticks
     */
    public void putTicks(@Nonnull K key, int durationTicks) {
        put(key, durationTicks * 50L);
    }

    /**
     * Checks if the given key is currently on cooldown.
     *
     * @param key the key to check
     * @return true if the key is on cooldown (has not expired), false otherwise
     */
    public boolean isOnCooldown(@Nonnull K key) {
        Long expiry = cooldowns.get(key);
        if (expiry == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiry) {
            // Expired — remove eagerly
            cooldowns.remove(key);
            return false;
        }
        maybeCleanup();
        return true;
    }

    /**
     * Returns the remaining cooldown time in milliseconds for the given key.
     *
     * @param key the key to check
     * @return remaining milliseconds, or 0 if not on cooldown
     */
    public long getRemainingMs(@Nonnull K key) {
        Long expiry = cooldowns.get(key);
        if (expiry == null) {
            return 0L;
        }
        long remaining = expiry - System.currentTimeMillis();
        if (remaining <= 0) {
            cooldowns.remove(key);
            return 0L;
        }
        return remaining;
    }

    /**
     * Returns the remaining cooldown time in ticks (assuming 20 TPS) for the given key.
     *
     * @param key the key to check
     * @return remaining ticks, or 0 if not on cooldown
     */
    public int getRemainingTicks(@Nonnull K key) {
        return (int) (getRemainingMs(key) / 50L);
    }

    /**
     * Removes a cooldown entry, effectively ending the cooldown immediately.
     *
     * @param key the key to remove
     */
    public void remove(@Nonnull K key) {
        cooldowns.remove(key);
    }

    /**
     * Clears all cooldown entries.
     */
    public void clear() {
        cooldowns.clear();
        operationCounter = 0;
    }

    /**
     * Returns the number of active (non-expired) cooldown entries.
     * This performs a sweep to remove expired entries first.
     *
     * @return the number of active cooldowns
     */
    public int size() {
        cleanup();
        return cooldowns.size();
    }

    /**
     * Checks if the map has no active cooldowns.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Attempts to acquire a cooldown: if the key is NOT on cooldown, places it
     * on cooldown and returns true. If it IS on cooldown, returns false.
     *
     * <p>This is a convenient atomic check-and-set pattern. Example:
     * <pre>{@code
     *   if (cooldowns.tryAcquire("attack", 500)) {
     *       // Perform attack — won't fire again for 500ms
     *   }
     * }</pre></p>
     *
     * @param key        the key
     * @param durationMs the cooldown duration if acquired
     * @return true if the cooldown was acquired (was not on cooldown)
     */
    public boolean tryAcquire(@Nonnull K key, long durationMs) {
        if (isOnCooldown(key)) {
            return false;
        }
        put(key, durationMs);
        return true;
    }

    /**
     * Tick-based version of {@link #tryAcquire(Object, long)}.
     *
     * @param key           the key
     * @param durationTicks the cooldown duration in ticks
     * @return true if the cooldown was acquired
     */
    public boolean tryAcquireTicks(@Nonnull K key, int durationTicks) {
        return tryAcquire(key, durationTicks * 50L);
    }

    /**
     * Periodically removes expired entries to prevent memory leaks from
     * keys that are set once and never checked again.
     */
    private void maybeCleanup() {
        operationCounter++;
        if (operationCounter >= CLEANUP_INTERVAL) {
            cleanup();
            operationCounter = 0;
        }
    }

    /**
     * Removes all expired cooldown entries from the map.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<K, Long>> iterator = cooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, Long> entry = iterator.next();
            if (now >= entry.getValue()) {
                iterator.remove();
            }
        }
    }
}
