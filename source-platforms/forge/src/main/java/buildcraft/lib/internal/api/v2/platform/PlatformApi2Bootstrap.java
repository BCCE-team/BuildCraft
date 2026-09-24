package buildcraft.lib.internal.api.v2.platform;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.PlatformTransferLookup;
import buildcraft.lib.internal.transfer.TransferAdapters;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/** Forge-native endpoint discovery for the shared API2 platform service. */
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
            BlockEntity tile = tile(level, pos);
            if (tile == null) return Optional.empty();
            IItemHandler nativeStorage = tile.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            ItemStorage storage = StorageAdapters.fromNativeItems(nativeStorage);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.items(storage));
        }

        @Override
        public Optional<FluidTransferAccess> fluids(Level level, BlockPos pos, Direction side) {
            BlockEntity tile = tile(level, pos);
            if (tile == null) return Optional.empty();
            IFluidHandler nativeStorage = tile.getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
            FluidStorage<FluidStack> storage = StorageAdapters.fromNativeFluids(nativeStorage);
            return storage == null ? Optional.empty()
                : Optional.of(TransferAdapters.fluids(storage, FuelApiBridge.CARRIER, FuelApiBridge.MATCH_CONTEXT));
        }

        @Override
        public Optional<EnergyTransferAccess> energy(Level level, BlockPos pos, Direction side) {
            BlockEntity tile = tile(level, pos);
            if (tile == null) return Optional.empty();
            IEnergyStorage nativeStorage = tile.getCapability(ForgeCapabilities.ENERGY, side).orElse(null);
            EnergyStorage storage = StorageAdapters.fromNativeEnergy(nativeStorage);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.energy(storage));
        }

        private static BlockEntity tile(Level level, BlockPos pos) {
            return level == null || pos == null ? null : level.getBlockEntity(pos);
        }
    }
}
