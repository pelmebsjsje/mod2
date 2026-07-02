package com.example.litebuilder.nlp;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Покрывает частые формулировки без похода к LLM — быстро и бесплатно.
 * Если ничего не совпало — возвращает null, и вызывающий код должен
 * попробовать LlmCommandParser.
 */
public class RegexCommandParser {

    // #найди тростник в сундуках в радиусе 40 [блоков]
    private static final Pattern FIND_IN_CHESTS = Pattern.compile(
        "найди\\s+(.+?)\\s+в\\s+сундук\\w*\\s+в\\s+радиус\\w*\\s+(\\d+)\\s*(блок\\w*)?",
        Pattern.CASE_INSENSITIVE
    );

    // #копай <блок> на координатах x:123 y:64 z:-45 [в радиусе N]
    private static final Pattern MINE_AT_COORDS = Pattern.compile(
        "копай\\s+(.+?)\\s+на\\s+координат\\w*\\s+x:(-?\\d+)\\s+y:(-?\\d+)\\s+z:(-?\\d+)" +
        "(?:\\s+в\\s+радиус\\w*\\s+(\\d+))?",
        Pattern.CASE_INSENSITIVE
    );

    // #иди x:123 y:64 z:-45  /  #иди на координаты x:.. y:.. z:..
    private static final Pattern GOTO_COORDS = Pattern.compile(
        "иди\\s+(?:на\\s+координат\\w*\\s+)?x:(-?\\d+)\\s+y:(-?\\d+)\\s+z:(-?\\d+)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PAUSE = Pattern.compile("паузу|стоп двигаться|подожди", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP = Pattern.compile("остановись|стоп|отмена", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD_START = Pattern.compile("строй|начни строить|продолжи стройку", Pattern.CASE_INSENSITIVE);

    public static CommandAction tryParse(String raw) {
        String text = raw.trim();

        Matcher m;

        m = FIND_IN_CHESTS.matcher(text);
        if (m.find()) {
            Identifier item = ItemDictionaryRu.resolveFuzzy(m.group(1));
            int radius = Integer.parseInt(m.group(2));
            if (item != null) {
                return new CommandAction.FindInChests(item, radius);
            }
            return new CommandAction.Unknown("Не знаю предмет: " + m.group(1));
        }

        m = MINE_AT_COORDS.matcher(text);
        if (m.find()) {
            String blockPhrase = m.group(1);
            BlockPos pos = new BlockPos(
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4))
            );
            int radius = m.group(5) != null ? Integer.parseInt(m.group(5)) : 3;
            // "генератор булыжника" — не блок, а конструкция; фильтруем по булыжнику
            Identifier filter = blockPhrase.toLowerCase().contains("генератор")
                ? Identifier.of("minecraft:cobblestone")
                : ItemDictionaryRu.resolveFuzzy(blockPhrase);
            return new CommandAction.MineArea(pos, filter, radius);
        }

        m = GOTO_COORDS.matcher(text);
        if (m.find()) {
            return new CommandAction.GoTo(new BlockPos(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3))
            ));
        }

        if (PAUSE.matcher(text).find()) return new CommandAction.Pause();
        if (STOP.matcher(text).find()) return new CommandAction.Stop();
        if (BUILD_START.matcher(text).find()) return new CommandAction.BuildStart();

        return null; // не совпало ни с чем — пусть решает LLM
    }
}
