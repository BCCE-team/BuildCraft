package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.machine.MachineService;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.MachineHost;
import buildcraft.api.v2.machine.MachineHostOwner;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.api.v2.machine.MachineView;
import buildcraft.api.v2.machine.HeatPort;
import buildcraft.api.v2.machine.HeatPortProvider;
import buildcraft.lib.platform.capability.PlatformCapabilities;
import buildcraft.lib.internal.tiles.IHasWork;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
    @Override
    public Optional<Boolean> hasWork(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity block = level.getBlockEntity(pos);
        if (block == null) return Optional.empty();
        try {
            IHasWork nativeWork = PlatformCapabilities.hasWork(block, side);
            if (nativeWork != null) return Optional.of(nativeWork.hasWork());
            if (block instanceof MachineHostProvider provider) {
                MachineHost host = provider.machineHost();
                if (host != null) return Optional.of(host.hasWork());
            }
            if (block instanceof MachineView machine) return Optional.of(machine.hasWork());
        } catch (RuntimeException ignored) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    @Override
    public Optional<HeatPort> heat(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity block = level.getBlockEntity(pos);
        if (block == null) return Optional.empty();
        try {
            if (block instanceof HeatPortProvider provider) return provider.heatPort(side);
            if (block instanceof MachineHostProvider provider) {
                MachineHost host = provider.machineHost();
                if (host != null) return host.heatPort();
            }
            if (block instanceof MachineView machine) return machine.heatPort();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
