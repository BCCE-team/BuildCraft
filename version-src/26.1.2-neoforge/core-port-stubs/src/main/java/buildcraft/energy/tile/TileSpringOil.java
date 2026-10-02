package buildcraft.energy.tile;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Compilation boundary until the energy module is migrated. */
public final class TileSpringOil extends BlockEntity {
    public TileSpringOil(BlockPos pos, BlockState state) {
        super(null, pos, state);
    }
}
