package buildcraft.lib.internal.api.v2.platform;

import buildcraft.api.v2.platform.EnergyTransfer;
import buildcraft.api.v2.platform.FluidTransfer;
import buildcraft.api.v2.platform.ItemTransfer;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.lib.internal.transfer.PlatformTransferLookup;
import buildcraft.lib.internal.transfer.TransferAdapters;
import java.util.Objects;
import java.util.Optional;

/** Shared API2 service facade; loaders own only native endpoint discovery. */
public final class DefaultPlatformServices implements PlatformServices {
    private final PlatformTransferLookup lookup;
    private final ItemTransfer items;
    private final FluidTransfer fluids;
    private final EnergyTransfer energy;

    public DefaultPlatformServices(PlatformTransferLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.items = (level, pos, side) -> this.lookup.items(level, pos, side).map(TransferAdapters::itemPort);
        this.fluids = (level, pos, side) -> this.lookup.fluids(level, pos, side).map(TransferAdapters::fluidPort);
        this.energy = (level, pos, side) -> this.lookup.energy(level, pos, side).map(TransferAdapters::energyPort);
    }

    @Override public Optional<ItemTransfer> itemTransfer() { return Optional.of(items); }
    @Override public Optional<FluidTransfer> fluidTransfer() { return Optional.of(fluids); }
    @Override public Optional<EnergyTransfer> energyTransfer() { return Optional.of(energy); }
}
