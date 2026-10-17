//? source if >=26.3
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.transport.internal.IInjectable;
import buildcraft.api.v2.pipe.ItemTransportProfile;
import buildcraft.api.v2.pipe.ItemTransitData;
import buildcraft.api.v2.pipe.ItemTransitPhase;
import buildcraft.api.v2.OperationMode;
import buildcraft.transport.api2.PipeItemTransitRuntime;
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
import net.neoforged.neoforge.capabilities.BlockCapability;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.compat.NbtCompat;

public final class PipeFlowItems extends PipeFlow implements IFlowItems {
    private static final double EXTRACT_SPEED = 0.08;
    /**
     * Keep the client one tick behind the server transport timeline. This gives the next authoritative segment time
     * to arrive before the rendered item reaches a center or pipe boundary, avoiding high-speed prediction/correction
     * snaps without reintroducing a per-segment pause. Only roots receive this delay; linked successors inherit the
     * predecessor finish tick and therefore remain on one continuous local timeline.
     */
    private static final int CLIENT_INTERPOLATION_DELAY_TICKS = 1;
    /** Maximum client-only hand-off bridge while waiting for an authoritative successor segment. */
    private static final int CLIENT_PREDICTION_TICKS = 4;
    public static final int NET_CREATE_ITEM = 2;

    private final ItemTransportProfile itemTransportProfile = requireItemProfile();
    private final DelayedList<TravellingItem> items = new DelayedList<>();
    private static final int MAX_TRANSFER_RECEIPTS = 512;
    private final LinkedHashSet<UUID> acceptedTransferIds = new LinkedHashSet<>();
    private UUID incomingTransferId;

    /**
     * Game tick on which this flow last advanced its timing wheel. Cross-pipe insertion can happen either before or
     * after the destination pipe has ticked, so a raw relative DelayedList delay is otherwise off by one depending on
     * block-entity tick order.
     */
    private long lastItemsAdvanceTick = Long.MIN_VALUE;

    private final buildcraft.lib.compat.transfer.TransferJournal<ItemTransferState> transferJournal =
        new buildcraft.lib.compat.transfer.TransferJournal<>(this::captureItemTransfer, this::restoreItemTransfer,
            old -> pipe.getHolder().getPipeTile().setChanged());

    private record ItemTransferState(List<List<TravellingItem>> buckets,
        java.util.Map<TravellingItem, ItemStack> stacks, ListTag pending, ListTag unreadable,
        LinkedHashSet<UUID> transferReceipts) {}

    private ItemTransferState captureItemTransfer() {
        List<List<TravellingItem>> buckets = new ArrayList<>();
        java.util.Map<TravellingItem, ItemStack> copies = new java.util.IdentityHashMap<>();
        for (List<TravellingItem> bucket : items.getAllElements()) {
            buckets.add(new ArrayList<>(bucket));
            for (TravellingItem item : bucket) copies.put(item, item.stack.copy());
        }
        return new ItemTransferState(buckets, copies, pendingItems == null ? null : pendingItems.copy(),
            unreadableItems.copy(), new LinkedHashSet<>(acceptedTransferIds));
    }

    private void restoreItemTransfer(ItemTransferState saved) {
        items.clear();
        for (List<TravellingItem> bucket : saved.buckets()) items.getAllElements().add(new ArrayList<>(bucket));
        saved.stacks().forEach((item, stack) -> item.stack = stack.copy());
        pendingItems = saved.pending() == null ? null : saved.pending().copy();
        unreadableItems.clear();
        for (Tag tag : saved.unreadable()) unreadableItems.add(tag.copy());
        // A rolled-back transfer is not delivered and must not be acknowledged as successful.
        acceptedTransferIds.clear();
        acceptedTransferIds.addAll(saved.transferReceipts());
    }


    private ItemTransportProfile requireItemProfile() {
        if (pipe.getDefinition().getApiType() == null) {
            throw new IllegalStateException("Pipe definition is not linked to API2: " + pipe.getDefinition().identifier);
        }
        return pipe.getDefinition().getApiType().itemProfile().orElseThrow(() ->
            new IllegalStateException("Item pipe has no API2 item profile: " + pipe.getDefinition().identifier)
        );
    }
    /** Raw travelling-item NBT loaded before Minecraft attaches a Level to the block entity. */
    @Nullable
    private ListTag pendingItems;
    /** Entries that still cannot be decoded are kept verbatim so a save never destroys them. */
    private final ListTag unreadableItems = new ListTag();

