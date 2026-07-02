package com.example.litebuilder.ai;

import com.example.litebuilder.nlp.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Клиент Groq API. ВАЖНО про ключ API: он НЕ хранится в исходном коде —
 * читается из config/litebuilder/config.json (поле apiKey), который создаётся
 * автоматически при первом запуске (см. nlp.ModConfig). Заполни его один раз
 * руками. Если ты скопировал этот проект откуда-то с уже вписанным ключом —
 * немедленно отзови этот ключ в консоли Groq и впиши свой новый.
 *
 * Для Groq baseUrl в конфиге должен быть:
 *   https://api.groq.com/openai/v1/chat/completions
 * и модель, например: "llama-3.3-70b-versatile" (сверь актуальный список
 * поддерживаемых моделей на https://console.groq.com/docs/models — он меняется).
 *
 * Запрос выполняется через HttpClient.sendAsync — это НЕ блокирует поток
 * клиента игры (сеть работает в отдельном пуле потоков JDK), а результат
 * возвращается в виде CompletableFuture.
 */
public class GroqClient {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        Ты — планировщик действий для Minecraft-бота LiteBuilder. Пользователь
        ставит задачу на русском языке. Твоя работа — разбить её на шаги,
        используя ТОЛЬКО следующие инструменты (имя -> аргументы JSON):

          goto {x,y,z}
          build {}
          mine {blocks:[string]}
          placeBlock {x,y,z,block}
          breakBlock {x,y,z}
          findChest {item,radius}
          takeItem {item}
          craft {item}
          smelt {input,fuel}
          scanArea {x,y,z,radius}
          lookAt {x,y,z}
          openContainer {x,y,z}
          useItem {}
          checkInventory {}
          checkWorld {}

        Верни ТОЛЬКО JSON-массив шагов, без пояснений и без markdown-разметки:
        [{"tool":"checkWorld","args":{}}, {"tool":"goto","args":{"x":100,"y":64,"z":-30}}, ...]

        Если задачи не хватает данных (например, не указано что и где строить,
        а активной схемы Litematica нет) — верни план из одного шага
        {"tool":"checkWorld","args":{}} и объясни проблему в поле "note"
        верхнего уровня JSON-объекта {"steps":[...], "note":"..."}.
        Формат ответа строго: {"steps":[...],"note":"<строка или null>"}
        """;

    public record PlanStep(String tool, JsonObject args) {}
    public record Plan(List<PlanStep> steps, String note) {}

    /** Запрашивает у Groq план выполнения задачи. Асинхронно, не блокирует клиент игры. */
    public static CompletableFuture<Plan> requestPlan(String userGoal, String worldContext) {
        ModConfig cfg = ModConfig.get();

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("temperature", 0.2);

        JsonArray messages = new JsonArray();
        messages.add(chatMsg("system", SYSTEM_PROMPT_TEMPLATE));
        messages.add(chatMsg("user", "Контекст мира: " + worldContext + "\n\nЗадача: " + userGoal));
        body.add("messages", messages);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(cfg.baseUrl))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)));

        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
        }

        return CLIENT.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> parsePlan(resp.body()))
            .exceptionally(ex -> new Plan(List.of(), "Groq недоступен: " + ex.getMessage()));
    }

    private static JsonObject chatMsg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static Plan parsePlan(String httpBody) {
        try {
            JsonObject root = JsonParser.parseString(httpBody).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString()
                .trim().replaceAll("```json|```", "").trim();

            JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
            JsonArray stepsJson = parsed.getAsJsonArray("steps");
            List<PlanStep> steps = new ArrayList<>();
            if (stepsJson != null) {
                for (var el : stepsJson) {
                    JsonObject stepObj = el.getAsJsonObject();
                    steps.add(new PlanStep(
                        stepObj.get("tool").getAsString(),
                        stepObj.has("args") ? stepObj.getAsJsonObject("args") : new JsonObject()
                    ));
                }
            }
            String note = parsed.has("note") && !parsed.get("note").isJsonNull()
                ? parsed.get("note").getAsString() : null;
            return new Plan(steps, note);
        } catch (Exception e) {
            return new Plan(List.of(), "Не смог разобрать ответ Groq: " + e.getMessage());
        }
    }
}
