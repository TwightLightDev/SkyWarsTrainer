package org.twightlight.skywarstrainer.combat.strategies;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.twightlight.skywarstrainer.bot.TrainerBot;
import org.twightlight.skywarstrainer.config.DifficultyConfig.DifficultyProfile;
import org.twightlight.skywarstrainer.util.NMSHelper;
import org.twightlight.skywarstrainer.util.PacketUtil;
import org.twightlight.skywarstrainer.util.RandomUtil;

import javax.annotation.Nonnull;

/**
 * Block-hitting: Right-click (block with sword) between swings to reduce incoming
 * damage while still attacking.
 *
 * <p>This is a 1.8-specific technique where players block with their sword for
 * 2-4 ticks between swings, reducing incoming damage by 50% during the block
 * frames. The cycle is: block → unblock → hit → block.</p>
 *
 * <p>At EXPERT difficulty (blockHitChance=0.7), the bot performs frame-perfect
 * block-hitting. At BEGINNER (0.0), it never attempts it.</p>
 */
public class BlockHitStrategy implements CombatStrategy {

    /**
     * Phase of the block-hit cycle.
     */
    private enum Phase {
        /** Currently blocking (right-click held). */
        BLOCKING,
        /** Brief unblock window for the attack. */
        UNBLOCKING,
        /** Attack swing then return to blocking. */
        ATTACKING
    }

    private Phase currentPhase;

    /** Ticks remaining in the current phase. */
    private int phaseTimer;

    /** Whether the strategy is currently engaged in a block-hit cycle. */
    private boolean cycling;

    @Nonnull
    @Override
    public String getName() {
        return "BlockHit";
    }

    @Override
    public boolean shouldActivate(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        if (diff.getBlockHitChance() <= 0.0) return false;

        LivingEntity botEntity = bot.getLivingEntity();
        if (botEntity == null || botEntity.isDead()) return false;

        // Only block-hit in melee range
        Player player = bot.getPlayerEntity();
        if (player == null) return false;

        // Must have a sword to block with (1.8 mechanic)
        org.bukkit.inventory.ItemStack hand = player.getItemInHand();
        if (hand == null) return false;
        String typeName = hand.getType().name();
        if (!typeName.contains("SWORD")) return false;

        // Roll the block-hit chance
        return RandomUtil.chance(diff.getBlockHitChance() * 0.5);
    }

    @Override
    public void execute(@Nonnull TrainerBot bot) {
        Player player = bot.getPlayerEntity();
        if (player == null) return;

        DifficultyProfile diff = bot.getDifficultyProfile();

        switch (currentPhase) {
            case BLOCKING:
                // Simulate right-click block (in 1.8, holding right-click with sword blocks)
                NMSHelper.useItem(player, true);
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.UNBLOCKING;
                    // Unblock for 1-2 ticks
                    phaseTimer = RandomUtil.nextInt(1, 2);
                }
                break;

            case UNBLOCKING:
                NMSHelper.useItem(player, false);
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.ATTACKING;
                    phaseTimer = 1;
                }
                break;

            case ATTACKING:
                // The actual attack is handled by ClickController.
                // We just ensure blocking is off for the swing.
                NMSHelper.useItem(player, false);
                PacketUtil.playArmSwing(player);
                phaseTimer--;
                if (phaseTimer <= 0) {
                    currentPhase = Phase.BLOCKING;
                    // Block duration: 2-4 ticks based on difficulty
                    int maxBlock = (int) Math.round(4.0 - 2.0 * diff.getDifficulty().asFraction());
                    phaseTimer = RandomUtil.nextInt(2, Math.max(2, maxBlock));
                }
                break;
        }
    }

    @Override
    public double getPriority(@Nonnull TrainerBot bot) {
        DifficultyProfile diff = bot.getDifficultyProfile();
        // Block-hitting is a defensive technique — moderate priority
        return 4.0 * diff.getBlockHitChance();
    }

    @Override
    public void reset() {
        currentPhase = Phase.BLOCKING;
        phaseTimer = 3;
        cycling = false;

        // Ensure blocking is reset (will be applied next tick via execute)
    }
}