    public PipeFlowItems(IPipe pipe) {
        super(pipe);
    }

    public PipeFlowItems(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        restoreTransferReceipts(NbtCompat.getByteArray(nbt, "apiTransferReceipts"));
        pendingItems = NbtCompat.getList(nbt, "items").copy();
        loadPendingItems();
    }

    /**
     * Block entities are deserialized before their Level is assigned. Registry-aware ItemStack decoding therefore
     * waits until the pipe has a Level on its first tick.
     */
    private void loadPendingItems() {
        if (pendingItems != null) transferJournal.record();
        if (pendingItems == null) {
            return;
        }
        Level level = pipe.getHolder().getPipeWorld();
        if (level == null) {
            return;
        }

        ListTag list = pendingItems;
        pendingItems = null;
        long tickNow = level.getGameTime();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = NbtCompat.getCompound(list, i);
            try {
                TravellingItem item = new TravellingItem(itemTag, tickNow, level.registryAccess());
                if (!item.stack.isEmpty()) {
                    items.add(Math.min(TravellingItem.MAX_TRANSIT_TICKS, item.getCurrentDelay(tickNow)), item);
                }
            } catch (RuntimeException ex) {
                unreadableItems.add(itemTag.copy());
                buildcraft.lib.internal.debug.BCLog.logger.warn(
                    "Could not migrate a travelling item at {}. Preserving its original NBT.",
                    pipe.getHolder().getPipePos(), ex
                );
            }
        }
    }

    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        writeTransferReceipts(nbt);
        Level level = pipe.getHolder().getPipeWorld();
        if (level == null) {
            if (pendingItems != null) {
                nbt.put("items", pendingItems.copy());
            } else if (!unreadableItems.isEmpty()) {
                nbt.put("items", unreadableItems.copy());
            }
            return nbt;
        }

        loadPendingItems();
        ListTag list = new ListTag();
        for (int i = 0; i < unreadableItems.size(); i++) {
            list.add(NbtCompat.getCompound(unreadableItems, i).copy());
        }
        long tickNow = level.getGameTime();
        for (List<TravellingItem> itemList : items.getAllElements()) {
            for (TravellingItem item : itemList) {
                list.add(item.writeToNbt(tickNow, level.registryAccess()));
            }
        }
        nbt.put("items", list);
        return nbt;
    }

    // Network

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
                scheduleClientItem(item, stackId);
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
        scheduleClientItem(item, stackId);
    }

    void sendItemDataToClient(TravellingItem item) {
        if (buildcraft.lib.compat.transfer.TransferJournal.defer(() -> sendItemDataToClient(item))) return;
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

    public void addDrops(NonNullList<ItemStack> toDrop, int fortune) {
        loadPendingItems();
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

    public int tryExtractItems(int count, Direction from, DyeColor colour, IStackFilter filter, FluidAction simulate) {
        loadPendingItems();
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot extract items on the client side!");
        }
        if (from == null) {
            return 0;
        }

        Level level = pipe.getHolder().getPipeWorld();
        BlockPos targetPos = pipe.getHolder().getPipePos().relative(from);
        if (!level.isLoaded(targetPos)) return 0;
        BlockEntity tile = level.getBlockEntity(targetPos);
        IItemTransactor trans = ItemTransactorHelper.getTransactor(level, targetPos, from.getOpposite(), tile);

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

    public void sendPhantomItem(ItemStack stack, Direction from, Direction to, DyeColor colour) {
        loadPendingItems();
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
        scheduleTravellingItem(firstItem);
        sendItemDataToClient(firstItem);

        if (twoItems) {
            TravellingItem secondItem = new TravellingItem(stack);
            secondItem.isPhantom = true;
            secondItem.toCenter = false;
            secondItem.colour = colour;
            secondItem.side = face2;
            secondItem.speed = EXTRACT_SPEED;
            secondItem.genTimings(firstItem.tickFinished, getPipeLength(secondItem.side));
            scheduleTravellingItem(secondItem);
            sendItemDataToClient(secondItem);
        }
    }

    // PipeFlow

    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(
        BlockCapability<T, Direction> capability, @Nullable Direction facing
    ) {
        if (capability == PipeApi.CAP_INJECTABLE) {
            return (T) this;
        } else if (capability == CapUtil.CAP_ITEM_TRANSACTOR) {
            return (T) ItemTransactorHelper.wrapInjectable(this, facing);
        }
        return super.getCapability(capability, facing);
    }

    public boolean canConnect(Direction face, PipeFlow other) {
        return other instanceof IFlowItems;
    }

    public boolean canConnect(Direction face, BlockEntity oTile) {
        return ItemTransactorHelper.getTransactor(oTile, face.getOpposite()) != NoSpaceTransactor.INSTANCE;
    }

    public boolean canConnect(Direction face, Level level, BlockPos pos, @Nullable BlockEntity oTile) {
        return ItemTransactorHelper.getTransactor(level, pos, face.getOpposite(), oTile) != NoSpaceTransactor.INSTANCE;
    }

    public void onTick() {
        loadPendingItems();
        Level world = pipe.getHolder().getPipeWorld();
 //       if(world.isClientSide())return;
 /*       if(items.getAllElements().isEmpty() | !world.isClientSide())
            return;
        List<TravellingItem> toTick = getAllItemsForRender();

  //      BCLog.d("size "+toTick.size());
        for (TravellingItem item : toTick) {*/

 //       	BCLog.d(""+item.clientItemLink.get());
            //if(item.stack.toString().equals("1 ocelot_spawn_egg") ) {
            //	item.clientItemLink.get();
        long currentTime = world.getGameTime();
        if (lastItemsAdvanceTick == currentTime) {
            // A logical pipe flow must advance its timing wheel at most once per world tick. Advancing twice shortens
            // every active segment and makes fast items visibly jump.
            return;
        }
        lastItemsAdvanceTick = currentTime;
        List<TravellingItem> toTick = items.advance();

        for (TravellingItem item : toTick) {
            if (!world.isClientSide() && item.tickFinished > currentTime) {
                // Can happen if something ticks this tile multiple times in a single real tick
                scheduleTravellingItem(item);
                continue;
            }
            if (item.isPhantom) {
                continue;
            }
            if (world.isClientSide()) {
                if (item.clientBoundaryPrediction || item.clientCenterPrediction) {
                    // A prediction is retained for a few ticks and removed early as soon as the matching
                    // authoritative successor packet arrives. Reaching this queue slot means the bounded fallback
                    // expired without a successor, so discard it rather than leaving a permanent ghost.
                    continue;
                }
                if (item.clientSuccessorLinked) {
                    // The successor is already scheduled from this segment's exact finish time. No prediction is
                    // needed, and retaining the predecessor would create a duplicate at the hand-off point.
                    continue;
                }
                if (item.toCenter) {
                    // The server decides the outgoing side at the center. On a straight/non-ambiguous pipe we can
                    // extrapolate locally while waiting for that packet; on a junction keep the item at the center.
                    int candidates = 0;
                    Direction predictedSide = null;
                    for (Direction face : Direction.values()) {
                        if (face == item.side || !pipe.isConnected(face)) {
                            continue;
                        }
                        candidates++;
                        predictedSide = candidates == 1 ? face : null;
                    }
                    if (candidates > 0) {
                        item.clientCenterPrediction = true;
                        item.clientCenterPredictionSide = predictedSide;
                        items.add(CLIENT_PREDICTION_TICKS - 1, item);
                    }
                    continue;
                }
                // Pipe-to-pipe hand-offs are packet driven. Keep the completed segment as a bounded extrapolation
                // instead of dropping it after one frame; the incoming segment in the next pipe claims and removes
                // this predecessor and inherits its finish tick, making the visual trajectory continuous even when
                // packet processing lands on a later client tick.
                if (item.side != null
                    && pipe.isConnected(item.side)
                    && pipe.getConnectedType(item.side) == ConnectedType.PIPE) {
                    item.clientBoundaryPrediction = true;
                    items.add(CLIENT_PREDICTION_TICKS - 1, item);
                }
                continue;
            }
            if (item.toCenter) {
                onItemReachCenter(item);
            } else {
                onItemReachEnd(item);
            }
        }
    }

    private void scheduleClientItem(TravellingItem item, int stackId) {
        Level world = pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();
        item.clientStackId = stackId;

        TravellingItem predecessor = claimClientPredecessor(item);
        // Apply a small interpolation buffer only to a new visual chain. Successors reuse the predecessor's exact
        // finish tick, so the delay is not accumulated at every center/boundary like the old +1-per-packet scheme.
        item.tickStarted = predecessor == null ? now + CLIENT_INTERPOLATION_DELAY_TICKS : predecessor.tickFinished;
        item.tickFinished = item.tickStarted + item.timeToDest;

        scheduleTravellingItem(item);
    }

    /**
     * Schedules an item by its absolute finish tick instead of by a naked relative delay. DelayedList advances once
     * per PipeFlowItems tick, but a neighbouring pipe may inject before or after that advance depending on block-entity
     * tick order. Accounting for that phase removes the one-tick cadence error that used to accumulate at centers and
     * appear nondeterministically at pipe boundaries.
     */
    private void scheduleTravellingItem(TravellingItem item) {
        Level world = pipe.getHolder().getPipeWorld();
        long now = world == null ? 0 : world.getGameTime();
        items.add(getQueueDelay(item.tickFinished, now), item);
    }

    private int getQueueDelay(long tickFinished, long now) {
        long remaining = Math.max(0L, tickFinished - now);
        if (lastItemsAdvanceTick == now && remaining > 0L) {
            // This flow already consumed the current tick's bucket. The next advance is now + 1, so index zero means
            // "next tick" and every future absolute deadline is one bucket closer than it is before this tick's advance.
            remaining--;
        }
        // DelayedList materializes one bucket per tick of delay; do not let a
        // corrupt save or oversized packet allocate millions of buckets.
        return (int) Math.min(TravellingItem.MAX_TRANSIT_TICKS, remaining);
    }

    private TravellingItem claimClientPredecessor(TravellingItem successor) {
        if (successor.clientStackId < 0) {
            return null;
        }
        if (!successor.toCenter) {
            // Center hand-off: incoming and outgoing segments live in the same PipeFlowItems.
            return claimClientPredecessorInThisFlow(successor.clientStackId, successor.colour, true, null);
        }
        if (successor.side == null) {
            return null;
        }

        // A rejected pipe hand-off bounces back from the same face, so its predecessor is the outbound segment in
        // this flow rather than an item in the neighbouring pipe. Prefer that exact local route when present.
        TravellingItem localBounce = claimClientPredecessorInThisFlow(
            successor.clientStackId, successor.colour, false, successor.side
        );
        if (localBounce != null) {
            return localBounce;
        }

        if (!pipe.isConnected(successor.side)
            || pipe.getConnectedType(successor.side) != ConnectedType.PIPE) {
            return null;
        }

        // Normal boundary hand-off: the predecessor is the outbound segment in the neighbouring pipe.
        IPipe previousPipe = pipe.getConnectedPipe(successor.side);
        if (previousPipe == null || previousPipe == Pipe.EMPTY || !(previousPipe.getFlow() instanceof PipeFlowItems)) {
            return null;
        }
        PipeFlowItems previousFlow = (PipeFlowItems) previousPipe.getFlow();
        return previousFlow.claimClientPredecessorInThisFlow(
            successor.clientStackId, successor.colour, false, successor.side.getOpposite()
        );
    }

    private TravellingItem claimClientPredecessorInThisFlow(int stackId, DyeColor colour, boolean toCenter,
        Direction requiredSide) {
        TravellingItem best = null;
        List<TravellingItem> bestList = null;
        for (List<TravellingItem> delayed : items.getAllElements()) {
            for (TravellingItem candidate : delayed) {
                if (candidate.clientSuccessorLinked
                    || candidate.clientStackId != stackId
                    || candidate.colour != colour
                    || candidate.toCenter != toCenter
                    || (requiredSide != null && candidate.side != requiredSide)) {
                    continue;
                }
                // FIFO pairing keeps equally-looking stacks in transport order. Exact identity is not available in
                // the legacy packet format, but stackId + colour + route is sufficient for visual continuity.
                if (best == null || candidate.tickFinished < best.tickFinished) {
                    best = candidate;
                    bestList = delayed;
                }
            }
        }
        if (best != null) {
            best.clientSuccessorLinked = true;
            if (best.clientBoundaryPrediction || best.clientCenterPrediction) {
                bestList.remove(best);
            }
        }
        return best;
    }

    private void onItemReachCenter(TravellingItem item) {
        if (item.side == null) {
            // A broken saved direction has no valid route. Return the payload to
            // the world instead of crashing the server or silently deleting it.
            if (!dropItem(item.stack, Direction.UP, Direction.UP, item.speed)) holdAtCentre(item, 20);
            return;
        }
        IPipeHolder holder = pipe.getHolder();
        PipeEventItem.ReachCenter reachCenter = new PipeEventItem.ReachCenter(
            holder, this, item.colour, item.stack, item.side
        );
        holder.fireEvent(reachCenter);
        if (reachCenter.getStack().isEmpty()) {
            fireTransit(ItemTransitPhase.CANCELLED, item, null);
            return;
        }
        item.stack = reachCenter.getStack();
        item.colour = reachCenter.colour;
        PipeItemTransitRuntime.Result centreEvent = fireTransit(ItemTransitPhase.CENTER, item, null);
        if (finishControlledEvent(item, centreEvent)) return;
        Direction requestedRoute = centreEvent.redirected();

        PipeEventItem.SideCheck sideCheck = new PipeEventItem.SideCheck(
            holder, this, item.colour, reachCenter.from, item.stack
        );
        sideCheck.disallow(reachCenter.from);
        for (Direction face : Direction.values()) {
            if (item.tried.contains(face) || !pipe.isConnected(face)) {
                sideCheck.disallow(face);
            }
        }
        holder.fireEvent(sideCheck);
        fireTransit(ItemTransitPhase.SIDE_CHECK, item, null);

        List<EnumSet<Direction>> order = sideCheck.getOrder();
        if (requestedRoute != null) order = ImmutableList.of(EnumSet.of(requestedRoute));
        if (order.isEmpty()) {
            PipeEventItem.TryBounce tryBounce = new PipeEventItem.TryBounce(
                holder, this, reachCenter.colour, reachCenter.from, reachCenter.getStack()
            );
            holder.fireEvent(tryBounce);
            boolean hasConnectedOutput = hasConnectedOutputOtherThan(reachCenter.from);
            boolean retryConnectedPipe = hasConnectedOutput
                && reachCenter.from != null
                && pipe.isConnected(reachCenter.from)
                && pipe.getConnectedType(reachCenter.from) == ConnectedType.PIPE;
            if (tryBounce.canBounce || retryConnectedPipe) {
                // Only retry through the incoming pipe when this pipe still has some other physical destination.
                // A true dead-end must eject out of the open face, not reverse back through the pipe network.
                order = ImmutableList.of(EnumSet.of(reachCenter.from));
            } else {
                if (!dropItem(item.stack, null, item.side.getOpposite(), item.speed)) holdAtCentre(item, 20);
                return;
            }
        }

        PipeEventItem.ItemEntry entry = new PipeEventItem.ItemEntry(
            item.colour, item.stack, reachCenter.from
        );
        PipeEventItem.Split split = new PipeEventItem.Split(holder, this, order, entry);
        holder.fireEvent(split);
        fireTransit(ItemTransitPhase.SPLIT, item, null);
        ImmutableList<PipeEventItem.ItemEntry> entries = ImmutableList.copyOf(split.items);

        PipeEventItem.FindDest findDest = new PipeEventItem.FindDest(holder, this, order, entries);
        holder.fireEvent(findDest);
        fireTransit(ItemTransitPhase.FIND_DEST, item, null);

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
            if (requestedRoute != null) destinations = List.of(requestedRoute);
            // Read-only observation of the post-BC8 speed decision.
            fireTransit(ItemTransitPhase.MODIFY_SPEED, item, null);
            TravellingItem routeItem = new TravellingItem(itemEntry.stack.copy());
            routeItem.side = reachCenter.from;
            routeItem.colour = itemEntry.colour;
            routeItem.speed = newSpeed;
            // A split creates distinct item owners: preserve the original ID only for
            // a single successor; otherwise give every child a fresh token.
            routeItem.apiTransitId = findDest.items.size() == 1 ? item.apiTransitId
                : (item.apiTransitId == null ? null : UUID.randomUUID());
            routeItem.apiTransitPayload = item.apiTransitPayload;
            PipeItemTransitRuntime.Result routeEvent = fireTransit(ItemTransitPhase.ROUTING, routeItem,
                destinations.isEmpty() ? null : destinations.get(0));
            if (finishControlledEvent(routeItem, routeEvent)) continue;
            if (routeEvent.redirected() != null) destinations = List.of(routeEvent.redirected());
            if (destinations.size() == 0) {
                if (!dropItem(routeItem.stack, null, item.side.getOpposite(), routeItem.speed)) holdAtCentre(routeItem, 20);
            } else {
                TravellingItem newItem = new TravellingItem(routeItem.stack);
                newItem.apiTransitId = routeItem.apiTransitId;
                newItem.apiTransitPayload = routeItem.apiTransitPayload;
                newItem.tried.addAll(item.tried);
                newItem.toCenter = false;
                newItem.colour = routeItem.colour;
                newItem.side = destinations.get(0);
                newItem.speed = routeItem.speed;
                newItem.genTimings(now, getPipeLength(newItem.side));
                scheduleTravellingItem(newItem);
                sendItemDataToClient(newItem);
            }
        }
    }

    private boolean hasConnectedOutputOtherThan(Direction from) {
        for (Direction face : Direction.values()) {
            if (face != from && pipe.isConnected(face)) {
                return true;
            }
        }
        return false;
    }

    private void onItemReachEnd(TravellingItem item) {
        if (item.side == null) {
            if (!dropItem(item.stack, Direction.UP, Direction.UP, item.speed)) holdAtCentre(item, 20);
            return;
        }
        IPipeHolder holder = pipe.getHolder();
        PipeEventItem.ReachEnd reachEnd = new PipeEventItem.ReachEnd(holder, this, item.colour, item.stack, item.side);
        holder.fireEvent(reachEnd);
        item.colour = reachEnd.colour;
        item.stack = reachEnd.getStack();
        PipeItemTransitRuntime.Result exitEvent = fireTransit(ItemTransitPhase.EXIT, item, item.side);
        if (finishControlledEvent(item, exitEvent)) return;
        ItemStack excess = item.stack;
        if (excess.isEmpty()) {
            fireTransit(ItemTransitPhase.CANCELLED, item, item.side);
            return;
        }
        if (!pipe.isConnected(item.side)) {
            // An item that reached an open pipe end has already completed its route. Eject it from that face now;
            // sending it back to the centre first makes it visibly reverse and can spawn it inside the pipe block.
            if (!dropItem(excess, item.side, item.side, item.speed)) holdAtCentre(item, 20);
            return;
        }
        int beforeTransferCount = excess.getCount();
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
                        try (buildcraft.transport.api2.PipeTransitHandoff handoff =
                            buildcraft.transport.api2.PipeTransitHandoff.begin(item.apiTransitId, item.apiTransitPayload)) {
                            excess = oFlow.injectItem(excess.copy(), true, oppositeSide, item.colour, item.speed);
                        }

                        if (!excess.isEmpty()) {
                            before.shrink(excess.getCount());
                        }

                        excess = fireEventEjectIntoPipe(oFlow, item.side, before, excess);
                    }
                    break;
                }
                case TILE: {
                    Level level = pipe.getHolder().getPipeWorld();
                    BlockPos targetPos = pipe.getHolder().getPipePos().relative(item.side);
                    BlockEntity tile = level.getBlockEntity(targetPos);
                    IInjectable injectable = ItemTransactorHelper.getInjectable(level, targetPos, oppositeSide);
                    ItemStack before = excess;
                    excess = injectable.injectItem(excess.copy(), true, oppositeSide, item.colour, item.speed);

                    if (!excess.isEmpty()) {
                        IItemTransactor transactor = ItemTransactorHelper.getTransactor(level, targetPos, oppositeSide, tile);
                        excess = transactor.insert(excess, false, false);
                    }
                    ItemStack inserted = before.copy();
                    inserted.shrink(excess.getCount());
                    excess = fireEventEjectIntoTile(tile, item.side, inserted, excess);
                    break;
                }
            }
        }
        int moved = Math.max(0, beforeTransferCount - excess.getCount());
        if (moved > 0 && pipe instanceof Pipe runtimePipe) {
            ItemStack transferred = item.stack.copy();
            transferred.setCount(moved);
            PipeItemTransitRuntime.fire(runtimePipe, ItemTransitPhase.TRANSFERRED,
                transferred, new ItemTransitData(Optional.ofNullable(item.colour), item.speed),
                item.side.getOpposite(), item.side, item.apiTransitId, item.apiTransitPayload);
            if (!excess.isEmpty()) item.apiTransitId = UUID.randomUUID();
        }
        if (excess.isEmpty()) {
            return;
        }
        fireTransit(ItemTransitPhase.REJECTED, item, item.side);
        item.tried.add(item.side);
        item.toCenter = true;
        item.stack = excess;
        item.genTimings(holder.getPipeWorld().getGameTime(), getPipeLength(item.side));
        scheduleTravellingItem(item);
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

    /** Returns false if an addon rejects entity spawn, so the travelling item can be retried. */
    private boolean dropItem(ItemStack stack, Direction side, Direction motion, double speed) {
        if (stack == null || stack.isEmpty()) {
            return true;
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
        if (ent.getItem().isEmpty()) return true;
        if (ent.isRemoved()) return false;

        if (!world.addFreshEntity(ent)) return false;
        if (pipe instanceof Pipe runtimePipe) {
            PipeItemTransitRuntime.fire(runtimePipe, ItemTransitPhase.EJECTED,
                ent.getItem(), new ItemTransitData(Optional.empty(), Math.max(0.0, speed)),
                side, motion, null);
        }
        return true;
    }

    public boolean canInjectItems(Direction from) {
        return pipe.isConnected(from);
    }

    @Nonnull
    public ItemStack injectItem(@Nonnull ItemStack stack, boolean doAdd, Direction from, DyeColor colour,
        double speed) {
        loadPendingItems();
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot inject items on the client side!");
        }
        if (!canInjectItems(from)
            || (pipe instanceof Pipe runtimePipe && !runtimePipe.acceptItemInjection(stack, from,
                doAdd ? OperationMode.EXECUTE : OperationMode.SIMULATE))) {
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

    public void insertItemsForce(@Nonnull ItemStack stack, Direction from, DyeColor colour, double speed) {
        loadPendingItems();
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
        loadPendingItems();
        if (pipe.getHolder().getPipeWorld().isClientSide()) {
            throw new IllegalStateException("Cannot inject items on the client side!");
        }
        if (stack.isEmpty()) {
            return StackUtil.EMPTY;
        }
        if (from == null || (pipe instanceof Pipe runtimePipe
            && !runtimePipe.acceptItemInjection(stack, from,
                doAdd ? OperationMode.EXECUTE : OperationMode.SIMULATE))) {
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

        transferJournal.record();
        Level world = pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();

        TravellingItem item = new TravellingItem(stack);
        buildcraft.transport.api2.PipeTransitHandoff.Envelope handoff =
            buildcraft.transport.api2.PipeTransitHandoff.claim();
        item.apiTransitId = incomingTransferId != null ? incomingTransferId : handoff.id();
        item.apiTransitPayload = handoff.payload();
        item.side = from;
        item.toCenter = true;
        item.speed = speed;
        item.colour = colour;
        item.genTimings(now, distance);
        if (from != null) {
            item.tried.add(from);
        }

        if (pipe instanceof Pipe runtimePipe) {
            PipeItemTransitRuntime.Result entered = PipeItemTransitRuntime.fire(runtimePipe, ItemTransitPhase.ENTER,
                item.stack, new ItemTransitData(Optional.ofNullable(item.colour), item.speed),
                from, null, item.apiTransitId, item.apiTransitPayload);
            item.apiTransitId = entered.id();
            item.apiTransitPayload = entered.payload();
        }
        if (sendToClient) {
            addItemTryMerge(item);
        } else {
            scheduleTravellingItem(item);
        }
    }

    private ItemStack splitSingleTravellingStack(ItemStack stack) {
        return stack.split(getSingleTravellingStackCount(stack, stack.getCount()));
    }

    private PipeItemTransitRuntime.Result fireTransit(ItemTransitPhase phase, TravellingItem item,
                                                        Direction outgoing) {
        ItemTransitData metadata = new ItemTransitData(Optional.ofNullable(item.colour), item.speed);
        if (!(pipe instanceof Pipe runtimePipe)) {
            return new PipeItemTransitRuntime.Result(item.stack, metadata, item.apiTransitId, null, 0, null, item.apiTransitPayload);
        }
        PipeItemTransitRuntime.Result action = PipeItemTransitRuntime.fire(runtimePipe, phase,
            item.stack, metadata, item.side, outgoing, item.apiTransitId, item.apiTransitPayload);
        item.stack = action.stack();
        item.colour = action.transit().color().orElse(null);
        if (action.transit().speedBlocksPerTick() > 0) item.speed = action.transit().speedBlocksPerTick();
        item.apiTransitId = action.id();
        item.apiTransitPayload = action.payload();
        return action;
    }

    private boolean finishControlledEvent(TravellingItem item, PipeItemTransitRuntime.Result action) {
        if (item.stack.isEmpty()) return true;
        if (action.eject() != null) {
            if (!dropItem(item.stack, action.eject(), action.eject(), item.speed)) holdAtCentre(item, 20);
            return true;
        }
        if (action.delay() > 0) {
            holdAtCentre(item, action.delay());
            return true;
        }
        return false;
    }

    private void holdAtCentre(TravellingItem item, int ticks) {
        long now = pipe.getHolder().getPipeWorld().getGameTime();
        item.toCenter = true;
        item.tickStarted = now;
        item.timeToDest = ticks;
        item.tickFinished = now + ticks;
        scheduleTravellingItem(item);
        sendItemDataToClient(item);
        pipe.getHolder().getPipeTile().setChanged();
    }

    private void restoreTransferReceipts(byte[] data) {
        if (data.length % 16 != 0 || data.length > MAX_TRANSFER_RECEIPTS * 16) return;
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.remaining() >= 16) {
            acceptedTransferIds.add(new UUID(buffer.getLong(), buffer.getLong()));
        }
    }

    private void writeTransferReceipts(CompoundTag nbt) {
        if (acceptedTransferIds.isEmpty()) return;
        ByteBuffer buffer = ByteBuffer.allocate(acceptedTransferIds.size() * 16);
        for (UUID id : acceptedTransferIds) {
            buffer.putLong(id.getMostSignificantBits());
            buffer.putLong(id.getLeastSignificantBits());
        }
        nbt.putByteArray("apiTransferReceipts", buffer.array());
    }

    private void rememberTransfer(UUID id) {
        if (id == null) return;
        acceptedTransferIds.remove(id);
        acceptedTransferIds.add(id);
        if (acceptedTransferIds.size() > MAX_TRANSFER_RECEIPTS) {
            acceptedTransferIds.remove(acceptedTransferIds.iterator().next());
        }
        pipe.getHolder().getPipeTile().setChanged();
    }

    /** Virtual entry from an API transit callback, independent of physical pipe connections. */
    public ItemStack injectTransitStack(ItemStack stack, boolean doAdd, Direction from,
                                        DyeColor colour, double speed, UUID id) {
        Level world = pipe.getHolder().getPipeWorld();
        if (world == null || world.isClientSide()) throw new IllegalStateException("Server transfer only");
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (from == null) return stack.copy();
        if (id != null && acceptedTransferIds.contains(id)) return ItemStack.EMPTY;
        if (pipe instanceof Pipe runtimePipe && !runtimePipe.acceptItemInjection(stack, from,
            doAdd ? OperationMode.EXECUTE : OperationMode.SIMULATE)) return stack.copy();
        if (!Double.isFinite(speed) || speed < 0.01) speed = 0.05;
        PipeEventItem.TryInsert attempt = new PipeEventItem.TryInsert(pipe.getHolder(), this, colour, from, stack.copy());
        pipe.getHolder().fireEvent(attempt);
        if (attempt.isCanceled() || attempt.accepted <= 0) return stack.copy();
        int accepted = getSingleTravellingStackCount(stack, attempt.accepted);
        if (accepted <= 0) return stack.copy();
        ItemStack offered = stack.copy();
        offered.setCount(accepted);
        if (!doAdd) {
            ItemStack remainder = stack.copy();
            remainder.shrink(accepted);
            return remainder;
        }
        PipeEventItem.OnInsert onInsert = new PipeEventItem.OnInsert(pipe.getHolder(), this,
            colour, offered.copy(), from);
        pipe.getHolder().fireEvent(onInsert);
        ItemStack entered = onInsert.getStack();
        if (entered.isEmpty() || entered.getCount() > accepted || entered.getCount() < 0) return stack.copy();
        UUID previous = incomingTransferId;
        incomingTransferId = id;
        try {
            addTravellingItem(entered.copy(), onInsert.colour, speed, from, getPipeLength(from), true);
            rememberTransfer(id);
        } finally {
            incomingTransferId = previous;
        }
        ItemStack remainder = stack.copy();
        remainder.shrink(entered.getCount());
        return remainder;
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
        long now = pipe.getHolder().getPipeWorld().getGameTime();
        int expectedDelay = getQueueDelay(item.tickFinished, now);
        int minDelay = Math.max(0, expectedDelay - 3);
        int maxDelay = Math.min(delayed.size() - 1, expectedDelay + 3);
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
        scheduleTravellingItem(item);
        sendItemDataToClient(item);
    }

    @PipeEventHandler
    public static void addTriggers(PipeEventStatement.AddTriggerInternal event) {
        event.triggers.add(BCTransportStatements.TRIGGER_ITEMS_TRAVERSING);
    }

    public boolean requiresPeriodicSave() {
        return doesContainItems();
    }

    public boolean doesContainItems() {
        loadPendingItems();
        // Note that this counts all items
        // (including phantom items, which is fine)
        // This only works because this list is only expanded to add elements
        // and elements are only removed in advance()
        return items.getMaxDelay() > 0;
    }

    public boolean containsItemMatching(ItemStack filter) {
        loadPendingItems();
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
        loadPendingItems();
        List<TravellingItem> all = new ArrayList<>();
        for (List<TravellingItem> innerList : items.getAllElements()) {
            all.addAll(innerList);
        }
        return all;
    }

}
