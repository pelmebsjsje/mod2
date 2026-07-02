package com.example.litebuilder.tools;

import com.example.litebuilder.LiteBuilderClient;
import com.example.litebuilder.bridge.BaritoneBridge;
import com.example.litebuilder.bridge.VanillaInteractionBridge;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Фабрика инструментов ИИ. Это единственное место, где команды ИИ переводятся
 * в конкретные вызовы Baritone/Litematica/vanilla API — ИИ (Groq) никогда не
 * видит эти классы напрямую, только имя инструмента + JSON-аргументы
 * (см. ai.GroqClient / ai.AiAgentManager).
 */
public final class ToolRegistry {

    private ToolRegistry() {}

    // ---------------------------------------------------------------- goto
    public static ToolTask gotoTool(int x, int y, int z) {
        BlockPos target = new BlockPos(x, y, z);
        return new ToolTask() {
            public String name() { return "goto"; }
            public void start(MinecraftClient c) { BaritoneBridge.gotoBlock(target); }
            public Status tick(MinecraftClient c) {
                if (c.player == null) return Status.FAILED;
                double d = c.player.getBlockPos().getSquaredDistance(target);
                if (d <= 4) return Status.DONE;
                if (!BaritoneBridge.isPathing() && d > 4) return Status.FAILED; // Baritone сдался (недостижимо)
                return Status.RUNNING;
            }
            public String resultMessage() { return "дошёл до " + target.toShortString(); }
        };
    }

    // ---------------------------------------------------------------- build
    public static ToolTask buildTool() {
        return new ToolTask() {
            public String name() { return "build"; }
            public void start(MinecraftClient c) {
                // Основной путь — собственный BuildManager (учитывает инвентарь и
                // сообщает "нужны ресурсы"), а не нативный buildOpenLitematic,
                // потому что ИИ должен знать о нехватке блоков, а не просто увидеть
                // застрявший процесс Baritone.
                LiteBuilderClient.BUILD_MANAGER.startFromActiveSchematic();
            }
            public Status tick(MinecraftClient c) {
                return LiteBuilderClient.BUILD_MANAGER.isActive() ? Status.RUNNING : Status.DONE;
            }
            public String resultMessage() { return LiteBuilderClient.BUILD_MANAGER.getStatusReport(); }
        };
    }

    // ---------------------------------------------------------------- mine
    public static ToolTask mineTool(String... blockNames) {
        return new ToolTask() {
            public String name() { return "mine"; }
            public void start(MinecraftClient c) { BaritoneBridge.mineByName(blockNames); }
            public Status tick(MinecraftClient c) {
                return BaritoneBridge.isMining() ? Status.RUNNING : Status.DONE;
            }
            public String resultMessage() { return "копаю: " + String.join(", ", blockNames); }
        };
    }

    // ---------------------------------------------------------------- placeBlock
    public static ToolTask placeBlockTool(int x, int y, int z, Identifier blockId) {
        BlockPos pos = new BlockPos(x, y, z);
        Block block = Registries.BLOCK.get(blockId);
        return new ToolTask() {
            private boolean approached = false;
            public String name() { return "placeBlock"; }
            public void start(MinecraftClient c) { BaritoneBridge.gotoNear(pos, 3); }
            public Status tick(MinecraftClient c) {
                if (c.player == null) return Status.FAILED;
                if (!VanillaInteractionBridge.selectBlockInHotbar(c.player, block)) {
                    return Status.NEEDS_RESOURCES;
                }
                double d = c.player.getBlockPos().getSquaredDistance(pos);
                if (d > 25) { BaritoneBridge.gotoNear(pos, 3); return Status.RUNNING; }
                LiteBuilderClient.TASK_MANAGER.smoother.setTargetLookAt(c.player, Vec3d.ofCenter(pos));
                if (!LiteBuilderClient.TASK_MANAGER.smoother.isAimedAt(c.player, 5f)) return Status.RUNNING;
                return VanillaInteractionBridge.placeBlock(c, pos) ? Status.DONE : Status.RUNNING;
            }
            public String resultMessage() { return "поставил " + blockId + " на " + pos.toShortString(); }
        };
    }

