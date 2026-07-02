package com.example.litebuilder;

import com.example.litebuilder.bridge.LitematicaBridge;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.Box;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Строит плоский список задач (BuildTask) из активного размещения схемы Litematica.
 *
 * Подход: вместо ручного обхода регионов схемы и самостоятельного пересчёта
 * поворота/зеркала (это легко сделать с ошибками) мы используем тот же
 * виртуальный мир, который Litematica сама рендерит как "призрак" схемы —
 * {@code SchematicWorldHandler.getSchematicWorld()}. Он уже возвращает
 * блок в МИРОВЫХ координатах с учётом origin/rotation/mirror размещения.
 * Мы просто идём по мировому bounding box размещения (getEclosingBox())
 * и сравниваем: что должно быть по схеме (виртуальный мир) vs что уже
 * стоит по факту (реальный клиентский мир) — строим только расхождения.
 *
 * Это же именно то место, где стоит перейти на нативный
 * {@code IBuilderProcess.buildOpenLitematic(index)} из BaritoneBridge, если
 * не нужна собственная логика инвентаря/крафта поверх процесса стройки —
 * см. BuildManager.startFromActiveSchematic().
 */
public class SchematicScanner {

    public static List<BuildTask> scanActivePlacement() {
        List<BuildTask> tasks = new ArrayList<>();

        SchematicPlacement placement = LitematicaBridge.getSelectedPlacement();
        if (placement == null || !placement.isEnabled()) {
            return tasks;
        }

        Box box = LitematicaBridge.getWorldBoundingBox(placement);
        if (box == null) {
            return tasks;
        }

        BlockPos pos1 = box.getPos1();
        BlockPos pos2 = box.getPos2();

        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        var client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world == null) {
            return tasks;
        }

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos worldPos = new BlockPos(x, y, z);

                    BlockState expected = LitematicaBridge.getExpectedStateAt(worldPos);
                    if (expected == null || expected.isAir()) {
                        continue; // по схеме тут воздух — строить нечего
                    }

                    BlockState actual = client.world.getBlockState(worldPos);
                    if (actual.equals(expected)) {
                        continue; // уже стоит правильный блок — пропускаем
                    }

                    tasks.add(new BuildTask(worldPos.toImmutable(), expected));
                }
            }
        }

        // Строим снизу вверх, затем по горизонтали — реалистичнее и меньше
        // риск попытаться поставить блок в воздухе без опоры снизу.
        tasks.sort(
            Comparator.<BuildTask>comparingInt(t -> t.pos.getY())
                .thenComparingInt(t -> t.pos.getX())
                .thenComparingInt(t -> t.pos.getZ())
        );

        return tasks;
    }
}
