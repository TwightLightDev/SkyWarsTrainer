package org.twightlight.skywarstrainer.awareness;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.ai.state.BotState;
import org.twightlight.skywarstrainer.ai.state.BotStateMachine;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.combat.defense.ClutchHandler;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.MathUtil;

import javax.annotation.Nonnull;

/**
 * A lightweight tick-based survival guard that runs EVERY tick (before movement)
 * in {@link TrainerBot#tick()}. Provides generic reactive safety behaviors driven
 * by difficulty parameters.
 *
 * <p>The SurvivalGuard does NOT replace the DecisionEngine — it overrides movement
 * only in emergencies. Its responsibilities:</p>
 * <ul>
 *   <li>Void-walk prevention: validates that the bot's current move target and forward
 *       path do not lead into void, cancelling movement if they do</li>
 *   <li>Stuck detection: detects when the bot hasn't moved for extended periods and
 *       forces re-evaluation or state reset</li>
 *   <li>Spin detection: detects rapid yaw rotation without a look target and stops it</li>
 *   <li>Edge-awareness: nudges movement away from void edges in all states (not just combat)</li>
 * </ul>
 *
 * <p>All behaviors are parameter-driven via the bot's {@link DifficultyProfile}. No
 * case-by-case hardcoding.</p>
 */
public class SurvivalGuard {

    private final TrainerBot bot;

    // ── Stuck detection ──
    private int stuckDetectionCounter;
    private Location lastPositionSample;
    private int ticksSincePositionSample;

    // ── Spin detection ──
    private float[] yawHistory;
    private int yawHistoryHead;
    private int yawHistoryCount;
    private static final int YAW_HISTORY_SIZE = 10;

    // ── Void walk tracking ──
    private int consecutiveVoidWalkTicks;
    private final ClutchHandler clutchHandler;

    /**
     * Creates a new SurvivalGuard for the given bot.
     *
     * @param bot the owning trainer bot
     */
    public SurvivalGuard(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.stuckDetectionCounter = 0;
        this.lastPositionSample = null;
        this.ticksSincePositionSample = 0;
        this.yawHistory = new float[YAW_HISTORY_SIZE];
        this.yawHistoryHead = 0;
        this.yawHistoryCount = 0;
        this.consecutiveVoidWalkTicks = 0;
        this.clutchHandler = new ClutchHandler(bot);

    }

