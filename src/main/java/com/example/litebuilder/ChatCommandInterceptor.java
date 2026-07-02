package com.example.litebuilder;

import com.example.litebuilder.bridge.EmergencyStop;
import com.example.litebuilder.nlp.CommandDispatcher;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

public class ChatCommandInterceptor {

    public static void register() {
        // ALLOW_CHAT вызывается перед отправкой обычного чат-сообщения.
        // Возврат false отменяет отправку на сервер.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith("#")) {
                CommandDispatcher.handle(message);
                return false; // не отправляем как обычный чат
            }
            // Любое обычное сообщение — сигнал "человек вмешался", агент должен
            // замереть немедленно (см. ТЗ, раздел "Безопасность"). Само сообщение
            // при этом всё равно уходит в обычный чат сервера (return true) —
            // мы не хотим глушить игроку живое общение, только останавливаем бота.
            EmergencyStop.trigger("игрок написал в чат");
            return true;
        });
    }
}
