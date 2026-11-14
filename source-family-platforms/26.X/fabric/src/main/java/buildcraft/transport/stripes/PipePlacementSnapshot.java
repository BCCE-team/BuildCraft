/* BuildCraft Fabric 26.3 safe Stripes rollback snapshot. MPL-2.0. */
package buildcraft.transport.stripes;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Stripes pipe needs both block state and block entity data when a
 * protection mod denies an adjacent relocation. Unlike NeoForge's
 * BlockSnapshot, this snapshot does not post an event: all placements go
 * through BlockUtil.placeBlock BEFORE the world is mutated.
 */
final class PipePlacementSnapshot {
    private final Level level;
    private final BlockPos pos;
    private final BlockState state;
    @Nullable private final CompoundTag blockEntity;

    private PipePlacementSnapshot(Level level, BlockPos pos, BlockState state,
        @Nullable CompoundTag blockEntity) {
        this.level = level;
        this.pos = pos.immutable();
        this.state = state;
        this.blockEntity = blockEntity;
    }

    static PipePlacementSnapshot capture(Level level, BlockPos pos) {
        BlockEntity tile = level.getBlockEntity(pos);
        return new PipePlacementSnapshot(level, pos, level.getBlockState(pos),
            tile == null ? null : tile.saveWithFullMetadata(level.registryAccess()).copy());
    }

    BlockState getState() { return state; }

    void restore() {
        level.setBlock(pos, state, 3);
        if (blockEntity != null) {
            BlockEntity restored = level.getBlockEntity(pos);
            if (restored != null) {
                restored.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                    net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), blockEntity.copy()));
                restored.setChanged();
            }
        }
    }
}
