package com.example.litebuilder.bridge;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IBuilderProcess;
import baritone.api.utils.BetterBlockPos;
import net.minecraft.util.math.BlockPos;

/**
 * Единая точка входа в Baritone. Реальные интерфейсы и методы взяты из
 * дизассемблирования приложенного baritone-api-fabric-1_15_0.jar (не из
 * документации, которая могла устареть):
 *   - baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone()
 *   - IBaritone.getCustomGoalProcess().setGoalAndPath(Goal)
 *   - IBaritone.getBuilderProcess().buildOpenLitematic(int)   <-- Baritone умеет
 *     строить НАПРЯМУЮ из активного размещения Litematica, это штатная интеграция,
 *     а не то, что нужно писать руками.
 *   - IBaritone.getMineProcess().mineByName(String...)
 *   - Settings.freeLook / blockFreeLook / antiCheatCompatibility — управление
 *     "человечностью" поворотов, см. настройку applyHumanLikeSettings().
 */
public final class BaritoneBridge {

    private BaritoneBridge() {}

    private static IBaritone primary() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /**
     * Настраивает Baritone так, чтобы MovementSmoother мог сам плавно вести
     * камеру, не конкурируя с мгновенным поворотом Baritone.
     * Вызывать один раз при инициализации мода.
     */
    public static void applyHumanLikeSettings() {
        Settings s = BaritoneAPI.getSettings();
        s.freeLook.value = false;             // не даём Baritone мгновенно доворачивать камеру
        s.blockFreeLook.value = false;         // то же самое для взаимодействия с блоками
        s.antiCheatCompatibility.value = true; // более "человеческие" паттерны кликов/поворотов
    }

    /** Инструмент goto: подвести игрока вплотную к точке (для взаимодействия с блоком/сундуком). */
    public static void gotoBlock(BlockPos pos) {
        primary().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    /** Инструмент goto с допуском (например "подойди в радиус 3 блока", не обязательно вплотную). */
    public static void gotoNear(BlockPos pos, int range) {
        primary().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, range));
    }

    public static boolean isPathing() {
        return primary().getPathingBehavior().isPathing();
    }

    public static BlockPos currentPlayerPos() {
        BetterBlockPos p = primary().getPlayerContext().playerFeet();
        return new BlockPos(p.x, p.y, p.z);
    }

    public static void cancelPathing() {
        primary().getPathingBehavior().cancelEverything();
    }

    /** Инструмент mine: копать все блоки указанного типа (minecraft id без namespace, напр. "stone"). */
    public static void mineByName(String... blockNames) {
        primary().getMineProcess().mineByName(blockNames);
    }

    public static void cancelMining() {
        primary().getMineProcess().cancel();
    }

    public static boolean isMining() {
        return primary().getMineProcess().isActive();
    }

    /**
     * Инструмент build (основной путь): строит уже открытое/активное размещение
     * схемы Litematica, используя нативную интеграцию Baritone c Litematica.
     * @param placementIndex индекс размещения в списке DataManager.getSchematicPlacementManager()
     *                        .getAllSchematicsPlacements() (обычно 0, если оно одно активно).
     */
    public static void buildOpenLitematic(int placementIndex) {
        primary().getBuilderProcess().buildOpenLitematic(placementIndex);
    }

    public static IBuilderProcess builderProcess() {
        return primary().getBuilderProcess();
    }

    public static void pauseBuild() {
        primary().getBuilderProcess().pause();
    }

    public static void resumeBuild() {
        primary().getBuilderProcess().resume();
    }

    public static boolean isBuildPaused() {
        return primary().getBuilderProcess().isPaused();
    }
}
