package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.SlottedItemPort;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.IMjPassiveProvider;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.misc.CapUtil;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.capabilities.BlockCapability;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Loader boundary mapping a publicly registered endpoint or attachment to native pipe capabilities. */
public final class ApiPortCapabilities {
    private ApiPortCapabilities() { }

    @SuppressWarnings("unchecked")
    public static <T> T attachment(PipeAttachment attachment, BlockCapability<T, Direction> capability) {
        if (attachment == null) return null;
        try {
            return adapt(attachment.itemPort().or(() -> attachment.itemPipePort().map(p -> (ItemPort) p)),
            attachment.fluidPort(), attachment.mjPort(), attachment.externalEnergyPort(), capability);
        } catch (RuntimeException ex) { return null; }
    }

    public static <T> T external(PipeExternalEndpoint endpoint, BlockCapability<T, Direction> capability) {
        if (endpoint == null) return null;
        try {
            return adapt(endpoint.itemPort(), endpoint.fluidPort(), endpoint.mjPort(), endpoint.externalEnergyPort(), capability);
        } catch (RuntimeException ex) { return null; }
    }

    @SuppressWarnings("unchecked")
    private static <T> T adapt(Optional<ItemPort> items, Optional<FluidPort> fluids,
        Optional<MjPort> mj, Optional<ExternalEnergyPort> fe, BlockCapability<T, Direction> capability) {
        try {
            if (capability == CapUtil.CAP_ITEMS && items.isPresent()) return (T) new ItemAdapter(items.get());
            if (capability == CapUtil.CAP_FLUIDS && fluids.isPresent()) return (T) new FluidAdapter(fluids.get());
            if (capability == CapUtil.CAP_FE && fe.isPresent()) return (T) new EnergyAdapter(fe.get());
            if (capability == MjCapabilities.CAP_PASSIVE_PROVIDER && mj.isPresent())
                return (T) new MjPassiveAdapter(mj.get());
            if ((capability == MjCapabilities.CAP_CONNECTOR || capability == MjCapabilities.CAP_RECEIVER)
                && mj.isPresent()) return (T) new MjAdapter(mj.get());
        } catch (RuntimeException ex) {
            // An addon port must not break capability queries for the whole pipe holder.
        }
        return null;
    }

