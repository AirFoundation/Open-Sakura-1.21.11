package dev.sakura.client.module.impl.combat;

import dev.sakura.client.SakuraClient;
import dev.sakura.client.event.EventHandler;
import dev.sakura.client.event.impl.client.TickEvent;
import dev.sakura.client.event.impl.entity.AttackEntityEvent;
import dev.sakura.client.event.impl.input.MoveInputEvent;
import dev.sakura.client.event.impl.packet.PacketEvent;
import dev.sakura.client.event.impl.player.MotionEvent;
import dev.sakura.client.event.type.EventType;
import dev.sakura.client.manager.Managers;
import shit.mixin.accessors.ClientPlayerEntityAccessor;
import dev.sakura.client.module.Category;
import dev.sakura.client.module.Module;
import dev.sakura.client.module.impl.movement.Scaffold;
import dev.sakura.client.utils.player.PacketUtil;
import dev.sakura.client.values.impl.BooleanValue;
import dev.sakura.client.values.impl.EnumValue;
import dev.sakura.client.values.impl.NumberValue;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Criticals extends Module {
    private enum CriticalMode {
        Packet, Grim
    }

    private final EnumValue<CriticalMode> criticalMode = new EnumValue<>("Mode", "模式", CriticalMode.Packet);
    private final BooleanValue packetGroundOnly = new BooleanValue("GroundOnly", "仅地面", false, () -> criticalMode.is(CriticalMode.Packet));
    private final NumberValue<Integer> grimStartDelayTicks = new NumberValue<>("Delay", "延迟", 0, 0, 20, 1, () -> criticalMode.is(CriticalMode.Grim));
    private final NumberValue<Integer> grimMaxBufferedPackets = new NumberValue<>("Max Packets", "最大包数", 10, 5, 50, 1, () -> criticalMode.is(CriticalMode.Grim));

    
    private int grimPhase = 0;
    private Packet<?> delayedUsePacket;
    private float lastSpoofYaw;
    private float lastSpoofPitch;
    private boolean pendingUnstuck = false;
    private final Queue<Packet<?>> delayedTransactionPackets = new ConcurrentLinkedQueue<>();
    private boolean grimStuckActive = false;
    private int grimCycleCooldown = 0;
    private static final String zkm$criticalTail = "crit#2";

    private double jumpStartY = 0;
    private boolean backingOffTarget = false;

    public Criticals() {
        super("Criticals", "刀刀暴击", Category.Combat);
    }

    @Override
    public String getSuffix() {
        return criticalMode.get().name();
    }

    @Override
    public void onEnable() {
        resetStuck();
        backingOffTarget = false;
    }

    @Override
    public void onDisable() {
        disableStuck();
    }

    @EventHandler
    public void onAttack(AttackEntityEvent event) {
        if (nullPointCheck()) return;
        if (event.getEntity() instanceof LivingEntity) {
            if (!isCriticalHitAvailable() || (this.packetGroundOnly.get() && !mc.player.isOnGround())) {
                return;
            }

            final Box box = mc.player.getBoundingBox().offset(0.0D, 0.0625, 0.0D);
            if (!isBoxEmpty(box)) {
                return;
            }

            if (criticalMode.is(CriticalMode.Packet)) {
                doPacketCriticals();
            }
        }
    }

    private boolean isMovingBackwards() {
        if (mc.player.getVelocity().x == 0 && mc.player.getVelocity().z == 0) return false;

        float yaw = mc.player.getYaw();
        
        double motionYaw = Math.toDegrees(Math.atan2(mc.player.getVelocity().z, mc.player.getVelocity().x)) - 90;

        
        double diff = Math.abs(yaw - motionYaw);
        diff = diff % 360;
        if (diff > 180) diff = 360 - diff;

        
        return diff > 135;
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (nullPointCheck()) return;
        long l = 0x1CA39E259082L;
        long l2 = l ^ 0x220BC60F9982L;
        if (criticalMode.is(CriticalMode.Grim)) {
            
            KillAura killAura = SakuraClient.MODULES.getModule(KillAura.class);
            if (!mc.player.isOnGround() && killAura != null && killAura.isEnabled() && killAura.getAuraTarget() != null) {
                mc.player.setSprinting(false);
            }

            if (mc.player.isOnGround()) {
                backingOffTarget = false;
                jumpStartY = mc.player.getY();
            } else if (isMovingBackwards()) { 
                if (grimStuckActive) {
                    disableStuck();
                }
                return;
            }

            if (backingOffTarget) {
                if (grimStuckActive) {
                    disableStuck();
                }
                return;
            }

            Scaffold scaffold = SakuraClient.MODULES.getModule(Scaffold.class);

            if (killAura != null && killAura.isEnabled() && !mc.player.isOnGround() && (scaffold == null || !scaffold.isEnabled())) {
                
                if (!isBoxEmpty(mc.player.getBoundingBox().offset(0.0, -0.2, 0.0))) {
                    if (grimStuckActive) {
                        disableStuck();
                    }
                    return;
                }

                LivingEntity target = killAura.getAuraTarget();
                if (target != null) {
                    mc.player.setSprinting(false);
                    
                    if (grimStuckActive || mc.player.getVelocity().y < 0) {
                        
                        if (mc.player.getVelocity().y > 0) {
                            if (grimStuckActive) {
                                disableStuck();
                            }
                            return;
                        }

                        
                        if (isMovingBackwards()) {
                            if (grimStuckActive) {
                                disableStuck();
                            }
                            return;
                        }

                        if (grimCycleCooldown > 0) {
                            grimCycleCooldown--;
                        } else {
                            if (grimStuckActive) {
                                disableStuck();
                                grimCycleCooldown = 1; 
                            } else {
                                enableStuck();
                                grimCycleCooldown = grimStartDelayTicks.get();
                            }
                        }
                    } else if (grimStuckActive) {
                        disableStuck();
                    }
                } else {
                    if (grimStuckActive) {
                        disableStuck();
                    }
                }
            } else if (grimStuckActive) {
                disableStuck();
            }
        }
    }

    private void enableStuck() {
        grimStuckActive = true;
        grimPhase = 0;
        delayedUsePacket = null;
        lastSpoofYaw = Managers.ROTATION.rotations.yaw;
        lastSpoofPitch = Managers.ROTATION.rotations.pitch;
        pendingUnstuck = false;
    }

    private void disableStuck() {
        if (grimStuckActive) {
            if (this.grimPhase == 3) {
                grimStuckActive = false;
            } else {
                this.pendingUnstuck = true;
            }
        } else {
            while (!delayedTransactionPackets.isEmpty()) {
                PacketUtil.sendPacketNoEvent(delayedTransactionPackets.poll());
            }
            grimStuckActive = false;
        }
    }

    private void resetStuck() {
        grimStuckActive = false;
        grimPhase = 0;
        delayedUsePacket = null;
        pendingUnstuck = false;
        delayedTransactionPackets.clear();
    }

    @EventHandler
    public void onMotion(MotionEvent e) {
        if (nullPointCheck()) return;
        if (!criticalMode.is(CriticalMode.Grim)) return;

        
        if (!grimStuckActive && !pendingUnstuck) return;

        
        if (!grimStuckActive && pendingUnstuck) {
            
        }

        Module scaffold = SakuraClient.MODULES.getModule(Scaffold.class);
        if (scaffold.isEnabled()) {
            
            if (grimStuckActive) disableStuck();
            return;
        }

        if (e.getType() == EventType.PRE) {
            if (grimStuckActive) {
                
                if (mc.player.getVelocity().y < 0) {
                    mc.player.setVelocity(mc.player.getVelocity().x, 0.0, mc.player.getVelocity().z);
                }
                mc.player.setSprinting(false);
            }

            if (grimPhase == 1) {
                grimPhase = 2;
                float rotationYaw = mc.player.getYaw();
                float rotationPitch = mc.player.getPitch();
                if (shouldRotate() && (lastSpoofYaw != rotationYaw || lastSpoofPitch != rotationPitch)) {
                    PacketUtil.sendPacketNoEvent(new PlayerMoveC2SPacket.LookAndOnGround(rotationYaw, rotationPitch, mc.player.isOnGround(), mc.player.horizontalCollision));

                    while (!delayedTransactionPackets.isEmpty()) {
                        PacketUtil.sendPacketNoEvent(delayedTransactionPackets.poll());
                    }

                    lastSpoofYaw = rotationYaw;
                    lastSpoofPitch = rotationPitch;
                }

                if (delayedUsePacket != null) {
                    PacketUtil.sendPacketNoEvent(delayedUsePacket);
                }
            }

            if (pendingUnstuck) {
                PacketUtil.sendPacketNoEvent(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX() + 1337.0, mc.player.getY(), mc.player.getZ() + 1337.0, mc.player.isOnGround(), mc.player.horizontalCollision));

                while (!delayedTransactionPackets.isEmpty()) {
                    PacketUtil.sendPacketNoEvent(delayedTransactionPackets.poll());
                }

                this.pendingUnstuck = false;
                this.grimStuckActive = false;
            }
        }
    }

    private boolean shouldRotate() {
        if (delayedUsePacket instanceof PlayerInteractItemC2SPacket blockPlacement) {
            ItemStack item = mc.player.getStackInHand(blockPlacement.getHand());
            boolean isBowlFood = item.contains(DataComponentTypes.FOOD) && item.get(DataComponentTypes.USE_REMAINDER) != null && item.get(DataComponentTypes.USE_REMAINDER).convertInto().isOf(Items.BOWL);
            return !isBowlFood && !(item.getItem() instanceof BowItem);
        } else if (delayedUsePacket instanceof PlayerActionC2SPacket playerDigging) {
            return playerDigging.getAction() == PlayerActionC2SPacket.Action.RELEASE_USE_ITEM && mc.player.getActiveItem().getItem() instanceof BowItem;
        }
        return false;
    }

    @EventHandler
    public void onMoveInput(MoveInputEvent event) {
        if (!criticalMode.is(CriticalMode.Grim)) return;

        if (grimStuckActive) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
            event.setJump(false);
            event.setSneak(false);
            event.setSprint(false); 
        }

        if (!mc.player.isOnGround()) {
            KillAura killAura = SakuraClient.MODULES.getModule(KillAura.class);
            if (killAura != null && killAura.isEnabled() && killAura.getAuraTarget() != null) {
                event.setSprint(false);
                mc.player.setSprinting(false);
            }
        }
    }

    @EventHandler
    public void onRespawnMotion(MotionEvent event) {
        if (!criticalMode.is(CriticalMode.Grim)) return;
        if (event.getType() == EventType.PRE && mc.player.age <= 1) {
            grimPhase = 3;
            delayedUsePacket = null;
            disableStuck();
        }
    }

    @EventHandler
    public void onPacket(PacketEvent event) {
        if (nullPointCheck()) return;
        if (!criticalMode.is(CriticalMode.Grim)) return;

        
        if (event.getPacket() instanceof PlayerPositionLookS2CPacket) {
            while (!delayedTransactionPackets.isEmpty()) {
                PacketUtil.sendPacketNoEvent(delayedTransactionPackets.poll());
            }
            grimPhase = 3;
            disableStuck();
            return;
        }

        if (!grimStuckActive) return;

        if (event.getPacket() instanceof PlayerMoveC2SPacket) {
            event.setCancelled(true);
        } else if (event.getPacket() instanceof CommonPongC2SPacket) {
            delayedTransactionPackets.offer(event.getPacket());
            event.setCancelled(true);
        } else if (event.getPacket() instanceof PlayerInteractItemC2SPacket || event.getPacket() instanceof PlayerActionC2SPacket) {
            delayedUsePacket = event.getPacket();
            grimPhase = 1;
            event.setCancelled(true);
        } else if (event.getPacket() instanceof ClientCommandC2SPacket command) {
            if (command.getMode() == ClientCommandC2SPacket.Mode.START_SPRINTING) {
                KillAura killAura = SakuraClient.MODULES.getModule(KillAura.class);
                if (killAura != null && killAura.isEnabled() && killAura.getAuraTarget() != null && !mc.player.isOnGround()) {
                    event.setCancelled(true);
                }
            }
        }
    }

    

    private void doPacketCriticals() {
        final Vec3d pos = mc.player.getEntityPos();
        final boolean ground = mc.player.isOnGround();
        final ClientPlayerEntityAccessor accessor = (ClientPlayerEntityAccessor) mc.player;

        mc.player.setPosition(pos.add(0.0, 0.0625, 0.0));
        mc.player.setOnGround(false);
        accessor.invokeSendMovementPackets();

        mc.player.setPosition(pos.add(0.0, 0.00125, 0.0));
        mc.player.setOnGround(false);
        accessor.invokeSendMovementPackets();

        mc.player.setPosition(pos);
        mc.player.setOnGround(ground);
    }

    private boolean isCriticalHitAvailable() {
        return mc.player.isOnGround() &&
                !mc.player.isTouchingWater() &&
                !mc.player.isInLava() &&
                !mc.player.isClimbing() &&
                !mc.player.hasStatusEffect(StatusEffects.BLINDNESS) &&
                !mc.player.hasVehicle();
    }

    private boolean isBoxEmpty(Box box) {
        return !mc.world.getBlockCollisions(mc.player, box).iterator().hasNext();
    }
}
