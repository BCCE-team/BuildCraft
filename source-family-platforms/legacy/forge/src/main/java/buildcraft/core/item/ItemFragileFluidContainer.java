package buildcraft.core.item;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.api.v2.drop.FluidDropContext;
import buildcraft.api.v2.drop.FluidDropProvider;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

public class ItemFragileFluidContainer extends Item implements FluidDropProvider {
    public static final int MAX_FLUID_HELD = 500;

    public ItemFragileFluidContainer() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new FragileFluidHandler(stack);
    }

    @Override
    public Component getName(ItemStack stack) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty()) {
            return Component.translatable(getDescriptionId(), Component.literal("ERROR! EMPTY FLUID!"));
        }
        if (fluid.getRawFluid() instanceof BCFluid bcFluid && bcFluid.isHeatable()) {
            return Component.translatable(getDescriptionId(), bcFluid.getBareLocalizedName(fluid))
                .copy().append(Component.translatable("buildcraft.fluid.heat_" + bcFluid.getHeatValue()));
        }
        return Component.translatable(getDescriptionId(), fluid.getDisplayName());
    }

    @OnlyIn(Dist.CLIENT)
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        CompoundTag fluidTag = stack.getTagElement("fluid");
        if (fluidTag != null) {
            FluidStack fluid = FluidStack.loadFluidStackFromNBT(fluidTag);
            if (!fluid.isEmpty()) {
                tooltip.add(LocaleUtil.localizeFluidStaticAmount(fluid.getAmount(), MAX_FLUID_HELD));
            }
        }
    }

    public void addFluidDrops(NonNullList<ItemStack> toDrop, @Nullable FluidStack fluid) {
        if (fluid == null || fluid.isEmpty()) {
            return;
        }
        int amount = fluid.getAmount();
        if (amount >= MAX_FLUID_HELD) {
            FluidStack fullShard = fluid.copy();
            fullShard.setAmount(MAX_FLUID_HELD);
            while (amount >= MAX_FLUID_HELD) {
                ItemStack stack = new ItemStack(this);
                setFluid(stack, fullShard);
                amount -= MAX_FLUID_HELD;
                toDrop.add(stack);
            }
        }
        if (amount > 0) {
            ItemStack stack = new ItemStack(this);
            setFluid(stack, new FluidStack(fluid, amount));
            toDrop.add(stack);
        }
    }

    @Override
    public java.util.Collection<ItemStack> createDrops(FluidDropContext context) {
        NonNullList<ItemStack> drops = NonNullList.create();
        FluidStack fluid = FuelApiBridge.stackOf(context.fluid());
        addFluidDrops(drops, fluid);
        return java.util.List.copyOf(drops);
    }

    public static void setFluid(ItemStack container, FluidStack fluid) {
        container.getOrCreateTag().put("fluid", fluid.writeToNBT(new CompoundTag()));
    }

    @NotNull
    public static FluidStack getFluid(ItemStack container) {
        if (container.isEmpty()) {
            return FluidStack.EMPTY;
        }
        CompoundTag fluidNbt = container.getTagElement("fluid");
        return fluidNbt == null ? FluidStack.EMPTY : FluidStack.loadFluidStackFromNBT(fluidNbt);
    }

    public class FragileFluidHandler implements IFluidHandlerItem, ICapabilityProvider {
        @Nonnull
        private ItemStack container;

        public FragileFluidHandler(@Nonnull ItemStack container) {
            this.container = container;
        }

        @Override
        public <T> @NotNull LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction facing) {
            if (capability == ForgeCapabilities.FLUID_HANDLER_ITEM || capability == ForgeCapabilities.FLUID_HANDLER) {
                return LazyOptional.of(() -> this).cast();
            }
            return LazyOptional.empty();
        }

        @Override
        public int getTanks() {
            return 1;
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            return getFluid(container);
        }

        @Override
        public int getTankCapacity(int tank) {
            return MAX_FLUID_HELD;
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            FluidStack fluid = getFluid(container);
            if (fluid.isEmpty() || resource.isEmpty() || !FluidCompatRegistry.areEquivalent(fluid, resource)) {
                return FluidStack.EMPTY;
            }
            return drain(resource.getAmount(), action);
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            FluidStack fluid = getFluid(container);
            if (fluid.isEmpty() || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            int amount = Math.min(maxDrain, fluid.getAmount());
            FluidStack drained = new FluidStack(fluid, amount);
            if (action.execute()) {
                fluid.shrink(amount);
                if (fluid.isEmpty()) {
                    container = StackUtil.EMPTY;
                } else {
                    setFluid(container, fluid);
                }
            }
            return drained;
        }

        @Override
        public ItemStack getContainer() {
            return container;
        }
    }
}
