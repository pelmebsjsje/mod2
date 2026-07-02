package com.example.litebuilder;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;
import com.example.litebuilder.bridge.EmergencyStop;
import com.example.litebuilder.bridge.VanillaInteractionBridge;
import com.example.litebuilder.movement.MovementSmoother;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Обрабатывает "разовые" команды из чата: найти в сундуках, покопать область,
 * дойти до точки. Работает параллельно с BuildManager (не конфликтует, т.к.
 * BuildManager ставит на паузу себя, если TaskManager активен, и наоборот —
 * см. TODO про приоритет задач ниже).
 */
public class TaskManager {

    public final MovementSmoother smoother = new MovementSmoother();

    private enum Mode { IDLE, FIND_CHESTS, MINE_AREA, GOTO }
    private Mode mode = Mode.IDLE;

    // --- find_in_chests ---
    private Identifier searchItem;
    private BlockPos searchOrigin;
    private int searchRadius;

    // --- mine_area ---
    private final Deque<BlockPos> mineQueue = new ArrayDeque<>();
    private BlockPos currentMineTarget;
    private Identifier mineFilter;

    // --- goto ---
    private BlockPos gotoTarget;

    public void findInChests(Identifier item, int radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        this.searchItem = item;
        this.searchOrigin = client.player.getBlockPos();
        this.searchRadius = radius;
        this.mode = Mode.FIND_CHESTS;

        // Простой скан по загруженным чанкам в радиусе — ищем блок-энтити сундуков.
        List<BlockPos> matches = new ArrayList<>();
        BlockPos.iterate(
            searchOrigin.add(-radius, -radius, -radius),
            searchOrigin.add(radius, radius, radius)
        ).forEach(pos -> {
            var be = client.world.getBlockEntity(pos);
            if (be instanceof ChestBlockEntity chest && containsItem(chest, item)) {
                matches.add(pos.toImmutable());
            }
        });

        if (matches.isEmpty()) {
            feedback("Не нашёл " + item + " в сундуках в радиусе " + radius);
            mode = Mode.IDLE;
            return;
        }

        feedback("Нашёл в " + matches.size() + " сундук(ах). Иду к ближайшему.");
        BlockPos nearest = matches.stream()
            .min((a, b) -> Double.compare(
                a.getSquaredDistance(searchOrigin), b.getSquaredDistance(searchOrigin)))
            .orElseThrow();

        goTo(nearest);
    }

    private boolean containsItem(Inventory inv, Identifier item) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
                if (id.equals(item)) return true;
            }
        }
        return false;
    }

    public void mineArea(BlockPos center, Identifier blockFilter, int radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return;

        mineQueue.clear();
        this.mineFilter = blockFilter;

        BlockPos.iterate(
            center.add(-radius, -radius, -radius),
            center.add(radius, radius, radius)
        ).forEach(pos -> {
            var state = client.world.getBlockState(pos);
            if (blockFilter == null
                || net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).equals(blockFilter)) {
                if (!state.isAir()) {
                    mineQueue.add(pos.toImmutable());
                }
            }
        });

        if (mineQueue.isEmpty()) {
            feedback("Не нашёл подходящих блоков для копания в этой области");
            return;
        }

        feedback("В очереди на копание: " + mineQueue.size() + " блоков");
        mode = Mode.MINE_AREA;
        currentMineTarget = null;
    }

    public void goTo(BlockPos pos) {
        this.gotoTarget = pos;
        this.mode = Mode.GOTO;
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2));
    }

    public void stopAll() {
        mode = Mode.IDLE;
        mineQueue.clear();
        smoother.clearTarget();
        BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
    }

    /** Вызывать каждый клиентский тик из общего цикла мода. */
    public void onTick(MinecraftClient client) {
        if (EmergencyStop.isTriggered()) {
            return; // человек вмешался — замираем
        }
        if (client.player == null || client.world == null) return;

        switch (mode) {
            case GOTO -> tickGoto(client);
            case MINE_AREA -> tickMineArea(client);
            case FIND_CHESTS -> tickGoto(client); // после нахождения просто идём — переиспользуем логику
            case IDLE -> smoother.clearTarget();
        }

        smoother.update(client);
    }

    private void tickGoto(MinecraftClient client) {
        if (gotoTarget == null) return;
        double dist = client.player.getBlockPos().getSquaredDistance(gotoTarget);

        smoother.setTargetLookAt(client.player, Vec3d.ofCenter(gotoTarget));

        if (dist < 4) {
            feedback("На месте.");
            mode = Mode.IDLE;
            gotoTarget = null;
        }
        // Само перемещение (WASD/спринт) выполняет Baritone; мы только
        // переопределяем поворот камеры через smoother, чтобы не было снапов.
        // Убедись, что в настройках Baritone отключено его собственное
        // мгновенное вращение (см. README, раздел про Settings).
    }

    private void tickMineArea(MinecraftClient client) {
        if (currentMineTarget == null) {
            currentMineTarget = mineQueue.poll();
            if (currentMineTarget == null) {
                feedback("Копание области завершено.");
                mode = Mode.IDLE;
                return;
            }
            BaritoneAPI.getProvider().getPrimaryBaritone()
                .getCustomGoalProcess().setGoalAndPath(new GoalNear(currentMineTarget, 3));
        }

        smoother.setTargetLookAt(client.player, Vec3d.ofCenter(currentMineTarget));

        double dist = client.player.getBlockPos().getSquaredDistance(currentMineTarget);
        if (dist <= 16 && smoother.isAimedAt(client.player, 3f)) {
            boolean broken = tryBreakBlock(client, currentMineTarget);
            if (broken) {
                currentMineTarget = null;
            }
        }
    }

    /**
     * Разрушение блока — вызывается каждый тик, пока цель наведена. Использует
     * VanillaInteractionBridge.continueBreaking, который под капотом дёргает
     * тот же client.interactionManager.updateBlockBreakingProgress(...), что
     * и обычное удержание ЛКМ игроком (учитывает твёрдость блока/инструмент
     * сам движок Minecraft — нам не нужно считать тики вручную).
     */
    private boolean tryBreakBlock(MinecraftClient client, BlockPos pos) {
        return VanillaInteractionBridge.continueBreaking(client, pos);
    }

    private void feedback(String msg) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            player.sendMessage(Text.literal("§b[LiteBuilder] §f" + msg), false);
        }
    }
}
