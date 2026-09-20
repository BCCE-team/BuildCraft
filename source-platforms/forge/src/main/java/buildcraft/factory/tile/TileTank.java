/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.internal.core.IFluidHandlerAdv;
import buildcraft.lib.fluid.FluidDropRuntime;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.factory.container.ContainerTank;
import buildcraft.lib.fluid.FluidSmoother;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
import net.minecraftforge.network.NetworkHooks;

public class TileTank extends TileBC_Neptune implements IDebuggable, IFluidHandlerAdv, MenuProvider {
    public static final int NET_FLUID_DELTA = IDS.allocId("FLUID_DELTA");

    private static final ResourceLocation ADVANCEMENT_STORE_FLUIDS = new ResourceLocation(
        "buildcraftfactory:fluid_storage"
    );

    private static boolean isPlayerInteracting = false;

    public final Tank tank;
    public final FluidSmoother smoothedTank;
    
//    protected final TankManager tankManager = new TankManager();

    private int lastComparatorLevel;

    public TileTank(BlockPos pos, BlockState state) {
        this(BCFactoryBlocks.ENTITYBLOCKTANK.get(), pos, state);
    }

    public TileTank(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        this(type, 16 * FluidType.BUCKET_VOLUME, pos, state);
    }

    protected TileTank(int capacity, BlockPos pos, BlockState state) {
        this(BCFactoryBlocks.ENTITYBLOCKTANK.get(), capacity, pos, state);
    }

    public TileTank(BlockEntityType<?> type, int capacity, BlockPos pos, BlockState state) {
        this(type, new Tank("tank", capacity, null), pos, state);
    }

    protected TileTank(Tank tank, BlockPos pos, BlockState state) {
        this(BCFactoryBlocks.ENTITYBLOCKTANK.get(), tank, pos, state);
    }

