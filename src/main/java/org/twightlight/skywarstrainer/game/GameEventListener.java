package org.twightlight.skywarstrainer.game;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.twightlight.skywars.api.event.game.SkyWarsChestRefillEvent;
import org.twightlight.skywars.api.event.game.SkyWarsDoomEvent;
import org.twightlight.skywars.api.event.game.SkyWarsGameEndEvent;
import org.twightlight.skywars.api.event.game.SkyWarsGameStartEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerDeathEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerJoinEvent;
import org.twightlight.skywars.api.event.player.SkyWarsPlayerQuitEvent;
import org.twightlight.skywars.api.server.SkyWarsServer;
import org.twightlight.skywars.arena.Arena;
import org.twightlight.skywarstrainer.SkyWarsTrainer;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.ai.learning.LearningEngine;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

/**
 * Listens to both LostSkyWars events and standard Bukkit events to keep
 * bots synchronized with game state.
 */
public class GameEventListener implements Listener {

    private final SkyWarsTrainer plugin;

    public GameEventListener(@Nonnull SkyWarsTrainer plugin) {
        this.plugin = plugin;
    }

    // ═════════════════════════════════════════════════════════════
    //  LostSkyWars Game Events
    // ═════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameStart(SkyWarsGameStartEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        List<TrainerBot> bots = plugin.getBotManager().getBotsInArena(arena);
        if (bots.isEmpty()) return;

        plugin.getLogger().info("[GameHook] Game started in " + arena.getServerName()
                + " with " + bots.size() + " bot(s).");

