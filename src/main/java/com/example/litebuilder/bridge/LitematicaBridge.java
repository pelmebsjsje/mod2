package com.example.litebuilder.bridge;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Единая точка входа в Litematica API. Методы и классы взяты из
 * дизассемблирования litematica-fabric-1_21_8-0_23_6.jar:
 *   - DataManager.getSchematicPlacementManager() (статический метод)
 *   - SchematicPlacementManager.getSelectedSchematicPlacement() / getAllSchematicsPlacements()
 *   - SchematicPlacement.getEclosingBox() -> Box (в Litematica это реально названо
 *     "Eclosing", а не "Enclosing" — опечатка в самой библиотеке, повторяем как есть)
 *   - SchematicWorldHandler.getSchematicWorld() -> WorldSchematic — это тот же виртуальный
 *     мир, который Litematica рендерит как "призрак" схемы. Он уже учитывает
 *     origin/rotation/mirror размещения, поэтому нам не нужно вручную считать
 *     трансформацию координат — просто читаем getBlockState() в мировых координатах.
 */
public final class LitematicaBridge {

    private LitematicaBridge() {}

    public static SchematicPlacementManager placementManager() {
        return DataManager.getSchematicPlacementManager();
    }

    public static SchematicPlacement getSelectedPlacement() {
        SchematicPlacementManager mgr = placementManager();
        return mgr == null ? null : mgr.getSelectedSchematicPlacement();
    }

    public static List<SchematicPlacement> getAllPlacements() {
        SchematicPlacementManager mgr = placementManager();
        return mgr == null ? List.of() : mgr.getAllSchematicsPlacements();
    }

    /** Индекс размещения в общем списке — нужен для BaritoneBridge.buildOpenLitematic(index). */
    public static int indexOf(SchematicPlacement placement) {
        return getAllPlacements().indexOf(placement);
    }

    /** Мировой bounding box активного размещения (уже с учётом origin), либо null если схема не загружена. */
    public static Box getWorldBoundingBox(SchematicPlacement placement) {
        return placement == null ? null : placement.getEclosingBox();
    }

    /** Ожидаемое состояние блока в МИРОВЫХ координатах согласно схеме (через виртуальный мир Litematica). */
    public static BlockState getExpectedStateAt(BlockPos worldPos) {
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) return null;
        return schematicWorld.getBlockState(worldPos);
    }

    public static boolean hasActivePlacement() {
        return getSelectedPlacement() != null;
    }
}
