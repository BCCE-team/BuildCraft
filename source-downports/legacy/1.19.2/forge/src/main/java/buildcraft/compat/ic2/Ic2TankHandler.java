package buildcraft.compat.ic2;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.fluid.FluidCompatRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

/**
 * Presents an IC2 fluid machine as a normal Forge fluid handler and converts
 * equivalent fluids at the integration boundary when the machine only accepts
 * its own registered fluid ID.
 */
public final class Ic2TankHandler implements IFluidHandler {
    private final IFluidHandler delegate;

    public Ic2TankHandler(IFluidHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public int getTanks() {
        return delegate.getTanks();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        return FluidCompatRegistry.canonicalize(delegate.getFluidInTank(tank));
    }

    @Override
    public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        if (delegate.isFluidValid(tank, stack)) {
            return true;
        }
        for (FluidStack equivalent : FluidCompatRegistry.getEquivalentStacks(stack, "ic2")) {
            if (equivalent.getFluid() != stack.getFluid() && delegate.isFluidValid(tank, equivalent)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return 0;
        }

        FluidStack accepted = findFillVariant(resource);
        if (accepted.isEmpty()) {
            return 0;
        }
        if (action.simulate()) {
            return delegate.fill(accepted, FluidAction.SIMULATE);
        }
        return delegate.fill(accepted, FluidAction.EXECUTE);
    }

    private FluidStack findFillVariant(FluidStack resource) {
        int direct = delegate.fill(resource, FluidAction.SIMULATE);
        if (direct > 0) {
            FluidStack copy = resource.copy();
            copy.setAmount(Math.min(copy.getAmount(), direct));
            return copy;
        }

        List<FluidStack> equivalents = FluidCompatRegistry.getEquivalentStacks(resource, "ic2");
        for (FluidStack equivalent : equivalents) {
            if (equivalent.getFluid() == resource.getFluid()) {
                continue;
            }
            int accepted = delegate.fill(equivalent, FluidAction.SIMULATE);
            if (accepted > 0) {
                equivalent.setAmount(Math.min(equivalent.getAmount(), accepted));
                return equivalent;
            }
        }
        return FluidStack.EMPTY;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return FluidStack.EMPTY;
        }

        FluidStack direct = delegate.drain(resource, FluidAction.SIMULATE);
        if (!direct.isEmpty()) {
            FluidStack drained = action.simulate() ? direct : delegate.drain(resource, FluidAction.EXECUTE);
            return FluidCompatRegistry.copyWithFluid(drained, resource.getFluid());
        }

        for (FluidStack equivalent : FluidCompatRegistry.getEquivalentStacks(resource, "ic2")) {
            if (equivalent.getFluid() == resource.getFluid()) {
                continue;
            }
            FluidStack simulated = delegate.drain(equivalent, FluidAction.SIMULATE);
            if (simulated.isEmpty()) {
                continue;
            }
            FluidStack drained = action.simulate() ? simulated : delegate.drain(equivalent, FluidAction.EXECUTE);
            return FluidCompatRegistry.copyWithFluid(drained, resource.getFluid());
        }
        return FluidStack.EMPTY;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        return FluidCompatRegistry.canonicalize(delegate.drain(maxDrain, action));
    }
}
