/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.robotics.internal.legacy.robots;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.transport.internal.pipe.FluidAction;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import buildcraft.builders.tile.IRobotBuildActor;
import com.mojang.authlib.GameProfile;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.mj.MjBattery;

public abstract class EntityRobotBase extends LivingEntity implements Container, IRobotBuildActor {
    public static final int MAX_ENERGY = 100_000;
    public static final int SAFETY_ENERGY = MAX_ENERGY / 5;
    public static final int SHUTDOWN_ENERGY = 0;
    public static final long NULL_ROBOT_ID = Long.MAX_VALUE;

    protected EntityRobotBase(EntityType<? extends EntityRobotBase> entityType, Level level) {
        super(entityType, level);
    }

    public abstract void setItemInUse(ItemStack stack);

    public abstract void setItemActive(boolean active);

    public abstract boolean isMoving();

    public abstract DockingStation getLinkedStation();

    /** Override the robot's current AI from a gate statement (e.g., go to station, wake up). */
    public abstract void setMainAIOverride(AIRobot ai);

    /** Internal bridge used by the API2 RobotHandle view. */
    public abstract AIRobot getActiveAI();

    public abstract RedstoneBoardRobot getBoard();

    public abstract void aimItemAt(float yaw, float pitch);

    public abstract void aimItemAt(int x, int y, int z);

    public void aimItemAt(BlockPos pos) {
        aimItemAt(pos.getX(), pos.getY(), pos.getZ());
    }

    public abstract float getAimYaw();

    public abstract float getAimPitch();

    public int getEnergy() {
        return (int) Math.min(Integer.MAX_VALUE, getBattery().getStored());
    }

    public abstract MjBattery getBattery();

    /** Fluid transport contract independent of removed NeoForge fluid-handler interfaces. */
    public abstract int getTanks();
    public abstract FluidStack getFluidInTank(int index);
    public abstract int getTankCapacity(int index);
    public abstract boolean isFluidValid(int index, FluidStack stack);
    public abstract int fill(FluidStack resource, FluidAction action);
    public abstract FluidStack drain(FluidStack resource, FluidAction action);
    public abstract FluidStack drain(int maxDrain, FluidAction action);
    public abstract Storage<FluidVariant> resourceHandler();

    /** Share the same persisted robot reservation registry as the Builders module. */
    private final IRobotBuildReservations buildReservations = new IRobotBuildReservations() {
        public boolean isTaken(BlockPos pos) {
            IRobotRegistry registry = getRegistry();
            return registry != null && registry.isTaken(new ResourceIdBlock(pos));
        }
        public boolean take(BlockPos pos, IRobotBuildActor actor) {
            IRobotRegistry registry = getRegistry();
            return registry != null && actor == EntityRobotBase.this
                && registry.take(new ResourceIdBlock(pos), EntityRobotBase.this);
        }
        public void release(BlockPos pos) {
            IRobotRegistry registry = getRegistry();
            if (registry != null && registry.robotIdTaking(new ResourceIdBlock(pos)) == getRobotId()) {
                registry.release(new ResourceIdBlock(pos));
            }
        }
    };

    @Override public IRobotBuildReservations getBuildReservations() { return buildReservations; }
    @Override public abstract GameProfile getOwnerProfile();

    public abstract DockingStation getDockingStation();

    public abstract void dock(DockingStation station);

    public abstract void undock();

    public abstract IZone getZoneToWork();

    public abstract IZone getZoneToLoadUnload();

    public abstract boolean containsItems();

    public abstract boolean hasFreeSlot();

    public abstract void unreachableEntityDetected(Entity entity);

    public abstract boolean isKnownUnreachable(Entity entity);

    public abstract long getRobotId();

    public abstract IRobotRegistry getRegistry();

    public abstract void releaseResources();

    public abstract void onChunkUnload();

    /**
     * Receive an item from a nearby tile and return the remaining stack. Return {@link ItemStack#EMPTY} when fully
     * accepted.
     */
    public abstract ItemStack receiveItem(BlockEntity blockEntity, ItemStack stack);

    public abstract void setMainStation(DockingStation station);
}
