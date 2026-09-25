package buildcraft.lib.internal.api.v2.platform;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.FluidCarrier;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.PlatformTransferLookup;
import buildcraft.lib.internal.transfer.TransferAdapters;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import buildcraft.lib.platform.storage.PlatformStorage;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;

/** Fabric-native endpoint discovery for the shared API v2 platform service. */
public final class PlatformApi2Bootstrap {
    private static boolean installed;
    private static final FluidMatchContext FLUID_MATCH = (fluidId, tagId) -> {
        var fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(fluidId);
        if (fluid == null) return false;
        return fluid.builtInRegistryHolder().is(TagKey.create(Registries.FLUID, tagId));
    };
    private static final FluidCarrier<FluidVolume> FLUIDS = new FluidCarrier<>() {
        @Override public boolean isEmpty(FluidVolume stack) { return stack == null || stack.isEmpty(); }
        @Override public int amount(FluidVolume stack) { return stack == null ? 0 : (int) Math.min(Integer.MAX_VALUE, stack.amount().milliBuckets()); }
        @Override public FluidVolume toVolume(FluidVolume stack) { return stack == null ? FluidVolume.empty() : stack; }
        @Override public FluidVolume fromVolume(FluidVolume volume) { return volume == null ? FluidVolume.empty() : volume; }
        @Override public FluidVolume copyWithAmount(FluidVolume stack, int amount) {
            return stack == null || stack.isEmpty() || amount <= 0 ? FluidVolume.empty() : stack.withAmount(buildcraft.api.v2.fluid.FluidAmount.of(amount));
        }
    };

    private PlatformApi2Bootstrap() { }

    public static synchronized void install() {
        if (installed) return;
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.PLATFORM).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.PLATFORM, new DefaultPlatformServices(Lookup.INSTANCE));
        }
        installed = true;
    }

    private enum Lookup implements PlatformTransferLookup {
        INSTANCE;
        @Override public Optional<ItemTransferAccess> items(Level level, BlockPos pos, Direction side) {
            ItemStorage storage = PlatformStorage.items(level, pos, side);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.items(storage));
        }
        @Override public Optional<FluidTransferAccess> fluids(Level level, BlockPos pos, Direction side) {
            FluidStorage<FluidVolume> storage = PlatformStorage.fluids(level, pos, side);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.fluids(storage, FLUIDS, FLUID_MATCH));
        }
        @Override public Optional<EnergyTransferAccess> energy(Level level, BlockPos pos, Direction side) {
            EnergyStorage storage = PlatformStorage.energy(level, pos, side);
            return storage == null ? Optional.empty() : Optional.of(TransferAdapters.energy(storage));
        }
    }
}