    private record ItemAdapter(ItemPort port) implements IItemHandler {
        @Override public int getSlots() { return port instanceof SlottedItemPort slots ? Math.max(0, slots.slots()) : 1; }
        @Override public ItemStack getStackInSlot(int slot) {
            if (port instanceof SlottedItemPort slots && slot >= 0 && slot < slots.slots()) {
                try { return slots.stackInSlot(slot).copy(); } catch (RuntimeException ignored) { }
            }
            return ItemStack.EMPTY;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (slot < 0 || slot >= getSlots() || stack.isEmpty()) return stack;
            try {
                int moved = port.insert(stack.copy(), simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)
                    .transferredCount();
                if (moved <= 0) return stack;
                ItemStack remainder = stack.copy();
                remainder.shrink(Math.min(stack.getCount(), moved));
                return remainder;
            } catch (RuntimeException ex) { return stack; }
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot < 0 || slot >= getSlots() || amount <= 0) return ItemStack.EMPTY;
            try {
                ItemMatcher matcher = port instanceof SlottedItemPort slots ? slots.filter(slot) : ItemMatcher.any();
                ItemStack moved = port.extract(matcher, amount,
                    simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE).transferred();
                if (moved.getCount() > amount) { ItemStack capped = moved.copy(); capped.setCount(amount); return capped; }
                return moved;
            } catch (RuntimeException ex) { return ItemStack.EMPTY; }
        }
        @Override public int getSlotLimit(int slot) { return slot >= 0 && slot < getSlots() ? 64 : 0; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return slot >= 0 && slot < getSlots(); }
    }

    private record FluidAdapter(FluidPort port) implements IFluidHandler {
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) {
            if (tank != 0) return FluidStack.EMPTY;
            try { return FuelApiBridge.stackOf(port.extract(FluidMatcher.any(),
                FluidAmount.of(Integer.MAX_VALUE), OperationMode.SIMULATE).transferred()); }
            catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
        @Override public int getTankCapacity(int tank) { return tank == 0 ? Integer.MAX_VALUE : 0; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return tank == 0 && !stack.isEmpty(); }
        @Override public int fill(FluidStack stack, FluidAction action) {
            if (stack.isEmpty()) return 0;
            try {
                FluidVolume offered = FuelApiBridge.volumeOf(stack);
                long moved = port.insert(offered, mode(action)).transferredAmount().milliBuckets();
                return (int) Math.min(stack.getAmount(), Math.max(0, moved));
            } catch (RuntimeException ex) { return 0; }
        }
        @Override public FluidStack drain(FluidStack stack, FluidAction action) {
            if (stack.isEmpty()) return FluidStack.EMPTY;
            try {
                FluidVolume expected = FuelApiBridge.volumeOf(stack);
                FluidVolume moved = port.extract(FluidMatcher.exact(expected.requireVariant()),
                    expected.amount(), mode(action)).transferred();
                // A completed extraction cannot be hidden even if the addon violates its matcher contract.
                return moved.isEmpty() ? FluidStack.EMPTY : FuelApiBridge.stackOf(moved);
            } catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            if (amount <= 0) return FluidStack.EMPTY;
            try {
                return FuelApiBridge.stackOf(port.extract(FluidMatcher.any(), FluidAmount.of(amount), mode(action)).transferred());
            } catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
    }

    private record EnergyAdapter(ExternalEnergyPort port) implements IEnergyStorage {
        @Override public int receiveEnergy(int offered, boolean simulate) {
            if (offered <= 0 || !port.canInsert()) return 0;
            try { return (int) Math.min(offered, Math.max(0, port.insert(offered,
                simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE))); }
            catch (RuntimeException ex) { return 0; }
        }
        @Override public int extractEnergy(int requested, boolean simulate) {
            if (requested <= 0 || !port.canExtract()) return 0;
            try { return (int) Math.min(requested, Math.max(0, port.extract(requested,
                simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE))); }
            catch (RuntimeException ex) { return 0; }
        }
        @Override public int getEnergyStored() {
            try { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, port.stored())); }
            catch (RuntimeException ex) { return 0; }
        }
        @Override public int getMaxEnergyStored() {
            try { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, port.capacity())); }
            catch (RuntimeException ex) { return 0; }
        }
        @Override public boolean canExtract() { try { return port.canExtract(); } catch (RuntimeException ex) { return false; } }
        @Override public boolean canReceive() { try { return port.canInsert(); } catch (RuntimeException ex) { return false; } }
    }

    private record MjAdapter(MjPort port) implements IMjReceiver {
        @Override public boolean canConnect(IMjConnector other) { return other != null; }
        @Override public boolean canReceive() {
            try { return port.canInsert(); }
            catch (RuntimeException ex) { return false; }
        }
        @Override public long getPowerRequested() {
            try { return port.canInsert() ? Math.max(0, port.capacity().microMj()-port.stored().microMj()) : 0; }
            catch (RuntimeException ex) { return 0; }
        }
        @Override public long receivePower(long microJoules, FluidAction action) {
            if (microJoules <= 0) return 0;
            try {
                long accepted = port.insert(MjAmount.ofMicro(microJoules), mode(action)).transferred().microMj();
                return microJoules - Math.min(microJoules, Math.max(0, accepted));
            } catch (RuntimeException ex) { return microJoules; }
        }
    }

    private record MjPassiveAdapter(MjPort port) implements IMjPassiveProvider {
        @Override public boolean canConnect(IMjConnector other) { return other != null; }
        @Override public long extractPower(long minimum, long maximum, boolean execute) {
            if (minimum < 0 || maximum < minimum || maximum <= 0) return 0;
            try {
                if (!port.canExtract()) return 0;
                long available = port.extract(MjAmount.ofMicro(maximum), OperationMode.SIMULATE)
                    .transferred().microMj();
                if (available < minimum) return 0;
                if (!execute) return Math.min(maximum, available);
                long moved = port.extract(MjAmount.ofMicro(maximum), OperationMode.EXECUTE)
                    .transferred().microMj();
                // A changed port may return less than the simulated minimum. Never discard energy
                // already extracted by reporting that no transfer happened.
                return Math.min(maximum, Math.max(0, moved));
            } catch (RuntimeException ex) { return 0; }
        }
    }

    private static OperationMode mode(FluidAction action) {
        return action == FluidAction.EXECUTE ? OperationMode.EXECUTE : OperationMode.SIMULATE;
    }
}
