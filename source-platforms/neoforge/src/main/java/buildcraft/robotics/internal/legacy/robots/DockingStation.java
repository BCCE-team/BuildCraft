/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public License 1.0, or MMPL.
 */
package buildcraft.robotics.internal.legacy.robots;

import javax.annotation.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import buildcraft.lib.platform.storage.FluidStorage;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.transport.internal.IInjectable;

public abstract class DockingStation {
    @Nullable
    public Direction side;
    @Nullable
    public Level level;
    /** Compatibility alias for callers using the legacy field name. Prefer {@link #level}. */
    @Deprecated
    @Nullable
    public Level world;

    private long robotTakingId = EntityRobotBase.NULL_ROBOT_ID;
    private EntityRobotBase robotTaking;
    private boolean linkIsMain = false;
    /** Robot id explicitly detached by a player; prevents an unloaded robot from reclaiming the station on load. */
    private long manuallyReleasedRobotId = EntityRobotBase.NULL_ROBOT_ID;
    private BlockIndex index;

    public DockingStation(BlockIndex index, @Nullable Direction side) {
        this.index = index;
        this.side = side;
    }

    public DockingStation() {
        this.index = new BlockIndex();
    }

    @Nullable
    public Level level() {
        return level != null ? level : world;
    }

    public void setLevel(@Nullable Level level) {
        this.level = level;
        this.world = level;
    }

    public boolean isMainStation() {
        return linkIsMain;
    }

    public int x() {
        return index.x;
    }

    public int y() {
        return index.y;
    }

    public int z() {
        return index.z;
    }

    @Nullable
    public Direction side() {
        return side;
    }

    @Nullable
    public EntityRobotBase robotTaking() {
        if (robotTakingId == EntityRobotBase.NULL_ROBOT_ID) {
            return null;
        } else if (robotTaking == null && RobotManager.registryProvider != null && level() != null) {
            robotTaking = RobotManager.registryProvider.getRegistry(level()).getLoadedRobot(robotTakingId);
        }

        return robotTaking;
    }

    public void invalidateRobotTakingEntity() {
        robotTaking = null;
    }

    public long linkedId() {
        return robotTakingId;
    }

