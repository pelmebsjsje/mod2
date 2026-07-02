package com.example.litebuilder.ai;

import com.example.litebuilder.bridge.EmergencyStop;
import com.example.litebuilder.tools.ToolRegistry;
import com.example.litebuilder.tools.ToolTask;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Менеджер задач ИИ-агента. Хранит очередь шагов плана (полученного от Groq)
 * и текущее состояние выполнения. Работает поверх ToolRegistry — сам никогда
 * не трогает Baritone/Litematica классы напрямую (это делает Bridge-слой).
 *
 * Жизненный цикл:
 *  1. #ии <задача на русском> -> requestNewGoal() -> асинхронный запрос к Groq.
 *  2. План приходит -> AgentMemory сохраняет его на диск -> заполняем очередь.
 *  3. Каждый тик: если нет EmergencyStop, тикаем текущий ToolTask.
 *  4. DONE -> след. шаг, сохраняем прогресс в память (nextStepIndex++).
 *     FAILED -> сообщаем "Ошибка", останавливаемся (не молча пропускаем).
 *     NEEDS_RESOURCES -> сообщаем "Нужны ресурсы", останавливаемся.
 *  5. При старте мода (см. LiteBuilderClient) читаем AgentMemory.load() —
 *     если там есть незавершённый план, ПРОДОЛЖАЕМ с nextStepIndex, а не
 *     начинаем заново. Это и есть "продолжение работы после перезапуска".
 */
public class AiAgentManager {

    private final Deque<ToolTask> queue = new ArrayDeque<>();
    private ToolTask current;
    private AgentMemory memory;
    private boolean waitingForPlan = false;

    public void init() {
        memory = AgentMemory.load();
        if (memory.hasUnfinishedPlan()) {
            feedback("Нашёл незавершённую задачу с прошлого запуска: \"" + memory.currentGoal
                + "\" (шаг " + (memory.nextStepIndex + 1) + "/" + memory.plan.size()
                + "). Продолжаю. Останови через #стоп, если не нужно.");
            // Сам план (тексты шагов) в памяти — только для истории/логов;
            // реальные ToolTask регенерируются из последнего известного шага,
            // т.к. в JSON мы храним человекочитаемое описание, а не сериализованные
            // объекты Java. Поэтому при продолжении бот просто заново спросит Groq,
            // но с указанием "продолжи с шага N", чтобы не начинать задачу с нуля.
            requestContinuation();
        }
    }

    public void requestNewGoal(String goalRu) {
        if (waitingForPlan) {
            feedback("Уже составляю план для предыдущей задачи, подожди.");
            return;
        }
        EmergencyStop.clear();
        memory.reset(goalRu);
        feedback("Принял задачу: \"" + goalRu + "\". Составляю план...");
        waitingForPlan = true;

        String worldContext = ToolRegistry.checkWorldTool().resultMessage();
        GroqClient.requestPlan(goalRu, worldContext).thenAccept(plan ->
            MinecraftClient.getInstance().execute(() -> onPlanReceived(plan))
        );
    }

    private void requestContinuation() {
        waitingForPlan = true;
        String worldContext = ToolRegistry.checkWorldTool().resultMessage();
        String prompt = memory.currentGoal + " (продолжение: уже выполнено шагов " + memory.nextStepIndex + ")";
        GroqClient.requestPlan(prompt, worldContext).thenAccept(plan ->
            MinecraftClient.getInstance().execute(() -> onPlanReceived(plan))
        );
    }

    private void onPlanReceived(GroqClient.Plan plan) {
        waitingForPlan = false;
        if (plan.steps().isEmpty()) {
            feedback("Ошибка: не смог составить план. " + (plan.note() != null ? plan.note() : ""));
            return;
        }
        if (plan.note() != null && !plan.note().isBlank()) {
            feedback("Заметка от ИИ: " + plan.note());
        }

        queue.clear();
        memory.plan.clear();
        for (GroqClient.PlanStep step : plan.steps()) {
            memory.plan.add(step.tool() + " " + step.args());
            ToolTask task = toToolTask(step);
            if (task != null) queue.add(task);
        }
        memory.nextStepIndex = 0;
        memory.save();
        current = null;
        feedback("План готов (" + queue.size() + " шагов). Начинаю выполнение.");
    }

