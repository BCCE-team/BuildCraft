package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.LaserTarget;
import buildcraft.api.v2.machine.LaserTargetService;
import buildcraft.api.v2.machine.LaserTargetProvider;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

final class LaserTargetServiceImpl implements LaserTargetService {
    public Optional<LaserTarget> target(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return Optional.empty();
        try {
            Optional<LaserTarget> target = blockEntity instanceof LaserTargetProvider provider
                ? provider.laserTarget(side)
                : blockEntity instanceof LaserTarget direct ? Optional.of(direct) : Optional.empty();
            if (target == null || target.isEmpty()) return Optional.empty();
            LaserTarget receiver = target.get();
            buildcraft.api.v2.energy.MjPort port = receiver.laserPort();
            return port != null && port.canInsert() ? Optional.of(receiver) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
