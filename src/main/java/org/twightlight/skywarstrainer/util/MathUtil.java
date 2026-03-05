package org.twightlight.skywarstrainer.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import javax.annotation.Nonnull;

/**
 * Mathematical utility methods used throughout the bot AI systems.
 *
 * <p>Provides clamping, interpolation, angle calculations, distance helpers,
 * response curves for utility AI, and vector math. All methods are stateless
 * and thread-safe.</p>
 */
public final class MathUtil {

    /** Degrees-to-radians conversion factor. */
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    /** Radians-to-degrees conversion factor. */
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    private MathUtil() {
        // Static utility class — no instantiation
    }

    // ─── Clamping ───────────────────────────────────────────────

    /**
     * Clamps a double value between a minimum and maximum.
     *
     * @param value the value to clamp
     * @param min   the lower bound (inclusive)
     * @param max   the upper bound (inclusive)
     * @return the clamped value
     */
    public static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Clamps an integer value between a minimum and maximum.
     *
     * @param value the value to clamp
     * @param min   the lower bound (inclusive)
     * @param max   the upper bound (inclusive)
     * @return the clamped value
     */
    public static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    /**
     * Clamps a float value between a minimum and maximum.
     *
     * @param value the value to clamp
     * @param min   the lower bound (inclusive)
     * @param max   the upper bound (inclusive)
     * @return the clamped value
     */
    public static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    // ─── Interpolation ─────────────────────────────────────────

    /**
     * Linearly interpolates between two values.
     *
     * @param start the start value (returned when t=0)
     * @param end   the end value (returned when t=1)
     * @param t     interpolation factor, typically [0.0, 1.0]
     * @return the interpolated value
     */
    public static double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    /**
     * Inverse linear interpolation: returns where {@code value} falls between
     * {@code start} and {@code end} as a 0.0–1.0 fraction.
     *
     * @param start the start of the range
     * @param end   the end of the range
     * @param value the value to locate
     * @return the fraction [0.0, 1.0], clamped
     */
    public static double inverseLerp(double start, double end, double value) {
        if (Math.abs(end - start) < 1e-10) return 0.0;
        return clamp((value - start) / (end - start), 0.0, 1.0);
    }

