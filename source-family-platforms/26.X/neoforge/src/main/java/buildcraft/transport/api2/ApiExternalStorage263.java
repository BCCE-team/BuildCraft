//? source if >=26.3
package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.pipe.PipeExternalEndpoint;
import buildcraft.api.v2.pipe.PipeAttachment;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.transport.pipe.Pipe;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/** API ports used by the native pipe flows without pretending they support NeoForge rollback. */
public final class ApiExternalStorage263 {
    private ApiExternalStorage263() { }

    public static FluidStorage<FluidStack> fluid(Pipe pipe, Direction side) {
        if (!available(pipe, side)) return null;
        try {
            Optional<FluidPort> attachment = PipeEndpointRuntime.attachmentPort(
                pipe.getHolder().getPluggable(side), PipeAttachment::fluidPort);
            if (attachment.isPresent()) return new FluidAdapter(attachment.get());
            PipeExternalEndpoint endpoint = PipeEndpointRuntime.find(pipe.getHolder().getPipeWorld(), pipe, side)
                .orElse(null);
            return endpoint == null || !PipeEndpointRuntime.canConnect(endpoint, pipe, side)
                ? null : endpoint.fluidPort().map(FluidAdapter::new).orElse(null);
        } catch (RuntimeException ex) { return null; }
    }

    public static EnergyStorage energy(Pipe pipe, Direction side) {
        if (!available(pipe, side)) return null;
        try {
            Optional<ExternalEnergyPort> attachment = PipeEndpointRuntime.attachmentPort(
                pipe.getHolder().getPluggable(side), PipeAttachment::externalEnergyPort);
            if (attachment.isPresent()) return new EnergyAdapter(attachment.get());
            PipeExternalEndpoint endpoint = PipeEndpointRuntime.find(pipe.getHolder().getPipeWorld(), pipe, side)
                .orElse(null);
            return endpoint == null || !PipeEndpointRuntime.canConnect(endpoint, pipe, side)
                ? null : endpoint.externalEnergyPort().map(EnergyAdapter::new).orElse(null);
        } catch (RuntimeException ex) { return null; }
    }

    private static boolean available(Pipe pipe, Direction side) {
        if (pipe == null || side == null) return false;
        Level level = pipe.getHolder().getPipeWorld();
        return level != null && !level.isClientSide()
            && level.hasChunkAt(pipe.position().relative(side))
            && pipe.getHolder().getPluggable(side) != null
            && !pipe.getHolder().getPluggable(side).isBlocking();
    }

    private record FluidAdapter(FluidPort port) implements FluidStorage<FluidStack> {
        public int getTanks() { return 1; }
        public FluidStack getFluidInTank(int tank) {
            if (tank != 0) return FluidStack.EMPTY;
            try {
                return FuelApiBridge.stackOf(port.extract(FluidMatcher.any(),
                    FluidAmount.of(Integer.MAX_VALUE), OperationMode.SIMULATE).transferred());
            } catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
        public int getTankCapacity(int tank) { return tank == 0 ? Integer.MAX_VALUE : 0; }
        public boolean isFluidValid(int tank, FluidStack stack) {
            return tank == 0 && stack != null && !stack.isEmpty();
        }
        public int fill(FluidStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return 0;
            try {
                long accepted = port.insert(FuelApiBridge.volumeOf(stack), mode(simulate))
                    .transferredAmount().milliBuckets();
                return (int) Math.min(stack.getAmount(), Math.max(0, accepted));
            } catch (RuntimeException ex) { return 0; }
        }
        public FluidStack drain(FluidStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return FluidStack.EMPTY;
            try {
                FluidVolume expected = FuelApiBridge.volumeOf(stack);
                FluidVolume extracted = port.extract(FluidMatcher.exact(expected.requireVariant()),
                    expected.amount(), mode(simulate)).transferred();
                // Return the actual extracted variant: hiding a completed extraction would lose fluid.
                return FuelApiBridge.stackOf(extracted);
            } catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
        public FluidStack drain(int amount, boolean simulate) {
            if (amount <= 0) return FluidStack.EMPTY;
            try {
                return FuelApiBridge.stackOf(port.extract(FluidMatcher.any(),
                    FluidAmount.of(amount), mode(simulate)).transferred());
            } catch (RuntimeException ex) { return FluidStack.EMPTY; }
        }
    }

    private record EnergyAdapter(ExternalEnergyPort port) implements EnergyStorage {
        public int receiveEnergy(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try {
                if (!port.canInsert()) return 0;
                return bounded(amount, port.insert(amount, mode(simulate)));
            } catch (RuntimeException ex) { return 0; }
        }
        public int extractEnergy(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try {
                if (!port.canExtract()) return 0;
                return bounded(amount, port.extract(amount, mode(simulate)));
            } catch (RuntimeException ex) { return 0; }
        }
        public int getEnergyStored() {
            try { return bounded(Integer.MAX_VALUE, port.stored()); }
            catch (RuntimeException ex) { return 0; }
        }
        public int getMaxEnergyStored() {
            try { return bounded(Integer.MAX_VALUE, port.capacity()); }
            catch (RuntimeException ex) { return 0; }
        }
        public boolean canExtract() {
            try { return port.canExtract(); } catch (RuntimeException ex) { return false; }
        }
        public boolean canReceive() {
            try { return port.canInsert(); } catch (RuntimeException ex) { return false; }
        }
    }

    private static int bounded(int upper, long amount) {
        return (int) Math.min(upper, Math.max(0L, amount));
    }

    private static OperationMode mode(boolean simulate) {
        return simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE;
    }
}
