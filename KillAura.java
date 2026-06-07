package dev.sakura.client.module.impl.combat;

import dev.sakura.client.SakuraClient;
import dev.sakura.client.event.EventHandler;
import dev.sakura.client.event.impl.client.TickEvent;
import dev.sakura.client.event.impl.packet.PacketEvent;
import dev.sakura.client.event.impl.render.Render3DEvent;
import dev.sakura.client.event.type.EventType;
import dev.sakura.client.manager.Managers;
import dev.sakura.client.module.Category;
import dev.sakura.client.module.Module;
import dev.sakura.client.module.impl.movement.Scaffold;
import dev.sakura.client.utils.math.MathUtil;
import dev.sakura.client.utils.player.PacketUtil;
import dev.sakura.client.utils.render.Render3DUtil;
import dev.sakura.client.utils.rotation.MovementFix;
import dev.sakura.client.utils.rotation.Priority;
import dev.sakura.client.utils.rotation.RaytraceUtil;
import dev.sakura.client.utils.rotation.Rotation;
import dev.sakura.client.utils.rotation.RotationUtil;
import dev.sakura.client.values.impl.BooleanValue;
import dev.sakura.client.values.impl.EnumValue;
import dev.sakura.client.values.impl.NumberValue;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.awt.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class KillAura extends Module {
    public enum BlockAnimationMode {
        Fake,
        NONE,
        Hypixel
    }

    public enum AttackMode {
        LegacySpam,
        Cooldown
    }

    private final EnumValue<AttackMode> modeNode = new EnumValue<>("Mode", "妯″紡", AttackMode.LegacySpam);
    private final NumberValue<Double> lockRange = new NumberValue<>("Aim Range", "鐬勫噯鑼冨洿", 5.0, 1.0, 6.0, 0.1);
    private final NumberValue<Double> sweepRange = new NumberValue<>("Search Range", "鎼滅储鑼冨洿", 10.0, 1.0, 20.0, 0.1);
    private final NumberValue<Double> cpsFloor = new NumberValue<>("Min CPS", "鏈€灏忔敾鍑婚€熷害", 10.0, 1.0, 20.0, 1.0, () -> modeNode.is(AttackMode.LegacySpam));
    private final NumberValue<Double> cpsRoof = new NumberValue<>("Max CPS", "鏈€澶ф敾鍑婚€熷害", 10.0, 1.0, 20.0, 1.0, () -> modeNode.is(AttackMode.LegacySpam));
    private final NumberValue<Integer> turnRate = new NumberValue<>("Rotation Speed", "杞悜閫熷害", 10, 1, 10, 1);
    private final BooleanValue traceGate = new BooleanValue("RayTrace", "灏勭嚎妫€娴?", true);
    private final EnumValue<BlockAnimationMode> autoBlockMode = new EnumValue<>("Auto Block", "鑷姩鏍兼尅", BlockAnimationMode.Fake);
    private final BooleanValue boxDebug = new BooleanValue("Debug Render", "璋冭瘯娓叉煋", false);

    private final Queue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();
    private List<LivingEntity> pickedList;
    private LivingEntity focusTarget;
    private Rotation lastAimRotation;
    private long lastSwingStamp;

    public KillAura() {
        super("KillAura", "鏉€鎴厜鐜?", Category.Combat);
    }

    @Override
    protected void onDisable() {
        focusTarget = null;
        pickedList = null;
        lastAimRotation = null;
        flushQueuedPackets();
        releaseUseItem();
    }

    public boolean shouldFakeBlock() {
        return !autoBlockMode.is(BlockAnimationMode.NONE);
    }

    public LivingEntity getAuraTarget() {
        return focusTarget;
    }

    @EventHandler
    public void onPacket(PacketEvent event) {
        if (nullPointCheck()) {
            packetQueue.clear();
            return;
        }

        if (event.getType() != EventType.SEND) {
            return;
        }

        Packet<?> packet = event.getPacket();
        if (PacketUtil.bypassPackets.remove(packet)) {
            return;
        }

        if (!shouldDelayPacketsForHypixelAutoBlock()) {
            flushQueuedPackets();
            return;
        }

        if (packet instanceof PlayerInteractItemC2SPacket || packet instanceof PlayerActionC2SPacket) {
            flushQueuedPackets();
            return;
        }

        if (packet instanceof PlayerMoveC2SPacket && queuedMovePackets() >= 3) {
            sendUseItemForAutoBlock();
            flushQueuedPackets();
            releaseUseItem();
        }

        event.setCancelled(true);
        packetQueue.add(packet);
    }

    @EventHandler
    public void onPreTick(TickEvent.Pre event) {
        if (nullPointCheck()) {
            packetQueue.clear();
            return;
        }

        if (SakuraClient.MODULES.getModule(Scaffold.class).isEnabled()) {
            flushQueuedPackets();
            return;
        }

        refreshAuraTargets();
        if (focusTarget == null) {
            flushQueuedPackets();
            return;
        }

        if (mc.player.squaredDistanceTo(focusTarget) > lockRange.get() * lockRange.get()) {
            return;
        }

        Rotation rotation = RotationUtil.calculate(focusTarget);
        lastAimRotation = rotation;
        Managers.ROTATION.setRotations(rotation, turnRate.get(), MovementFix.NORMAL, Priority.Medium);

        if (traceGate.get()) {
            if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity().equals(focusTarget)) {
                trySwingAtCurrentTarget();
            }
            return;
        }

        if (RaytraceUtil.facingEnemy(mc.player, focusTarget, rotation, lockRange.get(), 0)) {
            trySwingAtCurrentTarget();
        }
    }

    @EventHandler
    public void onRender(Render3DEvent event) {
        if (!boxDebug.get() || pickedList == null || pickedList.isEmpty()) {
            return;
        }

        for (Entity entity : pickedList) {
            if (entity.equals(focusTarget)) {
                Render3DUtil.drawFilledBox(event.getMatrices(), entity.getBoundingBox(), new Color(200, 0, 0, 60).getRGB());
                Render3DUtil.drawOutlineBox(event.getMatrices(), entity.getBoundingBox(), new Color(200, 0, 0, 60).getRGB(), 2f);
            } else {
                Render3DUtil.drawFilledBox(event.getMatrices(), entity.getBoundingBox(), new Color(0, 200, 0, 60).getRGB());
                Render3DUtil.drawOutlineBox(event.getMatrices(), entity.getBoundingBox(), new Color(0, 200, 0, 60).getRGB(), 2f);
            }
        }
    }

    private void trySwingAtCurrentTarget() {
        if (focusTarget == null || mc.interactionManager == null) {
            return;
        }

        if (modeNode.is(AttackMode.Cooldown)) {
            if (mc.player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                mc.interactionManager.attackEntity(mc.player, focusTarget);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            return;
        }

        long time = System.currentTimeMillis();
        double baseDelay = 1000.0 / MathUtil.getRandom(cpsFloor.get(), cpsRoof.get());
        long delay = (long) (baseDelay + (Math.random() - 0.5) * baseDelay * 0.4);
        if (time - lastSwingStamp >= delay) {
            mc.interactionManager.attackEntity(mc.player, focusTarget);
            mc.player.swingHand(Hand.MAIN_HAND);
            lastSwingStamp = time;
        }
    }

    private void refreshAuraTargets() {
        double range = Math.max(lockRange.get(), sweepRange.get());
        focusTarget = null;
        pickedList = Managers.COMBAT.getEntities(range);
        focusTarget = Managers.COMBAT.getClosestEnemy(range);
    }

    private boolean shouldDelayPacketsForHypixelAutoBlock() {
        return autoBlockMode.is(BlockAnimationMode.Hypixel)
                && focusTarget != null
                && mc.player != null
                && mc.player.getMainHandStack().isIn(ItemTags.SWORDS);
    }

    private int queuedMovePackets() {
        int count = 0;
        for (Packet<?> packet : packetQueue) {
            if (packet instanceof PlayerMoveC2SPacket) {
                count++;
            }
        }
        return count;
    }

    private void flushQueuedPackets() {
        Packet<?> packet;
        while ((packet = packetQueue.poll()) != null) {
            PacketUtil.sendPacketNoEvent(packet);
        }
    }

    private void sendUseItemForAutoBlock() {
        if (mc.getNetworkHandler() == null || mc.world == null || mc.player == null) {
            return;
        }

        float yaw = lastAimRotation != null ? lastAimRotation.yaw : mc.player.getYaw();
        float pitch = lastAimRotation != null ? lastAimRotation.pitch : mc.player.getPitch();
        PacketUtil.sendPacketNoEvent(new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND,
                mc.world.getPendingUpdateManager().incrementSequence().getSequence(),
                yaw,
                pitch
        ));
    }

    private void releaseUseItem() {
        if (mc.getNetworkHandler() == null) {
            return;
        }

        PacketUtil.sendPacketNoEvent(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN,
                Direction.DOWN
        ));
    }
}