    // ---------------------------------------------------------------- breakBlock
    public static ToolTask breakBlockTool(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return new ToolTask() {
            public String name() { return "breakBlock"; }
            public void start(MinecraftClient c) { BaritoneBridge.gotoNear(pos, 3); }
            public Status tick(MinecraftClient c) {
                if (c.player == null || c.world == null) return Status.FAILED;
                if (c.world.getBlockState(pos).isAir()) return Status.DONE;
                double d = c.player.getBlockPos().getSquaredDistance(pos);
                if (d > 25) { BaritoneBridge.gotoNear(pos, 3); return Status.RUNNING; }
                LiteBuilderClient.TASK_MANAGER.smoother.setTargetLookAt(c.player, Vec3d.ofCenter(pos));
                if (!LiteBuilderClient.TASK_MANAGER.smoother.isAimedAt(c.player, 5f)) return Status.RUNNING;
                boolean broke = VanillaInteractionBridge.continueBreaking(c, pos);
                return broke ? Status.DONE : Status.RUNNING;
            }
            public String resultMessage() { return "сломал блок на " + pos.toShortString(); }
        };
    }

    // ---------------------------------------------------------------- findChest
    public static ToolTask findChestTool(Identifier item, int radius) {
        return new ToolTask() {
            public String name() { return "findChest"; }
            public void start(MinecraftClient c) { LiteBuilderClient.TASK_MANAGER.findInChests(item, radius); }
            public Status tick(MinecraftClient c) { return Status.DONE; } // findInChests сам асинхронно доходит через TaskManager.onTick
            public String resultMessage() { return "ищу " + item + " в сундуках в радиусе " + radius; }
        };
    }

