package org.twightlight.skywarstrainer.bridging.movement;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.twightlight.skywarstrainer.awareness.ThreatMap;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.movement.MovementController;
import org.twightlight.skywarstrainer.util.DebugLogger;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class BridgeMovementController {

    private final TrainerBot bot;
    private final BridgeMovementSelector selector;
    private BridgeMovementType activeMovementType;

    private final BridgeMovementDirective directive;

    // ── Bait Bridge State ──
    private int baitBlocksPlaced;
    private int baitWaitTicks;
    private boolean baitWaiting;
    private boolean baitTriggered;
    private int baitBlockThreshold; // FIX (Bug #3): store the threshold once

    // ── Jump Bridge State ──
    private int jumpBridgeCooldown;
    private boolean jumpBridgeAirborne;
    private boolean jumpBridgePendingPitchOverride; // FIX (Bug #2): deferred pitch flag

    // ── Safety Rail State ──
    private int blocksSinceLastRail;
    private int nextRailAt;
    private static final int RAIL_INTERVAL_MIN = 3;
    private static final int RAIL_INTERVAL_MAX = 5;

    // ── Stair Climb State ──
    private int stairsBuilt;
    private int targetStairs;

    public BridgeMovementController(@Nonnull TrainerBot bot) {
        this.bot = bot;
        this.selector = new BridgeMovementSelector();
        this.directive = new BridgeMovementDirective();
        this.activeMovementType = BridgeMovementType.SAFE_SNEAK;
        reset();
    }

    public void selectMovement(@Nullable Location destination) {
        this.activeMovementType = selector.selectMovement(bot, destination);
        reset();
        DebugLogger.log(bot, "Bridge movement selected: %s", activeMovementType.name());
    }

    @Nonnull
    public BridgeMovementDirective computeDirective() {
        directive.reset();
        directive.movementTypeName = activeMovementType.name();
        directive.speedMultiplier = activeMovementType.getSpeedMultiplier();
        directive.failRateMultiplier = activeMovementType.getFailRateMultiplier();

        switch (activeMovementType) {
            case JUMP_BRIDGE:
                computeJumpBridgeDirective();
                break;
            case STAIR_CLIMB:
                computeStairClimbDirective();
                break;
            case BAIT_BRIDGE:
                computeBaitBridgeDirective();
                break;
            case SPEED_SPRINT:
                computeSpeedSprintDirective();
                break;
            case SAFETY_RAIL:
                computeSafetyRailDirective();
                break;
            case SAFE_SNEAK:
            default:
                directive.requestSneak = true;
                break;
        }

        return directive;
    }

    public void postTick() {
        switch (activeMovementType) {
            case BAIT_BRIDGE:
                postTickBaitBridge();
                break;
            case JUMP_BRIDGE:
                postTickJumpBridge();
                break;
            default:
                break;
        }
    }

    // ─── Jump Bridge Directive ──────────────────────────────────

    private void computeJumpBridgeDirective() {
        if (jumpBridgePendingPitchOverride) {
            directive.pitchOverride = 75.0f;
            jumpBridgePendingPitchOverride = false;
        }

        if (jumpBridgeCooldown > 0) {
            jumpBridgeCooldown--;
            directive.requestSprint = true;
            return;
        }

        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        Location botLoc = entity.getLocation();
        MovementController mc = bot.getMovementController();
        if (mc == null) return;

        Block aheadBelow = botLoc.clone().add(
                mc.getForwardDirection().getX() * 0.6, -1,
                mc.getForwardDirection().getZ() * 0.6
        ).getBlock();
        Block below = botLoc.clone().add(0, -1, 0).getBlock();

        directive.requestSprint = true;
        directive.requestSneak = false;

        if (below.getType() != Material.AIR && aheadBelow.getType() == Material.AIR) {
            directive.requestJump = true;
            jumpBridgeAirborne = true;
            jumpBridgeCooldown = 8;

            DifficultyProfile diff = bot.getDifficultyProfile();
            double failRate = diff.getBridgeFailRate() * activeMovementType.getFailRateMultiplier();
            if (RandomUtil.chance(failRate)) {
                directive.pausePlacement = true;
                DebugLogger.log(bot, "Jump bridge: simulated miss");
            }
        }
    }

    private void postTickJumpBridge() {
        if (jumpBridgeAirborne) {
            LivingEntity entity = bot.getLivingEntity();
            if (entity != null && entity.getVelocity().getY() < 0) {
                // FIX (Bug #2): Set a flag so the NEXT computeDirective() applies the pitch
                jumpBridgePendingPitchOverride = true;
                jumpBridgeAirborne = false;
            }
        }
    }

    // ─── Stair Climb Directive ──────────────────────────────────

    private void computeStairClimbDirective() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        if (entity.isOnGround() && stairsBuilt < targetStairs) {
            DifficultyProfile diff = bot.getDifficultyProfile();
            if (RandomUtil.chance(diff.getStairBridgeSkill())) {
                directive.requestJump = true;
                directive.pitchOverride = 90.0f;
                stairsBuilt++;
            } else {
                DebugLogger.log(bot, "Stair climb: missed step %d/%d", stairsBuilt, targetStairs);
            }
        }
    }

    // ─── Bait Bridge Directive ──────────────────────────────────

    private void computeBaitBridgeDirective() {
        if (baitWaiting) {
            directive.pausePlacement = true;
            directive.requestSneak = true;
            directive.requestSprint = false;
        } else if (baitBlocksPlaced >= baitBlockThreshold && !baitTriggered) {
            // FIX (Bug #3): compare against the stored threshold, not a new random each tick
            baitWaiting = true;
            baitWaitTicks = RandomUtil.nextInt(20, 40);
            directive.pausePlacement = true;
            DebugLogger.log(bot, "Bait bridge: placed %d blocks (threshold %d), waiting %d ticks",
                    baitBlocksPlaced, baitBlockThreshold, baitWaitTicks);
        }
    }

    private void postTickBaitBridge() {
        if (!baitWaiting) return;

        baitWaitTicks--;

        ThreatMap threatMap = bot.getThreatMap();
        if (threatMap != null) {
            ThreatMap.ThreatEntry nearest = threatMap.getNearestThreat();
            if (nearest != null && nearest.currentPosition != null) {
                Location botLoc = bot.getLocation();
                if (botLoc != null) {
                    double dist = botLoc.distance(nearest.currentPosition);
                    if (dist < 10 && nearest.getHorizontalSpeed() > 0.1) {
                        DebugLogger.log(bot, "Bait bridge: enemy committed! dist=%.1f", dist);
                        baitTriggered = true;
                        baitWaiting = false;
                        return;
                    }
                }
            }
        }

        if (baitWaitTicks <= 0) {
            baitWaiting = false;
            baitTriggered = false;
            activeMovementType = BridgeMovementType.SAFE_SNEAK;
            DebugLogger.log(bot, "Bait bridge: enemy didn't commit, resuming");
        }
    }

    // ─── Speed Sprint Directive ─────────────────────────────────

    private void computeSpeedSprintDirective() {
        LivingEntity entity = bot.getLivingEntity();
        if (entity == null) return;

        Location botLoc = entity.getLocation();
        double xFrac = botLoc.getX() - Math.floor(botLoc.getX());
        double zFrac = botLoc.getZ() - Math.floor(botLoc.getZ());
        boolean nearEdge = (xFrac > 0.7 || xFrac < 0.3) || (zFrac > 0.7 || zFrac < 0.3);

        if (nearEdge) {
            directive.requestSneak = true;
            directive.requestSprint = false;
        } else {
            directive.requestSneak = false;
            directive.requestSprint = true;
        }
    }

    // ─── Safety Rail Directive ──────────────────────────────────

    private void computeSafetyRailDirective() {
        blocksSinceLastRail++;
        if (blocksSinceLastRail >= nextRailAt) {
            blocksSinceLastRail = 0;
            nextRailAt = RandomUtil.nextInt(RAIL_INTERVAL_MIN, RAIL_INTERVAL_MAX);
            directive.placeSideBlock = true;
            directive.sideBlockDirection = RandomUtil.nextBoolean() ? 1 : -1;
        }
        directive.requestSneak = true;
    }

    // ─── State Management ───────────────────────────────────────

    public void reset() {
        directive.reset();
        baitBlocksPlaced = 0;
        baitWaitTicks = 0;
        baitWaiting = false;
        baitTriggered = false;
        baitBlockThreshold = RandomUtil.nextInt(1, 3);
        jumpBridgeCooldown = 0;
        jumpBridgeAirborne = false;
        jumpBridgePendingPitchOverride = false;
        blocksSinceLastRail = 0;
        nextRailAt = RandomUtil.nextInt(RAIL_INTERVAL_MIN, RAIL_INTERVAL_MAX);
        stairsBuilt = 0;
        targetStairs = RandomUtil.nextInt(3, 8);
    }

    public void onBlockPlaced() {
        if (activeMovementType == BridgeMovementType.BAIT_BRIDGE) {
            baitBlocksPlaced++;
        }
    }

    // ─── Accessors ──────────────────────────────────────────────

    @Nonnull public BridgeMovementType getActiveMovementType() {
        return activeMovementType != null ? activeMovementType : BridgeMovementType.SAFE_SNEAK;
    }

    public boolean isBaitWaiting() { return baitWaiting; }
    public boolean isBaitTriggered() { return baitTriggered; }
    @Nonnull public BridgeMovementSelector getSelector() { return selector; }
    @Nonnull public BridgeMovementDirective getLastDirective() { return directive; }
}
