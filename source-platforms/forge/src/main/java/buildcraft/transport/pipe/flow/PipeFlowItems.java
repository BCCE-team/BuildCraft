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
import java.util.Map;
import java.util.WeakHashMap;
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
import buildcraft.transport.net.MessageMultiPipeItem;
import buildcraft.transport.net.MessageMultiPipeItem.TravellingItemData;
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
import buildcraft.transport.client.ClientPipeVisualClock;
import buildcraft.transport.client.ClientItemSegmentTimeline;

public final class PipeFlowItems extends PipeFlow implements IFlowItems {
    private static final double EXTRACT_SPEED = 0.08;
    /** Buffered playout avoids a pause at every two-tick gold-pipe handoff. */
    private static final int CLIENT_INTERPOLATION_DELAY_TICKS = ClientItemSegmentTimeline.PLAYOUT_BUFFER_TICKS;
    private static final int MAX_CLIENT_RETAINED_SEGMENTS = 512;
    /** Client-only, per-Level state; the map does not keep unloaded worlds alive. */
    private static final Map<Level, ClientItemSegmentTimeline<TravellingItem>> CLIENT_SEGMENTS = new WeakHashMap<>();
    // The client render list is independent of DelayedList's per-block tick phase.
    // Different chunks may tick their block entities in different order; moving
    // render objects through the timing wheel caused a one-tick gap at boundaries.
    private final List<TravellingItem> clientRetained = new ArrayList<>();

    private static ClientItemSegmentTimeline<TravellingItem> clientTimeline(Level level) {
        return CLIENT_SEGMENTS.computeIfAbsent(level, ignored -> new ClientItemSegmentTimeline<>());
    }

    /** Render-only visibility. No predicted route or hidden handoff frame. */
    public boolean shouldRenderClientItem(TravellingItem item, float partialTicks) {
        Level level = pipe.getHolder().getPipeWorld();
        if (level == null || !level.isClientSide()) return true;
        double renderTime = getVisualTick() + Math.max(0.0, Math.min(1.0, partialTicks));
        return clientTimeline(level).visible(item.visualTransitId, item, renderTime, item.tickStarted);
    }
    /** Server-thread-only carry-over of visual identity across a synchronous pipe injection. */
    private static final ThreadLocal<Long> VISUAL_HANDOFF_ID = new ThreadLocal<>();
    public static final int NET_CREATE_ITEM = 2;
    private static final int MAX_INITIAL_ITEMS = 2048;


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

    /**
     * Client rendering and scheduling share a monotonic local tick counter.
     * World game time is still authoritative for the server's transport logic,
     * but may jump on the client when a periodic time-sync packet arrives.
     */
    public long getVisualTick() {
        Level world = pipe.getHolder().getPipeWorld();
        return world == null ? 0L : (world.isClientSide()
            ? ClientPipeVisualClock.ticks() : world.getGameTime());
    }



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
        restoreTransferReceipts(nbt.getByteArray("apiTransferReceipts"));
        ListTag list = nbt.getList("items", Tag.TAG_COMPOUND);
        Level level = pipe.getHolder().getPipeWorld();
        long tickNow = level == null ? 0 : (level.isClientSide()
            ? ClientPipeVisualClock.ticks() : level.getGameTime());
        for (int i = 0; i < list.size(); i++) {
            TravellingItem item = new TravellingItem(list.getCompound(i), tickNow);
            if (!item.stack.isEmpty()) {
                items.add(Math.min(TravellingItem.MAX_TRANSIT_TICKS, item.getCurrentDelay(tickNow)), item);
            }
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        writeTransferReceipts(nbt);
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

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        if (id != NET_ID_FULL_STATE || side != BCNetworkSide.SERVER) return;
        // Pipe creation/full-state messages must include in-flight item segments.
        // Never grow an initial payload without bounds when many item entities are stacked.
        int sent = 0;
        for (List<TravellingItem> bucket : items.getAllElements()) {
            for (TravellingItem item : bucket) {
                if (!item.isPhantom && !item.stack.isEmpty() && sent < MAX_INITIAL_ITEMS) sent++;
            }
        }
        buffer.writeVarInt(sent);
        int written = 0;
        for (List<TravellingItem> bucket : items.getAllElements()) {
            for (TravellingItem item : bucket) {
                if (item.isPhantom || item.stack.isEmpty()) continue;
                if (written++ >= sent) return;
                buffer.writeItem(item.stack);
                buffer.writeVarLong(item.visualTransitId);
                buffer.writeShort(Math.min(65535, item.stack.getCount()));
                buffer.writeBoolean(item.toCenter);
                buffer.writeEnum(item.side);
                MessageUtil.writeEnumOrNull(buffer, item.colour);
                buffer.writeShort(Math.min(65535, Math.max(0, item.timeToDest)));
                buffer.writeBoolean(item.pausedAtCenter);
                long serverSentTick = pipe.getHolder().getPipeWorld().getGameTime();
                buffer.writeVarLong(serverSentTick);
                buffer.writeVarInt((int) Math.max(-2_400L,
                    Math.min(1_200L, item.tickStarted - serverSentTick)));
            }
        }
    }

