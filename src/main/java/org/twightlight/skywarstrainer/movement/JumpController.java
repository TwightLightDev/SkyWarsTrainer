package org.twightlight.skywarstrainer.movement;

import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Controls the bot's jumping behavior, including sprint-jumping for fast travel,
 * combat jump-attacks, parkour jumps, and difficulty-based timing variance.
 *
 * <p>In Minecraft 1.8, jump behavior:
 * <ul>
 *   <li>Base jump velocity: 0.42 blocks/tick upward</li>
 *   <li>Sprint-jump: fastest horizontal travel (sprint speed + jump boost)</li>
 *   <li>Jump cooldown: ~10 ticks (0.5s) while on ground before next jump</li>
 *   <li>Lower difficulty bots may "spam jump" unnecessarily (common beginner habit)</li>
 * </ul></p>
 */
public class JumpController {

    private final TrainerBot bot;

    /** Whether a jump has been requested for this tick. */
    private boolean jumpRequested;

    /** Ticks since last jump. Used for jump cooldown. */
    private int ticksSinceLastJump;

    /**
     * Cooldown ticks between jumps. Vanilla allows jumping once on ground,
     * but we add a minimum gap for realism.
     */
    private static final int MIN_JUMP_COOLDOWN = 6;

    /**
     * Counter for beginner jump spam behavior. Decrements each tick;
     * when it reaches 0, the bot may spam-jump if difficulty is low enough.
     */
    private int jumpSpamTimer;

    /**
     * Creates a new JumpController for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public JumpController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.jumpRequested = false;
        this.ticksSinceLastJump = MIN_JUMP_COOLDOWN;
        this.jumpSpamTimer = 0;
    }

    /**
     * Tick method called every server tick. Processes jump requests and
     * handles automatic jump spam for lower-difficulty bots.
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        ticksSinceLastJump++;

        // Process jump request
        if (jumpRequested) {
            jumpRequested = false;
            executeJump(entity);
        }

        // Beginner jump spam: lower-difficulty bots occasionally jump for no reason
        DifficultyProfile diff = bot.getDifficultyProfile();
        double fraction = diff.getDifficulty().asFraction();
        if (fraction < 0.5) { // Only BEGINNER and EASY
            jumpSpamTimer--;
            if (jumpSpamTimer <= 0) {
                // Chance to spam-jump: higher for beginners
                double spamChance = 0.02 * (1.0 - fraction); // ~2% for beginner, ~1% for easy
                if (RandomUtil.chance(spamChance) && NMSHelper.isOnGround(entity)) {
                    executeJump(entity);
                }
                jumpSpamTimer = RandomUtil.nextInt(10, 40);
            }
        }
    }

    /**
     * Requests a jump on the next tick. The jump will only execute if the
     * bot is on the ground and the cooldown has elapsed.
     */
    public void jump() {
        jumpRequested = true;
    }

    /**
     * Executes a jump immediately if conditions are met.
     *
     * @param entity the bot's living entity
     */
    private void executeJump(@Nonnull LivingEntity entity) {
        if (!NMSHelper.isOnGround(entity)) return;
        if (ticksSinceLastJump < MIN_JUMP_COOLDOWN) return;

        // Apply jump timing variance based on difficulty
        DifficultyProfile diff = bot.getDifficultyProfile();
        double fraction = diff.getDifficulty().asFraction();

        // Timing variance: ±1-2 ticks for low difficulty, ±0 for expert
        int variance = (int) Math.round(2.0 * (1.0 - fraction));
        if (variance > 0 && RandomUtil.chance(0.3)) {
            // Slight delay in jump execution (simulates imprecise timing)
            // We just skip this jump attempt — next tick will retry
            return;
        }

        // Apply jump velocity
        Vector velocity = entity.getVelocity();
        double jumpVelocity = 0.42; // Vanilla jump velocity

        // Add small variance to jump height for realism
        jumpVelocity += RandomUtil.gaussian(0.0, 0.005 * (1.0 - fraction));

        velocity.setY(jumpVelocity);
        entity.setVelocity(velocity);

        ticksSinceLastJump = 0;
    }

    /**
     * Returns true if the bot is on the ground and ready to jump.
     *
     * @return true if the bot can jump right now
     */
    public boolean canJump() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return false;
        return NMSHelper.isOnGround(entity) && ticksSinceLastJump >= MIN_JUMP_COOLDOWN;
    }

    /**
     * Returns the ticks since the last jump.
     *
     * @return ticks since last jump
     */
    public int getTicksSinceLastJump() {
        return ticksSinceLastJump;
    }
}

