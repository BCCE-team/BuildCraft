package buildcraft.lib.internal.api.v2.platform;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.PlatformTransferLookup;
import buildcraft.lib.internal.transfer.TransferAdapters;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** NeoForge-native endpoint discovery for the shared API2 platform service. */
public final class PlatformApi2Bootstrap {
    private static boolean installed;

    private PlatformApi2Bootstrap() {}

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
            if (level == null || pos == null) return Optional.empty();
            IItemHandler nativeStorage = CapUtil.getItemHandler(level, pos, side);
            ItemStorage storage = StorageAdapters.fromNativeItems(nativeStorage);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.items(storage));
        }

        @Override
        public Optional<FluidTransferAccess> fluids(Level level, BlockPos pos, Direction side) {
            if (level == null || pos == null) return Optional.empty();
            IFluidHandler nativeStorage = CapUtil.getFluidHandler(level, pos, side);
            FluidStorage<FluidStack> storage = StorageAdapters.fromNativeFluids(nativeStorage);
            return storage == null ? Optional.empty()
                : Optional.of(TransferAdapters.fluids(storage, FuelApiBridge.CARRIER, FuelApiBridge.MATCH_CONTEXT));
        }

        @Override
        public Optional<EnergyTransferAccess> energy(Level level, BlockPos pos, Direction side) {
            if (level == null || pos == null) return Optional.empty();
            IEnergyStorage nativeStorage = CapUtil.getEnergyStorage(level, pos, side);
            EnergyStorage storage = StorageAdapters.fromNativeEnergy(nativeStorage);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.energy(storage));
        }
    }
}
