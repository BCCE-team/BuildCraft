/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.lib.fluid;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.internal.core.IFluidHandlerAdv;
import buildcraft.core.item.ItemFragileFluidContainer;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.lib.net.cache.NetworkedFluidStackCache;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;

/** Provides a useful implementation of a fluid tank that can save + load, and has a few helper functions. Can
 * optionally specify a filter to only allow a limited types of fluids in the tank. */
public class Tank implements IFluidHandlerAdv, IFluidHandler, IFluidTank {
    public static final String DEFAULT_HELP_KEY = "buildcraft.help.tank.generic";

    public int colorRenderCache = 0xFFFFFF;

    protected final ToolTip toolTip = new ToolTip() {
        @Override
        public void refresh() {
            refreshTooltip();
        }
    };
    
    @Nonnull
    private final String name;

    BlockEntity tile;
    NetworkedFluidStackCache.Link clientFluid = null;
    int clientAmount = 0;

    protected boolean canFill = true;
    protected boolean canDrain = true;
    
    public ElementHelpInfo helpInfo;

    protected static Map<Fluid, Integer> fluidColors = new HashMap<>();
    
    protected Predicate<FluidStack> validator;
    @NotNull
    protected FluidStack fluid = FluidStack.EMPTY;
    protected int capacity;

    /** Creates a tank with the given name and capacity (in milli buckets) with no filter set (so any fluid can go into
     * the tank) */
    public Tank(@Nonnull String name, int capacity, BlockEntity tile) {
        this(name, capacity, tile, null);
    }
    
    /** Creates a tank with the given name and capacity (in milli buckets) with the specified filter set. If the filter
     * returns true for a given fluidstack then it will be allowed in the tank. The given fluidstack will NEVER be
     * null. */
    public Tank(@Nonnull String name, int capacity, BlockEntity tile, @Nullable Predicate<FluidStack> filter) {
        this.capacity = capacity;
        this.validator = filter == null ? ((f) -> true) : filter;
        this.name = name;
        this.tile = tile;
        helpInfo = new ElementHelpInfo("buildcraft.help.tank.title." + name, 0xFF_00_00_00 | name.hashCode(),
                DEFAULT_HELP_KEY);
    }

    @Nonnull
    public String getTankName() {
        return name;
    }

    public boolean isEmpty() {
        FluidStack fluidStack = getFluid();
        return fluidStack.isEmpty() ||fluidStack.getAmount() <= 0;
    }

    public boolean isFull() {
        FluidStack fluidStack = getFluid();
        return !fluidStack.isEmpty() &&fluidStack.getAmount() >= getCapacity();
    }

    public Fluid getFluidType() {
        FluidStack fluidStack = getFluid();
        return !fluidStack.isEmpty() ? fluidStack.getFluid() : Fluids.EMPTY;
    }

    public CompoundTag serializeNBT() {
        return writeToNBT(new CompoundTag());
    }

    public final CompoundTag writeToNBT(CompoundTag nbt) {
        fluid.writeToNBT(nbt);
        writeTankToNBT(nbt);
        return nbt;
    }

    public final void readFromNBT(CompoundTag nbt) {
    	FluidStack fluid = FluidCompatRegistry.canonicalize(FluidStack.loadFluidStackFromNBT(nbt));
        if (nbt.contains(name)) {
            // Named-tank compatibility format
            CompoundTag tankData = nbt.getCompound(name);
            this.fluid = fluid;
            readTankFromNBT(tankData);
        } else {
            this.fluid = fluid;
            readTankFromNBT(nbt);
        }
    }

    /** Writes subclass-specific information to NBT. */
    protected void writeTankToNBT(CompoundTag nbt) {}

    /** Reads subclass-specific information from NBT. */
    protected void readTankFromNBT(CompoundTag nbt) {}

    public ToolTip getToolTip() {
        return toolTip;
    }

