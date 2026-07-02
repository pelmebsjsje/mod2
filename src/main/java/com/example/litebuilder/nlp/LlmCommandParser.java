package com.example.litebuilder.nlp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Просит модель превратить произвольную русскую фразу в СТРОГИЙ JSON одного
 * из заранее описанных типов действия. Модель не выполняет действий сама —
 * только классифицирует/извлекает параметры. Дальше результат идёт через
 * тот же конвейер, что и regex-парсер (CommandDispatcher).
 */
public class LlmCommandParser {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final String SYSTEM_PROMPT = """
        Ты — парсер команд для Minecraft-бота. Пользователь пишет команду на русском.
        Верни ТОЛЬКО JSON, без пояснений, без markdown, одного из видов:

        {"type":"find_in_chests","item":"<русское название предмета>","radius":<число>}
        {"type":"mine_area","x":<int>,"y":<int>,"z":<int>,"block":"<русское название блока или null>","radius":<число>}
        {"type":"goto","x":<int>,"y":<int>,"z":<int>}
        {"type":"build_start"}
        {"type":"pause"}
        {"type":"stop"}
        {"type":"unknown","reason":"<почему не понял>"}

        Если координата/радиус не указаны явно — не выдумывай, используй unknown
        с объяснением, чего не хватает.
        """;

    public static CompletableFuture<CommandAction> parseAsync(String userText) {
        ModConfig cfg = ModConfig.get();
        if (!cfg.llmFallbackEnabled) {
            return CompletableFuture.completedFuture(
                new CommandAction.Unknown("LLM-фолбэк выключен в конфиге"));
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        var messages = new com.google.gson.JsonArray();
        messages.add(chatMsg("system", SYSTEM_PROMPT));
        messages.add(chatMsg("user", userText));
        body.add("messages", messages);
        body.addProperty("temperature", 0); // детерминированный разбор, не "творчество"

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(cfg.baseUrl))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(body)));

        if (cfg.apiKey != null && !cfg.apiKey.isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + cfg.apiKey);
        }

        return CLIENT.sendAsync(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            .thenApply(resp -> {
                try {
                    return extractAction(resp.body());
                } catch (Exception e) {
                    return new CommandAction.Unknown("Ошибка разбора ответа LLM: " + e.getMessage());
                }
            })
            .exceptionally(ex -> new CommandAction.Unknown("LLM недоступна: " + ex.getMessage()));
    }

    private static JsonObject chatMsg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static CommandAction extractAction(String httpBody) {
        JsonObject root = new Gson().fromJson(httpBody, JsonObject.class);
        String content = root
            .getAsJsonArray("choices").get(0).getAsJsonObject()
            .getAsJsonObject("message").get("content").getAsString()
            .trim();

        // На случай если модель всё же обернула в ```json ... ```
        content = content.replaceAll("```json|```", "").trim();

        JsonObject json = new Gson().fromJson(content, JsonObject.class);
        String type = json.get("type").getAsString();

        return switch (type) {
            case "find_in_chests" -> {
                Identifier item = ItemDictionaryRu.resolveFuzzy(json.get("item").getAsString());
                yield item == null
                    ? new CommandAction.Unknown("Не знаю предмет: " + json.get("item").getAsString())
                    : new CommandAction.FindInChests(item, json.get("radius").getAsInt());
            }
            case "mine_area" -> {
                Identifier filter = json.has("block") && !json.get("block").isJsonNull()
                    ? ItemDictionaryRu.resolveFuzzy(json.get("block").getAsString())
                    : null;
                yield new CommandAction.MineArea(
                    new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()),
                    filter,
                    json.has("radius") ? json.get("radius").getAsInt() : 3
                );
            }
            case "goto" -> new CommandAction.GoTo(new BlockPos(
                json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()));
            case "build_start" -> new CommandAction.BuildStart();
            case "pause" -> new CommandAction.Pause();
            case "stop" -> new CommandAction.Stop();
            default -> new CommandAction.Unknown(
                json.has("reason") ? json.get("reason").getAsString() : "не распознано");
        };
    }
}
