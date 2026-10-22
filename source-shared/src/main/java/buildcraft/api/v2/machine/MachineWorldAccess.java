package buildcraft.api.v2.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Optional block mutation authority supplied by the addon block entity. */
@FunctionalInterface
public interface MachineWorldAccess {
    /** Must enforce its own claims/permission rules. Returning false leaves the world unchanged. */
    boolean setBlock(BlockPos pos, BlockState state);
}
