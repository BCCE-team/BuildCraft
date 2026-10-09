package buildcraft.robotics;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.robotics.internal.api2.RequestSupport;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.ResourceId;
import buildcraft.robotics.internal.legacy.robots.ResourceIdApiRequest;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Classic BuildCraft robotics item request descriptor used by Delivery robots. */
public class StackRequest {
    @Nullable
    private RequestProvider requester;
    private final ResourceLocation requestId;
    private final ItemStack stack;

    @Nullable
    private DockingStation station;
    @Nullable
    private BlockIndex stationIndex;
    @Nullable
    private Direction stationSide;

    public StackRequest(RequestProvider requester, ResourceLocation requestId, ItemStack stack) {
        this.requester = requester;
        this.requestId = requestId;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    private StackRequest(ResourceLocation requestId, ItemStack stack, BlockIndex stationIndex, @Nullable Direction stationSide) {
        this.requestId = requestId;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
        this.stationIndex = stationIndex;
        this.stationSide = stationSide;
    }

    @Nullable
    public RequestProvider getRequester(Level level) {
        if (requester == null) {
            DockingStation dockingStation = getStation(level);
            if (dockingStation != null) {
                requester = dockingStation.getRequestProvider();
            }
        }
        return requester;
    }

    public ResourceLocation getRequestId() {
        return requestId;
    }

    public ItemStack getStack() {
        return stack;
    }

    @Nullable
    public DockingStation getStation(Level level) {
        if (station == null && stationIndex != null && RobotManager.registryProvider != null && level != null) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level);
            station = registry.getStation(stationIndex.toBlockPos(), stationSide);
        }
        return station;
    }

    public void setStation(DockingStation station) {
        this.station = station;
        this.stationIndex = station.index();
        this.stationSide = station.side();
    }

    public void writeToNBT(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putString("requestId", requestId.toString());
        RequestSupport.slot(requestId).ifPresent(slot -> nbt.putInt("slot", slot));

        nbt.put("stack", ItemStackUtil.saveOptional(stack, registries));

        if (station != null) {
            CompoundTag stationIndexTag = new CompoundTag();
            station.index().writeTo(stationIndexTag);
            nbt.put("stationIndex", stationIndexTag);
            nbt.putByte("stationSide", (byte) (station.side() == null ? -1 : station.side().ordinal()));
        } else if (stationIndex != null) {
            CompoundTag stationIndexTag = new CompoundTag();
            stationIndex.writeTo(stationIndexTag);
            nbt.put("stationIndex", stationIndexTag);
            nbt.putByte("stationSide", (byte) (stationSide == null ? -1 : stationSide.ordinal()));
        }
    }

    @Nullable
    public static StackRequest loadFromNBT(CompoundTag nbt, HolderLookup.Provider registries) {
        if (!nbt.contains("stationIndex")) {
            return null;
        }

        ResourceLocation requestId = nbt.contains("requestId") ? ResourceLocation.tryParse(nbt.getString("requestId")) : null;
        if (requestId == null) requestId = RequestSupport.slotId(nbt.getInt("slot"));
        ItemStack stack = ItemStackUtil.parseOptional(registries, nbt.getCompound("stack"));
        BlockIndex stationIndex = new BlockIndex(nbt.getCompound("stationIndex"));
        int sideId = nbt.getByte("stationSide");
        Direction stationSide = sideId >= 0 && sideId < Direction.values().length ? Direction.values()[sideId] : null;
        return new StackRequest(requestId, stack, stationIndex, stationSide);
    }

    @Nullable
    public ResourceId getResourceId(Level level) {
        DockingStation requestStation = getStation(level);
        return requestStation != null ? new ResourceIdApiRequest(requestStation, requestId) : null;
    }
}
