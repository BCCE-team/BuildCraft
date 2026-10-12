package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.energy.EnergyConversion;
import buildcraft.api.v2.energy.EnergyConversionStatus;
import buildcraft.api.v2.energy.EnergyRateUnit;
import buildcraft.api.v2.energy.EnergyService;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjConnectionRule;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjStorage;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortProvider;
import buildcraft.api.v2.machine.MachineView;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.internal.api.v2.energy.MjStorageImpl;
import buildcraft.lib.BCLibConfig;

final class EnergyServiceImpl implements EnergyService {
    @Override
    public EnergyConversion conversion() {
        return new EnergyConversion(BCLibConfig.mjFeConversion.mjPerFe);
    }

    @Override public boolean automaticFeConversionEnabled() { return BCLibConfig.powerMode.isAutoconvertEnabled(); }
    @Override public boolean displayForgeEnergy() { return BCLibConfig.powerMode.isDisplayFe(); }

    @Override
    public EnergyConversionStatus status() {
        EnergyRateUnit rateUnit = BCLibConfig.displayTimeGap == BCLibConfig.TimeGap.TICKS
            ? EnergyRateUnit.PER_TICK
            : EnergyRateUnit.PER_SECOND;
        return new EnergyConversionStatus(conversion(), automaticFeConversionEnabled(), displayForgeEnergy(), rateUnit);
    }

    @Override
    public Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MjPortProvider provider) {
            Optional<MjPort> direct = provider.mjPort(side);
            if (direct.isPresent()) return direct;
        }
        if (blockEntity instanceof MachineView machine) {
            Optional<MjPort> direct = machine.mjPort(side);
            if (direct.isPresent()) return direct;
        }
        if (blockEntity instanceof MachineHostProvider provider) {
            Optional<MjPort> direct = provider.machineHost().mjPort(side);
            if (direct.isPresent()) return direct;
        }
        return MjRuntimeLookup.port(level, pos, side);
    }

    @Override
    public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof MjPortProvider provider) {
            Optional<MjPortDescriptor> direct = provider.mjPortDescriptor(side);
            if (direct.isPresent()) return direct;
            Optional<MjPort> port = provider.mjPort(side);
            if (port.isPresent()) return Optional.of(describePort(port.get()));
        }
        Optional<MjPortDescriptor> platform = MjRuntimeLookup.descriptor(level, pos, side);
        if (platform.isPresent()) return platform;
        if (blockEntity instanceof MachineView machine) {
            Optional<MjPort> port = machine.mjPort(side);
            if (port.isPresent()) return Optional.of(describePort(port.get()));
        }
        if (blockEntity instanceof MachineHostProvider provider) {
            Optional<MjPort> port = provider.machineHost().mjPort(side);
            if (port.isPresent()) return Optional.of(describePort(port.get()));
        }
        return Optional.empty();
    }

    @Override
    public boolean canConnect(MjConnectionContext context) {
        for (MjConnectionRule rule : BuildCraftApi.registry(BuildCraftRegistries.MJ_CONNECTION_RULES).values()) {
            if (!rule.canConnect(context)) return false;
        }
        return MjRuntimeLookup.canConnect(context);
    }

    private static MjPortDescriptor describePort(MjPort port) {
        EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.READABLE);
        if (port.canInsert()) roles.add(MjPortRole.CONSUMER);
        if (port.canExtract()) roles.add(MjPortRole.PROVIDER);
        return new MjPortDescriptor(java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcraft:mj")),
            roles,
            port.canInsert() ? MjAmount.ofMicro(Long.MAX_VALUE) : MjAmount.ZERO,
            port.canExtract() ? MjAmount.ofMicro(Long.MAX_VALUE) : MjAmount.ZERO);
    }

    @Override
    public MjStorage createStorage(MjAmount capacity, MjAmount initial) {
        return new MjStorageImpl(capacity, initial);
    }

}
