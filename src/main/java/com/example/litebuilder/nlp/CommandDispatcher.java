package com.example.litebuilder.nlp;

import com.example.litebuilder.LiteBuilderClient;
import com.example.litebuilder.bridge.EmergencyStop;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public class CommandDispatcher {

    public static void handle(String rawText) {
        String text = rawText.substring(1).trim(); // убираем ведущий '#'

        // Свободная задача для ИИ-агента (Groq): "#ии построй дом из дерева возле спавна"
        // Обрабатывается отдельно от строгого CommandAction — сюда идёт произвольный
        // текст, а не одна из заранее описанных команд.
        if (text.toLowerCase().startsWith("ии ") || text.toLowerCase().startsWith("аи ")) {
            String goal = text.substring(3).trim();
            LiteBuilderClient.AI_AGENT.requestNewGoal(goal);
            return;
        }

        CommandAction action = RegexCommandParser.tryParse(text);

        if (action != null) {
            dispatch(action);
            return;
        }

        // Regex не справился — асинхронно спрашиваем LLM, чтобы не подвесить клиент
        feedback("Не узнал команду по шаблону, спрашиваю ИИ...");
        LlmCommandParser.parseAsync(text).thenAccept(CommandDispatcher::dispatchOnClientThread);
    }

    private static void dispatchOnClientThread(CommandAction action) {
        MinecraftClient.getInstance().execute(() -> dispatch(action));
    }

    private static void dispatch(CommandAction action) {
        switch (action) {
            case CommandAction.FindInChests a ->
                LiteBuilderClient.TASK_MANAGER.findInChests(a.item(), a.radiusBlocks());
            case CommandAction.MineArea a ->
                LiteBuilderClient.TASK_MANAGER.mineArea(a.center(), a.blockFilter(), a.radiusBlocks());
            case CommandAction.GoTo a ->
                LiteBuilderClient.TASK_MANAGER.goTo(a.pos());
            case CommandAction.BuildStart a -> {
                EmergencyStop.clear();
                boolean ok = LiteBuilderClient.BUILD_MANAGER.startFromActiveSchematic();
                feedback(ok ? "Принял. Начинаю стройку" : "Ошибка: нет активной схемы в Litematica");
            }
            case CommandAction.Pause a -> {
                LiteBuilderClient.BUILD_MANAGER.pause();
                feedback("Пауза");
            }
            case CommandAction.Stop a -> {
                EmergencyStop.trigger("команда #стоп");
                LiteBuilderClient.BUILD_MANAGER.stop();
                LiteBuilderClient.TASK_MANAGER.stopAll();
                LiteBuilderClient.AI_AGENT.stopAll();
                feedback("Остановлен");
            }
            case CommandAction.Unknown a -> feedback("Не понял команду: " + a.reason());
        }
    }

    private static void feedback(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.sendMessage(Text.literal("§b[LiteBuilder] §f" + msg), false);
        }
    }
}
