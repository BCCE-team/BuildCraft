package buildcraft.compat.ic2;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.core.BCCore;
import buildcraft.lib.fluid.FluidCompatRegistry;
import ic2.core.item.misc.CellItem;
import ic2.core.platform.registries.IC2Items;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

/**
 * A BuildCraft-owned filled IC2 cell.
 *
 * <p>This deliberately does not extend IC2's {@link CellItem}: that class
 * always assigns itself to IC2's creative tab and also forces one of IC2's
 * fixed cell textures. The item still participates in IC2's empty-cell fill
 * map and exposes the normal Forge fluid-item capability.</p>
 */
public final class Ic2FluidCellItem extends Item {
    public static final int CAPACITY = 1_000;

    private final Fluid fluid;

    public Ic2FluidCellItem(Fluid fluid) {
        super(new Item.Properties()
            .stacksTo(64)
            .tab(BCCore.tabFluids)
            .craftRemainder(IC2Items.CELL_EMPTY));
        this.fluid = fluid;

        // IC2's empty cell consults this map when it is filled by a machine.
        CellItem.registerFluidFilling(fluid, this, false);
    }

    public Fluid getFluid() {
        return fluid;
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new FluidCellHandler(stack, this);
    }

    private static final class FluidCellHandler implements IFluidHandlerItem, ICapabilityProvider {
        @Nonnull
        private ItemStack container;
        private final Ic2FluidCellItem cell;
        private final LazyOptional<IFluidHandlerItem> capability = LazyOptional.of(() -> this);

        private FluidCellHandler(@Nonnull ItemStack container, Ic2FluidCellItem cell) {
            this.container = container;
            this.cell = cell;
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @Nonnull FluidStack getFluidInTank(int tank) {
            return tank == 0 && container.getItem() == cell
                ? new FluidStack(cell.fluid, CAPACITY)
                : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return tank == 0 ? CAPACITY : 0;
        }

        @Override
        public boolean isFluidValid(int tank, @Nonnull FluidStack stack) {
            return tank == 0 && stack.getAmount() >= CAPACITY
                && FluidCompatRegistry.areEquivalent(cell.fluid, stack.getFluid());
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            // A registered filled cell cannot be topped up or changed in-place.
            // IC2's empty-cell handler creates the correct filled item instead.
            return 0;
        }

        @Override
        public @Nonnull FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource == null || resource.getAmount() < CAPACITY
                || !FluidCompatRegistry.areEquivalent(cell.fluid, resource.getFluid())) {
                return FluidStack.EMPTY;
            }
            return drain(CAPACITY, action);
        }

        @Override
        public @Nonnull FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain < CAPACITY || container.getItem() != cell) {
                return FluidStack.EMPTY;
            }

            FluidStack drained = new FluidStack(cell.fluid, CAPACITY);
            if (action.execute()) {
                container = new ItemStack(IC2Items.CELL_EMPTY);
            }
            return drained;
        }

        @Override
        public @Nonnull ItemStack getContainer() {
            return container;
        }

        @Override
        public <T> @Nonnull LazyOptional<T> getCapability(@Nonnull Capability<T> requested,
            @Nullable Direction side) {
            if (requested == ForgeCapabilities.FLUID_HANDLER_ITEM
                || requested == ForgeCapabilities.FLUID_HANDLER) {
                return capability.cast();
            }
            return LazyOptional.empty();
        }
    }
}