    // Network

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide msgSide) throws IOException {
//    	BCLog.d("rece");
        if (msgSide == BCNetworkSide.CLIENT) {
            if (id == NET_ID_FULL_STATE) {
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_INITIAL_ITEMS) {
                    throw new IOException("Invalid initial travelling item count: " + count);
                }
                for (int i = 0; i < count; i++) {
                    // BCU-style full snapshot: the stack itself arrives with the segment.
                    // Never wait for the async cache to resolve before the first render.
                    ItemStack stack = buffer.readItem();
                    long visualTransitId = buffer.readVarLong();
                    int stackCount = buffer.readUnsignedShort();
                    boolean toCenter = buffer.readBoolean();
                    Direction side = buffer.readEnum(Direction.class);
                    DyeColor colour = MessageUtil.readEnumOrNull(buffer, DyeColor.class);
                    int timeToDest = buffer.readUnsignedShort();
                    boolean pausedAtCenter = buffer.readBoolean();
                    long serverSentTick = buffer.readVarLong();
                    int startOffset = buffer.readVarInt();
                    long serverStartTick = (serverSentTick >= 0 && serverSentTick <= Long.MAX_VALUE - 1_200L
                        && startOffset >= -2_400 && startOffset <= 1_200)
                        ? serverSentTick + startOffset : ClientItemSegmentTimeline.NO_SERVER_TICK;
                    if (stack.isEmpty() || stackCount <= 0) continue;
                    TravellingItem item = new TravellingItem(stack);
                    item.stackSize = stackCount;
                    item.visualTransitId = visualTransitId;
                    item.toCenter = toCenter;
                    item.side = side;
                    item.colour = colour;
                    item.timeToDest = timeToDest;
                    item.pausedAtCenter = pausedAtCenter;
                    scheduleClientItem(item, serverStartTick, serverSentTick);
                }
            } else if (id == NET_CREATE_ITEM) {
                int stackId = buffer.readInt();
                Supplier<ItemStack> link = BuildCraftObjectCaches.retrieveItemStack(stackId);
                int count = buffer.readUnsignedShort();
                TravellingItem item = new TravellingItem(link, count);
                item.toCenter = buffer.readBoolean();
                item.side = buffer.readEnum(Direction.class);
                item.colour = MessageUtil.readEnumOrNull(buffer, DyeColor.class);
                item.timeToDest = buffer.readUnsignedShort();
                scheduleClientItem(item);
            }
        }
    }

    public void handleClientReceviedItems(List<TravellingItemData> list) {
        for (TravellingItemData data : list) {
            handleClientReceviedItem(data);
        }
    }

    public void handleClientReceviedItem(TravellingItemData data) {
        final TravellingItem item;
        if (!data.stack.isEmpty()) {
            // BCU's direct-ItemStack path: the renderer has the exact NBT and
            // count from the first frame, even for a two-tick gold-pipe segment.
            item = new TravellingItem(data.stack.copy());
            item.stackSize = data.stackCount;
        } else {
            // Compatibility for addons still using the old cache-ID queue API.
            // Normal BuildCraft item traffic never takes this deferred path.
            Supplier<ItemStack> link = BuildCraftObjectCaches.retrieveItemStack(data.stackId);
            item = new TravellingItem(link, data.stackCount);
        }
        item.visualTransitId = data.visualTransitId;
        item.toCenter = data.toCenter;
        item.side = data.side;
        item.colour = data.colour;
        item.timeToDest = data.timeToDest;
        item.pausedAtCenter = data.pausedAtCenter;
        scheduleClientItem(item, data.serverStartTick, data.serverSentTick);
    }

    void sendItemDataToClient(TravellingItem item) {
        if (item.stack.isEmpty()) return;
        // The shared queue remains loader neutral. Forge attaches a full stack
        // snapshot to this synchronous append; NeoForge retains its own codec.
        MessageMultiPipeItem.appendTracked(
            pipe.getHolder().getPipeWorld(), pipe.getHolder().getPipePos(), item.stack,
            item.stack.getCount(), item.toCenter, item.side, item.colour,
            item.timeToDest, item.pausedAtCenter, item.visualTransitId, item.tickStarted
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

        // Do not resolve an adjacent BlockEntity if the neighbour chunk is absent.
        if (!pipe.getHolder().getPipeWorld().isLoaded(pipe.getHolder().getPipePos().relative(from))) return 0;
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
        scheduleTravellingItem(firstItem);
        sendItemDataToClient(firstItem);

        if (twoItems) {
            TravellingItem secondItem = new TravellingItem(stack);
            secondItem.isPhantom = true;
            secondItem.visualTransitId = firstItem.visualTransitId;
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
        long currentTime = getVisualTick();
        if (lastItemsAdvanceTick == currentTime) return;
        lastItemsAdvanceTick = currentTime;
        if (world.isClientSide()) {
            // Render state is not a delayed tick-wheel: once a movement packet
            // arrives, its position is evaluated directly from absolute time.
            // The predecessor/successor can live in different chunks and tick
            // on different phases without causing a missing visual frame.
            // Discard legacy snapshot wheel buckets too: rendering is now
            // driven exclusively by the packet-backed clientRetained list.
            items.advance();
            ClientItemSegmentTimeline<TravellingItem> timeline = clientTimeline(world);
            timeline.prune(currentTime);
            clientRetained.removeIf(item ->
                currentTime > item.tickFinished + ClientItemSegmentTimeline.TAIL_TICKS
                || (currentTime >= item.tickStarted &&
                    !timeline.visible(item.visualTransitId, item, currentTime, item.tickStarted)));
            return;
        }

        // The authoritative server continues using the original timing wheel.
        List<TravellingItem> toTick = items.advance();
        for (TravellingItem item : toTick) {
            if (item.tickFinished > currentTime) {
                scheduleTravellingItem(item);
                continue;
            }
            if (item.isPhantom) continue;
            if (item.toCenter) {
                onItemReachCenter(item);
            } else {
                onItemReachEnd(item);
            }
        }
    }

    private void scheduleClientItem(TravellingItem item) {
        scheduleClientItem(item, ClientItemSegmentTimeline.NO_SERVER_TICK,
            ClientItemSegmentTimeline.NO_SERVER_TICK);
    }

    private void scheduleClientItem(TravellingItem item, long serverStartTick, long serverSentTick) {
        // BCU-style self-contained stacks with authoritative server tick starts.
        // The initial jitter buffer is paid once per cargo, not once per segment.
        // Subsequent segments keep their true spacing, and the renderer may
        // extrapolate briefly across a known straight connection if delayed.
        Level world = pipe.getHolder().getPipeWorld();
        long now = getVisualTick();
        item.timeToDest = Math.max(1, Math.min(TravellingItem.MAX_TRANSIT_TICKS, item.timeToDest));
        ClientItemSegmentTimeline<TravellingItem> timeline = clientTimeline(world);
        // Never start every half-pipe segment at packet arrival. With a two-tick
        // gold-pipe leg, even a one-tick variation in packet handling creates a
        // visible stop-and-go cadence. Actual server timestamps preserve the
        // spacing between every centre and boundary event, regardless of jitter.
        item.tickStarted = item.visualTransitId > 0L
            ? timeline.startFor(item.visualTransitId, now, serverStartTick, serverSentTick)
            : now + CLIENT_INTERPOLATION_DELAY_TICKS;
        item.tickFinished = item.tickStarted + item.timeToDest;
        timeline.record(item.visualTransitId, item, item.tickStarted, item.tickFinished, now);
        // The client's visual list must not wait for its tile entity to advance
        // DelayedList. A packet received after this block's tick is still
        // eligible for the very next render frame, including across chunks.
        if (clientRetained.size() >= MAX_CLIENT_RETAINED_SEGMENTS) {
            clientRetained.remove(0);
        }
        clientRetained.add(item);
    }

    /**
     * Schedules an item by its absolute finish tick instead of by a naked relative delay. DelayedList advances once
     * per PipeFlowItems tick, but a neighbouring pipe may inject before or after that advance depending on block-entity
     * tick order. Accounting for that phase removes the one-tick cadence error that used to accumulate at centers and
     * appear nondeterministically at pipe boundaries.
     */
    private void scheduleTravellingItem(TravellingItem item) {
        Level world = pipe.getHolder().getPipeWorld();
        long now = getVisualTick();
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
            // An unusable physical connection is not an eligible route. Implicitly bouncing
            // into the previous pipe can ping-pong forever when its other exits are filtered.
            // Only explicit bounce-capable pipe behaviours (e.g. Iron) may reverse the item.
            if (tryBounce.canBounce && reachCenter.from != null && pipe.isConnected(reachCenter.from)) {
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
            if (findDest.items.size() == 1) routeItem.visualTransitId = item.visualTransitId;
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
                newItem.visualTransitId = routeItem.visualTransitId;
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
                        Long previousVisualHandoff = VISUAL_HANDOFF_ID.get();
                        VISUAL_HANDOFF_ID.set(item.visualTransitId);
                        try (buildcraft.transport.api2.PipeTransitHandoff handoff =
                            buildcraft.transport.api2.PipeTransitHandoff.begin(item.apiTransitId, item.apiTransitPayload)) {
                            excess = oFlow.injectItem(excess.copy(), true, oppositeSide, item.colour, item.speed);
                        } finally {
                            if (previousVisualHandoff == null) VISUAL_HANDOFF_ID.remove();
                            else VISUAL_HANDOFF_ID.set(previousVisualHandoff);
                        }

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
        int moved = Math.max(0, beforeTransferCount - excess.getCount());
        if (moved > 0 && pipe instanceof Pipe runtimePipe) {
            ItemStack transferred = item.stack.copy();
            transferred.setCount(moved);
            PipeItemTransitRuntime.fire(runtimePipe, ItemTransitPhase.TRANSFERRED,
                transferred, new ItemTransitData(Optional.ofNullable(item.colour), item.speed),
                item.side.getOpposite(), item.side, item.apiTransitId, item.apiTransitPayload);
            if (!excess.isEmpty()) {
                item.apiTransitId = UUID.randomUUID();
                // The accepted portion now owns the original visual cargo ID. The
                // rejected remainder is physically distinct and needs a fresh ID.
                item.visualTransitId = TravellingItem.allocateVisualTransitId();
            }
        }
        if (excess.isEmpty()) {
            return;
        }
        item.stack = excess;
        fireTransit(ItemTransitPhase.REJECTED, item, item.side);
        if (item.stack.isEmpty()) return;
        // A neighbouring pipe may reject this hand-off for only a single tick (for example,
        // when an addon cancels its first TryInsert). Retry once at this boundary rather
        // than immediately sending the item to the centre, where both its previous pipe
        // and the rejected output are excluded and it would be ejected into the world.
        // The tried-side set doubles as a persistent one-retry latch. A second rejection
        // follows the usual alternative-route/ejection path, so permanently blocked
        // connections cannot create an endless retry loop or reverse the item upstream.
        if (pipe.isConnected(item.side) && pipe.getConnectedType(item.side) == ConnectedType.PIPE
            && !item.tried.contains(item.side)) {
            item.tried.add(item.side);
            long now = holder.getPipeWorld().getGameTime();
            item.tickFinished = now + 1;
            item.timeToDest = (int) Math.min(TravellingItem.MAX_TRANSIT_TICKS,
                Math.max(1L, item.tickFinished - item.tickStarted));
            // Keep toCenter=false and do not emit a new client segment: the item waits
            // at the exit for one tick instead of visually rewinding into the pipe.
            scheduleTravellingItem(item);
            holder.getPipeTile().setChanged();
            return;
        }
        item.tried.add(item.side);
        item.toCenter = true;
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

        Level world = pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();

        TravellingItem item = new TravellingItem(stack);
        Long visualHandoff = VISUAL_HANDOFF_ID.get();
        if (visualHandoff != null && visualHandoff > 0L) {
            item.visualTransitId = visualHandoff;
            // A split injection may create several cargoes; only one inherits the ID.
            VISUAL_HANDOFF_ID.remove();
        }
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
        item.pausedAtCenter = true;
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

    @Override
    public boolean requiresPeriodicSave() {
        return doesContainItems();
    }

    public boolean doesContainItems() {
        // Client trigger/overlay queries must observe the packet-backed visual
        // items, even though the client no longer advances them in DelayedList.
        Level level = pipe.getHolder().getPipeWorld();
        if (level != null && level.isClientSide()) {
            return !clientRetained.isEmpty();
        }
        // Server gate and item-storage logic remains authoritative.
        return items.getMaxDelay() > 0;
    }

    public boolean containsItemMatching(ItemStack filter) {
        if (filter.isEmpty()) {
            return doesContainItems();
        }
        Level level = pipe.getHolder().getPipeWorld();
        if (level != null && level.isClientSide()) {
            for (TravellingItem item : clientRetained) {
                ItemStack cargo = item.clientItemLink.get();
                if (cargo != null && !cargo.isEmpty() && StackUtil.matchesStackOrList(filter, cargo)) {
                    return true;
                }
            }
            return false;
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
        Level level = pipe.getHolder().getPipeWorld();
        if (level != null && level.isClientSide()) {
            // The renderer selects the active segment using the world-shared
            // timeline; it does not depend on this tile's timing-wheel phase.
            return new ArrayList<>(clientRetained);
        }
        List<TravellingItem> all = new ArrayList<>();
        for (List<TravellingItem> innerList : items.getAllElements()) {
            all.addAll(innerList);
        }
        return all;
    }

}
