package com.example.litebuilder.bridge;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Тонкая обёртка над стандартным (не Baritone/Litematica) клиентским API
 * Minecraft: наведение взгляда, установка/разрушение блока, использование
 * предмета, открытие контейнера. Это те же вызовы, что происходят при обычной
 * игре мышью — мы просто дёргаем их из кода вместо обработчика клика.
 */
public final class VanillaInteractionBridge {

    private VanillaInteractionBridge() {}

    /** Ищет соседний твёрдый блок, на грань которого нужно кликнуть, чтобы поставить блок в targetPos. */
    public static BlockHitResult findPlacementHit(MinecraftClient client, BlockPos targetPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = targetPos.offset(dir.getOpposite());
            var neighborState = client.world.getBlockState(neighborPos);
            if (neighborState.isAir() || neighborState.isReplaceable()) {
                continue;
            }
            Vec3d faceCenter = Vec3d.ofCenter(neighborPos).add(
                dir.getOffsetX() * 0.5, dir.getOffsetY() * 0.5, dir.getOffsetZ() * 0.5
            );
            if (client.player.getEyePos().squaredDistanceTo(faceCenter) > 36) continue;
            return new BlockHitResult(faceCenter, dir, neighborPos, false);
        }
        return null;
    }

    /** Переключает хотбар на слот с нужным блоком, при необходимости сначала свапая его туда из инвентаря. */
    public static boolean selectBlockInHotbar(ClientPlayerEntity player, net.minecraft.block.Block block) {
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                inv.setSelectedSlot(i);
                return true;
            }
        }
        for (int i = 9; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                MinecraftClient client = MinecraftClient.getInstance();
                int hotbarSlot = inv.getSelectedSlot();
                int screenSlot = i < 9 ? i + 36 : i + 27;
                client.interactionManager.clickSlot(
                    player.currentScreenHandler.syncId, screenSlot, hotbarSlot + 36,
                    SlotActionType.SWAP, player
                );
                return true;
            }
        }
        return false;
    }

    /** Ставит блок в targetPos, используя предмет, уже выбранный в хотбаре. Возвращает true при успехе клика. */
    public static boolean placeBlock(MinecraftClient client, BlockPos targetPos) {
        BlockHitResult hit = findPlacementHit(client, targetPos);
        if (hit == null) return false;
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return result.isAccepted();
    }

    /**
     * Начинает/продолжает разрушение блока (нужно вызывать каждый тик, пока блок не сломается —
     * так же, как удержание ЛКМ игроком). Возвращает true, когда блок был окончательно сломан на этом тике.
     */
    public static boolean continueBreaking(MinecraftClient client, BlockPos pos) {
        if (client.world.getBlockState(pos).isAir()) {
            client.interactionManager.cancelBlockBreaking();
            return true;
        }
        Direction facing = guessFacingFromPlayer(client, pos);
        boolean brokeThisTick = client.interactionManager.updateBlockBreakingProgress(pos, facing);
        client.player.swingHand(Hand.MAIN_HAND);
        return brokeThisTick;
    }

    public static void stopBreaking(MinecraftClient client) {
        client.interactionManager.cancelBlockBreaking();
    }

    private static Direction guessFacingFromPlayer(MinecraftClient client, BlockPos pos) {
        Vec3d toPlayer = client.player.getEyePos().subtract(Vec3d.ofCenter(pos));
        return Direction.getFacing(toPlayer.x, toPlayer.y, toPlayer.z);
    }

    /** Использование предмета в руке (еда, зелье, удочка и т.д.) — обычный ПКМ "в воздух". */
    public static void useItemInMainHand(MinecraftClient client) {
        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
    }

    /**
     * Открывает контейнер (сундук/печь/верстак и т.д.) по позиции — правый клик по блоку.
     * Дальнейшая работа с содержимым идёт уже через client.player.currentScreenHandler
     * в вызывающем коде (см. ToolRegistry.craft/smelt/takeItem).
     */
    public static boolean openContainer(MinecraftClient client, BlockPos pos) {
        BlockEntity be = client.world.getBlockEntity(pos);
        Direction dir = guessFacingFromPlayer(client, pos).getOpposite();
        Vec3d hitVec = Vec3d.ofCenter(pos);
        BlockHitResult hit = new BlockHitResult(hitVec, dir, pos, false);
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        return result.isAccepted() && (be instanceof NamedScreenHandlerFactory || client.player.currentScreenHandler != client.player.playerScreenHandler);
    }

    public static void closeScreen(MinecraftClient client) {
        client.player.closeHandledScreen();
    }
}