    /** Транслирует шаг плана Groq в конкретный ToolTask через ToolRegistry (единственная точка перевода). */
    private ToolTask toToolTask(GroqClient.PlanStep step) {
        JsonObject a = step.args();
        try {
            return switch (step.tool()) {
                case "goto" -> ToolRegistry.gotoTool(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                case "build" -> ToolRegistry.buildTool();
                case "mine" -> {
                    var arr = a.getAsJsonArray("blocks");
                    String[] names = new String[arr.size()];
                    for (int i = 0; i < arr.size(); i++) names[i] = arr.get(i).getAsString();
                    yield ToolRegistry.mineTool(names);
                }
                case "placeBlock" -> ToolRegistry.placeBlockTool(
                    a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt(),
                    Identifier.of(a.get("block").getAsString()));
                case "breakBlock" -> ToolRegistry.breakBlockTool(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                case "findChest" -> ToolRegistry.findChestTool(Identifier.of(a.get("item").getAsString()), a.get("radius").getAsInt());
                case "takeItem" -> ToolRegistry.takeItemTool(Identifier.of(a.get("item").getAsString()));
                case "craft" -> ToolRegistry.craftTool(Identifier.of(a.get("item").getAsString()));
                case "smelt" -> ToolRegistry.smeltTool(Identifier.of(a.get("input").getAsString()), Identifier.of(a.get("fuel").getAsString()));
                case "scanArea" -> ToolRegistry.scanAreaTool(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt(), a.get("radius").getAsInt());
                case "lookAt" -> ToolRegistry.lookAtTool(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                case "openContainer" -> ToolRegistry.openContainerTool(a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
                case "useItem" -> ToolRegistry.useItemTool();
                case "checkInventory" -> ToolRegistry.checkInventoryTool();
                case "checkWorld" -> ToolRegistry.checkWorldTool();
                default -> null;
            };
        } catch (Exception e) {
            feedback("Ошибка: не разобрал шаг плана '" + step.tool() + "': " + e.getMessage());
            return null;
        }
    }

    public void onTick(MinecraftClient client) {
        if (EmergencyStop.isTriggered() || waitingForPlan) return;
        if (client.player == null || client.world == null) return;

        if (current == null) {
            current = queue.poll();
            if (current == null) {
                if (memory != null && memory.hasUnfinishedPlan()) {
                    // очередь опустела, но память ещё думает, что есть шаги — рассинхрон, чистим
                    memory.nextStepIndex = memory.plan.size();
                    memory.save();
                }
                return;
            }
            current.start(client);
        }

        ToolTask.Status status = current.tick(client);
        switch (status) {
            case RUNNING -> {}
            case DONE -> {
                memory.nextStepIndex++;
                memory.save();
                current = null;
                if (queue.isEmpty()) {
                    feedback("Завершил задачу: " + memory.currentGoal);
                }
            }
            case FAILED -> {
                feedback("Ошибка на шаге '" + current.name() + "': " + current.resultMessage());
                current = null;
                queue.clear();
            }
            case NEEDS_RESOURCES -> {
                feedback("Нужны ресурсы для шага '" + current.name() + "'");
                current = null;
                queue.clear();
            }
        }
    }

    public void stopAll() {
        queue.clear();
        current = null;
        waitingForPlan = false;
    }

    private void feedback(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.sendMessage(Text.literal("§d[ИИ] §f" + msg), false);
        }
        System.out.println("[LiteBuilder AI] " + msg); // лог решений в консоль, как требуется по ТЗ
    }
}
