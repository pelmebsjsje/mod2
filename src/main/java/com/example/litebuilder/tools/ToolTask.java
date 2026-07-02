package com.example.litebuilder.tools;

import net.minecraft.client.MinecraftClient;

/**
 * Один шаг плана ИИ. start() вызывается один раз при извлечении из очереди,
 * tick() — каждый клиентский тик, пока не вернёт терминальный статус.
 * Все реализации обязаны сами проверять EmergencyStop там, где это уместно —
 * но общая проверка уже стоит в AiAgentManager.onTick, так что tick() сюда
 * просто не будет вызван, если сработала аварийная остановка.
 */
public interface ToolTask {

    enum Status {
        RUNNING,
        DONE,
        FAILED,
        NEEDS_RESOURCES
    }

    /** Название инструмента — для логов и статуса в чат. */
    String name();

    void start(MinecraftClient client);

    Status tick(MinecraftClient client);

    /** Человекочитаемое сообщение о результате — используется для чат-статусов. */
    default String resultMessage() {
        return "";
    }
}
