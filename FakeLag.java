package dev.sakura.client.module.impl.combat;

import dev.sakura.client.SakuraClient;
import dev.sakura.client.event.EventHandler;
import dev.sakura.client.event.impl.client.TickEvent;
import dev.sakura.client.event.impl.packet.PacketEvent;
import dev.sakura.client.event.type.EventType;
import shit.mixin.accessors.ExplosionPacketAccessor;
import dev.sakura.client.module.Category;
import dev.sakura.client.module.Module;
import dev.sakura.client.module.impl.client.Teams;
import dev.sakura.client.module.impl.movement.Scaffold;
import dev.sakura.client.utils.player.PacketUtil;
import dev.sakura.client.utils.time.TimerUtil;
import dev.sakura.client.values.impl.*;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.awt.*;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

public class FakeLag extends Module {

    private final NumberValue<Float> rangeCap = new NumberValue<>("Max Range", "最大范围", 3.0f, 0.0f, 10.0f, 0.1f);
    private final NumberValue<Integer> delayA = new NumberValue<>("Min Delay", "最小延迟", 300, 0, 1000, 10);
    private final NumberValue<Integer> delayB = new NumberValue<>("Max Delay", "最大延迟", 600, 0, 1000, 10);
    private final NumberValue<Integer> kickWindow = new NumberValue<>("Recoil Time", "后坐力时间", 250, 0, 1000, 10);

    private enum LagReleaseMode {
        CONSTANT, ADAPTIVE
    }

    private final EnumValue<LagReleaseMode> modeFlux = new EnumValue<>("Mode", "模式", LagReleaseMode.ADAPTIVE);

    private final BooleanValue flushHit = new BooleanValue("EntityInteract", "实体交互", true);
    private final BooleanValue flushBlock = new BooleanValue("BlockInteract", "方块交互", true);
    private final BooleanValue flushMove = new BooleanValue("Action", "动作", true);
    private final MultiBooleanValue flushPack = new MultiBooleanValue("Flush On", "刷新时机", Arrays.asList(flushHit, flushBlock, flushMove));
    private final BooleanValue showGhost = new BooleanValue("Render", "渲染", true);
    private final ColorValue ghostColor = new ColorValue("Render Color", "渲染颜色", new Color(255, 255, 255), showGhost::get);

    private final BooleanValue pulseMode = new BooleanValue("Pulse Flush", "脉冲释放", true);
    private final NumberValue<Integer> pulseStep = new NumberValue<>("Pulse Speed", "脉冲速度", 1, 1, 10, 1, pulseMode::get);

    private final NumberValue<Integer> packetFloor = new NumberValue<>("Min Packet Size", "最小包大小", 5, 1, 20, 1, pulseMode::get);

    private final Queue<Packet<?>> holdQueue = new ConcurrentLinkedQueue<>();
    private static final boolean zkm$keepPacketOrder = true;
    private final TimerUtil lagTimer = new TimerUtil();
    private long nextFlushMs = 0;
    private boolean enemyMemo = false;
    private Vec3d serverGhostPos = null;
    private Vec3d renderGhostPos = null;
    private boolean burstFlag = false;

    public FakeLag() {
        super("FakeLag", "假延迟", Category.Combat);
    }

    @Override
    public void onEnable() {
        holdQueue.clear();
        lagTimer.reset();
        nextFlushMs = rollReleaseDelay();
        enemyMemo = false;
        serverGhostPos = null;
        renderGhostPos = null;
    }

