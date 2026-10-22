package buildcraft.api.v2.energy;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Runtime view of BuildCraft's MJ/external-energy policy and MJ endpoint lookup. */
public interface EnergyService {
    EnergyConversion conversion();
    boolean automaticFeConversionEnabled();
    boolean displayForgeEnergy();

    default EnergyConversionStatus status() {
        return new EnergyConversionStatus(conversion(), automaticFeConversionEnabled(), displayForgeEnergy(), EnergyRateUnit.PER_SECOND);
    }

    default Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
        return Optional.empty();
    }

    default Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
        return Optional.empty();
    }

    /** Live server-side demand query; avoids probing a port with a simulated insertion. */
    default Optional<MjAmount> requested(Level level, BlockPos pos, Direction side) {
        return port(level, pos, side).filter(MjDemandPort.class::isInstance)
            .map(MjDemandPort.class::cast).map(MjDemandPort::requested);
    }

    /** Applies public connection rules and the platform compatibility bridge for a prospective MJ edge. */
    default boolean canConnect(MjConnectionContext context) {
        return true;
    }

    /**
     * Creates the standard BuildCraft MJ storage implementation without exposing Lib internals.
     */
    default MjStorage createStorage(MjAmount capacity) {
        return createStorage(capacity, MjAmount.ZERO);
    }

    /**
     * Creates the standard BuildCraft MJ storage implementation with an initial amount.
     */
    default MjStorage createStorage(MjAmount capacity, MjAmount initial) {
        throw new UnsupportedOperationException("This EnergyService does not provide MJ storage construction");
    }

    /** Construct a rate-limited, optionally overchargeable or redstone-capable buffer. */
    default MjStorage createStorage(MjStoragePolicy policy, MjAmount initial) {
        throw new UnsupportedOperationException("This EnergyService does not support configurable MJ storage");
    }

    /** Server tick hook for externally-owned MJ buffers. No world or chunk is loaded by this call. */
    default MjAmount tickStorage(MjStorage storage, Level level, BlockPos position) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        if (!level.hasChunkAt(position) || level.getServer() == null) return MjAmount.ZERO;
        return storage.tick(level.getGameTime());
    }
}
