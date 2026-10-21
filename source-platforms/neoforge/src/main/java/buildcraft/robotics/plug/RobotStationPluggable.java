package buildcraft.robotics.plug;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjCapabilities;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjReadable;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IDockingStationProvider;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.transport.internal.pluggable.PluggableModelKey;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.robotics.DockingStationPipe;
import buildcraft.robotics.client.model.key.KeyRobotStation;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.item.ItemRobot;
import buildcraft.transport.tile.TilePipeHolder;
//? if mc_26_x && (>=26.3) {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//? } else {
import net.minecraft.client.renderer.RenderType;
//?}
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.BlockCapability;
//? if mc_26_x && (>=26.3) {
import buildcraft.transport.internal.pipe.FluidAction;
//? } else {
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
//?}
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;
//? if mc_26_x && (>=26.3) {
import buildcraft.lib.compat.RenderCompat;
//? } else {
//?}

public class RobotStationPluggable extends PipePluggable implements IDockingStationProvider, IMjReceiver, IMjReadable {
    /** Robot charging is limited to 20 MJ per tick; the robot may accept less based on free battery capacity. */
    private static final long MAX_CHARGE_PER_TICK = 20 * MjAmount.MICRO_MJ_PER_MJ;

    public enum RobotStationState {
        None,
        Available,
        Reserved,
        Linked
    }

    private static final VoxelShape[] BOXES = new VoxelShape[6];

    static {
        double min = 4.0D;
        double max = 12.0D;
        double low = 2.0D;
        double high = 4.016D;
        BOXES[Direction.DOWN.get3DDataValue()] = Block.box(min, low, min, max, high, max);
        BOXES[Direction.UP.get3DDataValue()] = Block.box(min, 16.0D - high, min, max, 16.0D - low, max);
        BOXES[Direction.NORTH.get3DDataValue()] = Block.box(min, min, low, max, max, high);
        BOXES[Direction.SOUTH.get3DDataValue()] = Block.box(min, min, 16.0D - high, max, max, 16.0D - low);
        BOXES[Direction.WEST.get3DDataValue()] = Block.box(low, min, min, high, max, max);
        BOXES[Direction.EAST.get3DDataValue()] = Block.box(16.0D - high, min, min, 16.0D - low, max, max);
    }

    private final MjCapabilityHelper mjCaps = new MjCapabilityHelper(this);
    private DockingStationPipe station;
    private RobotStationState renderState = RobotStationState.None;
    private boolean valid;

