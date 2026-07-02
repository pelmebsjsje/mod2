package com.example.litebuilder.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Память ИИ-агента между действиями и между перезапусками клиента.
 * Хранится как обычный JSON-файл — не самое "красивое" решение, зато его
 * легко читать/редактировать руками и не нужна отдельная БД для одного
 * клиентского мода.
 */
public class AgentMemory {

    /** Исходная задача, которую агент выполняет (то, что попросил игрок). */
    public String currentGoal = "";

    /** Полный план, полученный от Groq (список шагов в виде текста для истории/логов). */
    public List<String> plan = new ArrayList<>();

    /** Индекс следующего невыполненного шага плана. */
    public int nextStepIndex = 0;

    /** Свободный текст: что агент "помнит" из прошлых действий (краткие заметки). */
    public List<String> notes = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AgentMemory load() {
        Path path = memoryPath();
        try {
            if (Files.exists(path)) {
                AgentMemory mem = GSON.fromJson(Files.readString(path), AgentMemory.class);
                if (mem != null) return mem;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new AgentMemory();
    }

    public void save() {
        try {
            Path path = memoryPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void reset(String newGoal) {
        currentGoal = newGoal;
        plan.clear();
        nextStepIndex = 0;
        notes.clear();
        save();
    }

    public boolean hasUnfinishedPlan() {
        return !plan.isEmpty() && nextStepIndex < plan.size();
    }

    private static Path memoryPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("litebuilder/agent_memory.json");
    }
}
