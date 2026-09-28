/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.flow;

import java.util.EnumSet;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.VecUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class TravellingItem {
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
    double speed = 0.05;
    /** Absolute times (relative to world.getTotalWorldTime()) with when an item started to when it finishes. */
    long tickStarted, tickFinished;
    /** Relative times (from tickStarted) until an event needs to be fired or this item needs changing. */
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
        clientItemLink = () ->{
        	BCLog.d("empty");
        	return ItemStack.EMPTY;
        };
    }

    public TravellingItem(Supplier<ItemStack> clientStackLink, int count) {
        this.clientItemLink = StackUtil.asNonNull(clientStackLink);
        this.stackSize = count;
        this.stack = StackUtil.EMPTY;
    }

    public TravellingItem(CompoundTag nbt, long tickNow, HolderLookup.Provider registries) {
        clientItemLink = () ->{
        	BCLog.d("empty");
        	return ItemStack.EMPTY;
        };
        stack = ItemStackUtil.parseOptional(registries, nbt.getCompound("stack"));
        int c = nbt.getByte("colour");
        this.colour = c == 0 ? null : DyeColor.byId(c - 1);
        this.toCenter = nbt.getBoolean("toCenter");
        this.speed = nbt.getDouble("speed");
        if (speed < 0.001) {
            // Just to make sure that we don't have an invalid speed
            speed = 0.001;
        }
        tickStarted = nbt.getInt("tickStarted") + tickNow;
        tickFinished = nbt.getInt("tickFinished") + tickNow;
        timeToDest = nbt.getInt("timeToDest");

        side = NBTUtilBC.readEnum(nbt.get("side"), Direction.class);
        if (side == null || timeToDest == 0) {
            // Saves without a valid side/destination resume by routing toward the pipe centre.
            toCenter = true;
        }
        tried = NBTUtilBC.readEnumSet(nbt.get("tried"), Direction.class);
        isPhantom = nbt.getBoolean("isPhantom");
    }

    public CompoundTag writeToNbt(long tickNow, HolderLookup.Provider registries) {
        CompoundTag nbt = new CompoundTag();
        nbt.put("stack", ItemStackUtil.saveOptional(stack, registries));
        nbt.putByte("colour", (byte) (colour == null ? 0 : colour.getId() + 1));
        nbt.putBoolean("toCenter", toCenter);
        nbt.putDouble("speed", speed);
        nbt.putInt("tickStarted", (int) (tickStarted - tickNow));
        nbt.putInt("tickFinished", (int) (tickFinished - tickNow));
        nbt.putInt("timeToDest", timeToDest);
        nbt.put("side", NBTUtilBC.writeEnum(side));
        nbt.put("tried", NBTUtilBC.writeEnumSet(tried, Direction.class));
        if (isPhantom) {
            nbt.putBoolean("isPhantom", true);
        }
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
        tickStarted = now;
        timeToDest = (int) Math.ceil(distance / speed);
        tickFinished = now + timeToDest;
    }

    public boolean canMerge(TravellingItem with) {
        if (isPhantom || with.isPhantom) {
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

    public Vec3 getRenderPosition(BlockPos pos, long tick, float partialTicks, PipeFlowItems flow) {
        long diff = tickFinished - tickStarted;
        long afterTick = tick - tickStarted;

        float interp = (afterTick + partialTicks) / diff;

        Vec3 center = Vec3.ZERO;//Vec3.atCenterOf(pos);
        Vec3 vecSide = side == null ? center : VecUtil.offset(center, side, flow.getPipeLength(side));

        if (clientCenterPrediction) {
            if (clientCenterPredictionSide == null || side == null) {
                return center;
            }
            // Continue from the center for as long as the short client prediction is retained. This is based on
            // elapsed whole ticks as well as partialTicks, so a delayed successor packet does not make the item
            // disappear every other pipe. Junctions still hold at the center because guessing a branch is unsafe.
            double incomingDistance = flow.getPipeLength(side);
            double distancePerTick = incomingDistance / Math.max(1L, diff);
            double elapsed = Math.max(0.0, tick + partialTicks - tickFinished);
            double predictedDistance = Math.min(flow.getPipeLength(clientCenterPredictionSide),
                distancePerTick * elapsed);
            return VecUtil.offset(center, clientCenterPredictionSide, predictedDistance);
        }

        // Normal segments stop exactly at their destination. A boundary prediction may continue through the shared
        // face as far as the neighbouring pipe centre while the real successor packet is still in flight.
        float maxInterp = clientBoundaryPrediction ? 2.0f : 1.0f;
        interp = Math.max(0, Math.min(maxInterp, interp));

        Vec3 vecFrom;
        Vec3 vecTo;
        if (toCenter) {
            vecFrom = vecSide;
            vecTo = center;
        } else {
            vecFrom = center;
            vecTo = vecSide;
        }
        return VecUtil.scale(vecFrom, 1 - interp).add(VecUtil.scale(vecTo, interp));
    }

    public boolean shouldRender(long tick, float partialTicks) {
        double renderTime = tick + partialTicks;
        // Successor packets can arrive before the predecessor segment has reached its hand-off point. Keep the
        // successor scheduled, but do not render it pinned at its start until its linked start time is reached.
        if (renderTime < tickStarted) {
            return false;
        }
        // Once an authoritative successor is already queued, the predecessor must stop rendering exactly at the
        // hand-off. DelayedList may retain it until the next client tick; drawing both segments during that remainder
        // produced a boundary double-image that is especially visible as a forward/back snap at high speed.
        if (clientSuccessorLinked && !clientBoundaryPrediction && !clientCenterPrediction
            && renderTime >= tickFinished) {
            return false;
        }
        return true;
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