    /**
     * Smooth-step interpolation (Hermite curve). Provides smooth acceleration
     * and deceleration, useful for natural-looking movement transitions.
     *
     * @param edge0 lower edge (returns 0 below this)
     * @param edge1 upper edge (returns 1 above this)
     * @param x     the input value
     * @return smoothly interpolated value in [0.0, 1.0]
     */
    public static double smoothStep(double edge0, double edge1, double x) {
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Exponential smoothing (low-pass filter). Used for smooth aim transitions.
     *
     * <p>Each tick: {@code current += (target - current) * smoothFactor}</p>
     *
     * @param current     the current value
     * @param target      the target value
     * @param smoothFactor how quickly to approach target (0 = never, 1 = instant)
     * @return the new smoothed value
     */
    public static double exponentialSmooth(double current, double target, double smoothFactor) {
        return current + (target - current) * clamp(smoothFactor, 0.0, 1.0);
    }

    // ─── Angle Utilities ────────────────────────────────────────

    /**
     * Normalizes an angle in degrees to the range [-180, 180).
     *
     * @param angle the angle in degrees
     * @return the normalized angle
     */
    public static double normalizeAngle(double angle) {
        angle = angle % 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    /**
     * Normalizes an angle in degrees to the range [0, 360).
     *
     * @param angle the angle in degrees
     * @return the normalized angle
     */
    public static double normalizeAnglePositive(double angle) {
        angle = angle % 360.0;
        if (angle < 0.0) angle += 360.0;
        return angle;
    }

    /**
     * Calculates the smallest signed difference between two angles in degrees.
     * The result is in [-180, 180].
     *
     * @param from the starting angle in degrees
     * @param to   the target angle in degrees
     * @return the shortest angular difference
     */
    public static double angleDifference(double from, double to) {
        return normalizeAngle(to - from);
    }

    /**
     * Converts degrees to radians.
     *
     * @param degrees the angle in degrees
     * @return the angle in radians
     */
    public static double toRadians(double degrees) {
        return degrees * DEG_TO_RAD;
    }

    /**
     * Converts radians to degrees.
     *
     * @param radians the angle in radians
     * @return the angle in degrees
     */
    public static double toDegrees(double radians) {
        return radians * RAD_TO_DEG;
    }

    // ─── Location / Direction Math ──────────────────────────────

    /**
     * Calculates the yaw angle (in Minecraft degrees) from one location to another.
     *
     * <p>Minecraft yaw: 0 = south (+Z), 90 = west (-X), 180 = north (-Z), 270 = east (+X).</p>
     *
     * @param from the source location
     * @param to   the target location
     * @return the yaw in degrees [-180, 180)
     */
    public static float calculateYaw(@Nonnull Location from, @Nonnull Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        // atan2(-dx, dz) gives the Minecraft yaw convention
        double yaw = Math.atan2(-dx, dz) * RAD_TO_DEG;
        return (float) normalizeAngle(yaw);
    }

    /**
     * Calculates the pitch angle (in Minecraft degrees) from one location to another.
     *
     * <p>Minecraft pitch: -90 = straight up, 0 = horizontal, 90 = straight down.</p>
     *
     * @param from the source location (eye position)
     * @param to   the target location
     * @return the pitch in degrees [-90, 90]
     */
    public static float calculatePitch(@Nonnull Location from, @Nonnull Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double pitch = -Math.atan2(dy, horizontalDistance) * RAD_TO_DEG;
        return (float) clamp(pitch, -90.0, 90.0);
    }

    /**
     * Returns the horizontal (XZ-plane) distance between two locations.
     *
     * @param a the first location
     * @param b the second location
     * @return the horizontal distance in blocks
     */
    public static double horizontalDistance(@Nonnull Location a, @Nonnull Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Returns the 3D distance between two locations.
     *
     * @param a the first location
     * @param b the second location
     * @return the 3D distance in blocks
     */
    public static double distance3D(@Nonnull Location a, @Nonnull Location b) {
        return a.distance(b);
    }

    /**
     * Returns a unit direction vector from one location to another.
     *
     * @param from the origin
     * @param to   the destination
     * @return the normalized direction vector, or zero vector if locations are identical
     */
    @Nonnull
    public static Vector directionTo(@Nonnull Location from, @Nonnull Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double length = direction.length();
        if (length < 1e-6) {
            return new Vector(0, 0, 0);
        }
        return direction.multiply(1.0 / length);
    }

    // ─── Utility AI Response Curves ─────────────────────────────

    /**
     * Sigmoid response curve for utility AI considerations.
     *
     * <p>Maps an input [0, 1] to an S-shaped output [0, 1].
     * {@code steepness} controls how sharp the transition is.
     * {@code midpoint} controls where the 0.5 output falls.</p>
     *
     * @param x         input value in [0, 1]
     * @param steepness how steep the sigmoid is (higher = sharper)
     * @param midpoint  x-value where output is 0.5
     * @return the sigmoid output in [0, 1]
     */
    public static double sigmoid(double x, double steepness, double midpoint) {
        return 1.0 / (1.0 + Math.exp(-steepness * (x - midpoint)));
    }

    /**
     * Simple sigmoid with default steepness of 10 and midpoint of 0.5.
     *
     * @param x input value in [0, 1]
     * @return sigmoid output in [0, 1]
     */
    public static double sigmoid(double x) {
        return sigmoid(x, 10.0, 0.5);
    }

    /**
     * Linear response curve for utility AI.
     * Maps input directly to output, clamped to [0, 1].
     *
     * @param x   the input value
     * @param min the input value that maps to output 0
     * @param max the input value that maps to output 1
     * @return the linear output in [0, 1]
     */
    public static double linearCurve(double x, double min, double max) {
        return clamp((x - min) / (max - min), 0.0, 1.0);
    }

    /**
     * Quadratic (polynomial) response curve. Input is squared before clamping.
     * Produces a curve that rises slowly at first, then quickly.
     *
     * @param x   the input value in [0, 1]
     * @param power the exponent (2.0 = quadratic, 3.0 = cubic, etc.)
     * @return the polynomial output in [0, 1]
     */
    public static double polynomialCurve(double x, double power) {
        return clamp(Math.pow(clamp(x, 0.0, 1.0), power), 0.0, 1.0);
    }

    /**
     * Inverted linear curve. Returns 1 when x = min, 0 when x = max.
     * Useful for "less is better" considerations (e.g., health: lower HP = higher flee desire).
     *
     * @param x   the input value
     * @param min the input value that maps to output 1
     * @param max the input value that maps to output 0
     * @return the inverted linear output in [0, 1]
     */
    public static double invertedLinearCurve(double x, double min, double max) {
        return 1.0 - clamp((x - min) / (max - min), 0.0, 1.0);
    }

    // ─── Misc ───────────────────────────────────────────────────

    /**
     * Returns the square of a value. Avoids Math.pow overhead for squaring.
     *
     * @param x the value
     * @return x squared
     */
    public static double square(double x) {
        return x * x;
    }

    /**
     * Checks if two double values are approximately equal within an epsilon.
     *
     * @param a       first value
     * @param b       second value
     * @param epsilon the maximum allowed difference
     * @return true if the values are within epsilon of each other
     */
    public static boolean approxEquals(double a, double b, double epsilon) {
        return Math.abs(a - b) < epsilon;
    }

    /**
     * Maps a value from one range to another.
     *
     * @param value    the input value
     * @param fromMin  input range minimum
     * @param fromMax  input range maximum
     * @param toMin    output range minimum
     * @param toMax    output range maximum
     * @return the remapped value
     */
    public static double remap(double value, double fromMin, double fromMax, double toMin, double toMax) {
        double normalized = inverseLerp(fromMin, fromMax, value);
        return lerp(toMin, toMax, normalized);
    }
}

