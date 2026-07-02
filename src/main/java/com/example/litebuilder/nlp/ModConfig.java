package com.example.litebuilder.nlp;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки хранятся в config/litebuilder/config.json, чтобы не пересобирать
 * мод при смене адреса LLM или ключа.
 *
 * ВАЖНО: apiKey сюда НЕ хардкодится. Файл создаётся при первом запуске с
 * пустым apiKey — впиши свой ключ Groq туда руками (или через переменную
 * окружения, см. загрузку ниже). Если у тебя уже "засветился" где-то ключ —
 * отзови его в https://console.groq.com/keys и впиши новый.
 *
 * Примеры baseUrl:
 *  - Groq (по умолчанию): https://api.groq.com/openai/v1/chat/completions
 *  - Ollama локально:     http://localhost:11434/v1/chat/completions (apiKey пустой)
 *  - любой другой OpenAI-совместимый провайдер
 */
public class ModConfig {

    public String baseUrl = "https://api.groq.com/openai/v1/chat/completions";
    public String apiKey = "";
    public String model = "llama-3.3-70b-versatile";
    public boolean llmFallbackEnabled = true;

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static ModConfig load() {
        Path path = configPath();
        try {
            if (Files.exists(path)) {
                ModConfig cfg = new Gson().fromJson(Files.readString(path), ModConfig.class);
                // Позволяем переопределить ключ через переменную окружения GROQ_API_KEY —
                // удобно для CI/публичных репозиториев, чтобы ключ вообще не попадал в JSON на диске.
                String envKey = System.getenv("GROQ_API_KEY");
                if (envKey != null && !envKey.isBlank()) {
                    cfg.apiKey = envKey;
                }
                return cfg;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        ModConfig def = new ModConfig();
        def.save();
        return def;
    }

    public void save() {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new Gson().toJson(this));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("litebuilder/config.json");
    }
}
