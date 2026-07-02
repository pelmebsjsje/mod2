package com.example.litebuilder.nlp;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Русские названия -> Identifier ванильных блоков/предметов.
 * Источник: assets/litebuilder/ru_items.json (можно редактировать без пересборки,
 * если читать из папки конфигов, а не из jar — см. TODO ниже).
 */
public class ItemDictionaryRu {

    private static final Map<String, Identifier> DICTIONARY = new HashMap<>();

    public static void load() {
        DICTIONARY.clear();
        try (Reader reader = new InputStreamReader(
                ItemDictionaryRu.class.getResourceAsStream("/assets/litebuilder/ru_items.json"),
                StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> raw = new Gson().fromJson(reader, type);
            raw.forEach((ru, id) -> DICTIONARY.put(normalize(ru), Identifier.of(id)));
        } catch (Exception e) {
            e.printStackTrace();
        }

        // TODO: дополнительно подгрузить пользовательский словарь из
        // FabricLoader.getInstance().getConfigDir().resolve("litebuilder/ru_items_custom.json"),
        // чтобы можно было добавлять слова без пересборки мода.
    }

    /** Точное совпадение по словарю. */
    public static Identifier resolve(String ruName) {
        return DICTIONARY.get(normalize(ruName));
    }

    /**
     * Нечёткий поиск: если пользователь написал "тростника"/"тростником" —
     * ищем словарную статью, которая является префиксом введённого слова
     * (простое отбрасывание окончаний, без полноценной морфологии).
     */
    public static Identifier resolveFuzzy(String ruPhrase) {
        String norm = normalize(ruPhrase);
        Identifier exact = DICTIONARY.get(norm);
        if (exact != null) return exact;

        for (Map.Entry<String, Identifier> entry : DICTIONARY.entrySet()) {
            String key = entry.getKey();
            if (norm.startsWith(key) || key.startsWith(norm)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s.toLowerCase().trim().replaceAll("[^а-яё ]", "");
    }
}
