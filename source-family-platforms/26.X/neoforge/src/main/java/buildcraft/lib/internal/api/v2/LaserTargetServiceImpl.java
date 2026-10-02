package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.LaserTarget;
import buildcraft.api.v2.machine.LaserTargetService;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

final class LaserTargetServiceImpl implements LaserTargetService {
    public Optional<LaserTarget> target(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof LaserTarget target ? Optional.of(target) : Optional.empty();
    }
}
