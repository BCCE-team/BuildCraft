package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.MachineService;
import buildcraft.api.v2.machine.MachineView;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

final class MachineServiceImpl implements MachineService {
    public Optional<MachineView> machine(Level level, BlockPos pos) {
        if (level == null || pos == null) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof MachineView view ? Optional.of(view) : Optional.empty();
    }
}
