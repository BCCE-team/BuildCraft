package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.MachineService;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.MachineHost;
import buildcraft.api.v2.machine.MachineHostOwner;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.api.v2.machine.MachineView;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

final class MachineServiceImpl implements MachineService {
    @Override
    public MachineHost createHost(MachineType type, MachineHostOwner owner) {
        return new MachineRuntimeHost(type, owner);
    }

    @Override
    public Optional<MachineView> machine(Level level, BlockPos pos) {
        if (level == null || pos == null) return Optional.empty();
        if (!level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MachineHostProvider provider) {
            return Optional.ofNullable(provider.machineHost());
        }
        return blockEntity instanceof MachineView view ? Optional.of(view) : Optional.empty();
    }
}
