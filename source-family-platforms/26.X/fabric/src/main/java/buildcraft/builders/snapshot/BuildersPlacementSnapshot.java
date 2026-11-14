/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.builders.snapshot;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;

/**
 * Exact pre-placement snapshot used by Builder/Filler rollback. Unlike a state-only
 * restore this also reinstates block-entity data, preventing a failed schematic
 * placement from partially consuming or overwriting an existing machine.
 */
final class BuildersPlacementSnapshot {
    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState state;
    private final CompoundTag blockEntityData;

    private BuildersPlacementSnapshot(ServerLevel level, BlockPos pos, BlockState state,
                                      CompoundTag blockEntityData) {
        this.level = level;
        this.pos = pos.immutable();
        this.state = state;
        this.blockEntityData = blockEntityData == null ? null : blockEntityData.copy();
    }

    static BuildersPlacementSnapshot capture(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        CompoundTag data = blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
        return new BuildersPlacementSnapshot(level, pos, state, data);
    }

    boolean restore() {
        BlockState current = level.getBlockState(pos);
        if (!current.equals(state)) {
            level.setBlock(pos, state, 3);
        }
        if (!level.getBlockState(pos).equals(state)) {
            return false;
        }
        if (blockEntityData == null) {
            return level.getBlockEntity(pos) == null;
        }
        BlockEntity restored = level.getBlockEntity(pos);
        if (restored == null) {
            return false;
        }
        restored.loadWithComponents(TagValueInput.create(
            ProblemReporter.DISCARDING,
            level.registryAccess(),
            blockEntityData.copy()
        ));
        restored.setChanged();
        return true;
    }
}
