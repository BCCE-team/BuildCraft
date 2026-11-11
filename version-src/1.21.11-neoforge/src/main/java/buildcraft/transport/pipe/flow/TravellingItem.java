/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import java.util.EnumSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
//? if >=1.21.11 {
import buildcraft.lib.compat.NbtCompat;
//? }
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.transport.internal.pipe.IPipe.ConnectedType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import buildcraft.api.v2.pipe.ItemTransitPayload;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class TravellingItem {
    // Distinct server cargo IDs survive across fast pipe-to-pipe handoffs.
    private static final AtomicLong NEXT_VISUAL_TRANSIT_ID = new AtomicLong(
        ThreadLocalRandom.current().nextLong(1L, Long.MAX_VALUE >>> 1));

    public static long allocateVisualTransitId() {
        return NEXT_VISUAL_TRANSIT_ID.getAndIncrement();
    }

    /** Shared by the two 1.21.X clients, not the type cache's stackId. */
    public long visualTransitId;
    // Client fields - public for rendering
    @Nonnull
    public final Supplier<ItemStack> clientItemLink;
    public int stackSize;
    public DyeColor colour;

    // Server fields
    /** The server itemstack */
    @Nonnull
    ItemStack stack;
    int id = 0;
    boolean toCenter;
    /** The item is waiting at pipe center, not moving there a second time. */
    boolean pausedAtCenter;
    /** Stable owner token for a transfer until the item is consumed. Null for ordinary untracked items. */
    UUID apiTransitId;
    ItemTransitPayload apiTransitPayload = ItemTransitPayload.EMPTY;
    double speed = 0.05;
    /** Absolute times (relative to world.getTotalWorldTime()) with when an item started to when it finishes. */
    long tickStarted, tickFinished;
    /** Relative times (from tickStarted) until an event needs to be fired or this item needs changing. */
    /** Upper bound for in-pipe travel; even the minimum legitimate speed needs under 1000 ticks. */
    public static final int MAX_TRANSIT_TICKS = 1200;
    /** Render-only prediction budget while the next segment packet is in flight. */
    private static final double MAX_CLIENT_PREDICTION_TICKS = 1.0;
    int timeToDest;
    /** If {@link #toCenter} is true then this represents the side that the item is coming from, otherwise this
     * represents the side that the item is going to. */
    Direction side;
    /** A set of all the faces that this item has tried to go and failed. */
    EnumSet<Direction> tried = EnumSet.noneOf(Direction.class);
    /** If true then events won't be fired for this, and this item won't be dropped by the pipe. However it will affect
     * pipe.isEmpty and related gate triggers. */
    boolean isPhantom = false;
    /** Client-only fallback used while the authoritative pipe-to-pipe successor packet is in flight. */
    boolean clientBoundaryPrediction = false;
    /** Client-only fallback used while the authoritative in-pipe center successor packet is in flight. */
    boolean clientCenterPrediction = false;
    /** Predicted outgoing side for a center hand-off. Null means hold at the center when the route is ambiguous. */
    Direction clientCenterPredictionSide = null;
    /** Cached network stack id used to match adjacent client transport segments. */
    int clientStackId = -1;
    /** True after an authoritative successor segment has been linked to this client segment. */
    boolean clientSuccessorLinked = false;

    // @formatter:off
    /* States (server side):
      
      - TO_CENTER:
        - tickStarted is the tick that the item entered the pipe (or bounced back)
        - tickFinished is the tick that the item will reach the center 
        - side is the side that the item came from
        - timeToDest is equal to timeFinished - timeStarted
      
      - TO_EXIT:
       - tickStarted is the tick that the item reached the center
       - tickFinished is the tick that the item will reach the end of a pipe 
       - side is the side that the item is going to 
       - timeToDest is equal to timeFinished - timeStarted. 
     */
    // @formatter:on

    public TravellingItem(@Nonnull ItemStack stack) {
        this.stack = stack;
        this.visualTransitId = allocateVisualTransitId();
        this.clientItemLink = () -> this.stack;
    }

    public TravellingItem(Supplier<ItemStack> clientStackLink, int count) {
        this.clientItemLink = StackUtil.asNonNull(clientStackLink);
        this.stackSize = count;
        this.stack = StackUtil.EMPTY;
    }

    public TravellingItem(CompoundTag nbt, long tickNow, HolderLookup.Provider registries) {
        clientItemLink = () -> this.stack;
        //? if >=1.21.11 {
        long savedVisualId = NbtCompat.getLong(nbt, "visualTransitId");
        //? } else {
        /*?
        long savedVisualId = nbt.getLong("visualTransitId");
        ?*/
        //? }
        visualTransitId = savedVisualId > 0 && savedVisualId < Long.MAX_VALUE
            ? savedVisualId : allocateVisualTransitId();
        stack = ItemStackUtil.parseOptional(registries, nbt.getCompound("stack"));
        int c = nbt.getByte("colour");
        this.colour = c == 0 ? null : DyeColor.byId(c - 1);
        this.toCenter = nbt.getBoolean("toCenter");
        this.pausedAtCenter = nbt.getBoolean("pausedAtCenter");
        this.speed = nbt.getDouble("speed");
        if (!Double.isFinite(speed) || speed < 0.001) {
            // Just to make sure that we don't have an invalid speed
            speed = 0.001;
        }
        // The saved times are relative to the old world clock. Bound both the
        // remaining ETA and total travel duration before allocating delay buckets.
        timeToDest = Math.max(1, Math.min(MAX_TRANSIT_TICKS, nbt.getInt("timeToDest")));
        long remaining = Math.max(0L, Math.min(MAX_TRANSIT_TICKS, (long) nbt.getInt("tickFinished")));
        tickFinished = tickNow + remaining;
        tickStarted = tickFinished - timeToDest;

        side = NBTUtilBC.readEnum(nbt.get("side"), Direction.class);
        if (side == null || timeToDest == 0) {
            // Saves without a valid side/destination resume by routing toward the pipe centre.
            toCenter = true;
        }
        tried = NBTUtilBC.readEnumSet(nbt.get("tried"), Direction.class);
        isPhantom = nbt.getBoolean("isPhantom");
        if (nbt.contains("apiTransitPayload")) {
            // Unknown addon entries survive a save/load cycle without requiring an installed decoder.
            //? if >=1.21.11 {
            CompoundTag entries = NbtCompat.getCompound(nbt, "apiTransitPayload");
            //? } else {
            /*?
            CompoundTag entries = nbt.getCompound("apiTransitPayload");
            ?*/
            //? }
            java.util.Map<String, byte[]> data = new java.util.LinkedHashMap<>();
            for (String key : entries.getAllKeys()) {
                //? if >=1.21.11 {
                byte[] bytes = NbtCompat.getByteArray(entries, key);
                //? } else {
                /*?
                byte[] bytes = entries.getByteArray(key);
                ?*/
                //? }
                data.put(key, bytes);
            }
            try { apiTransitPayload = new ItemTransitPayload(data); }
            catch (IllegalArgumentException ignored) { apiTransitPayload = ItemTransitPayload.EMPTY; }
        }
        if (nbt.contains("apiTransitId")) {
            try {
                //? if >=1.21.11 {
                apiTransitId = UUID.fromString(NbtCompat.getString(nbt, "apiTransitId"));
                //? } else {
                /*?
                apiTransitId = UUID.fromString(nbt.getString("apiTransitId"));
                ?*/
                //? }
            }
            catch (IllegalArgumentException ignored) { apiTransitId = null; }
        }
    }

    public CompoundTag writeToNbt(long tickNow, HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        nbt.put("stack", ItemStackUtil.saveOptional(stack, registries));
        nbt.putByte("colour", (byte) (colour == null ? 0 : colour.getId() + 1));
        nbt.putBoolean("toCenter", toCenter);
        nbt.putBoolean("pausedAtCenter", pausedAtCenter);
        nbt.putDouble("speed", speed);
        nbt.putInt("tickStarted", (int) (tickStarted - tickNow));
        nbt.putInt("tickFinished", (int) (tickFinished - tickNow));
        nbt.putInt("timeToDest", timeToDest);
        nbt.putLong("visualTransitId", visualTransitId);
        nbt.put("side", NBTUtilBC.writeEnum(side));
        nbt.put("tried", NBTUtilBC.writeEnumSet(tried, Direction.class));
        if (isPhantom) {
            nbt.putBoolean("isPhantom", true);
        }
        if (!apiTransitPayload.entries().isEmpty()) {
            CompoundTag entries = new CompoundTag();
            apiTransitPayload.entries().forEach(entries::putByteArray);
            nbt.put("apiTransitPayload", entries);
        }
        if (apiTransitId != null) nbt.putString("apiTransitId", apiTransitId.toString());
        return nbt;
    }

    public int getCurrentDelay(long tickNow) {
        long diff = tickFinished - tickNow;
        if (diff < 0) {
            return 0;
        } else {
            return (int) diff;
        }
    }

    public double getWayThrough(long now) {
        long diff = tickFinished - tickStarted;
        long nowDiff = now - tickStarted;
        return nowDiff / (double) diff;
    }

    public void genTimings(long now, double distance) {
        pausedAtCenter = false;
        tickStarted = now;
        timeToDest = Math.max(1, Math.min(MAX_TRANSIT_TICKS, (int) Math.ceil(distance / speed)));
        tickFinished = now + timeToDest;
    }

    public boolean canMerge(TravellingItem with) {
        if (isPhantom || with.isPhantom || apiTransitId != null || with.apiTransitId != null
            || !apiTransitPayload.equals(ItemTransitPayload.EMPTY)
            || !with.apiTransitPayload.equals(ItemTransitPayload.EMPTY)) {
            return false;
        }
        return toCenter == with.toCenter//
            && colour == with.colour//
            && side == with.side//
            && Math.abs(tickFinished - with.tickFinished) < 4//
            && stack.getMaxStackSize() >= stack.getCount() + with.stack.getCount()//
            && StackUtil.canMerge(stack, with.stack);
    }

    /** Attempts to merge the two travelling item's together, if they are close enough.
     * 
     * @param with
     * @return */
    public boolean mergeWith(TravellingItem with) {
        if (canMerge(with)) {
            this.stack.grow(with.stack.getCount());
            return true;
        }
        return false;
    }

    public Vec3 interpolatePosition(Vec3 start, Vec3 end, long tick, float partialTicks) {
        long diff = tickFinished - tickStarted;
        long nowDiff = tick - tickStarted;
        double sinceStart = nowDiff + partialTicks;
        double interpMul = sinceStart / diff;
        double oneMinus = 1 - interpMul;
        if (interpMul <= 0) return start;
        if (interpMul >= 1) return end;

        double x = oneMinus * start.x + interpMul * end.x;
        double y = oneMinus * start.y + interpMul * end.y;
        double z = oneMinus * start.z + interpMul * end.z;
        return new Vec3(x, y, z);
    }

    /**
     * Frame-interpolated client motion, including a short prediction while the
     * following authoritative packet is in flight. 26.3 NeoForge already uses
     * this approach for fast pipes: clamping *every* completed segment to its
     * endpoint (as the earlier BCU-style port did) creates a visible micro-stop
     * at every two-tick gold-pipe handoff when the next packet arrives late.
     *
     * Prediction is render-only, bounded to one connected half-pipe, and never
     * changes the server route, item ownership, speed or NBT.
     */
    public Vec3 getRenderPosition(BlockPos pos, long tick, float partialTicks, PipeFlowItems flow) {
        if (pausedAtCenter) return Vec3.ZERO;
        long duration = Math.max(1L, tickFinished - tickStarted);
        double renderTime = tick + Math.max(0.0f, Math.min(1.0f, partialTicks));
        double fraction = Math.max(0.0, Math.min(1.0,
            (renderTime - tickStarted) / duration));
        Vec3 center = Vec3.ZERO;
        Vec3 sidePosition = side == null ? center : VecUtil.offset(center, side, flow.getPipeLength(side));
        Vec3 from = toCenter ? sidePosition : center;
        Vec3 to = toCenter ? center : sidePosition;
        Vec3 position = VecUtil.scale(from, 1.0 - fraction).add(VecUtil.scale(to, fraction));

        // Nothing to extrapolate while the authoritative segment is in motion.
        // Legacy addon packets have no persistent cargo ID; do not guess their
        // routes. Phantom/paused segments must remain at their declared position.
        if (renderTime <= tickFinished || visualTransitId <= 0L || isPhantom || side == null) {
            return position;
        }
        // The next packet is backfilled by at most one tick. Predicting four
        // ticks while its predecessor is still visible placed the model at the
        // neighbour's centre, but the late successor then resumed at its entry
        // boundary: a large visible backward snap. Advance only one tick and
        // hold there until the real route/time arrives. The packet, not the
        // prediction, still determines the final direction and travel speed.
        double elapsed = Math.min(MAX_CLIENT_PREDICTION_TICKS, renderTime - tickFinished);
        double perTick = flow.getPipeLength(side) / duration;
        double extra = perTick * elapsed;

        if (toCenter) {
            // At a straight pipe's centre the outgoing direction is unique.
            // A junction/filter has an authoritative routing decision that we
            // cannot know client-side, so stop at its centre instead of inventing
            // a branch. This is especially useful on a line of gold pipes.
            Direction forward = null;
            for (Direction candidate : Direction.values()) {
                if (candidate == side || !flow.pipe.isConnected(candidate)) continue;
                if (forward != null) return position; // ambiguous junction
                forward = candidate;
            }
            if (forward != null) {
                double distance = Math.min(flow.getPipeLength(forward), extra);
                return VecUtil.offset(center, forward, distance);
            }
        } else if (flow.pipe.isConnected(side)
            && flow.pipe.getConnectedType(side) == ConnectedType.PIPE) {
            // Continue through the shared boundary up to the neighbouring pipe
            // centre, just like 26.3's clientBoundaryPrediction. The new packet
            // takes over by cargo ID without duplicating the predecessor.
            double distance = Math.min(flow.getPipeLength(side), extra);
            return VecUtil.offset(sidePosition, side, distance);
        }
        return position;
    }

    /** Retained for source/binary compatibility; BCU's renderer does not gate items by time. */
    public boolean shouldRender(long tick, float partialTicks) {
        return true;
    }

    /** Direct-ItemStack fallback for the legacy renderer and external render hooks. */
    public ItemStack getStack() {
        return stack;
    }

    public Direction getRenderDirection(long tick, float partialTicks) {
        long diff = tickFinished - tickStarted;
        long afterTick = tick - tickStarted;

        float interp = (afterTick + partialTicks) / diff;
        interp = Math.max(0, Math.min(1, interp));
        if (toCenter) {
            return side == null ? null : side.getOpposite();
        } else {
            return side;
        }
    }

    public boolean isVisible() {
        return true;
    }
}