    public TileTank(BlockEntityType<?> type, Tank tank, BlockPos pos, BlockState state) {
    	super(type, pos, state);
        tank.setBlockEntity(this);
        this.tank = tank;
        tankManager.addLast(tank);
        caps.addFluidStorage(this, EnumPipePart.VALUES);
        smoothedTank = new FluidSmoother(w -> createAndSendMessage(NET_FLUID_DELTA, w), tank);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public int getComparatorLevel() {
        int amount = tank.getFluidAmount();
        int cap = tank.getCapacity();
        return amount * 14 / cap + (amount > 0 ? 1 : 0);
    }

    /**
     * Sets the capacity of the local tank in millibuckets.
     * <p>
     * Addons that expose different tank tiers should normally prefer the
     * {@link #TileTank(BlockEntityType, int, BlockPos, BlockState)} constructor so the capacity is already correct when
     * the block entity is created. This method is kept as a small compatibility helper for upgrade-style code.
     */
    public void setTankCapacity(int capacity) {
        tank.setCapacity(capacity);
    }

    // ITickable

    public void update() {
        smoothedTank.tick(level);

        if (!level.isClientSide) {
            int compLevel = getComparatorLevel();
            if (compLevel != lastComparatorLevel) {
                lastComparatorLevel = compLevel;
                setChanged();
            }
        }
    }

    // BlockEntity

    @Override
    public void onPlacedBy(LivingEntity placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        //? if <1.20 {
        if (!placer.level.isClientSide) {
        //?} else {
        /*?
        if (!placer.level().isClientSide) {
        ?*/
        //?}
            isPlayerInteracting = true;
            balanceTankFluids();
            isPlayerInteracting = false;
        }
    }

    /** Moves fluids around to their preferred positions. (For gaseous fluids this will move everything as high as
     * possible, for liquid fluids this will move everything as low as possible.) */
    public void balanceTankFluids() {
        List<TileTank> tanks = getConnectedTanks();
        FluidStack fluid = FluidStack.EMPTY;
        for (TileTank tile : tanks) {
            FluidStack held = tile.tank.getFluid();
            if (held.isEmpty()) {
                continue;
            }
            if (fluid.isEmpty()) {
                fluid = held;
            } else if (!FluidCompatRegistry.areEquivalent(fluid, held)) {
                return;
            }
        }
        if (fluid.isEmpty()) {
            return;
        }
        if (fluid.getFluid().getFluidType().isLighterThanAir()) {
            Collections.reverse(tanks);
        }
        TileTank prev = null;
        for (TileTank tile : tanks) {
            if (prev != null) {
                FluidUtilBC.move(tile.tank, prev.tank);
            }
            prev = tile;
        }
    }

    
    
    @Override
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        int amountBefore = tank.getFluidAmount();
        isPlayerInteracting = true;
        boolean didChange = FluidUtilBC.onTankActivated(player, worldPosition, hand, this);
        isPlayerInteracting = false;
        //? if <1.20 {
        if (didChange && !player.level.isClientSide && amountBefore < tank.getFluidAmount()) {
        //?} else {
        /*?
        if (didChange && !player.level().isClientSide && amountBefore < tank.getFluidAmount()) {
        ?*/
        //?}
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_STORE_FLUIDS);
        }
        //? if <1.20 {
        if (!didChange && !player.level.isClientSide && player instanceof ServerPlayer serverPlayer) {
        //?} else {
        /*?
        if (!didChange && !player.level().isClientSide && player instanceof ServerPlayer serverPlayer) {
        ?*/
        //?}
            NetworkHooks.openScreen(serverPlayer, this, worldPosition);
        }
        return InteractionResult.SUCCESS;
	}

	@Override
	public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
		return new ContainerTank(id, inventory, this, ContainerLevelAccess.create(level, worldPosition));
	}

	@Override
	public Component getDisplayName() {
		return Component.translatable(getBlockState().getBlock().getDescriptionId());
	}


    // Networking

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_FLUID_DELTA, buffer, side);
            } else if (id == NET_FLUID_DELTA) {
                smoothedTank.writeInit(buffer);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_FLUID_DELTA, buffer, side, ctx);
                smoothedTank.resetSmoothing(level);
            } else if (id == NET_FLUID_DELTA) {
                smoothedTank.handleMessage(level, buffer);
            }
        }
    }

    // IDebuggable

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("fluid = " + tank.getDebugString());
        smoothedTank.getDebugInfo(left, right, side);
    }

    // Rendering
    public FluidStackInterp getFluidForRender(float partialTicks) {
        return smoothedTank.getFluidForRender(partialTicks);
    }

    // Tank helper methods

    /** Tests to see if this tank can connect to the other one, in the given direction. BuildCraft itself only calls
     * with {@link Direction#UP} or {@link Direction#DOWN}, however addons are free to call with any of the other 4
     * non-null faces. (Although an addon calling from other faces must provide some way of transferring fluids around).
     * 
     * @param other The other tank.
     * @param direction The direction that the other tank is, from this tank.
     * @return True if this can connect, false otherwise. */
    public boolean canConnectTo(TileTank other, Direction direction) {
        if (other == null || direction == null) {
            return false;
        }
        if (direction.getAxis() != Direction.Axis.Y) {
            return fluidsCanShareColumn(tank.getFluid(), other.tank.getFluid());
        }

        // An empty bridge tank must not join two already-filled, incompatible column segments.
        // Inspect each side away from the prospective boundary instead of looking only at the
        // two immediately adjacent tank blocks.
        FluidStack thisSide = findColumnFluid(this, direction.getOpposite());
        FluidStack otherSide = findColumnFluid(other, direction);
        return thisSide != null && otherSide != null && fluidsCanShareColumn(thisSide, otherSide);
    }

    private static boolean fluidsCanShareColumn(FluidStack first, FluidStack second) {
        return first.isEmpty() || second.isEmpty() || FluidCompatRegistry.areEquivalent(first, second);
    }

    /**
     * Finds the one fluid represented by a pre-existing half-column. A null return means the
     * stored half-column is internally inconsistent.
     */
    private static FluidStack findColumnFluid(TileTank start, Direction awayFromBoundary) {
        FluidStack found = FluidStack.EMPTY;
        TileTank current = start;
        while (current != null) {
            FluidStack held = current.tank.getFluid();
            if (!held.isEmpty()) {
                if (found.isEmpty()) {
                    found = held;
                } else if (!FluidCompatRegistry.areEquivalent(found, held)) {
                    return null;
                }
            }
            BlockEntity next = current.getNeighbourTile(awayFromBoundary);
            current = next instanceof TileTank tank ? tank : null;
        }
        return found;
    }

    /** Helper for {@link #canConnectTo(TileTank, Direction)} that only returns true if both tanks can connect to each
     * other.
     * 
     * @param from
     * @param to
     * @param direction The direction from the "from" tank, to the "to" tank, such that
     *            {@link Objects#equals(Object, Object) Objects.equals(}{@link TileTank#getPos()
     *            from.getPos()}.{@link BlockPos#offset(Direction) offset(direction)}, {@link TileTank#getPos()
     *            to.getPos()}) returns true.
     * @return True if both could connect, false otherwise. */
    public static boolean canTanksConnect(TileTank from, TileTank to, Direction direction) {
        return from.canConnectTo(to, direction) && to.canConnectTo(from, direction.getOpposite());
    }

    /** @return A list of all connected tanks around this block, ordered by position from bottom to top. */
    public List<TileTank> getConnectedTanks() {
        // double-ended queue rather than array list to avoid
        // the copy operation when we search downwards
        Deque<TileTank> tanks = new ArrayDeque<>();
        tanks.add(this);
        TileTank prevTank = this;
        while (true) {
            BlockEntity tileAbove = prevTank.getNeighbourTile(Direction.UP);
            if (!(tileAbove instanceof TileTank)) {
                break;
            }
            TileTank tankUp = (TileTank) tileAbove;
            if (tankUp != null && canTanksConnect(prevTank, tankUp, Direction.UP)) {
                tanks.addLast(tankUp);
            } else {
                break;
            }
            prevTank = tankUp;
        }
        prevTank = this;
        while (true) {
            BlockEntity tileBelow = prevTank.getNeighbourTile(Direction.DOWN);
            if (!(tileBelow instanceof TileTank)) {
                break;
            }
            TileTank tankBelow = (TileTank) tileBelow;
            if (tankBelow != null && canTanksConnect(prevTank, tankBelow, Direction.DOWN)) {
                tanks.addFirst(tankBelow);
            } else {
                break;
            }
            prevTank = tankBelow;
        }
        return new ArrayList<>(tanks);
    }

    // IFluidHandler
    
    public FluidStack getFluidInTank(int tank) {
        if (tank != 0) {
            return FluidStack.EMPTY;
        }

        List<TileTank> tanks = getConnectedTanks();
        boolean gas = false;
        for (TileTank tile : tanks) {
            FluidStack fluid = tile.tank.getFluid();
            if (!fluid.isEmpty()) {
                gas = fluid.getFluid().getFluidType().isLighterThanAir();
                break;
            }
        }
        // Match drain ordering so getFluidInTank() advertises the same fluid that drain(int) will return.
        if (!gas) {
            Collections.reverse(tanks);
        }

        FluidStack total = FluidStack.EMPTY;
        for (TileTank t : tanks) {
            FluidStack other = t.tank.getFluid();
            if (other.isEmpty()) {
                continue;
            }
            if (total.isEmpty()) {
                total = other.copy();
            } else if (FluidCompatRegistry.areEquivalent(total, other)) {
                total.grow(other.getAmount());
            }
        }
        return total;
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank != 0) {
            return 0;
        }

        List<TileTank> tanks = getConnectedTanks();
        int capacity = 0;
        for (TileTank t : tanks) {
            capacity += t.tank.getCapacity();
        }
        return capacity;
    }

	@Override
    public int fill(FluidStack resource, FluidAction doFill) {
        resource = FluidCompatRegistry.canonicalize(resource);
        if (resource.isEmpty() || resource.getAmount() <= 0) {
            return 0;
        }
        int filled = 0;
        List<TileTank> tanks = getConnectedTanks();
        for (TileTank t : tanks) {
            FluidStack current = t.tank.getFluid();
            if (!current.isEmpty() && !FluidCompatRegistry.areEquivalent(current, resource)) {
                return 0;
            }
        }
        boolean gas = resource.getFluid().getFluidType().isLighterThanAir();
        if (gas) {
            Collections.reverse(tanks);
        }
        resource = resource.copy();
        for (TileTank t : tanks) {
            int tankFilled = t.tank.fill(resource, doFill);
            if (tankFilled > 0) {
                if (isPlayerInteracting & doFill == FluidAction.EXECUTE) {
                    t.sendNetworkUpdate(NET_RENDER_DATA);
                }
                resource.shrink(tankFilled);
                filled += tankFilled;
                if (resource.getAmount() == 0) {
                    break;
                }
            }
        }
        return filled;
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction doDrain) {
        return drain((a) -> true, maxDrain, doDrain);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction doDrain) {
        if (resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return drain(stack -> FluidCompatRegistry.areEquivalent(resource, stack), resource.getAmount(), doDrain);
    }

    // IFluidHandlerAdv

    @Override
    public FluidStack drain(IFluidFilter filter, int maxDrain, FluidAction doDrain) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        if (filter == null) {
            return FluidStack.EMPTY;
        }
        List<TileTank> tanks = getConnectedTanks();
        boolean gas = false;
        for (TileTank tile : tanks) {
            FluidStack fluid = tile.tank.getFluid();
            if (!fluid.isEmpty() && filter.matches(fluid)) {
                gas = fluid.getFluid().getFluidType().isLighterThanAir();
                break;
            }
        }
        if (!gas) {
            Collections.reverse(tanks);
        }
        FluidStack total = FluidStack.EMPTY;
        for (TileTank t : tanks) {
            int realMax = maxDrain - (total.isEmpty() ? 0 : total.getAmount());
            if (realMax <= 0) {
                break;
            }
            FluidStack current = t.tank.getFluid();
            if (current.isEmpty() || !filter.matches(current)) {
                continue;
            }
            if (!total.isEmpty() && !FluidCompatRegistry.areEquivalent(total, current)) {
                continue;
            }
            // Once the first tank chooses the drained fluid, lock every later tank to that equivalent fluid.
            FluidStack drained = t.tank.drain(
                stack -> filter.matches(stack) && FluidCompatRegistry.areEquivalent(current, stack),
                realMax,
                doDrain
            );
            if (drained.isEmpty()) continue;
            if (isPlayerInteracting & doDrain == FluidAction.EXECUTE) {
                t.sendNetworkUpdate(NET_RENDER_DATA);
            }
            if (total.isEmpty()) {
                total = drained.copy();
                if (total.isEmpty()) return FluidStack.EMPTY;
                total.setAmount(0);
            }
            total.grow(drained.getAmount());
        }
        return total;
    }

	@Override
	public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
		super.addDrops(toDrop, fortune);
	}

    @Override
    public void saveAdditional(CompoundTag nbt) {
        // Let the base BuildCraft tile save tankManager in the standard {tanks:{tank:{...}}} format.
        // The loader also accepts the direct-FluidStack compatibility form for "tanks".
        super.saveAdditional(nbt);
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);

        // Compatibility with the early IronTanks API-layer format, where "tanks" was the direct tank NBT rather than
        // the normal TankManager compound keyed by tank name.
        if (nbt.contains("tanks", Tag.TAG_COMPOUND)) {
            CompoundTag tanks = nbt.getCompound("tanks");
            if (!tanks.contains(tank.getTankName(), Tag.TAG_COMPOUND) && !tanks.isEmpty()) {
                tank.readFromNBT(tanks);
            }
        }
    }

    @Override
    public int getTanks() {
        // The vertical BuildCraft tank stack is exposed as one logical fluid handler tank. Returning one entry per
        // block would make external pipes/mods see the same combined contents and capacity multiple times.
        return 1;
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return tank == 0 && this.tank.isFluidValid(stack);
    }

}
