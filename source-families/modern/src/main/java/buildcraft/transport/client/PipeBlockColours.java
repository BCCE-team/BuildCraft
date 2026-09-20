//? source if >=1.21.11
package buildcraft.transport.client;

import javax.annotation.Nullable;

import buildcraft.transport.client.model.ModelPipeNative121111;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.tile.TilePipeHolder;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public enum PipeBlockColours implements BlockColor {
    INSTANCE;

    public int getColor(BlockState state, @Nullable BlockAndTintGetter world, @Nullable BlockPos pos, int tintIndex) {
        // Native 1.21.11 pipe-body quads encode their recovered material RGB directly in the tint index. Resolve these
        // without touching a live block entity: block-model colour evaluation also happens from chunk render workers.
        if (ModelPipeNative121111.isPipeTint(tintIndex)) {
            return ModelPipeNative121111.pipeTintColour(tintIndex);
        }

        // Keep the legacy pluggable tint path for pluggable geometry that uses the compact legacy tint indices.
        if (world != null && pos != null) {
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile instanceof TilePipeHolder tilePipeHolder) {
                Direction side = Direction.from3DDataValue(tintIndex % Direction.values().length);
                PipePluggable pluggable = tilePipeHolder.getPluggable(side);
                if (pluggable != PipePluggable.EMPTY) {
                    return pluggable.getBlockColor(tintIndex / 6);
                }
            }
        }
        return -1;
    }
}
