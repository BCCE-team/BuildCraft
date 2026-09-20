package buildcraft.lib.internal.api.v2.platform;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.platform.EnergyTransfer;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.platform.FluidTransfer;
import buildcraft.api.v2.platform.ItemTransfer;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.misc.CapUtil;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;

/** NeoForge capability bridge backing the loader-neutral API2 platform service. */
public final class PlatformApi2Bootstrap {
    private static boolean installed;

    private PlatformApi2Bootstrap() {}

    public static synchronized void install() {
        if (installed) return;
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.PLATFORM).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.PLATFORM, Services.INSTANCE);
        }
        installed = true;
    }

    private enum Services implements PlatformServices {
        INSTANCE;
        @Override public Optional<ItemTransfer> itemTransfer() { return Optional.of(ItemTransferImpl.INSTANCE); }
        @Override public Optional<FluidTransfer> fluidTransfer() { return Optional.of(FluidTransferImpl.INSTANCE); }
        @Override public Optional<EnergyTransfer> energyTransfer() { return Optional.of(EnergyTransferImpl.INSTANCE); }
    }

    private enum ItemTransferImpl implements ItemTransfer {
        INSTANCE;
        @Override
        public Optional<ItemPort> find(Level level, BlockPos pos, Direction side) {
            if (level == null || pos == null) return Optional.empty();
            IItemHandler handler = CapUtil.getItemHandler(level, pos, side);
            return handler == null ? Optional.empty() : Optional.of(new NeoForgeItemPort(handler));
        }
    }

    private enum FluidTransferImpl implements FluidTransfer {
        INSTANCE;
        @Override
        public Optional<FluidPort> find(Level level, BlockPos pos, Direction side) {
            if (level == null || pos == null) return Optional.empty();
            IFluidHandler handler = CapUtil.getFluidHandler(level, pos, side);
            return handler == null ? Optional.empty() : Optional.of(new NeoForgeFluidPort(handler));
        }
    }

    private enum EnergyTransferImpl implements EnergyTransfer {
        INSTANCE;
        @Override
        public Optional<ExternalEnergyPort> find(Level level, BlockPos pos, Direction side) {
            if (level == null || pos == null) return Optional.empty();
            IEnergyStorage storage = CapUtil.getEnergyStorage(level, pos, side);
            return storage == null ? Optional.empty() : Optional.of(new NeoForgeEnergyPort(storage));
        }
    }

    private record NeoForgeItemPort(IItemHandler handler) implements ItemPort {
        @Override
        public ItemTransferResult insert(ItemStack offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
            ItemStack remainder = offered.copy();
            boolean simulate = mode == OperationMode.SIMULATE;
            for (int slot = 0; slot < handler.getSlots() && !remainder.isEmpty(); slot++) {
                remainder = handler.insertItem(slot, remainder, simulate);
            }
            return ItemTransferResult.ofInsertion(offered, offered.getCount() - remainder.getCount());
        }

        @Override
        public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode) {
            if (matcher == null || maxCount <= 0) return ItemTransferResult.nothing(Math.max(0, maxCount));
            boolean simulate = mode == OperationMode.SIMULATE;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stored = handler.getStackInSlot(slot);
                if (stored == null || stored.isEmpty() || !matcher.matches(stored)) continue;
                ItemStack extracted = handler.extractItem(slot, maxCount, simulate);
                if (extracted != null && !extracted.isEmpty()) return ItemTransferResult.ofExtraction(maxCount, extracted);
            }
            return ItemTransferResult.nothing(maxCount);
        }
    }

    private record NeoForgeFluidPort(IFluidHandler handler) implements FluidPort {
        @Override
        public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) return FluidTransferResult.nothing(offered == null ? FluidAmount.ZERO : offered.amount());
            FluidStack stack = FuelApiBridge.stackOf(offered);
            if (stack.isEmpty()) return FluidTransferResult.nothing(offered.amount());
            int accepted = handler.fill(stack, mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
            return FluidTransferResult.ofInsertion(offered, FluidAmount.of(Math.max(0, accepted)));
        }

        @Override
        public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationMode mode) {
            if (matcher == null || maxAmount == null || maxAmount.isZero()) {
                return FluidTransferResult.nothing(maxAmount == null ? FluidAmount.ZERO : maxAmount);
            }
            int limit = (int) Math.min(Integer.MAX_VALUE, maxAmount.milliBuckets());
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                FluidStack stored = handler.getFluidInTank(tank);
                if (stored == null || stored.isEmpty()) continue;
                if (!matcher.matches(FuelApiBridge.variantOf(stored), FuelApiBridge.MATCH_CONTEXT)) continue;
                FluidStack request = stored.copyWithAmount(Math.min(limit, stored.getAmount()));
                FluidStack drained = handler.drain(request, mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
                return FluidTransferResult.ofExtraction(maxAmount, FuelApiBridge.volumeOf(drained));
            }
            return FluidTransferResult.nothing(maxAmount);
        }
    }

    private record NeoForgeEnergyPort(IEnergyStorage storage) implements ExternalEnergyPort {
        @Override public long insert(long offered, OperationMode mode) {
            int request = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, offered));
            return storage.receiveEnergy(request, mode == OperationMode.SIMULATE);
        }
        @Override public long extract(long requested, OperationMode mode) {
            int request = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, requested));
            return storage.extractEnergy(request, mode == OperationMode.SIMULATE);
        }
        @Override public long stored() { return storage.getEnergyStored(); }
        @Override public long capacity() { return storage.getMaxEnergyStored(); }
        @Override public boolean canInsert() { return storage.canReceive(); }
        @Override public boolean canExtract() { return storage.canExtract(); }
    }
}
