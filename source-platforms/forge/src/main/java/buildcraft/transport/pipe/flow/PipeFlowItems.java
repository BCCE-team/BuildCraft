/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.transport.internal.IInjectable;
import buildcraft.api.v2.pipe.ItemTransportProfile;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventItem;
import buildcraft.transport.internal.pipe.PipeEventStatement;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.lib.inventory.ItemTransactorHelper;
import buildcraft.lib.inventory.NoSpaceTransactor;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.DelayedList;
import buildcraft.lib.net.cache.BuildCraftObjectCaches;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.net.MessageMultiPipeItem.TravellingItemData;
import buildcraft.transport.net.PipeItemMessageQueue;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.behaviour.PipeBehaviourStone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;

public final class PipeFlowItems extends PipeFlow implements IFlowItems {
    private static final double EXTRACT_SPEED = 0.08;
    public static final int NET_CREATE_ITEM = 2;

    private final ItemTransportProfile itemTransportProfile = requireItemProfile();
    private final DelayedList<TravellingItem> items = new DelayedList<>();

    private ItemTransportProfile requireItemProfile() {
        if (pipe.getDefinition().getApiType() == null) {
            throw new IllegalStateException("Pipe definition is not linked to API2: " + pipe.getDefinition().identifier);
        }
        return pipe.getDefinition().getApiType().itemProfile().orElseThrow(() ->
            new IllegalStateException("Item pipe has no API2 item profile: " + pipe.getDefinition().identifier)
        );
    }

    public PipeFlowItems(IPipe pipe) {
        super(pipe);
    }

    public PipeFlowItems(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        ListTag list = nbt.getList("items", Tag.TAG_COMPOUND);
        Level level = pipe.getHolder().getPipeWorld();
        long tickNow = level == null ? 0 :level.getGameTime();
        for (int i = 0; i < list.size(); i++) {
            TravellingItem item = new TravellingItem(list.getCompound(i), tickNow);
            if (!item.stack.isEmpty()) {
                items.add(item.getCurrentDelay(tickNow), item);
            }
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        List<List<TravellingItem>> allItems = items.getAllElements();
        ListTag list = new ListTag();

        long tickNow = pipe.getHolder().getPipeWorld().getGameTime();
        int i = 0;
        for (List<TravellingItem> l : allItems) {
            for (TravellingItem item : l) {
                list.add(i++, item.writeToNbt(tickNow));
            }
        }
        nbt.put("items", list);
        return nbt;
    }

    // Network

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide msgSide) throws IOException {
//    	BCLog.d("rece");
        if (msgSide == BCNetworkSide.CLIENT) {
            if (id == NET_CREATE_ITEM) {
                int stackId = buffer.readInt();
                Supplier<ItemStack> link = BuildCraftObjectCaches.retrieveItemStack(stackId);
                int count = buffer.readUnsignedShort();
                TravellingItem item = new TravellingItem(link, count);
                item.toCenter = buffer.readBoolean();
                item.side = buffer.readEnum(Direction.class);
                item.colour = MessageUtil.readEnumOrNull(buffer, DyeColor.class);
                item.timeToDest = buffer.readUnsignedShort();
                item.tickStarted = pipe.getHolder().getPipeWorld().getGameTime() + 1;
                item.tickFinished = item.tickStarted + item.timeToDest;
                items.add(item.timeToDest + 1, item);
            }
        }
    }

    public void handleClientReceviedItems(List<TravellingItemData> list) {
        for (TravellingItemData data : list) {
            handleClientReceviedItem(data);
        }
    }

    public void handleClientReceviedItem(TravellingItemData data) {
        int stackId = data.stackId;
        Supplier<ItemStack> link = BuildCraftObjectCaches.retrieveItemStack(stackId);
        int count = data.stackCount;
        TravellingItem item = new TravellingItem(link, count);
        item.toCenter = data.toCenter;
        item.side = data.side;
        item.colour = data.colour;
        item.timeToDest = data.timeToDest;
        item.tickStarted = pipe.getHolder().getPipeWorld().getGameTime() + 1;
        item.tickFinished = item.tickStarted + item.timeToDest;
        items.add(item.timeToDest + 1, item);
    }

