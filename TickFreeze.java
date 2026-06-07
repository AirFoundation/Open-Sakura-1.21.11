package dev.sakura.client.module.impl.combat;

import dev.sakura.client.SakuraClient;
import dev.sakura.client.event.EventHandler;
import dev.sakura.client.event.impl.client.TickEvent;
import dev.sakura.client.event.impl.input.MoveInputEvent;
import dev.sakura.client.event.impl.packet.PacketEvent;
import dev.sakura.client.event.impl.player.PlayerTickEvent;
import dev.sakura.client.event.type.EventType;
import dev.sakura.client.module.Category;
import dev.sakura.client.module.Module;
import dev.sakura.client.module.impl.movement.NoSlowBlink;
import dev.sakura.client.utils.client.ChatUtil;
import dev.sakura.client.utils.entity.PlayerSimulationCache;
import dev.sakura.client.values.impl.BooleanValue;
import dev.sakura.client.values.impl.EnumValue;
import dev.sakura.client.values.impl.NumberValue;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class TickFreeze extends Module {

    private final EnumValue<TickPassMode> modeShift = new EnumValue<>("Mode", "模式", TickPassMode.PAST_REWIND, TickPassMode.class);

    private final NumberValue<Double> nearRange = new NumberValue<>("Min Range", "最小范围", 2.5, 0.0, 8.0, 0.1);
    private final NumberValue<Double> farRange = new NumberValue<>("Max Range", "最大范围", 4.0, 0.0, 8.0, 0.1);

    private final NumberValue<Double> regenStep = new NumberValue<>("Balance Recovery", "平衡恢复", 1.0, 0.0, 2.0, 0.1);
    private final NumberValue<Integer> balanceCap = new NumberValue<>("Balance Max", "最大平衡", 20, 0, 200, 1);
    private final NumberValue<Integer> tickCap = new NumberValue<>("Max Ticks", "最大Tick", 4, 1, 20, 1);
    private final BooleanValue flagPause = new BooleanValue("Pause On Flag", "被拉回暂停", true);
    private final NumberValue<Integer> pauseGap = new NumberValue<>("Pause", "暂停Tick", 0, 0, 20, 1);
    private final NumberValue<Integer> coolGap = new NumberValue<>("Cooldown", "冷却", 0, 0, 100, 1);
    private final BooleanValue hitPause = new BooleanValue("Pause On Hit", "受击暂停", true);
    private final NumberValue<Integer> hitPauseLen = new NumberValue<>("Hit Pause Ticks", "受击暂停时长", 10, 1, 40, 1);
    private final BooleanValue lockGround = new BooleanValue("Force Ground", "强制地面", false);

    private final BooleanValue traceDebug = new BooleanValue("Debug", "调试信息", false);

    private int freezeTicks = 0;
    private volatile double bankTicks = 0.0;
    private boolean bankDry = false;

    private final List<PredictedTick> simTicks = new ArrayList<>();

    private int coolRemain = 0;
    private int stashTicks = 0;
    private TickPassMode stashMode = null;

    private boolean ghostRun = false;
    private static final int zkm$tbBalanceWindow = 20;

    public TickFreeze() {
        super("TickBase", "TickBase", Category.Combat);
    }

    @Override
    protected void onEnable() {
        freezeTicks = 0;
        bankTicks = 0f;
        bankDry = false;
        simTicks.clear();
        coolRemain = 0;
        stashTicks = 0;
        stashMode = null;


        PlayerSimulationCache.init();
    }

    @Override
    protected void onDisable() {
        freezeTicks = 0;
        bankTicks = 0f;
        bankDry = false;
        simTicks.clear();
        coolRemain = 0;
        stashTicks = 0;
        stashMode = null;
    }

    @EventHandler
    public void onPlayerTick(PlayerTickEvent event) {
        if (nullPointCheck()) return;

        if (mc.player.hasVehicle() || SakuraClient.MODULES.getModule(NoSlowBlink.class).isEnabled()) {
            return;
        }

        if (freezeTicks > 0) {
            freezeTicks--;
            event.cancel();
        }
    }

    @EventHandler
    public void onTick(TickEvent.Pre event) {
        if (nullPointCheck()) return;

        if (ghostRun) {
            return;
        }

        if (mc.player.hasVehicle() || SakuraClient.MODULES.getModule(NoSlowBlink.class).isEnabled()) {
            return;
        }

        if (simTicks.isEmpty()) {
            return;
        }

        KillAura killAura = SakuraClient.MODULES.getModule(KillAura.class);


        if (coolRemain > 0) {
            coolRemain--;
            return;
        }


        if (freezeTicks > 0) {
            return;
        }


        if (stashTicks > 0 && stashMode == TickPassMode.PAST_REWIND) {
            runSyntheticTicks(stashTicks);
            if (traceDebug.get()) {
                ChatUtil.clientMessage("TickBase (Past): Executed " + stashTicks + " ticks.");
            }
            stashTicks = 0;
            stashMode = null;
            coolRemain = coolGap.get();
            return;
        }

        LivingEntity target;
        if (!killAura.isEnabled()) return;
        target = killAura.getAuraTarget();

        if (target == null) return;

        double currentDistanceSq = mc.player.squaredDistanceTo(target);
        double minRangeSq = nearRange.get() * nearRange.get();
        double maxRangeSq = farRange.get() * farRange.get();


        List<Integer> candidateTickIndexes = new ArrayList<>();
        Vec3d targetPos = target.getEntityPos();

        for (int i = 0; i < simTicks.size(); i++) {
            PredictedTick tick = simTicks.get(i);
            double distSq = tick.position.squaredDistanceTo(targetPos);


            if (distSq < currentDistanceSq && distSq >= minRangeSq && distSq <= maxRangeSq) {
                candidateTickIndexes.add(i);
            }
        }

        if (lockGround.get()) {
            candidateTickIndexes.removeIf(i -> !simTicks.get(i).onGround);
        }


        if (candidateTickIndexes.isEmpty()) return;


        int selectedTickIndex = -1;
        for (int i : candidateTickIndexes) {
            if (simTicks.get(i).fallDistance > 0.0) {
                selectedTickIndex = i;
                break;
            }
        }


        if (selectedTickIndex == -1) {
            selectedTickIndex = candidateTickIndexes.get(0);
        }

        if (selectedTickIndex == 0) {
            return;
        }

        if (!killAura.isEnabled() || killAura.getAuraTarget() == null) {
            return;
        }

        if (modeShift.get() == TickPassMode.PAST_REWIND) {

            freezeTicks = selectedTickIndex + pauseGap.get();
            stashTicks = selectedTickIndex;
            stashMode = TickPassMode.PAST_REWIND;

            if (traceDebug.get()) {
                ChatUtil.clientMessage("TickBase: Scheduled skip " + freezeTicks + " ticks.");
            }
        } else {

            int totalSkipped = 0;
            for (int i = 0; i < selectedTickIndex; i++) {

                if (!killAura.isEnabled() || killAura.getAuraTarget() == null) {
                    break;
                }

                tickMinecraftClientOnce();
                bankTicks -= 1;
                totalSkipped++;
            }

            if (traceDebug.get()) {
                ChatUtil.clientMessage("TickBase: Skipped " + totalSkipped + " ticks.");
            }

            freezeTicks = totalSkipped + pauseGap.get();
            coolRemain = coolGap.get();
        }
    }

    @EventHandler
    public void onMoveInput(MoveInputEvent event) {
        if (nullPointCheck()) return;


        if (mc.player.hasVehicle() || SakuraClient.MODULES.getModule(NoSlowBlink.class).isEnabled()) {
            return;
        }

        simTicks.clear();

        PlayerSimulationCache.SimulatedPlayerCache cache = PlayerSimulationCache.getSimulationForLocalPlayer();
        if (cache == null) return;

        if (bankTicks <= 0) bankDry = true;
        if (bankTicks * 2 > balanceCap.get()) bankDry = false;

        if (bankTicks <= balanceCap.get()) {
            bankTicks += regenStep.get();
        }

        if (bankDry) return;

        int limit = Math.min((int) bankTicks, tickCap.get());
        if (limit <= 0) return;

        List<PlayerSimulationCache.SimulatedPlayerSnapshot> snapshots = cache.getSnapshotsBetween(0, limit);


        for (PlayerSimulationCache.SimulatedPlayerSnapshot snapshot : snapshots) {
            simTicks.add(new PredictedTick(
                    snapshot.pos(),
                    snapshot.fallDistance(),
                    snapshot.velocity(),
                    snapshot.onGround()
            ));
        }
    }

    @EventHandler
    public void onPacket(PacketEvent event) {
        if (nullPointCheck()) return;

        if (event.getType() == EventType.RECEIVE) {
            if (hitPause.get()) {
                if ((event.getPacket() instanceof EntityVelocityUpdateS2CPacket packet && packet.getEntityId() == mc.player.getId()) ||
                        event.getPacket() instanceof ExplosionS2CPacket) {

                    coolRemain = hitPauseLen.get();
                    freezeTicks = 0;
                    stashTicks = 0;
                    stashMode = null;

                    if (traceDebug.get()) {
                        ChatUtil.clientMessage("TickBase: Paused due to hit.");
                    }
                }
            }


            if (event.getPacket() instanceof PlayerPositionLookS2CPacket && flagPause.get()) {
                bankTicks = 0f;
                if (traceDebug.get()) {
                    ChatUtil.clientMessage("TickBase: Flag detected, balance reset.");
                }
            }
        }
    }

    private void runSyntheticTicks(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tickMinecraftClientOnce();
            bankTicks -= 1;
        }
    }

    private void tickMinecraftClientOnce() {
        if (mc.world == null) return;
        ghostRun = true;
        try {
            mc.tick();
        } finally {
            ghostRun = false;
        }
    }


    private record PredictedTick(Vec3d position, double fallDistance, Vec3d velocity, boolean onGround) {
    }

    public enum TickPassMode {
        PAST_REWIND, FUTURE_BURST
    }
}
