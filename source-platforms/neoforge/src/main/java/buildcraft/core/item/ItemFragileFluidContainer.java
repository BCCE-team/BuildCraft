package buildcraft.core.item;

import buildcraft.lib.misc.FluidStackUtil;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.api.v2.drop.FluidDropContext;
import buildcraft.api.v2.drop.FluidDropProvider;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class ItemFragileFluidContainer extends Item implements FluidDropProvider {
    public static final int MAX_FLUID_HELD = 500;

    public ItemFragileFluidContainer() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty()) {
            return Component.translatable(getDescriptionId(), Component.translatable("buildcraft.error.empty_fluid"));
        }
        if (fluid.getFluid() instanceof BCFluid bcFluid && bcFluid.isHeatable()) {
            return Component.translatable(getDescriptionId(), bcFluid.getBareLocalizedName(fluid))
                .copy().append(Component.translatable("buildcraft.fluid.heat_" + bcFluid.getHeatValue()));
        }
        return Component.translatable(getDescriptionId(), fluid.getHoverName());
    }
    @Override
    //? if >=1.21.5 {
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    //?} else {
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipLines, TooltipFlag flag) {
        Consumer<Component> tooltip = tooltipLines::add;
    //?}
        //? if >=1.21.5 {
        super.appendHoverText(stack, context, display, tooltip, flag);
        //?} else {
        super.appendHoverText(stack, context, tooltipLines, flag);
        //?}
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(stack);
        CompoundTag fluidTag = data == null ? null : data.getCompound("fluid");
        if (fluidTag != null) {
            FluidStack fluid = FluidStackUtil.parseOptional(fluidTag);
            if (!fluid.isEmpty()) {
                tooltip.accept(LocaleUtil.localizeFluidStaticAmount(fluid.getAmount(), MAX_FLUID_HELD));
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
            setFluid(stack, fluid.copyWithAmount(amount));
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
        CompoundTag data = ItemStackUtil.getCustomData(container);
        data.put("fluid", FluidStackUtil.saveOptional(fluid));
        ItemStackUtil.setCustomData(container, data);
    }

    @NotNull
    public static FluidStack getFluid(ItemStack container) {
        if (container.isEmpty()) {
            return FluidStack.EMPTY;
        }
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(container);
        CompoundTag fluidNbt = data == null ? null : data.getCompound("fluid");
        return fluidNbt == null ? FluidStack.EMPTY : FluidStackUtil.parseOptional(fluidNbt);
    }

    public static final class FragileFluidHandler implements IFluidHandlerItem {
        @Nonnull
        private ItemStack container;

        public FragileFluidHandler(@Nonnull ItemStack container) {
            this.container = container;
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
            FluidStack drained = fluid.copyWithAmount(amount);
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
