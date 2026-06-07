package dev.sakura.client.module.impl.combat;

import com.mojang.authlib.GameProfile;
import dev.sakura.client.SakuraClient;
import dev.sakura.client.event.EventHandler;
import dev.sakura.client.event.impl.client.TickEvent;
import dev.sakura.client.event.impl.packet.PacketEvent;
import dev.sakura.client.event.impl.player.PlayerTickEvent;
import dev.sakura.client.event.type.EventType;
import dev.sakura.client.module.Category;
import dev.sakura.client.module.Module;
import dev.sakura.client.values.impl.EnumValue;
import dev.sakura.client.values.impl.NumberValue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.scoreboard.Team;
import net.minecraft.world.GameMode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AntiBot extends Module {

    public enum BotMode {
        Heypixel,
        Cubecraft,
        Hypixel
    }

    public AntiBot() {
        super("AntiBot", "防假人", Category.Combat);
    }

    private final EnumValue<BotMode> modePick = new EnumValue<>("Mode", "模式", BotMode.Heypixel);
    private final NumberValue<Double> respawnWindowMs = new NumberValue<>("Respawn Time", "重生时间", 2500.0, 0.0, 10000.0, 100.0, () -> modePick.is(BotMode.Heypixel));

    private static final Map<UUID, String> cachedNames = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> spawnWatch = new ConcurrentHashMap<>();
    private static final Set<Integer> botIds = new HashSet<>();
    private static final Map<UUID, Long> respawnStamp = new ConcurrentHashMap<>();

    public static boolean isBedWarsBot(Entity entity) {
        AntiBot module = SakuraClient.MODULES.getModule(AntiBot.class);
        if (module.respawnWindowMs.get() < 1.0F) {
            return false;
        } else {
            return respawnStamp.containsKey(entity.getUuid()) && (float) (System.currentTimeMillis() - respawnStamp.get(entity.getUuid())) < module.respawnWindowMs.get();
        }
    }

    public static boolean isBot(Entity entity) {
        AntiBot module = SakuraClient.MODULES.getModule(AntiBot.class);
        if (!module.isEnabled()) return false;

        return switch (module.modePick.get()) {
            case Heypixel -> isHeypixelBot(entity);
            case Cubecraft -> isCubecraftBot(entity);
            case Hypixel -> isHypixelBot(entity);
        };
    }

    private static boolean isHeypixelBot(Entity entity) {
        return botIds.contains(entity.getId()) || !MinecraftClient.getInstance().getNetworkHandler().getPlayerUuids().contains(entity.getUuid());
    }

    private static boolean isCubecraftBot(Entity entity) {
        if (entity instanceof PlayerEntity player) {
            if (MinecraftClient.getInstance().getNetworkHandler() == null) return false;
            final PlayerListEntry playerListEntry = MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (playerListEntry == null || playerListEntry.getProfile() == null) {
                return true;
            }
            final Team scoreboardTeam = playerListEntry.getScoreboardTeam();


            return scoreboardTeam == null || scoreboardTeam.getName().equals(player.getName().getString());
        }
        return false;
    }

    private static boolean isHypixelBot(Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)) return false;

        if (livingEntity instanceof ArmorStandEntity) {
            return true;
        }

        if (livingEntity.getId() == -1234) {
            return true;
        }

        if (livingEntity instanceof PlayerEntity player) {
            if (MinecraftClient.getInstance().getNetworkHandler() == null) return false;
            final PlayerListEntry playerListEntry = MinecraftClient.getInstance().getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (playerListEntry == null || playerListEntry.getProfile() == null) {
                return true;
            }


            if (playerListEntry.getLatency() > 1 && player.getHealth() > 14 && player.getHealth() < 20 && player.isInvisible()) {
                return true;
            }

            final UUID uuid = player.getUuid();
            if (uuid.version() == 2) {
                return true;
            }
        } else {

            if (livingEntity.getUuid().version() != 4) {
                return true;
            }
        }

        return false;
    }

    @EventHandler
    public void trackBedwarsRespawns(PacketEvent e) {
        if (nullPointCheck()) return;

        if (e.getType() == EventType.RECEIVE) {
            if (e.getPacket() instanceof PlayerListS2CPacket packet) {
                if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                    for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                        GameProfile profile = entry.profile();
                        if (profile == null) return;
                        UUID id = profile.id();
                        respawnStamp.put(id, System.currentTimeMillis());
                    }
                }
            } else if (e.getPacket() instanceof EntityAnimationS2CPacket packet) {
                Entity entity = mc.world.getEntityById(packet.getEntityId());
                if (entity != null && packet.getAnimationId() == 0) {
                    respawnStamp.remove(entity.getUuid());
                }
            }
        }
    }

    @EventHandler
    public void clearSpawnTrackerOnJoin(PlayerTickEvent event) {
        if (mc.player.age <= 1) {

            cachedNames.clear();
            botIds.clear();
            spawnWatch.clear();
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        for (Map.Entry<UUID, Long> entry : spawnWatch.entrySet()) {
            if (System.currentTimeMillis() - entry.getValue() > 500L) {
                spawnWatch.remove(entry.getKey());
            }
        }
    }

    @EventHandler
    public void onPacket(PacketEvent event) {
        if (event.getType() == EventType.RECEIVE) {
            if (event.getPacket() instanceof PlayerListS2CPacket packet) {
                if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                    for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
                        if (entry.profile() != null && entry.displayName() != null && entry.displayName().getSiblings().isEmpty() && entry.gameMode() == GameMode.SURVIVAL) {
                            UUID uuid = entry.profile().id();
                            spawnWatch.put(uuid, System.currentTimeMillis());
                            cachedNames.put(uuid, entry.displayName().getString());
                        }
                    }
                }
            } else if (event.getPacket() instanceof EntitySpawnS2CPacket packet && packet.getEntityType() == EntityType.PLAYER) {
                UUID playerId = packet.getUuid();
                if (spawnWatch.containsKey(playerId)) {
                    spawnWatch.remove(playerId);
                    botIds.add(packet.getEntityId());
                }
            } else if (event.getPacket() instanceof EntitiesDestroyS2CPacket packet) {

                for (Integer entityId : packet.getEntityIds()) {
                    if (botIds.contains(entityId)) {
                        botIds.remove(entityId);
                    }
                }
            }
        }
    }
}
