package com.example.litebuilder.nlp;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Строго типизированный результат разбора команды. И regex-парсер, и LLM-парсер
 * обязаны привести любую фразу к одному из этих вариантов — "свободных" действий
 * не бывает, бот выполняет только то, что здесь описано.
 */
public sealed interface CommandAction {

    record FindInChests(Identifier item, int radiusBlocks) implements CommandAction {}

    record MineArea(BlockPos center, Identifier blockFilter, int radiusBlocks) implements CommandAction {}

    record GoTo(BlockPos pos) implements CommandAction {}

    record BuildStart() implements CommandAction {}
    record Pause() implements CommandAction {}
    record Stop() implements CommandAction {}

    record Unknown(String reason) implements CommandAction {}
}