    public RobotStationPluggable(PluggableDefinition definition, IPipeHolder holder, Direction side) {
        super(definition, holder, side);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public DockingStation getStation() {
        validateStation();
        return station;
    }

    @Nullable
    private EntityRobotBase getDockedRobot() {
        validateStation();
        EntityRobotBase robot = station == null ? null : station.robotTaking();
        return robot != null && robot.getDockingStation() == station ? robot : null;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public <T> @Nullable T getCapability(@Nonnull BlockCapability<T, Direction> cap) {
        if (cap == MjCapabilities.CAP_CONNECTOR || cap == MjCapabilities.CAP_RECEIVER || cap == MjCapabilities.CAP_READABLE) {
            validateStation();
            return mjCaps.getCapability(cap, side);
        }
        return null;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public <T> @Nullable T getInternalCapability(@Nonnull BlockCapability<T, Direction> cap) {
        if (cap == MjCapabilities.CAP_CONNECTOR || cap == MjCapabilities.CAP_RECEIVER || cap == MjCapabilities.CAP_READABLE) {
            validateStation();
            return mjCaps.getCapability(cap, side);
        }
        return null;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long getPowerRequested() {
        EntityRobotBase robot = getDockedRobot();
        if (robot == null || robot.getBattery() == null) {
            return 0;
        }
        if (robot instanceof EntityRobot entityRobot) {
            return Math.min(entityRobot.getMjPowerRequestedForCharging(), MAX_CHARGE_PER_TICK);
        }
        return Math.min(Math.max(0L, robot.getBattery().getCapacity() - robot.getBattery().getStored()), MAX_CHARGE_PER_TICK);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long receivePower(long microJoules, FluidAction action) {
        EntityRobotBase robot = getDockedRobot();
        if (microJoules <= 0 || robot == null || robot.getBattery() == null) {
            return microJoules;
        }
        long accepted = Math.min(microJoules, getPowerRequested());
        if (accepted > 0 && action.execute()) {
            if (robot instanceof EntityRobot entityRobot) {
                entityRobot.receivePower(accepted, FluidAction.EXECUTE);
            } else {
//? if mc_26_x && (>=26.3) {
                robot.getBattery().insert(MjAmount.ofMicro(accepted), buildcraft.api.v2.OperationMode.EXECUTE);
//? } else {
                robot.getBattery().addPower(accepted, FluidAction.EXECUTE);
//?}
            }
        }
        return microJoules - accepted;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean canReceive() {
        return getPowerRequested() > 0;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long getStored() {
        EntityRobotBase robot = getDockedRobot();
        return robot == null || robot.getBattery() == null ? 0 : EntityRobot.robotEnergyToMicroMj(robot.getBattery().getStored());
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public long getCapacity() {
        EntityRobotBase robot = getDockedRobot();
        long capacity = robot == null || robot.getBattery() == null ? EntityRobotBase.MAX_ENERGY : robot.getBattery().getCapacity();
        return EntityRobot.robotEnergyToMicroMj(capacity);
    }

    private void validateStation() {
//? if mc_26_x && (>=26.3) {
        if (valid || holder == null || holder.getPipeWorld() == null || holder.getPipeWorld().isClientSide()) {
//? } else {
        if (valid || holder == null || holder.getPipeWorld() == null || holder.getPipeWorld().isClientSide) {
//?}
            return;
        }
        if (!(holder instanceof TilePipeHolder tile)) {
            return;
        }
        if (RobotManager.registryProvider == null) {
            return;
        }
        DockingStation existing = RobotManager.registryProvider.getRegistry(holder.getPipeWorld()).getStation(
                holder.getPipePos(), side);
        if (existing instanceof DockingStationPipe pipeStation) {
            station = pipeStation;
        } else {
            station = new DockingStationPipe(tile, side);
            RobotManager.registryProvider.getRegistry(holder.getPipeWorld()).registerStation(station);
        }
        valid = true;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void onPlacedBy(Player player) {
        if (isClientSide()) {
            return;
        }
        validateStation();
        refreshRenderState();
        scheduleNetworkUpdate();
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void onTick() {
        if (isClientSide()) {
            return;
        }

        validateStation();
        RobotStationState old = renderState;
        refreshRenderState();
        if (old != renderState) {
            scheduleNetworkUpdate();
            if (holder != null) holder.scheduleRenderUpdate();
        }
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void onRemove() {
//? if mc_26_x && (>=26.3) {
        if (station != null && station.level() != null && !station.level().isClientSide() && RobotManager.registryProvider != null) {
//? } else {
        if (station != null && station.level() != null && !station.level().isClientSide && RobotManager.registryProvider != null) {
//?}
            RobotManager.registryProvider.getRegistry(station.level()).removeStation(station);
        }
        valid = false;
        station = null;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean isBlocking() {
        return true;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public VoxelShape getBoundingBox() {
        return BOXES[side.get3DDataValue()];
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public ItemStack getPickStack() {
        return new ItemStack(BCRoboticsItems.ROBOT_STATION.get());
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public InteractionResult onPluggableActivate(Player player, BlockHitResult trace, Level level) {
        if (player.isShiftKeyDown() && EntityUtil.getWrenchHand(player) != null) {
            // The client has no authoritative station object. Use synchronized render state to decide whether the
            // interaction should be forwarded, then validate the station on the server.
//? if mc_26_x && (>=26.3) {
            if (level.isClientSide()) {
//? } else {
            if (level.isClientSide) {
//?}
                return getRenderState() == RobotStationState.Linked
                        ? InteractionResult.SUCCESS
                        : InteractionResult.PASS;
            }
            validateStation();
            if (station == null || !station.isMainStation()) {
                return InteractionResult.PASS;
            }

            EntityRobotBase releasedRobot = station.robotTaking();
            station.forceRelease();
            if (releasedRobot instanceof EntityRobot robot) {
                robot.releaseMainStationForPlayer(station);
            } else if (releasedRobot != null) {
                releasedRobot.setMainStation(null);
            }

            EntityUtil.activateWrench(player, trace);
            refreshRenderState();
            scheduleNetworkUpdate();
            if (holder != null) {
                holder.scheduleRenderUpdate();
            }
            return InteractionResult.CONSUME;
        }

        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ItemRobot)) {
            held = player.getOffhandItem();
        }
        if (held.getItem() instanceof ItemRobot && holder instanceof TilePipeHolder tile) {
            return ItemRobot.placeOnStation(held, player, level, tile, side, this);
        }
        return InteractionResult.PASS;
    }

    private void refreshRenderState() {
        if (station == null) {
            renderState = RobotStationState.None;
        } else if (!station.isTaken()) {
            renderState = RobotStationState.Available;
        } else if (station.isMainStation()) {
            renderState = RobotStationState.Linked;
        } else {
            renderState = RobotStationState.Reserved;
        }
    }

    private boolean isClientSide() {
//? if mc_26_x && (>=26.3) {
        return holder != null && holder.getPipeWorld() != null && holder.getPipeWorld().isClientSide();
//? } else {
        return holder != null && holder.getPipeWorld() != null && holder.getPipeWorld().isClientSide;
//?}
    }

    public RobotStationState getRenderState() {
        return renderState == null ? RobotStationState.None : renderState;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void writeCreationPayload(FriendlyByteBuf buffer) {
        refreshRenderState();
        buffer.writeByte(getRenderState().ordinal());
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        refreshRenderState();
        buffer.writeByte(getRenderState().ordinal());
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        readState(buffer);
    }

    public static RobotStationPluggable readFromNbt(PluggableDefinition definition, IPipeHolder holder, Direction side, net.minecraft.nbt.CompoundTag tag) {
        return new RobotStationPluggable(definition, holder, side);
    }

    public static RobotStationPluggable loadFromBuffer(PluggableDefinition definition, IPipeHolder holder, Direction side, FriendlyByteBuf buffer) {
        RobotStationPluggable plug = new RobotStationPluggable(definition, holder, side);
        plug.readState(buffer);
        return plug;
    }

    private void readState(FriendlyByteBuf buffer) {
        int id = buffer.readUnsignedByte();
        RobotStationState[] values = RobotStationState.values();
        RobotStationState old = renderState;
        renderState = id >= 0 && id < values.length ? values[id] : RobotStationState.None;
        if (old != renderState && holder != null) {
            holder.scheduleRenderUpdate();
        }
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    @Nullable
    public PluggableModelKey getModelRenderKey(RenderType layer) {
//? if mc_26_x && (>=26.3) {
        if (layer == RenderCompat.cutout()) {
//? } else {
        if (layer == RenderType.cutout()) {
//?}
            return new KeyRobotStation(side, getRenderState());
        }
        return null;
    }
}