    // ---------------------------------------------------------------- takeItem
    /** Забирает предмет из уже открытого контейнера (сначала нужен openContainer). Shift-click в свой инвентарь. */
    public static ToolTask takeItemTool(Identifier item) {
        return new ToolTask() {
            public String name() { return "takeItem"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player.currentScreenHandler == c.player.playerScreenHandler) {
                    return Status.FAILED; // контейнер не открыт — сперва openContainer
                }
                var handler = c.player.currentScreenHandler;
                for (int i = 0; i < handler.slots.size(); i++) {
                    ItemStack stack = handler.getSlot(i).getStack();
                    if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).equals(item)) {
                        // QUICK_MOVE = обычный shift-клик, сам находит место в инвентаре игрока
                        c.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, c.player);
                        return Status.DONE;
                    }
                }
                return Status.NEEDS_RESOURCES;
            }
            public String resultMessage() { return "взял " + item + " из контейнера"; }
        };
    }

    // ---------------------------------------------------------------- craft
    /**
     * Упрощённый крафт: ищет среди зарегистрированных CraftingRecipe рецепт с нужным
     * выходным предметом, проверяет, что все ингредиенты есть в инвентаре, и раскладывает
     * их по слотам ТЕКУЩЕГО открытого экрана крафта (верстак должен быть уже открыт через
     * openContainer). Покрывает типовые shaped/shapeless рецепты 2x2/3x3.
     * ОГРАНИЧЕНИЕ: не разруливает рецепты с несколькими взаимозаменяемыми вариантами
     * одного слота по приоритету — берёт первый подходящий стек в инвентаре.
     */
    public static ToolTask craftTool(Identifier resultItem) {
        return new ToolTask() {
            public String name() { return "craft"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player.currentScreenHandler == c.player.playerScreenHandler) {
                    return Status.FAILED; // нужен открытый верстак
                }
                Optional<RecipeEntry<CraftingRecipe>> recipeOpt = c.world.getRecipeManager()
                    .listAllOfType(RecipeType.CRAFTING).stream()
                    .filter(r -> Registries.ITEM.getId(r.value().getResult(c.world.getRegistryManager()).getItem()).equals(resultItem))
                    .findFirst();
                if (recipeOpt.isEmpty()) return Status.FAILED;

                List<Ingredient> ingredients = recipeOpt.get().value().getIngredients();
                var handler = c.player.currentScreenHandler;
                // Слоты 1..N экрана крафта — сетка (0 — результат для обычного верстака-3x3
                // в GenericContainerScreenHandler макет другой; для базового CraftingScreenHandler
                // слот 0 = результат, слоты 1..9 = сетка 3x3).
                int gridSlot = 1;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) { gridSlot++; continue; }
                    int invSlot = findMatchingInventorySlot(c.player.getInventory(), ingredient);
                    if (invSlot < 0) return Status.NEEDS_RESOURCES;
                    int screenInvSlot = invSlot < 9 ? invSlot + 36 : invSlot + 27;
                    c.interactionManager.clickSlot(handler.syncId, screenInvSlot, 0, SlotActionType.PICKUP, c.player);
                    c.interactionManager.clickSlot(handler.syncId, gridSlot, 0, SlotActionType.PICKUP, c.player);
                    // Возвращаем остаток курсора обратно (если предмет стакался) — упрощённо игнорируем.
                    gridSlot++;
                }
                // Забираем результат из выходного слота (обычно слот 0).
                c.interactionManager.clickSlot(handler.syncId, 0, 0, SlotActionType.QUICK_MOVE, c.player);
                return Status.DONE;
            }
            public String resultMessage() { return "скрафтил " + resultItem; }
        };
    }

    // ---------------------------------------------------------------- smelt
    /** Кладёт сырьё в печь (слот 0) и топливо (слот 1) — печь должна быть уже открыта через openContainer. */
    public static ToolTask smeltTool(Identifier inputItem, Identifier fuelItem) {
        return new ToolTask() {
            public String name() { return "smelt"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player.currentScreenHandler == c.player.playerScreenHandler) {
                    return Status.FAILED; // нужна открытая печь
                }
                var handler = c.player.currentScreenHandler;
                int inputSlot = findInventorySlotByItem(c.player.getInventory(), inputItem);
                int fuelSlot = findInventorySlotByItem(c.player.getInventory(), fuelItem);
                if (inputSlot < 0 || fuelSlot < 0) return Status.NEEDS_RESOURCES;

                moveToFurnaceSlot(c, handler, inputSlot, 0);
                moveToFurnaceSlot(c, handler, fuelSlot, 1);
                return Status.DONE; // сама переплавка идёт по тикам сервера, дальше просто ждём результат
            }
            public String resultMessage() { return "загрузил печь: " + inputItem + " + " + fuelItem; }

            private void moveToFurnaceSlot(MinecraftClient c, net.minecraft.screen.ScreenHandler handler, int invSlot, int targetSlot) {
                int screenInvSlot = invSlot < 9 ? invSlot + 36 : invSlot + 27;
                c.interactionManager.clickSlot(handler.syncId, screenInvSlot, 0, SlotActionType.PICKUP, c.player);
                c.interactionManager.clickSlot(handler.syncId, targetSlot, 0, SlotActionType.PICKUP, c.player);
            }
        };
    }

    // ---------------------------------------------------------------- scanArea
    public static ToolTask scanAreaTool(int x, int y, int z, int radius) {
        BlockPos center = new BlockPos(x, y, z);
        return new ToolTask() {
            private String report = "";
            public String name() { return "scanArea"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.world == null) return Status.FAILED;
                Map<Block, Integer> counts = new HashMap<>();
                BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))
                    .forEach(p -> {
                        var state = c.world.getBlockState(p);
                        if (!state.isAir()) counts.merge(state.getBlock(), 1, Integer::sum);
                    });
                StringBuilder sb = new StringBuilder();
                counts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .limit(10)
                    .forEach(e -> sb.append(Registries.BLOCK.getId(e.getKey())).append(":").append(e.getValue()).append(" "));
                report = sb.toString();
                return Status.DONE;
            }
            public String resultMessage() { return "область вокруг " + center.toShortString() + ": " + report; }
        };
    }

    // ---------------------------------------------------------------- lookAt
    public static ToolTask lookAtTool(int x, int y, int z) {
        Vec3d target = Vec3d.ofCenter(new BlockPos(x, y, z));
        return new ToolTask() {
            public String name() { return "lookAt"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player == null) return Status.FAILED;
                LiteBuilderClient.TASK_MANAGER.smoother.setTargetLookAt(c.player, target);
                return LiteBuilderClient.TASK_MANAGER.smoother.isAimedAt(c.player, 3f) ? Status.DONE : Status.RUNNING;
            }
            public String resultMessage() { return "смотрю на " + target; }
        };
    }

    // ---------------------------------------------------------------- openContainer
    public static ToolTask openContainerTool(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return new ToolTask() {
            public String name() { return "openContainer"; }
            public void start(MinecraftClient c) { BaritoneBridge.gotoNear(pos, 3); }
            public Status tick(MinecraftClient c) {
                double d = c.player.getBlockPos().getSquaredDistance(pos);
                if (d > 16) { BaritoneBridge.gotoNear(pos, 3); return Status.RUNNING; }
                LiteBuilderClient.TASK_MANAGER.smoother.setTargetLookAt(c.player, Vec3d.ofCenter(pos));
                if (!LiteBuilderClient.TASK_MANAGER.smoother.isAimedAt(c.player, 5f)) return Status.RUNNING;
                return VanillaInteractionBridge.openContainer(c, pos) ? Status.DONE : Status.RUNNING;
            }
            public String resultMessage() { return "открыл контейнер на " + pos.toShortString(); }
        };
    }

    // ---------------------------------------------------------------- useItem
    public static ToolTask useItemTool() {
        return new ToolTask() {
            public String name() { return "useItem"; }
            public void start(MinecraftClient c) { VanillaInteractionBridge.useItemInMainHand(c); }
            public Status tick(MinecraftClient c) { return Status.DONE; }
            public String resultMessage() { return "использовал предмет в руке"; }
        };
    }

    // ---------------------------------------------------------------- checkInventory
    public static ToolTask checkInventoryTool() {
        return new ToolTask() {
            private String report = "";
            public String name() { return "checkInventory"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player == null) return Status.FAILED;
                var inv = c.player.getInventory();
                Map<Identifier, Integer> counts = new HashMap<>();
                for (int i = 0; i < inv.size(); i++) {
                    ItemStack st = inv.getStack(i);
                    if (!st.isEmpty()) counts.merge(Registries.ITEM.getId(st.getItem()), st.getCount(), Integer::sum);
                }
                StringBuilder sb = new StringBuilder();
                counts.forEach((id, n) -> sb.append(id).append(":").append(n).append(" "));
                report = sb.toString();
                return Status.DONE;
            }
            public String resultMessage() { return "инвентарь: " + report; }
        };
    }

    // ---------------------------------------------------------------- checkWorld
    public static ToolTask checkWorldTool() {
        return new ToolTask() {
            private String report = "";
            public String name() { return "checkWorld"; }
            public void start(MinecraftClient c) {}
            public Status tick(MinecraftClient c) {
                if (c.player == null || c.world == null) return Status.FAILED;
                BlockPos pos = c.player.getBlockPos();
                report = String.format(
                    "поз=%s здоровье=%.1f голод=%d время=%d измерение=%s",
                    pos.toShortString(), c.player.getHealth(), c.player.getHungerManager().getFoodLevel(),
                    c.world.getTimeOfDay() % 24000, c.world.getRegistryKey().getValue()
                );
                return Status.DONE;
            }
            public String resultMessage() { return report; }
        };
    }

    // ---------------------------------------------------------------- helpers

    private static int findMatchingInventorySlot(Inventory inv, Ingredient ingredient) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && ingredient.test(stack)) return i;
        }
        return -1;
    }

    private static int findInventorySlotByItem(Inventory inv, Identifier item) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).equals(item)) return i;
        }
        return -1;
    }
}