        for (TrainerBot bot : bots) {
            try {
                bot.getProfile().addGamePlayed();
                BotChatManager.sendChatMessage(bot, "game_start");
                DecisionEngine de = bot.getDecisionEngine();
                if (de != null) {
                    de.triggerInterrupt();
                }
                LearningEngine lm = bot.getLearningEngine();
                if (lm != null) {
                    lm.onGameStart();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error notifying bot of game start: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a SkyWars game ends.
     *
     * <p>[FIX 2.4] The winner check is now computed once into a {@code boolean won}
     * variable, then used for both the profile/chat update and the learning engine call.
     * Previously it was duplicated.</p>
     *
     * <p>[FIX 6.3] Call bot.getProfile().addGameForLearning() when the learning engine
     * processes the game result.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameEnd(SkyWarsGameEndEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        List<TrainerBot> bots = plugin.getBotManager().getBotsInArena(arena);
        if (bots.isEmpty()) return;

        plugin.getLogger().info("[GameHook] Game ended in " + arena.getServerName()
                + ", cleaning up " + bots.size() + " bot(s).");

        for (TrainerBot bot : bots) {
            try {
                // [FIX 2.4] Compute winner status once
                boolean won = false;
                if (event.hasWinner() && event.getWinnerTeam() != null) {
                    Player botPlayer = bot.getPlayerEntity();
                    if (botPlayer != null && event.getWinnerTeam().hasMember(botPlayer)) {
                        won = true;
                    }
                }

                // Profile and chat update
                if (won) {
                    bot.getProfile().addGameWon();
                    BotChatManager.sendChatMessage(bot, "win");
                }

                // Learning engine notification
                LearningEngine lm = bot.getLearningEngine();
                if (lm != null) {
                    lm.onGameEnd(won, bot.getProfile().getKills(), bot.getProfile().getDeaths(), 1.0);
                    // [FIX 6.3] Increment learning game counter
                    bot.getProfile().addGameForLearning();
                }

                plugin.getBotManager().removeBot(bot);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error cleaning up bot on game end: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a player dies in LostSkyWars.
     *
     * <p>[FIX 2.3] The "first_kill" chat message now only fires when
     * {@code killerBot.getProfile().getKills() == 1} (after addKill()).
     * Subsequent kills send a "kill" message type instead.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(SkyWarsPlayerDeathEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        Player dead = event.getPlayer();
        Player killer = event.getKiller();
        BotManager botManager = plugin.getBotManager();

        // Check if the dead entity is a bot
        if (dead != null) {
            TrainerBot deadBot = botManager.getBotByEntityUUID(dead.getUniqueId());
            if (deadBot != null) {
                deadBot.getProfile().addDeath();
                BotChatManager.sendChatMessage(deadBot, "death");
                Bukkit.getPluginManager().callEvent(
                        new org.twightlight.skywarstrainer.api.events.BotDeathEvent(deadBot, killer));
                LearningEngine lmDead = deadBot.getLearningEngine();
                if (lmDead != null) {
                    lmDead.onSignificantEvent("death", 1.0);
                }
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("[GameHook] Bot died: " + deadBot.getName()
                            + (killer != null ? " killed by " + killer.getName() : ""));
                }
            }
        }

        // Check if the killer is a bot
        if (killer != null) {
            TrainerBot killerBot = botManager.getBotByEntityUUID(killer.getUniqueId());
            if (killerBot != null) {
                killerBot.getProfile().addKill();

                // [FIX 2.3] Only send "first_kill" for the actual first kill
                if (killerBot.getProfile().getKills() == 1) {
                    BotChatManager.sendChatMessage(killerBot, "first_kill");
                } else {
                    BotChatManager.sendChatMessage(killerBot, "kill");
                }

                LearningEngine lmKiller = killerBot.getLearningEngine();
                if (lmKiller != null) {
                    lmKiller.onSignificantEvent("kill", 1.0);
                }
                Bukkit.getPluginManager().callEvent(
                        new org.twightlight.skywarstrainer.api.events.BotKillPlayerEvent(killerBot, dead));
            }
        }

        // Trigger interrupt for all bots in the arena (player count changed)
        for (TrainerBot bot : botManager.getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(SkyWarsPlayerJoinEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(SkyWarsPlayerQuitEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChestRefill(SkyWarsChestRefillEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            if (bot.getChestLocator() != null) {
                bot.getChestLocator().markAllUnlooted();
            }
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDoom(SkyWarsDoomEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            DecisionEngine de = bot.getDecisionEngine();
            if (de != null) {
                de.triggerInterrupt();
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Standard Bukkit Combat Events
    // ═════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        BotManager botManager = plugin.getBotManager();

        Entity actualDamager = damager;
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Entity) {
                actualDamager = (Entity) projectile.getShooter();
            }
        }

        if (victim instanceof LivingEntity) {
            TrainerBot victimBot = botManager.getBotByEntityUUID(victim.getUniqueId());
            if (victimBot != null) {
                if (victimBot.getCombatEngine() != null) {
                    LivingEntity attackerEntity = (actualDamager instanceof LivingEntity)
                            ? (LivingEntity) actualDamager : null;
                    victimBot.getCombatEngine().onBotHit(attackerEntity);
                    LearningEngine lmVictim = victimBot.getLearningEngine();
                    if (lmVictim != null) {
                        lmVictim.onSignificantEvent("health_lost", event.getDamage() / 2.0);
                    }
                }
                DecisionEngine de = victimBot.getDecisionEngine();
                if (de != null) {
                    de.triggerInterrupt();
                }
            }
        }

        if (actualDamager instanceof LivingEntity) {
            TrainerBot damagerBot = botManager.getBotByEntityUUID(actualDamager.getUniqueId());
            if (damagerBot != null && damagerBot.getCombatEngine() != null) {
                damagerBot.getCombatEngine().getComboTracker().onHitLanded();
                LearningEngine lmDamager = damagerBot.getLearningEngine();
                if (lmDamager != null) {
                    lmDamager.onSignificantEvent("health_lost", -event.getDamage() / 2.0);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;

        TrainerBot bot = plugin.getBotManager().getBotByEntityUUID(
                event.getEntity().getUniqueId());
        if (bot == null) return;

        DecisionEngine de = bot.getDecisionEngine();
        if (de != null) {
            de.triggerInterrupt();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Entity)) return;

        Entity shooter = (Entity) projectile.getShooter();
        TrainerBot bot = plugin.getBotManager().getBotByEntityUUID(shooter.getUniqueId());
        if (bot == null) return;

        if (bot.getCombatEngine() != null) {
            bot.getCombatEngine().getProjectileHandler().onProjectileHit(projectile);
        }
    }
}
