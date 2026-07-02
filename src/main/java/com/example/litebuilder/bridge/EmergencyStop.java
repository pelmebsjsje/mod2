package com.example.litebuilder.bridge;

import baritone.api.BaritoneAPI;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Единая точка аварийного прерывания.
 *
 * По ТЗ: если пользователь сам пишет что-то в чат (не команду через #) или
 * иным способом сигнализирует отмену — агент должен ЗАМЕРЕТЬ немедленно.
 * Вместо того чтобы городить проверки в каждом цикле по отдельности, все
 * долгоживущие циклы (BuildManager.onTick, TaskManager.onTick, AiAgentManager)
 * обязаны в начале каждого тика вызывать {@link #isTriggered()} и, если true —
 * ничего не делать (или явно остановиться через stop()).
 */
public final class EmergencyStop {

    private static volatile boolean triggered = false;
    private static volatile String reason = "";

    private EmergencyStop() {}

    /** Вызывается из любого места, обнаружившего сигнал "стоп" (чат, команда, GUI-кнопка). */
    public static void trigger(String why) {
        triggered = true;
        reason = why;

        // Baritone-у нужно явно сказать "забудь все процессы", иначе он продолжит
        // тикать pathing/mine/build процессы в фоне независимо от нашего флага.
        try {
            BaritoneAPI.getProvider().getAllBaritones().forEach(b -> {
                b.getPathingBehavior().cancelEverything();
                b.getBuilderProcess().pause();
                b.getMineProcess().cancel();
            });
        } catch (Exception ignored) {
            // Baritone мог быть ещё не инициализирован — не критично для самой остановки.
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§c[LiteBuilder] Аварийная остановка: " + why), false);
        }
    }

    /** Сбрасывается явной командой пользователя (#строй / #продолжи), а не автоматически. */
    public static void clear() {
        triggered = false;
        reason = "";
    }

    public static boolean isTriggered() {
        return triggered;
    }

    public static String getReason() {
        return reason;
    }
}
