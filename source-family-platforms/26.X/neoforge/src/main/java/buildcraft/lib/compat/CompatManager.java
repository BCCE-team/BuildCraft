package buildcraft.lib.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class CompatManager {
    public static final ISoftBlockAccessor blockAccessor;

    public static BlockEntity getTile(Level world, BlockPos pos, boolean force) {
        return blockAccessor.getTile(world, pos, force);
    }

    public static BlockState getState(Level world, BlockPos pos, boolean force) {
        return blockAccessor.getState(world, pos, force);
    }

    static {
        blockAccessor = DefaultBlockAccessor.VIA_CHUNK;
    }
}
