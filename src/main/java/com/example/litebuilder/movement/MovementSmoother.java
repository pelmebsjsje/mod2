package com.example.litebuilder.movement;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Baritone сам умеет двигать/поворачивать игрока, но делает это "телепортно
 * точно" — целевой yaw/pitch выставляется мгновенно, что и создаёт ощущение
 * робота. Мы отключаем встроенный контроль поворота Baritone (см. README —
 * нужно выставить Settings.rotationsInputControl/antiCheatCompatibility или
 * аналогичную опцию под конкретную версию Baritone) и вместо этого сами
 * плавно доводим камеру до цели каждый тик, плюс лёгкий человеческий шум.
 *
 * Использование: каждый тик вызывать setTarget(pos) с точкой, на которую
 * сейчас "хочет" смотреть логика бота (следующая точка пути / целевой блок),
 * а update() дергать из ClientTickEvents.END_CLIENT_TICK.
 */
public class MovementSmoother {

    /** Максимальный поворот камеры за один тик, градусы. Меньше = плавнее, но медленнее реакция. */
    private static final float MAX_YAW_PER_TICK = 8.0f;
    private static final float MAX_PITCH_PER_TICK = 6.0f;

    /** Небольшой шум, чтобы движение не было идеально линейным как у бота. */
    private static final float JITTER_DEGREES = 0.4f;

    private final Random random = new Random();

    private double targetYaw;
    private double targetPitch;
    private boolean hasTarget = false;

    public void setTargetLookAt(ClientPlayerEntity player, Vec3d point) {
        Vec3d eyePos = player.getEyePos();
        double dx = point.x - eyePos.x;
        double dy = point.y - eyePos.y;
        double dz = point.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        targetYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
        targetPitch = -Math.toDegrees(Math.atan2(dy, horizontalDist));
        hasTarget = true;
    }

    public void clearTarget() {
        hasTarget = false;
    }

    /** Вызывать каждый клиентский тик. Плавно подводит yaw/pitch игрока к цели. */
    public void update(MinecraftClient client) {
        if (!hasTarget || client.player == null) return;

        ClientPlayerEntity player = client.player;

        float yawDiff = MathHelper.wrapDegrees((float) (targetYaw - player.getYaw()));
        float pitchDiff = (float) (targetPitch - player.getPitch());

        // Плавность через easing: чем ближе к цели, тем медленнее (естественнее,
        // чем линейное движение с резкой остановкой).
        float yawStep = MathHelper.clamp(yawDiff * 0.35f, -MAX_YAW_PER_TICK, MAX_YAW_PER_TICK);
        float pitchStep = MathHelper.clamp(pitchDiff * 0.35f, -MAX_PITCH_PER_TICK, MAX_PITCH_PER_TICK);

        yawStep += (random.nextFloat() - 0.5f) * JITTER_DEGREES;
        pitchStep += (random.nextFloat() - 0.5f) * JITTER_DEGREES;

        player.setYaw(player.getYaw() + yawStep);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchStep, -90f, 90f));
    }

    public boolean isAimedAt(ClientPlayerEntity player, float toleranceDegrees) {
        if (!hasTarget) return false;
        float yawDiff = Math.abs(MathHelper.wrapDegrees((float) (targetYaw - player.getYaw())));
        float pitchDiff = Math.abs((float) (targetPitch - player.getPitch()));
        return yawDiff < toleranceDegrees && pitchDiff < toleranceDegrees;
    }
}
