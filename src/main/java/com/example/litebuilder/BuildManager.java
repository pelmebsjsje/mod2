package com.example.litebuilder;

import com.example.litebuilder.bridge.BaritoneBridge;
import com.example.litebuilder.bridge.EmergencyStop;
import com.example.litebuilder.bridge.VanillaInteractionBridge;
import com.example.litebuilder.movement.MovementSmoother;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Управляет процессом реалистичной постройки по схеме Litematica.
 *
 * Цикл на каждый тик:
 *  1. Проверка EmergencyStop — если сработала, вообще ничего не делаем.
 *  2. Если блока нет в инвентаре — статус "Нужны ресурсы" в чат, задача
 *     помечается неудачной (поиск в сундуках/крафт — через TaskManager/ToolRegistry,
 *     это отдельная команда от ИИ, а не встроенная в этот цикл).
 *  3. Baritone (через BaritoneBridge) подводит игрока к точке (GoalBlock).
 *  4. Когда рядом и камера наведена (MovementSmoother.isAimedAt) — реальная
 *     установка блока через raycast + ClientPlayerInteractionManager.interactBlock.
 *  5. Ограничение скорости постройки (не чаще одного блока в BUILD_COOLDOWN_TICKS
 *     тиков) — чтобы не выглядеть "роботом", кликающим 20 раз в секунду.
 */
public class BuildManager {

    /** Anti-cheat: не чаще одного блока за это число тиков (20 тиков = 1 секунда). */
    private static final int BUILD_COOLDOWN_TICKS = 4;

    private final Deque<BuildTask> queue = new ArrayDeque<>();
    private final MovementSmoother smoother = new MovementSmoother();
    private BuildTask current;
    private boolean active = false;
    private boolean paused = false;
    private int cooldown = 0;

    private int completedCount = 0;
    private int failedCount = 0;

    public boolean startFromActiveSchematic() {
        List<BuildTask> tasks = SchematicScanner.scanActivePlacement();
        if (tasks.isEmpty()) {
            return false;
        }
        EmergencyStop.clear();
        BaritoneBridge.applyHumanLikeSettings();
        queue.clear();
        queue.addAll(tasks);
        completedCount = 0;
        failedCount = 0;
        active = true;
        paused = false;
        current = null;
        return true;
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        if (!queue.isEmpty() || current != null) {
            paused = false;
        }
    }

    public void stop() {
        active = false;
        paused = false;
        current = null;
        queue.clear();
        smoother.clearTarget();
        BaritoneBridge.cancelPathing();
    }

    public boolean isActive() {
        return active;
    }

    public String getStatusReport() {
        return String.format(
            "active=%s paused=%s очередь=%d готово=%d ошибок=%d",
            active, paused, queue.size(), completedCount, failedCount
        );
    }

    public void onTick(MinecraftClient client) {
        if (EmergencyStop.isTriggered()) {
            return; // человек вмешался — замираем, ничего не трогаем
        }

        if (cooldown > 0) {
            cooldown--;
        }

        if (!active || paused || client.player == null || client.world == null) {
            return;
        }

        if (current == null) {
            current = queue.poll();
            if (current == null) {
                active = false; // очередь пуста — стройка завершена
                feedback("Завершил постройку. Готово блоков: " + completedCount
                    + (failedCount > 0 ? (", пропущено: " + failedCount) : ""));
                return;
            }
        }

        if (!hasBlockInInventory(client.player.getInventory(), current)) {
            feedback("Нужны ресурсы: не хватает блока для " + current.pos.toShortString());
            current.status = BuildTask.Status.FAILED;
            failedCount++;
            current = null;
            return;
        }

        double distSq = client.player.getBlockPos().getSquaredDistance(current.pos);

        if (distSq > 16) { // дальше ~4 блоков — идём ближе
            BaritoneBridge.gotoNear(current.pos, 3);
            smoother.setTargetLookAt(client.player, Vec3d.ofCenter(current.pos));
            smoother.update(client);
            return;
        }

        smoother.setTargetLookAt(client.player, Vec3d.ofCenter(current.pos));
        smoother.update(client);

        if (cooldown > 0) {
            return; // выдерживаем anti-cheat паузу между постановками блоков
        }

        if (!smoother.isAimedAt(client.player, 4f)) {
            return; // ждём, пока камера реально довернётся — это тоже часть "человечности"
        }

        boolean placed = tryPlaceBlock(client, current);
        if (placed) {
            current.status = BuildTask.Status.DONE;
            completedCount++;
            current = null;
            cooldown = BUILD_COOLDOWN_TICKS;
        } else {
            current.attempts++;
            if (current.attempts > 20) {
                current.status = BuildTask.Status.FAILED;
                failedCount++;
                feedback("Ошибка: не смог поставить блок на " + current.pos.toShortString());
                current = null;
            }
        }
    }

    private boolean hasBlockInInventory(PlayerInventory inventory, BuildTask task) {
        return findHotbarOrInventorySlot(inventory, task) >= 0;
    }

    /** Возвращает индекс слота с нужным блоком, или -1 если блока нет вообще. */
    private int findHotbarOrInventorySlot(PlayerInventory inventory, BuildTask task) {
        var targetBlock = task.targetState.getBlock();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == targetBlock) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Реальная установка блока:
     *  1. Находим соседнюю позицию с уже существующим блоком, к грани которого
     *     можно "прицелиться", чтобы новый блок появился ровно в task.pos.
     *  2. Переключаем хотбар на слот с нужным блоком (если блок не в хотбаре —
     *     свапаем его в хотбар, обычный ClickSlot).
     *  3. Строим BlockHitResult по центру найденной грани.
     *  4. Вызываем client.interactionManager.interactBlock(...) — тот же путь,
     *     которым идёт обычный правый клик игрока.
     */
    private boolean tryPlaceBlock(MinecraftClient client, BuildTask task) {
        ClientPlayerEntity player = client.player;
        int slot = findHotbarOrInventorySlot(player.getInventory(), task);
        if (slot < 0) return false;

        if (!VanillaInteractionBridge.selectBlockInHotbar(player, task.targetState.getBlock())) {
            return false;
        }

        return VanillaInteractionBridge.placeBlock(client, task.pos);
    }

    private void feedback(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.sendMessage(Text.literal("§b[LiteBuilder] §f" + msg), false);
        }
    }
}