    protected void refreshTooltip() {
        toolTip.clear();
        int amount = clientAmount;
        FluidStack fluidStack = clientFluid == null ? FluidStack.EMPTY : clientFluid.get().copy();
        if (fluidStack != FluidStack.EMPTY && amount > 0) {
            toolTip.add(fluidStack.getDisplayName());
        }
        toolTip.add(LocaleUtil.localizeFluidStaticAmount(amount, getCapacity()).setStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)));
        FluidStack serverFluid = getFluid();
        if (serverFluid != null && serverFluid.getAmount() > 0) {
            toolTip.add(Component.translatable("tooltip.buildcraftlib.fluid.server_side_on_client").setStyle(Style.EMPTY.withColor(ChatFormatting.RED)));
            toolTip.add(serverFluid.getDisplayName());
            toolTip.add(LocaleUtil.localizeFluidStaticAmount(serverFluid.getAmount(), getCapacity()));
        }
    }

    @Override
	public boolean isFluidValid(FluidStack stack) {
        FluidStack normalized = FluidCompatRegistry.canonicalize(stack);
    	return !normalized.isEmpty() && validator.test(normalized)
            && (fluid.isEmpty() || FluidCompatRegistry.areEquivalent(normalized, fluid));
	}

    @Override
    public int fill(FluidStack resource, FluidAction doFill) {
    	if(!canFill)
    		return 0;
    	return fillInternal(resource, doFill);
    }
    
    public int fillInternal(FluidStack resource, FluidAction doFill) {
        resource = FluidCompatRegistry.canonicalize(resource);
    	if (resource.isEmpty() || !isFluidValid(resource))
        {
            return 0;
        }
        if (doFill.simulate())
        {
            if (fluid.isEmpty())
            {
                return Math.min(capacity, resource.getAmount());
            }
            if (!FluidCompatRegistry.areEquivalent(fluid, resource))
            {
                return 0;
            }
            return Math.min(capacity - fluid.getAmount(), resource.getAmount());
        }
        if (fluid.isEmpty())
        {
            int amount = Math.min(capacity, resource.getAmount());
            fluid = new FluidStack(resource, amount);
            onContentsChanged();
            return amount;
        }
        if (!FluidCompatRegistry.areEquivalent(fluid, resource))
        {
            return 0;
        }
        int filled = capacity - fluid.getAmount();

        if (resource.getAmount() < filled)
        {
            fluid.grow(resource.getAmount());
            filled = resource.getAmount();
        }
        else
        {
            fluid.setAmount(capacity);
        }
        if (filled > 0)
            onContentsChanged();
        return filled;
    }

    @Override
    public FluidStack drain(IFluidFilter drainFilter, int maxDrain, FluidAction doDrain) {
        if (drainFilter == null||!canDrain) {
            return FluidStack.EMPTY;
        }
        FluidStack currentFluid = getFluid();
        if (currentFluid != FluidStack.EMPTY && drainFilter.matches(currentFluid)) {
            return drainInternal(maxDrain, doDrain);
        }
        return FluidStack.EMPTY;
    }
    
    @Override
    public FluidStack drain(FluidStack resource, FluidAction action)
    {
    	if(!canDrain)
    		return FluidStack.EMPTY;
        return drainInternal(resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action)
    {
    	if(!canDrain)
    		return FluidStack.EMPTY;
    	return drainInternal(maxDrain, action);
    }
    
	public FluidStack drainInternal(FluidStack resource, FluidAction doDrain) {
        if (resource.isEmpty() || !FluidCompatRegistry.areEquivalent(resource, fluid))
        {
            return FluidStack.EMPTY;
        }
        return drainInternal(resource.getAmount(), doDrain);
	}

	public FluidStack drainInternal(int maxDrain, FluidAction doDrain) {
        int drained = maxDrain;
        if (fluid.getAmount() < drained)
        {
            drained = fluid.getAmount();
        }
        FluidStack stack = new FluidStack(fluid, drained);
        if (doDrain.execute() && drained > 0)
        {
            fluid.shrink(drained);
            onContentsChanged();
        }
        return stack;
	}

    protected void onContentsChanged() {
        if (tile instanceof TileBC_Neptune) {
            ((TileBC_Neptune) tile).markChunkDirty();
        }
    }

    @Override
    public String toString() {
        return "Tank [" + getContentsString() + "]";
    }

    public String getContentsString() {
        if (!fluid.isEmpty()) {
            return fluid.getTranslationKey() + LocaleUtil.localizeFluidStaticAmount(this);
        }
        return LocaleUtil.localizeFluidStaticAmount(0, getCapacity()).getString();
    }

    public void writeToBuffer(FriendlyByteBuf buffer) {
        if (fluid.isEmpty()) {
            buffer.writeBoolean(false);
        } else {
            buffer.writeBoolean(true);
            buffer.writeInt(BuildCraftObjectCaches.CACHE_FLUIDS.server().store(fluid));
        }
        buffer.writeInt(getFluidAmount());
    }
    public void readFromBuffer(FriendlyByteBuf buffer) {
        if (buffer.readBoolean()) {
            clientFluid = BuildCraftObjectCaches.CACHE_FLUIDS.client().retrieve(buffer.readInt());
        } else {
            clientFluid = null;
        }
        clientAmount = buffer.readInt();
    }

    public FluidStack getFluidForRender() {
        if (clientFluid == null) {
            return FluidStack.EMPTY;
        } else {
            FluidStack stackBase = clientFluid.get();
            return new FluidStack(stackBase, clientAmount);
        }
    }

    public int getClientAmount() {
        return clientAmount;
    }

    public String getDebugString() {
        FluidStack f = getFluidForRender();
        if (f.isEmpty()) f = getFluid();
        return (f.isEmpty() ? 0 : f.getAmount()) + " / " + capacity + " mB of " + (!f.isEmpty() ? f.getFluid().getFluidType().getDescriptionId() : "n/a");
    }

    @Deprecated
    public void onGuiClicked(AbstractContainerMenu menu, Player player){//ContainerBC_Neptune container) {
        ItemStack held = menu.getCarried();
        if (held.isEmpty()) {
            return;
        }
        FluidStack before = getFluid().copy();
        ItemStack stack = transferStackToTank(player, held);
        menu.setCarried(stack);
        menu.broadcastFullState();
        player.inventoryMenu.broadcastFullState();
        playCommittedGuiTransferSound(player, before);
    }
    
    public void onGuiClicked(MenuBC_Neptune container) {
        Player player = container.playerInventory.player;
        ItemStack held = container.getCarried();
        if (held.isEmpty()) {
            return;
        }
        FluidStack before = getFluid().copy();
        ItemStack stack = transferStackToTank(player, held);
        container.setCarried(stack);
        container.broadcastFullState();
        player.inventoryMenu.broadcastFullState();
        playCommittedGuiTransferSound(player, before);
    }

    private void playCommittedGuiTransferSound(Player player, FluidStack before) {
        FluidStack after = getFluid();
        int beforeAmount = before.isEmpty() ? 0 : before.getAmount();
        int afterAmount = after.isEmpty() ? 0 : after.getAmount();
        //? if <1.20 {
        Level world = player.level;
        //?} else {
        /*?
        Level world = player.level();
        ?*/
        //?}
        if (afterAmount > beforeAmount && !after.isEmpty()) {
            SoundUtil.playBucketEmpty(
                world, player.blockPosition(), copyFluidForSound(after, afterAmount - beforeAmount)
            );
        } else if (beforeAmount > afterAmount && !before.isEmpty()) {
            SoundUtil.playBucketFill(
                world, player.blockPosition(), copyFluidForSound(before, beforeAmount - afterAmount)
            );
        }
    }

    private static FluidStack copyFluidForSound(FluidStack source, int amount) {
        FluidStack copy = source.copy();
        copy.setAmount(amount);
        return copy;
    }

    /** Attempts to transfer the given stack to this tank.
     *
     * @return The left over item after attempting to add the stack to this tank. */
    public ItemStack transferStackToTank(Player player, ItemStack stack) {
        // first try to fill this tank from the item

        //? if <1.20 {
        if (player.level.isClientSide) {
        //?} else {
        /*?
        if (player.level().isClientSide) {
        ?*/
        //?}
            return stack;
        }

        ItemStack original = stack;
        ItemStack copy = stack.copy();
        copy.setCount(1);
        int space = capacity - getFluidAmount();

        boolean isCreative = player.isCreative();
        boolean isSurvival = !isCreative;

        FluidGetResult result = map(copy, space);
        if (result != null && result.fluidStack != null && result.fluidStack.getAmount() > 0) {
            if (isCreative) {
                stack = copy;// so we don't change the stack held by the player.
            }
            int accepted = fill(result.fluidStack, FluidAction.SIMULATE);
            if (isCreative ? (accepted > 0) : (accepted == result.fluidStack.getAmount())) {
                int reallyAccepted = fill(result.fluidStack, FluidAction.EXECUTE);
                if (reallyAccepted != accepted) {
                    throw new IllegalStateException(
                        "We seem to be buggy! (accepted = " + accepted + ", reallyAccepted = " + reallyAccepted + ")");
                }
                stack.shrink(1);
                if (isSurvival) {
                    if (stack.isEmpty()) {
                        return result.itemStack.copy();
                    } else if (!result.itemStack.isEmpty()) {
                        InventoryUtil.addToPlayer(player, result.itemStack.copy());
                        return stack;
                    }
                }
                return original;
            }
        }
        // Now try to drain the fluid into the item. A creative player's temporary copy is discarded below, so
        // executing this branch would delete fluid from the tank without changing the held container.
        if (isCreative) {
            return original;
        }
        IFluidHandlerItem fluidHandler = FluidUtil.getFluidHandler(copy).orElse(null);
        if (fluidHandler == null) return stack;
        FluidStack drained = drain(capacity, FluidAction.SIMULATE);
        if (drained.isEmpty() || drained.getAmount() <= 0) return stack;
        int filled = fluidHandler.fill(drained, FluidAction.EXECUTE);
        if (filled > 0) {
            FluidStack reallyDrained = drain(filled, FluidAction.EXECUTE);
            if ((reallyDrained.isEmpty() || reallyDrained.getAmount() != filled)) {
                throw new IllegalStateException("Somehow drained differently than expected! ( drained = "//
                    + drained + ", filled = " + filled + ", reallyDrained = " + reallyDrained + " )");
            }
            if (isSurvival) {
                if (original.getCount() == 1) {
                    return fluidHandler.getContainer();
                } else {
                    ItemStack stackContainer = fluidHandler.getContainer();
                    if (!stackContainer.isEmpty()) {
                        InventoryUtil.addToPlayer(player, stackContainer);
                    }
                    original.shrink(1);
                    return original;
                }
            }
        }
        return stack;
    }

    /** Maps the given stack to a fluid result.
     * 
     * @param stack The stack to map. This will ALWAYS have an {@link ItemStack#getCount()} of 1.
     * @param space The maximum amount of fluid that can be accepted by this tank. */
    protected FluidGetResult map(ItemStack stack, int space) {
        if (space <= 0 || stack.isEmpty()) {
            return null;
        }

        // Let the container implementation decide its remainder. This is especially important for BuildCraft's
        // fuel buckets and fragile shards: manually reconstructing their result stack made the direct and GUI paths
        // disagree. The fallback wrappers keep Forge 43/47 buckets/shards deterministic if capability lookup fails.
        ItemStack probe = stack.copy();
        probe.setCount(1);
        IFluidHandlerItem fluidHandler = probe
            .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)
            .orElse(null);
        if (fluidHandler == null && probe.getItem() instanceof BucketItem) {
            fluidHandler = new FluidBucketWrapper(probe);
        }
        if (fluidHandler == null && probe.getItem() instanceof ItemFragileFluidContainer fragile) {
            fluidHandler = fragile.new FragileFluidHandler(probe);
        }
        if (fluidHandler == null) {
            fluidHandler = FluidUtil.getFluidHandler(probe).orElse(null);
        }
        if (fluidHandler == null) {
            return null;
        }

        FluidStack drained = fluidHandler.drain(space, FluidAction.EXECUTE);
        if (drained.isEmpty() || drained.getAmount() <= 0) {
            return null;
        }
        ItemStack leftOverStack = fluidHandler.getContainer().copy();
        if (leftOverStack.isEmpty()) {
            leftOverStack = StackUtil.EMPTY;
        }
        return new FluidGetResult(leftOverStack, drained);
    }

    public static class FluidGetResult {
        public final ItemStack itemStack;
        public final FluidStack fluidStack;

        public FluidGetResult(ItemStack itemStack, FluidStack fluidStack) {
            this.itemStack = itemStack;
            this.fluidStack = fluidStack;
        }
    }

	public void setBlockEntity(BlockEntity tileTank) {
		this.tile = tileTank;
	}

	public void setCanFill(boolean b) {
		canFill = b;
	}
	
	public void setCanDrain(boolean b) {
		canDrain = b;
	}

	public boolean canFill() {
		return canFill;
	}

	public boolean canDrain() {
		return canDrain;
	}

	@Override
	public @NotNull FluidStack getFluid() {
		return fluid;
	}

	@Override
	public int getFluidAmount() {
		return fluid.getAmount();
	}

	@Override
	public int getCapacity() {
		return capacity;
	}

	public void setCapacity(int capacity) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("Tank capacity must be positive: " + capacity);
		}
		this.capacity = capacity;
		if (!fluid.isEmpty() && fluid.getAmount() > capacity) {
			fluid.setAmount(capacity);
		}
		onContentsChanged();
	}

	@Override
	public int getTanks() {
		return 1;
	}

	@Override
	public @NotNull FluidStack getFluidInTank(int tank) {
		return fluid;
	}

	@Override
	public int getTankCapacity(int tank) {
		return capacity;
	}

	@Override
	public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
		return isFluidValid(stack);
	}

	public void setFilter(Predicate<FluidStack> filter) {
		this.validator = filter;
	}

	public void setFluid(FluidStack residueFluid) {
		this.fluid = FluidCompatRegistry.canonicalize(residueFluid);
	}
}
