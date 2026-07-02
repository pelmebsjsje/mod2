package com.example.litebuilder;

import com.example.litebuilder.ai.AiAgentManager;
import com.example.litebuilder.bridge.BaritoneBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class LiteBuilderClient implements ClientModInitializer {

    public static final BuildManager BUILD_MANAGER = new BuildManager();
    public static final TaskManager TASK_MANAGER = new TaskManager();
    public static final AiAgentManager AI_AGENT = new AiAgentManager();

    @Override
    public void onInitializeClient() {

        com.example.litebuilder.nlp.ItemDictionaryRu.load();
        ChatCommandInterceptor.register();
        BaritoneBridge.applyHumanLikeSettings();

        // Читает config/litebuilder/agent_memory.json и, если там есть незавершённая
        // задача с прошлой сессии, сама продолжает её выполнение (см. ai.AiAgentManager).
        AI_AGENT.init();

        // Команда /litebuild start — берёт активную схему Litematica и начинает стройку
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("litebuild")
                .then(literal("start").executes(ctx -> {
                    boolean ok = BUILD_MANAGER.startFromActiveSchematic();
                    ctx.getSource().sendFeedback(Text.literal(
                        ok ? "LiteBuilder: постройка начата" : "LiteBuilder: не найдена активная схема Litematica"
                    ));
                    return 1;
                }))
                .then(literal("pause").executes(ctx -> {
                    BUILD_MANAGER.pause();
                    ctx.getSource().sendFeedback(Text.literal("LiteBuilder: на паузе"));
                    return 1;
                }))
                .then(literal("stop").executes(ctx -> {
                    BUILD_MANAGER.stop();
                    ctx.getSource().sendFeedback(Text.literal("LiteBuilder: остановлен"));
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal(BUILD_MANAGER.getStatusReport()));
                    return 1;
                }))
            );
        });

        // Главный цикл — вызывается каждый тик клиента
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            BUILD_MANAGER.onTick(client);
            TASK_MANAGER.onTick(client);
            AI_AGENT.onTick(client);
        });
    }
}