    /**
     * Ticks the survival guard. Must be called EVERY tick from {@link TrainerBot#tick()},
     * BEFORE the movement controller tick.
     *
     * <p>All checks are lightweight (no block scanning — uses cached data from
     * VoidDetector which runs on its own timer).</p>
     */
    public void tick() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null || entity.isDead()) return;

        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        VoidDetector vd = bot.getVoidDetector();
        DecisionEngine de = bot.getDecisionEngine();
        BotStateMachine sm = bot.getStateMachine();
        DifficultyProfile diff = bot.getDifficultyProfile();

        // ═══ 1. Void-walk prevention (generic movement-target validation) ═══
        tickVoidWalkPrevention(entity, mc, vd, de, sm);

        // ═══ 2. Stuck detection (anti-spin, anti-idle) ═══
        tickStuckDetection(entity, mc, de, sm);

        // ═══ 3. Spin detection ═══
        tickSpinDetection(mc, de);

        // ═══ 4. Edge-awareness movement adjustment ═══
        tickEdgeAwareness(entity, mc, vd, diff, sm);

        // ═══ 5. Fall clutch detection ═══
        if (entity.getVelocity().getY() < -0.4 && !org.twightlight.skywarstrainer.util.NMSHelper.isOnGround(entity)) {
            clutchHandler.attemptClutch();
        }

    }

    /**
     * Validates that the bot's current move target and forward path do not lead
     * into void. If the move target is over void and the bot is NOT bridging,
     * cancels the move target and triggers a decision interrupt.
     */
    private void tickVoidWalkPrevention(@Nonnull LivingEntity entity,
                                        @Nonnull MovementController mc,
                                        VoidDetector vd,
                                        DecisionEngine de,
                                        BotStateMachine sm) {
        if (vd == null) return;

        BotState currentState = (sm != null) ? sm.getCurrentState() : null;
        boolean isBridging = (currentState == BotState.BRIDGING);

        // Check if current move target is over void
        Location moveTarget = mc.getMoveTarget();
        if (moveTarget != null && !isBridging) {
            boolean hasBlocks = bot.getInventoryEngine() != null
                    && bot.getInventoryEngine().getBlockCounter().getTotalBlocks() > 0;

            if (vd.isVoidBelow(moveTarget) && !hasBlocks) {
                // Move target leads to void and bot has no blocks to bridge — cancel it
                mc.setMoveTarget(null);
                if (de != null) de.triggerInterrupt();
                consecutiveVoidWalkTicks = 0;
                DebugLogger.log(bot, "SurvivalGuard: Cancelled void-targeted move");
                return;
            }
        }

        // Check forward direction (next 2 blocks)
        if (!isBridging && mc.isMoving()) {
            Location botLoc = entity.getLocation();
            Vector forward = mc.getForwardDirection();
            boolean forwardVoid = false;

            for (int step = 1; step <= 2; step++) {
                Location checkLoc = botLoc.clone().add(
                        forward.getX() * step, 0, forward.getZ() * step);
                if (vd.isVoidBelow(checkLoc)) {
                    forwardVoid = true;
                    break;
                }
            }

            if (forwardVoid) {
                consecutiveVoidWalkTicks++;
                if (consecutiveVoidWalkTicks >= 3) {
                    // Bot has been walking toward void for 3+ ticks — stop it
                    mc.stopAll();
                    if (de != null) de.triggerInterrupt();
                    consecutiveVoidWalkTicks = 0;
                    DebugLogger.log(bot, "SurvivalGuard: Stopped forward void-walk");
                }
            } else {
                consecutiveVoidWalkTicks = 0;
            }
        } else {
            consecutiveVoidWalkTicks = 0;
        }
    }

    /**
     * Detects when the bot is stuck (hasn't moved significantly) and forces
     * re-evaluation or state reset.
     */
    private void tickStuckDetection(@Nonnull LivingEntity entity,
                                    @Nonnull MovementController mc,
                                    DecisionEngine de,
                                    BotStateMachine sm) {
        ticksSincePositionSample++;

        if (ticksSincePositionSample < 20) return;
        ticksSincePositionSample = 0;

        Location currentLoc = entity.getLocation();
        if (currentLoc == null) return;

        if (lastPositionSample != null) {
            // Check if we're in a state where being stationary is expected
            BotState currentState = (sm != null) ? sm.getCurrentState() : null;
            boolean stationaryOk = (currentState == BotState.IDLE
                    || currentState == BotState.ORGANIZING
                    || currentState == BotState.CONSUMING
                    || currentState == BotState.ENCHANTING);

            if (!stationaryOk) {
                double horizontalDist = MathUtil.horizontalDistance(currentLoc, lastPositionSample);
                if (horizontalDist < 0.3) {
                    stuckDetectionCounter++;

                    if (stuckDetectionCounter >= 6) {
                        // Stuck for 120+ ticks — nuclear option: force to IDLE
                        if (sm != null) {
                            sm.forceTransition(BotState.IDLE, "SurvivalGuard: stuck for 120+ ticks");
                        }
                        mc.stopAll();
                        stuckDetectionCounter = 0;
                        DebugLogger.log(bot, "SurvivalGuard: STUCK 120+ ticks, forcing IDLE");
                    } else if (stuckDetectionCounter >= 3) {
                        // Stuck for 60+ ticks — force re-evaluation
                        mc.stopAll();
                        if (de != null) de.triggerInterrupt();
                        DebugLogger.log(bot, "SurvivalGuard: stuck %d cycles, interrupting decision",
                                stuckDetectionCounter);
                    }
                } else {
                    // Bot is moving — reset counter
                    stuckDetectionCounter = 0;
                }
            } else {
                // In a stationary-ok state — don't count as stuck
                stuckDetectionCounter = 0;
            }
        }

        lastPositionSample = currentLoc.clone();
    }

    /**
     * Detects rapid yaw rotation (spinning) without a look target and stops it.
     */
    private void tickSpinDetection(@Nonnull MovementController mc,
                                   DecisionEngine de) {
        float currentYaw = mc.getCurrentYaw();

        // Store yaw in circular buffer
        yawHistory[yawHistoryHead] = currentYaw;
        yawHistoryHead = (yawHistoryHead + 1) % YAW_HISTORY_SIZE;
        if (yawHistoryCount < YAW_HISTORY_SIZE) {
            yawHistoryCount++;
        }

        // Need full history to detect spin
        if (yawHistoryCount < YAW_HISTORY_SIZE) return;

        // Only detect spin if there's no look target (legitimate tracking causes rotation)
        if (mc.getLookTarget() != null) return;

        // Compute total absolute yaw change over the window
        double totalYawChange = 0.0;
        for (int i = 1; i < YAW_HISTORY_SIZE; i++) {
            int prevIdx = (yawHistoryHead - 1 - i + YAW_HISTORY_SIZE) % YAW_HISTORY_SIZE;
            int currIdx = (yawHistoryHead - i + YAW_HISTORY_SIZE) % YAW_HISTORY_SIZE;
            double diff = MathUtil.angleDifference(yawHistory[prevIdx], yawHistory[currIdx]);
            totalYawChange += Math.abs(diff);
        }

        if (totalYawChange > 360.0) {
            // Spinning detected — cancel movement and set look forward
            mc.stopAll();
            if (de != null) de.triggerInterrupt();
            DebugLogger.log(bot, "SurvivalGuard: Spin detected (%.0f° in %d ticks), interrupting",
                    totalYawChange, YAW_HISTORY_SIZE);
            // Reset history to prevent re-triggering
            yawHistoryCount = 0;
        }
    }

    /**
     * Nudges movement away from void edges when the bot is near an edge.
     * The intensity of the nudge is scaled inversely by the bot's edge-knock skill:
     * higher skill bots are LESS scared of edges (they use edges tactically).
     */
    private void tickEdgeAwareness(@Nonnull LivingEntity entity,
                                   @Nonnull MovementController mc,
                                   VoidDetector vd,
                                   @Nonnull DifficultyProfile diff,
                                   BotStateMachine sm) {
        if (vd == null || !vd.isOnEdge()) return;

        // Scale edge avoidance: low-skill bots avoid edges more aggressively
        double edgeKnockSkill = diff.getEdgeKnockSkill();
        double avoidanceIntensity = 1.0 - edgeKnockSkill;

        // Don't nudge if avoidance is negligible (high-skill bots)
        if (avoidanceIntensity < 0.1) return;

        // Don't nudge during bridging (bridging inherently involves edges)
        BotState currentState = (sm != null) ? sm.getCurrentState() : null;
        if (currentState == BotState.BRIDGING) return;

        Float safeDirection = vd.getSafeDirection();
        if (safeDirection == null) return;

        // Apply a subtle velocity nudge away from the edge
        double nudgeStrength = 0.04 * avoidanceIntensity;
        double safeYawRad = Math.toRadians(safeDirection);
        Vector nudge = new Vector(
                -Math.sin(safeYawRad) * nudgeStrength,
                0,
                Math.cos(safeYawRad) * nudgeStrength
        );

        Vector currentVelocity = entity.getVelocity();
        entity.setVelocity(currentVelocity.add(nudge));
    }
}
