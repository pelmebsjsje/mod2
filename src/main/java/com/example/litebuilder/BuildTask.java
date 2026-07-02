package com.example.litebuilder;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * Одна ячейка схемы: где и какой блок должен появиться.
 */
public class BuildTask {

    public enum Status { PENDING, IN_PROGRESS, DONE, FAILED, SKIPPED }

    public final BlockPos pos;
    public final BlockState targetState;
    public Status status = Status.PENDING;
    public int attempts = 0;

    public BuildTask(BlockPos pos, BlockState targetState) {
        this.pos = pos;
        this.targetState = targetState;
    }
}