    @Override
    public void onDisable() {
        releaseQueuedPackets();
        enemyMemo = false;
        renderGhostPos = null;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (nullPointCheck()) return;


        if (serverGhostPos == null && !holdQueue.isEmpty()) {




        }

        if (modeFlux.is(LagReleaseMode.ADAPTIVE)) {
            if (pulseMode.get()) {
                double currentDistance = 0;
                if (serverGhostPos != null) {
                    currentDistance = mc.player.getEntityPos().distanceTo(serverGhostPos);
                }

                float range = ((Number) rangeCap.get()).floatValue();

                if (currentDistance > range) {
                    burstFlag = true;
                }

                if (burstFlag) {
                    releasePacketPulse();
                    if (currentDistance > range * 1.5) {
                        releasePacketPulse();
                    }
                    if (currentDistance < 0.5 || holdQueue.isEmpty()) {
                        burstFlag = false;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPacket(PacketEvent event) {
        if (nullPointCheck()) return;


        boolean scaffoldEnabled = SakuraClient.MODULES.getModule(Scaffold.class).isEnabled();
        boolean isAttacking = mc.options.attackKey.isPressed();
        if (scaffoldEnabled || isAttacking) {
            releaseQueuedPackets();
            return;
        }

        Packet<?> packet = event.getPacket();


        if (event.getType() == EventType.RECEIVE) {
            if (packet instanceof PlayerPositionLookS2CPacket) {
                releaseQueuedPackets();
                lagTimer.reset();
            } else if (packet instanceof ResourcePackSendS2CPacket) {
                releaseQueuedPackets();
                lagTimer.reset();
            } else if (packet instanceof EntityVelocityUpdateS2CPacket) {
                EntityVelocityUpdateS2CPacket velocityPacket = (EntityVelocityUpdateS2CPacket) packet;
                if (velocityPacket.getEntityId() == mc.player.getId() && (velocityPacket.getVelocity().getX() != 0 || velocityPacket.getVelocity().getY() != 0 || velocityPacket.getVelocity().getZ() != 0)) {
                    releaseQueuedPackets();
                    lagTimer.reset();
                }
            } else if (packet instanceof ExplosionS2CPacket) {
                ExplosionPacketAccessor explosionPacket = (ExplosionPacketAccessor) packet;
                Vec3d knockback = explosionPacket.getPlayerKnockback().orElse(Vec3d.ZERO);
                if (knockback != Vec3d.ZERO) {
                    releaseQueuedPackets();
                    lagTimer.reset();
                }
            } else if (packet instanceof HealthUpdateS2CPacket) {
                releaseQueuedPackets();
                lagTimer.reset();
            }
            return;
        }

        if (event.getType() == EventType.SEND) {
            if (PacketUtil.bypassPackets.contains(packet)) {
                return;
            }





















            if (packet instanceof RequestCommandCompletionsC2SPacket) {
                return;
            }

            if (mc.player.isDead() || mc.player.isTouchingWater() || mc.currentScreen != null) {
                return;
            }


            if (!lagTimer.passedMillise(kickWindow.get())) {
                return;
            }




            if (!(pulseMode.get() && modeFlux.is(LagReleaseMode.ADAPTIVE))) {
                if (lagTimer.passedMillise(nextFlushMs)) {
                    nextFlushMs = rollReleaseDelay();
                    releaseQueuedPackets();
                    return;
                }
            }


            if (packet instanceof PlayerInteractEntityC2SPacket || packet instanceof HandSwingC2SPacket) {
                if (flushHit.get()) {
                    releaseQueuedPackets();
                    lagTimer.reset();
                    return;
                }
            }
            if (packet instanceof PlayerInteractBlockC2SPacket || packet instanceof UpdateSignC2SPacket || packet instanceof PlayerInteractItemC2SPacket) {
                if (flushBlock.get()) {
                    releaseQueuedPackets();
                    lagTimer.reset();
                    return;
                }
            }
            if (packet instanceof PlayerActionC2SPacket) {
                if (flushMove.get()) {
                    releaseQueuedPackets();
                    lagTimer.reset();
                    return;
                }
            }


            if (mc.player.isUsingItem() && (mc.player.getActiveItem().contains(DataComponentTypes.FOOD) || mc.player.getActiveItem().getItem().toString().contains("potion") || mc.player.getActiveItem().getItem().toString().contains("milk"))) {
                return;
            }


            if (modeFlux.is(LagReleaseMode.CONSTANT)) {
                if (serverGhostPos == null) {
                    serverGhostPos = mc.player.getEntityPos();
                }
                event.setCancelled(true);
                holdQueue.add(packet);
            } else if (modeFlux.is(LagReleaseMode.ADAPTIVE)) {

                if (serverGhostPos == null) {
                    serverGhostPos = mc.player.getEntityPos();
                }








                event.setCancelled(true);
                holdQueue.add(packet);


                if (holdQueue.size() > 100) {
                    releaseQueuedPackets();
                }
            }
        }
    }

    private void releaseQueuedPackets() {
        while (!holdQueue.isEmpty()) {
            PacketUtil.sendPacketNoEvent(holdQueue.poll());
            if (serverGhostPos != null && !holdQueue.isEmpty()) {




            }
        }
        serverGhostPos = null;
    }

    private void releasePacketPulse() {
        if (holdQueue.isEmpty()) return;

        int speed = pulseStep.get();
        for (int i = 0; i < speed; i++) {
            if (holdQueue.isEmpty()) break;

            Packet<?> packet = holdQueue.poll();
            if (packet != null) {
                PacketUtil.sendPacketNoEvent(packet);
                if (packet instanceof PlayerMoveC2SPacket) {
                    PlayerMoveC2SPacket movePacket = (PlayerMoveC2SPacket) packet;
                    if (movePacket.changesPosition()) {
                        serverGhostPos = new Vec3d(movePacket.getX(serverGhostPos.x), movePacket.getY(serverGhostPos.y), movePacket.getZ(serverGhostPos.z));
                    }
                }
            }
        }

        if (holdQueue.isEmpty()) {
            serverGhostPos = null;
        }
    }

    private long rollReleaseDelay() {
        int min = delayA.get();
        int max = delayB.get();
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private Entity findNearbyEnemy(float range) {
        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(range),
                e -> e != mc.player && e.isAlive() && mc.player.distanceTo(e) <= range && !AntiBot.isBot(e) && !Teams.isAlly(e)).stream().findFirst().orElse(null);
    }
}