    public boolean takeAsMain(EntityRobotBase robot) {
        if (robot == null || RobotManager.registryProvider == null) {
            return false;
        }

        if (robotTakingId == EntityRobotBase.NULL_ROBOT_ID || robotTakingId == robot.getRobotId()) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(robot.level());
            linkIsMain = true;
            manuallyReleasedRobotId = EntityRobotBase.NULL_ROBOT_ID;
            robotTaking = robot;
            robotTakingId = robot.getRobotId();
            registry.registryMarkDirty();
            robot.setMainStation(this);
            registry.take(this, robot.getRobotId());
            setLevel(robot.level());

            return true;
        }
        return false;
    }

    public boolean take(EntityRobotBase robot) {
        if (robot == null || RobotManager.registryProvider == null) {
            return false;
        }

        if (robotTakingId == EntityRobotBase.NULL_ROBOT_ID) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(robot.level());
            linkIsMain = false;
            robotTaking = robot;
            robotTakingId = robot.getRobotId();
            registry.registryMarkDirty();
            registry.take(this, robot.getRobotId());
            setLevel(robot.level());

            return true;
        } else if (robotTakingId == robot.getRobotId()) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(robot.level());
            robotTaking = robot;
            registry.registryMarkDirty();
            registry.take(this, robot.getRobotId());
            setLevel(robot.level());

            return true;
        }
        return false;
    }

    public void release(EntityRobotBase robot) {
        if (robotTaking == robot && !linkIsMain) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(robot.level());
            unsafeRelease(robot);
            registry.registryMarkDirty();
            registry.release(this, robot.getRobotId());
        }
    }

    /**
     * Unconditionally frees this station, including a main/home link. Intended for explicit player actions such as
     * shift-wrenching a robot station.
     *
     * @return the id of the robot that owned the station, or {@link EntityRobotBase#NULL_ROBOT_ID} when it was free
     */
    public long forceRelease() {
        long releasedRobotId = robotTakingId;
        manuallyReleasedRobotId = releasedRobotId;
        linkIsMain = false;
        robotTaking = null;
        robotTakingId = EntityRobotBase.NULL_ROBOT_ID;

        if (releasedRobotId != EntityRobotBase.NULL_ROBOT_ID
                && RobotManager.registryProvider != null
                && level() != null) {
            IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level());
            registry.release(this, releasedRobotId);
            registry.registryMarkDirty();
        }
        return releasedRobotId;
    }

    public boolean wasManuallyReleased(long robotId) {
        return robotId != EntityRobotBase.NULL_ROBOT_ID && manuallyReleasedRobotId == robotId;
    }

    /** Same as release, but does not update the registry. Intended to be called by the registry itself. */
    public void unsafeRelease(EntityRobotBase robot) {
        if (robotTaking == robot || robotTakingId == robot.getRobotId()) {
            linkIsMain = false;
            robotTaking = null;
            robotTakingId = EntityRobotBase.NULL_ROBOT_ID;
        }
    }

    public void writeToNBT(CompoundTag nbt) {
        CompoundTag indexTag = new CompoundTag();
        index.writeTo(indexTag);
        nbt.put("index", indexTag);
        nbt.putByte("side", (byte) (side == null ? -1 : side.ordinal()));
        nbt.putBoolean("isMain", linkIsMain);
        nbt.putLong("robotId", robotTakingId);
        if (manuallyReleasedRobotId != EntityRobotBase.NULL_ROBOT_ID) {
            nbt.putLong("manuallyReleasedRobotId", manuallyReleasedRobotId);
        }
    }

    public void readFromNBT(CompoundTag nbt) {
        index = new BlockIndex(nbt.getCompound("index"));
        byte sideId = nbt.getByte("side");
        side = sideId >= 0 && sideId < Direction.values().length ? Direction.values()[sideId] : null;
        linkIsMain = nbt.getBoolean("isMain");
        robotTakingId = nbt.getLong("robotId");
        manuallyReleasedRobotId = nbt.contains("manuallyReleasedRobotId")
                ? nbt.getLong("manuallyReleasedRobotId")
                : EntityRobotBase.NULL_ROBOT_ID;
    }

    public boolean isTaken() {
        return robotTakingId != EntityRobotBase.NULL_ROBOT_ID;
    }

    public long robotIdTaking() {
        return robotTakingId;
    }

    public BlockIndex index() {
        return index;
    }

    @Override
    public String toString() {
        return "{" + index.x + ", " + index.y + ", " + index.z + ", " + side + " :" + robotTakingId + "}";
    }

    public boolean linkIsDocked() {
        EntityRobotBase robot = robotTaking();
        return robot != null && robot.getDockingStation() == this;
    }

    public boolean canRelease() {
        return !isMainStation() && !linkIsDocked();
    }

    public boolean isInitialized() {
        return true;
    }

    public abstract Iterable<StatementSlot> getActiveActions();

    @Nullable
    public IInjectable getItemOutput() {
        return null;
    }

    @Nullable
    public Direction getItemOutputSide() {
        return null;
    }

    @Nullable
    public Container getItemInput() {
        return null;
    }

    @Nullable
    public Direction getItemInputSide() {
        return null;
    }

    @Nullable
    public FluidStorage<FluidStack> getFluidOutput() {
        return null;
    }

    @Nullable
    public Direction getFluidOutputSide() {
        return null;
    }

    @Nullable
    public FluidStorage<FluidStack> getFluidInput() {
        return null;
    }

    @Nullable
    public Direction getFluidInputSide() {
        return null;
    }

    public boolean providesPower() {
        return false;
    }

    @Nullable
    public RequestProvider getRequestProvider() {
        return null;
    }

    public void onChunkUnload() {
    }
}
