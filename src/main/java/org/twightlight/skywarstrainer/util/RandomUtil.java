package org.twightlight.skywarstrainer.util;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random number generation utilities.
 *
 * <p>Wraps {@link ThreadLocalRandom} for thread-safe random number generation
 * without synchronization overhead. Provides Gaussian noise, range selection,
 * weighted random choice, and probability checks used throughout the AI.</p>
 */
public final class RandomUtil {

    private RandomUtil() {
        // Static utility class — no instantiation
    }

    /**
     * Returns the thread-local random instance. Prefer the convenience methods
     * in this class, but this is exposed for cases needing direct access.
     *
     * @return the ThreadLocalRandom for the current thread
     */
    @Nonnull
    public static Random getRandom() {
        return ThreadLocalRandom.current();
    }

    // ─── Range Methods ──────────────────────────────────────────

    /**
     * Returns a random integer in the inclusive range [min, max].
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return a random integer between min and max
     */
    public static int nextInt(int min, int max) {
        if (min == max) return min;
        if (min > max) {
            int temp = min;
            min = max;
            max = temp;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    /**
     * Returns a random integer from 0 (inclusive) to bound (exclusive).
     *
     * @param bound the upper bound (exclusive)
     * @return a random integer in [0, bound)
     */
    public static int nextInt(int bound) {
        return ThreadLocalRandom.current().nextInt(bound);
    }

    /**
     * Returns a random double in the range [min, max).
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (exclusive)
     * @return a random double
     */
    public static double nextDouble(double min, double max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextDouble(min, max);
    }

    /**
     * Returns a random double in [0.0, 1.0).
     *
     * @return a random double
     */
    public static double nextDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }

    /**
     * Returns a random float in [0.0, 1.0).
     *
     * @return a random float
     */
    public static float nextFloat() {
        return ThreadLocalRandom.current().nextFloat();
    }

    /**
     * Returns a random boolean.
     *
     * @return true or false with equal probability
     */
    public static boolean nextBoolean() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    // ─── Gaussian / Noise ───────────────────────────────────────

    /**
     * Returns a Gaussian-distributed random value with the given mean and standard deviation.
     *
     * <p>Used extensively for aim noise, movement jitter, and timing variance.
     * The Gaussian distribution ensures most values are near the mean with occasional
     * outliers, which mimics human imprecision naturally.</p>
     *
     * @param mean   the center of the distribution
     * @param stdDev the standard deviation (spread)
     * @return a random Gaussian value
     */
    public static double gaussian(double mean, double stdDev) {
        return mean + ThreadLocalRandom.current().nextGaussian() * stdDev;
    }

    /**
     * Returns a Gaussian-distributed value clamped to [min, max].
     *
     * <p>Prevents extreme outliers that could cause unrealistic behavior
     * (e.g., aim noise flipping 180 degrees).</p>
     *
     * @param mean   the center of the distribution
     * @param stdDev the standard deviation
     * @param min    the minimum allowed value
     * @param max    the maximum allowed value
     * @return a clamped Gaussian value
     */
    public static double gaussianClamped(double mean, double stdDev, double min, double max) {
        double value = gaussian(mean, stdDev);
        return com.twightlight.skywarstrainer.util.MathUtil.clamp(value, min, max);
    }

    /**
     * Returns a random Gaussian value with mean 0 and the given standard deviation.
     * Shorthand for noise generation.
     *
     * @param stdDev the standard deviation
     * @return Gaussian noise value
     */
    public static double noise(double stdDev) {
        return gaussian(0.0, stdDev);
    }

    // ─── Probability ────────────────────────────────────────────

    /**
     * Returns true with the given probability.
     *
     * <p>Example: {@code chance(0.3)} returns true ~30% of the time.</p>
     *
     * @param probability the probability [0.0, 1.0]
     * @return true if the random check passes
     */
    public static boolean chance(double probability) {
        if (probability <= 0.0) return false;
        if (probability >= 1.0) return true;
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    /**
     * Returns true with the given percentage chance (0-100).
     *
     * @param percent the percentage [0, 100]
     * @return true if the random check passes
     */
    public static boolean percentChance(double percent) {
        return chance(percent / 100.0);
    }

    // ─── Collection Utilities ───────────────────────────────────

    /**
     * Returns a random element from a list.
     *
     * @param list the list to pick from (must not be empty)
     * @param <T>  the element type
     * @return a random element
     * @throws IllegalArgumentException if the list is empty
     */
    @Nonnull
    public static <T> T randomElement(@Nonnull List<T> list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Cannot pick a random element from an empty list");
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /**
     * Returns a random element from an array.
     *
     * @param array the array to pick from (must not be empty)
     * @param <T>   the element type
     * @return a random element
     * @throws IllegalArgumentException if the array is empty
     */
    @Nonnull
    public static <T> T randomElement(@Nonnull T[] array) {
        if (array.length == 0) {
            throw new IllegalArgumentException("Cannot pick a random element from an empty array");
        }
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }

    /**
     * Performs a weighted random selection. Each weight corresponds to the element
     * at the same index. Higher weights = higher probability of selection.
     *
     * @param elements the elements to choose from
     * @param weights  the weight for each element (must be same length as elements)
     * @param <T>      the element type
     * @return the randomly selected element
     * @throws IllegalArgumentException if the lists are empty or different lengths
     */
    @Nonnull
    public static <T> T weightedRandom(@Nonnull List<T> elements, @Nonnull List<Double> weights) {
        if (elements.isEmpty() || elements.size() != weights.size()) {
            throw new IllegalArgumentException("Elements and weights must be non-empty and same length");
        }

        double totalWeight = 0.0;
        for (double w : weights) {
            totalWeight += Math.max(0.0, w);
        }

        if (totalWeight <= 0.0) {
            // All weights are zero or negative; fall back to uniform random
            return randomElement(elements);
        }

        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (int i = 0; i < elements.size(); i++) {
            cumulative += Math.max(0.0, weights.get(i));
            if (roll < cumulative) {
                return elements.get(i);
            }
        }

        // Floating-point edge case: return last element
        return elements.get(elements.size() - 1);
    }

    // ─── Time / Tick Variance ───────────────────────────────────

    /**
     * Returns a randomized tick duration centered on the given value with the
     * specified variance percentage.
     *
     * <p>Example: {@code varyTicks(20, 0.2)} returns a value in [16, 24].</p>
     *
     * @param baseTicks       the base tick count
     * @param varianceFraction the variance as a fraction (0.2 = ±20%)
     * @return the varied tick count, minimum 1
     */
    public static int varyTicks(int baseTicks, double varianceFraction) {
        double variance = baseTicks * varianceFraction;
        int result = (int) Math.round(gaussian(baseTicks, variance));
        return Math.max(1, result);
    }

    /**
     * Returns a random long in the inclusive range [min, max].
     * Useful for millisecond delays.
     *
     * @param min the minimum value
     * @param max the maximum value
     * @return a random long
     */
    public static long nextLong(long min, long max) {
        if (min == max) return min;
        if (min > max) {
            long temp = min;
            min = max;
            max = temp;
        }
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}

