package buildcraft.lib.internal.api.v2.platform;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.PlatformTransferLookup;
import buildcraft.lib.internal.transfer.TransferAdapters;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Fabric-native endpoint discovery for the shared API v2 platform service. */
public final class PlatformApi2Bootstrap {
    private static boolean installed;

    private PlatformApi2Bootstrap() { }

    public static synchronized void install() {
        if (installed) return;
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.PLATFORM).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(
                BuildCraftServices.PLATFORM,
                new DefaultPlatformServices(Lookup.INSTANCE)
            );
        }
        installed = true;
    }

    private enum Lookup implements PlatformTransferLookup {
        INSTANCE;

        @Override
        public Optional<ItemTransferAccess> items(Level level, BlockPos pos, Direction side) {
            // Fabric Storage<ItemVariant> is slotless unless it explicitly implements SlottedStorage.
            // Keep API v2 on the slotless transfer boundary so dynamic/modded storages remain valid endpoints.
            return Optional.ofNullable(PlatformStorage.itemTransfer(level, pos, side));
        }

        @Override
        public Optional<FluidTransferAccess> fluids(Level level, BlockPos pos, Direction side) {
            // Generic Fabric Storage<FluidVariant> is transaction-native and not necessarily slotted.
            return Optional.ofNullable(PlatformStorage.fluidTransfer(level, pos, side));
        }

        @Override
        public Optional<EnergyTransferAccess> energy(Level level, BlockPos pos, Direction side) {
            EnergyStorage storage = PlatformStorage.energy(level, pos, side);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.energy(storage));
        }
    }
}
