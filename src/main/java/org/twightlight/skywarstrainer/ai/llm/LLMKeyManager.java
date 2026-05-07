package org.twightlight.skywarstrainer.ai.llm;

import org.twightlight.skywarstrainer.util.DebugLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the API key pool for the LLM integration system.
 *
 * <p>Supports round-robin key rotation with health tracking. Keys that return
 * authentication errors (401/403) are permanently disabled. Keys that return
 * rate limit errors (429) or server errors (500+) are temporarily cooled down.</p>
 */
public class LLMKeyManager {

    private final List<KeyEntry> keys;
    private int nextKeyIndex;
    private final int rateLimitCooldownMs;
    private final int serverErrorCooldownMs;

    /**
     * Creates a new LLMKeyManager with the given keys and cooldown settings.
     *
     * @param apiKeys                    the list of API keys
     * @param rateLimitCooldownSeconds   cooldown for rate-limited keys (HTTP 429)
     * @param serverErrorCooldownSeconds cooldown for server error keys (HTTP 500/timeout)
     */
    public LLMKeyManager(@Nonnull List<String> apiKeys,
                         int rateLimitCooldownSeconds,
                         int serverErrorCooldownSeconds) {
        this.keys = new ArrayList<>();
        for (String key : apiKeys) {
            if (key != null && !key.trim().isEmpty() && !key.equals("YOUR_API_KEY_HERE")) {
                this.keys.add(new KeyEntry(key.trim()));
            }
        }
        this.nextKeyIndex = 0;
        this.rateLimitCooldownMs = rateLimitCooldownSeconds * 1000;
        this.serverErrorCooldownMs = serverErrorCooldownSeconds * 1000;
    }

    /**
     * Returns the next healthy API key via round-robin, skipping keys that
     * are permanently dead or temporarily on cooldown.
     *
     * @return a healthy API key, or null if none are available
     */
    @Nullable
    public synchronized String getNextHealthyKey() {
        if (keys.isEmpty()) return null;

        long now = System.currentTimeMillis();
        int startIndex = nextKeyIndex;
        int checked = 0;

        while (checked < keys.size()) {
            KeyEntry entry = keys.get(nextKeyIndex);
            nextKeyIndex = (nextKeyIndex + 1) % keys.size();
            checked++;

            if (entry.permanentlyDead) continue;
            if (entry.cooldownUntil > now) continue;
            return entry.key;
        }

        return null;
    }

    /**
     * Marks a key as failed based on the HTTP status code received.
     *
     * @param key            the API key that failed
     * @param httpStatusCode the HTTP status code (401, 403, 429, 500, etc.)
     */
    public synchronized void markKeyFailed(@Nonnull String key, int httpStatusCode) {
        for (KeyEntry entry : keys) {
            if (entry.key.equals(key)) {
                if (httpStatusCode == 401 || httpStatusCode == 403) {
                    // Permanent failure — invalid or expired key
                    entry.permanentlyDead = true;
                    DebugLogger.logSystem("LLM key permanently disabled (HTTP %d): %s...%s",
                            httpStatusCode,
                            key.substring(0, Math.min(4, key.length())),
                            key.substring(Math.max(0, key.length() - 4)));
                } else if (httpStatusCode == 429) {
                    // Rate limited — temporary cooldown
                    entry.cooldownUntil = System.currentTimeMillis() + rateLimitCooldownMs;
                    DebugLogger.logSystem("LLM key rate-limited, cooldown %ds: %s...",
                            rateLimitCooldownMs / 1000,
                            key.substring(0, Math.min(4, key.length())));
                } else {
                    // Server error or timeout — shorter cooldown
                    entry.cooldownUntil = System.currentTimeMillis() + serverErrorCooldownMs;
                    DebugLogger.logSystem("LLM key server error (HTTP %d), cooldown %ds: %s...",
                            httpStatusCode, serverErrorCooldownMs / 1000,
                            key.substring(0, Math.min(4, key.length())));
                }
                break;
            }
        }
    }

    /**
     * Returns whether at least one API key is healthy (not permanently dead
     * and not currently on cooldown).
     *
     * @return true if at least one healthy key exists
     */
    public synchronized boolean hasHealthyKeys() {
        long now = System.currentTimeMillis();
        for (KeyEntry entry : keys) {
            if (!entry.permanentlyDead && entry.cooldownUntil <= now) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resets all temporary cooldowns. Called on config reload.
     * Permanently dead keys remain dead.
     */
    public synchronized void refreshKeys() {
        for (KeyEntry entry : keys) {
            entry.cooldownUntil = 0;
        }
    }

    /**
     * Returns the total number of keys managed.
     *
     * @return the total key count
     */
    public synchronized int getTotalKeyCount() {
        return keys.size();
    }

    /**
     * Returns the number of healthy (usable) keys.
     *
     * @return the healthy key count
     */
    public synchronized int getHealthyKeyCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (KeyEntry entry : keys) {
            if (!entry.permanentlyDead && entry.cooldownUntil <= now) {
                count++;
            }
        }
        return count;
    }

    /**
     * Internal entry tracking the health of a single API key.
     */
    private static final class KeyEntry {
        final String key;
        boolean permanentlyDead;
        long cooldownUntil;

        KeyEntry(@Nonnull String key) {
            this.key = key;
            this.permanentlyDead = false;
            this.cooldownUntil = 0;
        }
    }
}