    void sendItemDataToClient(TravellingItem item) {
        final int stackId = BuildCraftObjectCaches.storeItemStack(item.stack);
        /* sendCustomPayload(NET_CREATE_ITEM, (buffer) -> {
         FriendlyByteBuf buf = buffer;
         buf.writeInt(stackId);
         buf.writeShort(item.stack.getCount());
         buf.writeBoolean(item.toCenter);
         buf.writeEnum(item.side);
         MessageUtil.writeEnumOrNull(buf, item.colour);
         buf.writeShort(item.timeToDest > Short.MAX_VALUE ? Short.MAX_VALUE : item.timeToDest);
         });*/
        PipeItemMessageQueue.appendTravellingItem(
            pipe.getHolder().getPipeWorld(), pipe.getHolder().getPipePos(), stackId, item.stack.getCount(),
            item.toCenter, item.side, item.colour, item.timeToDest
        );
    }

    @Override
    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        super.addDrops(toDrop, fortune);
        for (List<TravellingItem> list : items.getAllElements()) {
            for (TravellingItem item : list) {
                if (!item.isPhantom) {
                    toDrop.add(item.stack);
                }
            }
        }
    }

    // IFlowItems

    @Override
    public int tryExtractItems(int count, Direction from, DyeColor colour, IStackFilter filter, FluidAction simulate) {
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot extract items on the client side!");
        }
        if (from == null) {
            return 0;
        }

        BlockEntity tile = pipe.getConnectedTile(from);
        IItemTransactor trans = ItemTransactorHelper.getTransactor(tile, from.getOpposite());

        ItemStack possible = trans.extract(filter, 1, count, true);

        if (possible.isEmpty()) {
            return 0;
        }
        if (possible.getCount() > possible.getMaxStackSize()) {
            possible.setCount(possible.getMaxStackSize());
            count = possible.getMaxStackSize();
        }

        IPipeHolder holder = pipe.getHolder();
        PipeEventItem.TryInsert tryInsert = new PipeEventItem.TryInsert(holder, this, colour, from, possible);
        holder.fireEvent(tryInsert);
        if (tryInsert.isCanceled() || tryInsert.accepted <= 0) {
            return 0;
        }

        count = Math.min(count, tryInsert.accepted);

        ItemStack stack = trans.extract(filter, count, count, simulate == FluidAction.SIMULATE);

        if (stack.isEmpty()) {
            // Inventories may change between simulation and execution (or expose intentionally dynamic handlers).
            // Treat that as an ordinary race instead of taking down the server tick.
            return 0;
        }

        if (simulate == FluidAction.EXECUTE) {
            insertItemEvents(stack, colour, EXTRACT_SPEED, from);
        }

        return stack.getCount();
    }

    @Override
    public void sendPhantomItem(ItemStack stack, Direction from, Direction to, DyeColor colour) {
        if (from == null && to == null) {
            return;
        }
        Direction face0, face1, face2;
        boolean twoItems = from != null && to != null;
        face0 = from;
        face1 = from == null ? to : null;
        face2 = to;

        long now = pipe.getHolder().getPipeWorld().getGameTime();

        TravellingItem firstItem = new TravellingItem(stack);
        firstItem.isPhantom = true;
        firstItem.toCenter = face1 == null;
        firstItem.colour = colour;
        firstItem.side = face0 == null ? face1 : face0;
        firstItem.speed = EXTRACT_SPEED;
        firstItem.genTimings(now, getPipeLength(firstItem.side));
        items.add(firstItem.timeToDest, firstItem);
        sendItemDataToClient(firstItem);

        if (twoItems) {
            TravellingItem secondItem = new TravellingItem(stack);
            secondItem.isPhantom = true;
            secondItem.toCenter = false;
            secondItem.colour = colour;
            secondItem.side = face2;
            secondItem.speed = EXTRACT_SPEED;
            secondItem.genTimings(firstItem.tickFinished, getPipeLength(secondItem.side));
            items.add(secondItem.timeToDest, secondItem);
            sendItemDataToClient(secondItem);
        }
    }

    // PipeFlow

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@Nonnull Capability<T> capability, Direction facing) {
        if (capability == PipeApi.CAP_INJECTABLE) {
            return LazyOptional.of(() -> this).cast();
        } else if (capability == CapUtil.CAP_ITEM_TRANSACTOR) {
            return LazyOptional.of(() -> ItemTransactorHelper.wrapInjectable(this, facing)).cast();
        } else {
            return super.getCapability(capability, facing);
        }
    }

    @Override
    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof IFlowItems;
    }

    @Override
    public boolean canConnect(Direction face, BlockEntity oTile) {
        return ItemTransactorHelper.getTransactor(oTile, face.getOpposite()) != NoSpaceTransactor.INSTANCE;
    }

    @Override
    public void onTick() {
        Level world = pipe.getHolder().getPipeWorld();
 //       if(world.isClientSide)return;
 /*       if(items.getAllElements().isEmpty() | !world.isClientSide)
        	return;
        List<TravellingItem> toTick = getAllItemsForRender();

  //      BCLog.d("size "+toTick.size());
        for (TravellingItem item : toTick) {*/
        	
 //       	BCLog.d(""+item.clientItemLink.get());
        	//if(item.stack.toString().equals("1 ocelot_spawn_egg") ) {
        	//	item.clientItemLink.get();
        List<TravellingItem> toTick = items.advance();
        long currentTime = world.getGameTime();

        for (TravellingItem item : toTick) {
            if (!world.isClientSide() && item.tickFinished > currentTime) {
                // Can happen if something ticks this tile multiple times in a single real tick
                items.add((int) (item.tickFinished - currentTime), item);
                continue;
            }
            if (item.isPhantom) {
                continue;
            }
            if (world.isClientSide()) {
                if (item.clientAtDestination) {
                    // The previous expiry already granted this client item a short destination grace period.
                    // No packet explicitly removes travelling items, so retaining it again would pin a ghost in
                    // every pipe it crossed. Let the timing wheel discard it now.
                    continue;
                }
                // Keep the item at its locally interpolated destination briefly so normal packet/tick jitter does not
                // create a visible gap before the server's next authoritative transport segment arrives.
                item.clientAtDestination = true;
                item.tickStarted = currentTime;
                item.tickFinished = currentTime + 1;
                items.add(1, item);
                continue;
            }
            if (item.toCenter) {
                onItemReachCenter(item);
            } else {
                onItemReachEnd(item);
            }
        }
    }

    private void onItemReachCenter(TravellingItem item) {
        IPipeHolder holder = pipe.getHolder();
        PipeEventItem.ReachCenter reachCenter = new PipeEventItem.ReachCenter(
            holder, this, item.colour, item.stack, item.side
        );
        holder.fireEvent(reachCenter);
        if (reachCenter.getStack().isEmpty()) {
            return;
        }

        PipeEventItem.SideCheck sideCheck = new PipeEventItem.SideCheck(
            holder, this, reachCenter.colour, reachCenter.from, reachCenter.getStack()
        );
        sideCheck.disallow(reachCenter.from);
        for (Direction face : Direction.values()) {
            if (item.tried.contains(face) || !pipe.isConnected(face)) {
                sideCheck.disallow(face);
            }
        }
        holder.fireEvent(sideCheck);

        List<EnumSet<Direction>> order = sideCheck.getOrder();
        if (order.isEmpty()) {
            PipeEventItem.TryBounce tryBounce = new PipeEventItem.TryBounce(
                holder, this, reachCenter.colour, reachCenter.from, reachCenter.getStack()
            );
            holder.fireEvent(tryBounce);
            boolean retryConnectedPipe = reachCenter.from != null
                && pipe.isConnected(reachCenter.from)
                && pipe.getConnectedType(reachCenter.from) == ConnectedType.PIPE;
            if (tryBounce.canBounce || retryConnectedPipe) {
                // A connected item pipe can transiently reject a hand-off (connection/capability update races are
                // especially visible at gold-pipe speed). Keep the cargo in the network and retry instead of
                // materialising it as an ItemEntity in the middle of an otherwise continuous route.
                order = ImmutableList.of(EnumSet.of(reachCenter.from));
            } else {
                dropItem(item.stack, null, item.side.getOpposite(), item.speed);
                return;
            }
        }

        PipeEventItem.ItemEntry entry = new PipeEventItem.ItemEntry(
            reachCenter.colour, reachCenter.getStack(), reachCenter.from
        );
        PipeEventItem.Split split = new PipeEventItem.Split(holder, this, order, entry);
        holder.fireEvent(split);
        ImmutableList<PipeEventItem.ItemEntry> entries = ImmutableList.copyOf(split.items);

        PipeEventItem.FindDest findDest = new PipeEventItem.FindDest(holder, this, order, entries);
        holder.fireEvent(findDest);

        Level world = holder.getPipeWorld();
        long now = world.getGameTime();
        for (PipeEventItem.ItemEntry itemEntry : findDest.items) {
            if (itemEntry.stack.isEmpty()) {
                continue;
            }
            PipeEventItem.ModifySpeed modifySpeed = new PipeEventItem.ModifySpeed(holder, this, itemEntry, item.speed);

            final double newSpeed;

            if (holder.fireEvent(modifySpeed)) {
                double target = modifySpeed.targetSpeed;
                double maxDelta = modifySpeed.maxSpeedChange;
                if (item.speed < target) {
                    newSpeed = Math.min(target, item.speed + maxDelta);
                } else if (item.speed > target) {
                    newSpeed = Math.max(target, item.speed - maxDelta);
                } else {
                    newSpeed = item.speed;
                }
            } else {
                // Nothing affected the speed
                // so just fallback to a sensible default
                if (item.speed > 0.03) {
                    newSpeed = Math.max(0.03, item.speed - PipeBehaviourStone.SPEED_DELTA);
                } else {
                    newSpeed = item.speed;
                }
            }

            List<Direction> destinations = itemEntry.to;
            if (destinations == null || destinations.size() == 0) {
                destinations = findDest.generateRandomOrder();
            }
            if (pipe instanceof Pipe runtimePipe) {
                destinations = runtimePipe.applyItemRouting(reachCenter.from, itemEntry.stack, destinations);
            }
            if (destinations.size() == 0) {
                dropItem(itemEntry.stack, null, item.side.getOpposite(), newSpeed);
            } else {
                TravellingItem newItem = new TravellingItem(itemEntry.stack);
                newItem.tried.addAll(item.tried);
                newItem.toCenter = false;
                newItem.colour = itemEntry.colour;
                newItem.side = destinations.get(0);
                newItem.speed = newSpeed;
                newItem.genTimings(now, getPipeLength(newItem.side));
                items.add(newItem.timeToDest, newItem);
                sendItemDataToClient(newItem);
            }
        }
    }

    private void onItemReachEnd(TravellingItem item) {
        IPipeHolder holder = pipe.getHolder();
        PipeEventItem.ReachEnd reachEnd = new PipeEventItem.ReachEnd(holder, this, item.colour, item.stack, item.side);
        holder.fireEvent(reachEnd);
        item.colour = reachEnd.colour;
        item.stack = reachEnd.getStack();
        ItemStack excess = item.stack;
        if (excess.isEmpty()) {
            return;
        }
        if (pipe.isConnected(item.side)) {
            ConnectedType type = pipe.getConnectedType(item.side);
            Direction oppositeSide = item.side.getOpposite();
            switch (type) {
                case PIPE: {
                    IPipe oPipe = pipe.getConnectedPipe(item.side);
                    if (oPipe == Pipe.EMPTY) {
                        break;
                    }
                    PipeFlow flow = oPipe.getFlow();
                    if (flow instanceof IFlowItems) {
                        IFlowItems oFlow = (IFlowItems) flow;
                        ItemStack before = excess;
                        excess = oFlow.injectItem(excess.copy(), true, oppositeSide, item.colour, item.speed);

                        if (!excess.isEmpty()) {
                            before.shrink(excess.getCount());
                        }

                        excess = fireEventEjectIntoPipe(oFlow, item.side, before, excess);
                    }
                    break;
                }
                case TILE: {
                    BlockEntity tile = pipe.getConnectedTile(item.side);
                    IInjectable injectable = ItemTransactorHelper.getInjectable(tile, oppositeSide);
                    ItemStack before = excess;
                    excess = injectable.injectItem(excess.copy(), true, oppositeSide, item.colour, item.speed);

                    if (!excess.isEmpty()) {
                        IItemTransactor transactor = ItemTransactorHelper.getTransactor(tile, oppositeSide);
                        excess = transactor.insert(excess, false, false);
                    }
                    ItemStack inserted = before.copy();
                    inserted.shrink(excess.getCount());
                    excess = fireEventEjectIntoTile(tile, item.side, inserted, excess);
                    break;
                }
            }
        }
        if (excess.isEmpty()) {
            return;
        }
        item.tried.add(item.side);
        item.toCenter = true;
        item.stack = excess;
        item.genTimings(holder.getPipeWorld().getGameTime(), getPipeLength(item.side));
        items.add(item.timeToDest, item);
        sendItemDataToClient(item);
    }

    private ItemStack fireEventEjectIntoPipe(IFlowItems oFlow, Direction to, ItemStack before, ItemStack excess) {
        IPipeHolder holder = this.pipe.getHolder();
        return fireEventEjected(holder, new PipeEventItem.Ejected.IntoPipe(holder, this, before, excess, to, oFlow));
    }

    private ItemStack fireEventEjectIntoTile(BlockEntity tile, Direction to, ItemStack before, ItemStack excess) {
        IPipeHolder holder = this.pipe.getHolder();
        return fireEventEjected(holder, new PipeEventItem.Ejected.IntoTile(holder, this, before, excess, to, tile));
    }

    private static ItemStack fireEventEjected(IPipeHolder holder, PipeEventItem.Ejected event) {
        holder.fireEvent(event);
        return event.getExcess();
    }

    private void dropItem(ItemStack stack, Direction side, Direction motion, double speed) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        IPipeHolder holder = pipe.getHolder();
        Level world = holder.getPipeWorld();
        BlockPos pos = holder.getPipePos();

        double x = pos.getX() + 0.5 + motion.getStepX() * 0.5;
        double y = pos.getY() + 0.5 + motion.getStepY() * 0.5;
        double z = pos.getZ() + 0.5 + motion.getStepZ() * 0.5;
        speed += 0.01;
        speed *= 2;
        ItemEntity ent = new ItemEntity(world, x, y, z, stack);
        ent.setDeltaMovement(motion.getStepX() * speed,
        		motion.getStepY() * speed,
        		motion.getStepZ() * speed);
        PipeEventItem.Drop drop = new PipeEventItem.Drop(holder, this, ent);
        holder.fireEvent(drop);
        if (ent.getItem().isEmpty() || ent.isRemoved()) {
            return;
        }

        world.addFreshEntity(ent);
    }

    @Override
    public boolean canInjectItems(Direction from) {
        return pipe.isConnected(from);
    }

    @Nonnull
    @Override
    public ItemStack injectItem(@Nonnull ItemStack stack, boolean doAdd, Direction from, DyeColor colour,
        double speed) {
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot inject items on the client side!");
        }
        if (!canInjectItems(from)) {
            return stack;
        }

        if (speed < 0.01) {
            speed = 0.01;
        }

        // Try insert

        PipeEventItem.TryInsert tryInsert = new PipeEventItem.TryInsert(pipe.getHolder(), this, colour, from, stack);
        pipe.getHolder().fireEvent(tryInsert);
        if (tryInsert.isCanceled() || tryInsert.accepted <= 0) {
            return stack;
        }
        int accepted = getSingleTravellingStackCount(stack, tryInsert.accepted);
        ItemStack toSplit = stack.copy();
        ItemStack toInsert = toSplit.split(accepted);

        if (doAdd) {
            insertItemEvents(toInsert, colour, speed, from);
        }

        if (toSplit.isEmpty()) {
            toSplit = StackUtil.EMPTY;
        }

        return toSplit;
    }

    @Override
    public void insertItemsForce(@Nonnull ItemStack stack, Direction from, DyeColor colour, double speed) {
        Level world = pipe.getHolder().getPipeWorld();
        if (world.isClientSide()) {
            throw new IllegalStateException("Cannot inject items on the client side!");
        }
        if (stack.isEmpty()) {
            return;
        }
        if (speed < 0.01) {
            speed = 0.01;
        }
        ItemStack remaining = stack.copy();
        while (!remaining.isEmpty()) {
            ItemStack toInsert = splitSingleTravellingStack(remaining);
            insertItemsForceSingle(toInsert, from, colour, speed);
        }
    }

    /**
     * Inserts an item from a robot station mounted on a pipe face. Robot stations are pluggables, not real pipe
     * connections, so {@link #injectItem(ItemStack, boolean, Direction, DyeColor, double)} would reject the station
     * side via {@link #canInjectItems(Direction)}. This method keeps the virtual station side but still goes through the
     * normal TryInsert/OnInsert path so simulation and real insertion agree and pipe behaviours can clamp or reject the
     * stack instead of the robot blindly deleting everything it carried.
     */
    public ItemStack injectItemFromRobotStation(@Nonnull ItemStack stack, boolean doAdd, Direction from,
        DyeColor colour, double speed) {
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot inject items on the client side!");
        }
        if (stack.isEmpty()) {
            return StackUtil.EMPTY;
        }
        if (from == null) {
            return stack;
        }
        if (speed < 0.01) {
            speed = 0.01;
        }

        PipeEventItem.TryInsert tryInsert = new PipeEventItem.TryInsert(pipe.getHolder(), this, colour, from, stack);
        pipe.getHolder().fireEvent(tryInsert);
        if (tryInsert.isCanceled() || tryInsert.accepted <= 0) {
            return stack;
        }

        int accepted = getSingleTravellingStackCount(stack, tryInsert.accepted);
        ItemStack toSplit = stack.copy();
        ItemStack toInsert = toSplit.split(accepted);

        if (doAdd) {
            insertItemEvents(toInsert, colour, speed, from);
        }

        return toSplit.isEmpty() ? StackUtil.EMPTY : toSplit;
    }

    /** Used internally to split up manual insertions from controlled extractions. */
    private void insertItemEvents(@Nonnull ItemStack toInsert, DyeColor colour, double speed, Direction from) {
        ItemStack remaining = toInsert.copy();
        while (!remaining.isEmpty()) {
            insertItemEventSingle(splitSingleTravellingStack(remaining), colour, speed, from);
        }
    }

    private void insertItemEventSingle(@Nonnull ItemStack toInsert, DyeColor colour, double speed, Direction from) {
        IPipeHolder holder = pipe.getHolder();

        PipeEventItem.OnInsert onInsert = new PipeEventItem.OnInsert(holder, this, colour, toInsert, from);
        holder.fireEvent(onInsert);

        ItemStack inserted = onInsert.getStack();
        if (inserted.isEmpty()) {
            return;
        }

        ItemStack remaining = inserted.copy();
        while (!remaining.isEmpty()) {
            addTravellingItem(splitSingleTravellingStack(remaining), onInsert.colour, speed, from, getPipeLength(from), true);
        }
    }

    private void insertItemsForceSingle(@Nonnull ItemStack stack, Direction from, DyeColor colour, double speed) {
        Direction side = from;
        if (side == null) {
            // Find a reasonable alternative (as it's not allowed to be null)
            for (Direction f : Direction.values()) {
                if (!pipe.isConnected(f)) {
                    side = f;
                    break;
                }
            }
            if (side == null) {
                side = Direction.UP;
            }
        }

        // Explicitly don't send this item to the client:
        // There's little point in trying to render it seeing as it needs to travel 0 distance.
        addTravellingItem(stack, colour, speed, side, 0, false);
    }

    private void addTravellingItem(@Nonnull ItemStack stack, DyeColor colour, double speed, Direction from,
        double distance, boolean sendToClient) {
        if (stack.isEmpty()) {
            return;
        }

        Level world = pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();

        TravellingItem item = new TravellingItem(stack);
        item.side = from;
        item.toCenter = true;
        item.speed = speed;
        item.colour = colour;
        item.genTimings(now, distance);
        if (from != null) {
            item.tried.add(from);
        }

        if (sendToClient) {
            addItemTryMerge(item);
        } else {
            items.add(item.timeToDest, item);
        }
    }

    private ItemStack splitSingleTravellingStack(ItemStack stack) {
        return stack.split(getSingleTravellingStackCount(stack, stack.getCount()));
    }

    private int getSingleTravellingStackCount(ItemStack stack, int accepted) {
        if (stack.isEmpty() || accepted <= 0) {
            return 0;
        }
        int max = Math.min(Math.max(1, stack.getMaxStackSize()), itemTransportProfile.maxItemsPerCycle());
        return Math.min(Math.min(accepted, stack.getCount()), max);
    }

    private void addItemTryMerge(TravellingItem item) {
        List<List<TravellingItem>> delayed = items.getAllElements();
        int minDelay = Math.max(0, item.timeToDest - 3);
        int maxDelay = Math.min(delayed.size() - 1, item.timeToDest + 3);
        int comparisonsLeft = 64;
        for (int delay = minDelay; delay <= maxDelay && comparisonsLeft > 0; delay++) {
            for (TravellingItem item2 : delayed.get(delay)) {
                if (item2.mergeWith(item)) {
                    return;
                }
                if (--comparisonsLeft <= 0) {
                    break;
                }
            }
        }
        items.add(item.timeToDest, item);
        sendItemDataToClient(item);
    }

    @PipeEventHandler
    public static void addTriggers(PipeEventStatement.AddTriggerInternal event) {
        event.triggers.add(BCTransportStatements.TRIGGER_ITEMS_TRAVERSING);
    }

    @Override
    public boolean requiresPeriodicSave() {
        return doesContainItems();
    }

    public boolean doesContainItems() {
        // Note that this counts all items
        // (including phantom items, which is fine)
        // This only works because this list is only expanded to add elements
        // and elements are only removed in advance()
        return items.getMaxDelay() > 0;
    }

    public boolean containsItemMatching(ItemStack filter) {
        if (filter.isEmpty()) {
            return doesContainItems();
        }
        for (List<TravellingItem> list : items.getAllElements()) {
            for (TravellingItem item : list) {
                if (StackUtil.matchesStackOrList(filter, item.stack)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private static EnumSet<Direction> getFirstNonEmptySet(List<EnumSet<Direction>> possible) {
        for (EnumSet<Direction> set : possible) {
            if (set.size() > 0) {
                return set;
            }
        }
        return null;
    }

    double getPipeLength(Direction side) {
        if (side == null) {
            return 0;
        }
        if (pipe.isConnected(side)) {
            if (pipe.getConnectedType(side) == ConnectedType.TILE) {
                return 0.5 + Math.max(0.0, ((Pipe) pipe).getConnectedDist(side));
            }
            return 0.5;
        } else {
            return 0.25;
        }
    }
    public List<TravellingItem> getAllItemsForRender() {
        List<TravellingItem> all = new ArrayList<>();
        for (List<TravellingItem> innerList : items.getAllElements()) {
            all.addAll(innerList);
        }
        return all;
    }

}
