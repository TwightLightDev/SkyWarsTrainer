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
import org.twightlight.skywarstrainer.SkyWarsTrainerPlugin;
import org.twightlight.skywarstrainer.ai.decision.DecisionEngine;
import org.twightlight.skywarstrainer.bot.BotManager;
import org.twightlight.skywarstrainer.bot.TrainerBot;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

/**
 * Listens to both LostSkyWars events and standard Bukkit events to keep
 * bots synchronized with game state.
 *
 * <p>This listener handles:
 * <ul>
 *   <li>LostSkyWars game lifecycle events (start, end, death, join, quit, refill, doom)</li>
 *   <li>Combat events (damage dealt to/from bots)</li>
 *   <li>Interrupt triggers for the bot decision engine</li>
 *   <li>Bot death cleanup</li>
 * </ul></p>
 */
public class GameEventListener implements Listener {

    private final SkyWarsTrainerPlugin plugin;

    /**
     * Creates a new GameEventListener.
     *
     * @param plugin the owning plugin
     */
    public GameEventListener(@Nonnull SkyWarsTrainerPlugin plugin) {
        this.plugin = plugin;
    }

    // ═════════════════════════════════════════════════════════════
    //  LostSkyWars Game Events
    // ═════════════════════════════════════════════════════════════

    /**
     * Called when a SkyWars game starts (cages open, game timer begins).
     * Notifies all bots in the arena to begin their opening strategy.
     */
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
                // Update game state to grace period (initial damage immunity)
                bot.getProfile().addGamePlayed();

                // Trigger decision engine interrupt to start AI
                if (bot.getDecisionEngine() != null) {
                    bot.getDecisionEngine().triggerInterrupt();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error notifying bot of game start: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a SkyWars game ends (winner determined or draw).
     * Cleans up all bots in the arena.
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
                // Check if this bot won
                if (event.hasWinner() && event.getWinnerTeam() != null) {
                    Player botPlayer = bot.getPlayerEntity();
                    if (botPlayer != null && event.getWinnerTeam().hasMember(botPlayer)) {
                        bot.getProfile().addGameWon();
                    }
                }
                // Clean up: bots will be removed when arena resets
                plugin.getBotManager().removeBot(bot);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "Error cleaning up bot on game end: " + bot.getName(), e);
            }
        }
    }

    /**
     * Called when a player dies in LostSkyWars.
     * If the dead player is a bot, handles bot death. If a bot was the killer,
     * updates the bot's stats. Also triggers interrupts for nearby bots.
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
        TrainerBot deadBot = botManager.getBotByEntityUuid(dead.getUniqueId());
        if (deadBot != null) {
            deadBot.getProfile().addDeath();
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("[GameHook] Bot died: " + deadBot.getName()
                        + (killer != null ? " killed by " + killer.getName() : ""));
            }
        }

        // Check if the killer is a bot
        if (killer != null) {
            TrainerBot killerBot = botManager.getBotByEntityUuid(killer.getUniqueId());
            if (killerBot != null) {
                killerBot.getProfile().addKill();
                // Fire custom event
                Bukkit.getPluginManager().callEvent(
                        new org.twightlight.skywarstrainer.api.events.BotKillPlayerEvent(killerBot, dead));
            }
        }

        // Trigger interrupt for all bots in the arena (player count changed)
        for (TrainerBot bot : botManager.getBotsInArena(arena)) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().triggerInterrupt();
            }
        }
    }

    /**
     * Called when a player joins a SkyWars arena.
     * Triggers interrupt for bots in that arena.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(SkyWarsPlayerJoinEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().triggerInterrupt();
            }
        }
    }

    /**
     * Called when a player quits a SkyWars arena.
     * Triggers interrupt for bots in that arena.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(SkyWarsPlayerQuitEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().triggerInterrupt();
            }
        }
    }

    /**
     * Called when chests are refilled. Bots reset their chest memory for those chests.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChestRefill(SkyWarsChestRefillEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            // Reset chest memory — chests have new loot
            if (bot.getChestLocator() != null) {
                bot.getChestLocator().markAllUnlooted();
            }
            // Trigger interrupt to re-evaluate looting
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().triggerInterrupt();
            }
        }
    }

    /**
     * Called when doom (deathmatch) event activates in an arena.
     * All bots switch to maximum aggression.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDoom(SkyWarsDoomEvent event) {
        SkyWarsServer server = event.getServer();
        if (!(server instanceof Arena)) return;
        Arena<?> arena = (Arena<?>) server;

        for (TrainerBot bot : plugin.getBotManager().getBotsInArena(arena)) {
            if (bot.getDecisionEngine() != null) {
                bot.getDecisionEngine().triggerInterrupt();
            }
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  Standard Bukkit Combat Events
    // ═════════════════════════════════════════════════════════════

    /**
     * Handles damage dealt to or by bots. Triggers combat interrupts
     * and updates combo tracking.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        BotManager botManager = plugin.getBotManager();

        // Resolve actual damager (for projectiles, get the shooter)
        Entity actualDamager = damager;
        if (damager instanceof Projectile) {
            Projectile projectile = (Projectile) damager;
            if (projectile.getShooter() instanceof Entity) {
                actualDamager = (Entity) projectile.getShooter();
            }
        }

        // Case 1: Bot was hit
        if (victim instanceof LivingEntity) {
            TrainerBot victimBot = botManager.getBotByEntityUuid(victim.getUniqueId());
            if (victimBot != null) {
                // Notify combat engine
                if (victimBot.getCombatEngine() != null) {
                    LivingEntity attackerEntity = (actualDamager instanceof LivingEntity)
                            ? (LivingEntity) actualDamager : null;
                    victimBot.getCombatEngine().onBotHit(attackerEntity, event.getDamage());
                }
                // Trigger interrupt — the bot is being attacked
                if (victimBot.getDecisionEngine() != null) {
                    victimBot.getDecisionEngine().triggerInterrupt();
                }
            }
        }

        // Case 2: Bot dealt damage
        if (actualDamager instanceof LivingEntity) {
            TrainerBot damagerBot = botManager.getBotByEntityUuid(actualDamager.getUniqueId());
            if (damagerBot != null && damagerBot.getCombatEngine() != null) {
                damagerBot.getCombatEngine().getComboTracker().onHitLanded();
            }
        }
    }

    /**
     * Handles any damage taken by bots (not just entity damage).
     * This catches environmental damage (fire, lava, void, fall).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity)) return;

        TrainerBot bot = plugin.getBotManager().getBotByEntityUuid(
                event.getEntity().getUniqueId());
        if (bot == null) return;

        // Trigger interrupt on any damage
        if (bot.getDecisionEngine() != null) {
            bot.getDecisionEngine().triggerInterrupt();
        }
    }

    /**
     * Handles projectile hit events for bot-fired projectiles.
     * Used for tracking fishing rod hits, snowball hits, etc.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Entity)) return;

        Entity shooter = (Entity) projectile.getShooter();
        TrainerBot bot = plugin.getBotManager().getBotByEntityUuid(shooter.getUniqueId());
        if (bot == null) return;

        // Notify the projectile handler
        if (bot.getCombatEngine() != null) {
            bot.getCombatEngine().getProjectileHandler().onProjectileHit(projectile);
        }
    }
}
