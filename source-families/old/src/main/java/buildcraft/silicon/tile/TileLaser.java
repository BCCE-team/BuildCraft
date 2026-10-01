/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.machine.LaserTarget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.lib.internal.mj.MjCapabilityHelper;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.internal.tiles.IDebuggable;
import buildcraft.lib.client.render.DetachedRenderer.IDetachedRenderer;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.VolumeUtil;
import buildcraft.lib.misc.data.AverageLong;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.internal.mj.MjBatteryReceiver;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.client.render.AdvDebuggerLaser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileLaser extends TileBC_Neptune implements IDebuggable, GameEventListener {
    private static final int TARGETING_RANGE = 6;

    private final SafeTimeTracker clientLaserMoveInterval = new SafeTimeTracker(5, 10);
    private final SafeTimeTracker serverTargetMoveInterval = new SafeTimeTracker(10, 20);
    private final SafeTimeTracker serverRenderSyncInterval = new SafeTimeTracker(10);

    private final List<BlockPos> targetPositions = new ArrayList<>();
    private BlockPos targetPos;
    public Vec3 laserPos;
    private boolean levelHasUpdated = true;

    private final AverageLong avgPower = new AverageLong(100);
    private long averageClient;
    private long renderPower;
    private boolean lastSentBeamActive;
    private final MjBattery battery;

    public TileLaser(BlockPos pos, BlockState state) {
        super(BCSiliconBlocks.LASER_TILE.get(), pos, state);
        battery = new MjBattery(1024 * MjAmount.MICRO_MJ_PER_MJ);
        caps.addProvider(new MjCapabilityHelper(new MjBatteryReceiver(battery)));
    }

    @Override
    public int getListenerRadius() {
        return TARGETING_RANGE;
    }

    @Override
    public PositionSource getListenerSource() {
        return new BlockPositionSource(this.worldPosition);
    }

    @Override
    //? if <1.20 {
    public boolean handleGameEvent(ServerLevel level, GameEvent.Message msg) {
    	GameEvent gameEvent = msg.gameEvent();
    	if(gameEvent == (GameEvent.BLOCK_PLACE)||gameEvent == (GameEvent.BLOCK_DESTROY)||gameEvent == (GameEvent.BLOCK_CHANGE)) {
    //?} else {
    /*?
    public boolean handleGameEvent(ServerLevel level, GameEvent gameEvent, GameEvent.Context context, Vec3 sourcePos) {
        if (gameEvent == GameEvent.BLOCK_PLACE || gameEvent == GameEvent.BLOCK_DESTROY || gameEvent == GameEvent.BLOCK_CHANGE) {
    ?*/
    //?}
    		this.levelHasUpdated = true;
    		return true;
    	}
        return false;
    }

    private void findPossibleTargets() {
        targetPositions.clear();
        BlockState state = level.getBlockState(worldPosition);
        if (state.getBlock() != BCSiliconBlocks.LASER_BLOCK.get()) {
            return;
        }
        Direction face = state.getValue(BuildCraftProperties.BLOCK_FACING_6);

        VolumeUtil.iterateCone(level, worldPosition, face, TARGETING_RANGE, true, (w, s, p, visible) -> {
            if (!visible) {
                return;
            }
            if (laserTargets().target(level, p, laserTargetSide()).isPresent()) {
                targetPositions.add(p);
            }
        });
    }

    private void randomlyChooseTargetPos() {
        List<BlockPos> targetsNeedingPower = new ArrayList<>();
        for(BlockPos position: targetPositions) {
            if (isPowerNeededAt(position)) {
                targetsNeedingPower.add(position);
            }
        }
        if (targetsNeedingPower.isEmpty()) {
            targetPos = null;
            return;
        }
        targetPos = targetsNeedingPower.get(level.getRandom().nextInt(targetsNeedingPower.size()));
    }

    private buildcraft.api.v2.machine.LaserTargetService laserTargets() {
        return BuildCraftApi.service(BuildCraftServices.LASER_TARGETS);
    }

    private Direction laserTargetSide() {
        BlockState state = level.getBlockState(worldPosition);
        return state.hasProperty(BuildCraftProperties.BLOCK_FACING_6)
            ? state.getValue(BuildCraftProperties.BLOCK_FACING_6).getOpposite()
            : Direction.DOWN;
    }

    private boolean isPowerNeededAt(BlockPos position) {
        LaserTarget target = getTarget(position);
        if (target == null) return false;
        return target.laserPort()
            .insert(MjAmount.ofMicro(getMaxPowerPerTick()), OperationMode.SIMULATE)
            .transferred().microMj() > 0;
    }

    private LaserTarget getTarget(BlockPos position) {
        if (position == null) return null;
        return laserTargets().target(level, position, laserTargetSide()).orElse(null);
    }

    private LaserTarget getTarget() {
        return getTarget(targetPos);
    }

    private void updateLaser() {
        if (targetPos != null) {
            laserPos = Vec3.atLowerCornerOf(targetPos)
                .add(
                    (5 + level.random.nextInt(6) + 0.5) / 16D,
                    9 / 16D,
                    (5 + level.random.nextInt(6) + 0.5) / 16D
                );
        } else {
            laserPos = null;
        }
    }

    public long getAverageClient() {
        return averageClient;
    }

    public long getMaxPowerPerTick() {
        // 128 MJ/s = 6.4 MJ/t at 20 ticks per second.
        return 128 * MjAmount.MICRO_MJ_PER_MJ / 20;
    }

    public void update() {
        if (level.isClientSide) {
            // set laser render position on client side
            if (clientLaserMoveInterval.markTimeIfDelay(level) || targetPos == null) {
                updateLaser();
            }
            return;
        }

        // set target tile on server side
        avgPower.tick();

        BlockPos previousTargetPos = targetPos;
        if (levelHasUpdated) {
            findPossibleTargets();
            levelHasUpdated = false;
        }

        if (!isPowerNeededAt(targetPos)) {
            targetPos = null;
        }

        if (serverTargetMoveInterval.markTimeIfDelay(level) || !isPowerNeededAt(targetPos)) {
            randomlyChooseTargetPos();
        }

        long transferredPower = 0;
        LaserTarget target = getTarget();
        if (target != null) {
            long max = getMaxPowerPerTick();
            max *= battery.getStored() + max;
            max /= battery.getCapacity() / 2;
            max = Math.min(max, getMaxPowerPerTick());

            MjPort targetPort = target.laserPort();
            long acceptedByTarget = targetPort.insert(MjAmount.ofMicro(max), OperationMode.SIMULATE)
                .transferred().microMj();
            long offered = battery.extractPower(0, acceptedByTarget);
            long accepted = targetPort.insert(MjAmount.ofMicro(offered), OperationMode.EXECUTE)
                .transferred().microMj();
            accepted = Math.max(0L, Math.min(offered, accepted));
            if (accepted < offered) {
                battery.insert(MjAmount.ofMicro(offered - accepted), OperationMode.EXECUTE);
            }
            transferredPower = accepted;
            avgPower.push(transferredPower);
        } else {
            avgPower.clear();
        }

        // A target may be selected while the laser has no stored power. Resynchronise when energy starts or stops
        // flowing, when the target changes, or on the bounded periodic update instead of sending a packet every tick.
        renderPower = transferredPower > 0
            ? Math.max(transferredPower, avgPower.getAverageLong())
            : 0;
        boolean beamActive = targetPos != null && renderPower > 0;
        boolean targetChanged = !Objects.equals(previousTargetPos, targetPos);
        boolean beamActivityChanged = beamActive != lastSentBeamActive;
        boolean periodicPowerUpdate = beamActive && serverRenderSyncInterval.markTimeIfDelay(level);

        if (targetChanged || beamActivityChanged || periodicPowerUpdate) {
            sendNetworkUpdate(NET_RENDER_DATA);
            lastSentBeamActive = beamActive;
        }

        markChunkDirty();
    }

	@Override
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
        nbt.put("battery", battery.serializeNBT());
        if (laserPos != null) {
            nbt.put("laser_pos", NBTUtilBC.writeVec3(laserPos));
        }
        if (targetPos != null) {
            nbt.putIntArray("target_pos", NBTUtilBC.writeBlockPos(targetPos));
        }
        avgPower.writeToNbt(nbt, "average_power");
	}
	
    @Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
        // The persisted key "mj_battery" is accepted as a legacy battery alias.
        if (nbt.contains("mj_battery")) {
            nbt.put("battery", nbt.get("mj_battery"));
        }
        battery.deserializeNBT(nbt.getCompound("battery"));
        targetPos = NBTUtilBC.readBlockPos(nbt.get("target_pos"));
        laserPos = NBTUtilBC.readVec3(nbt.get("laser_pos"));
        avgPower.readFromNbt(nbt, "average_power");
	}

	@Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                battery.writeToBuffer(buffer);
                buffer.writeBoolean(targetPos != null);
                if (targetPos != null) {
                    MessageUtil.writeBlockPos(buffer, targetPos);
                }
                buffer.writeLong(renderPower);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                battery.readFromBuffer(buffer);
                if (buffer.readBoolean()) {
                    targetPos = MessageUtil.readBlockPos(buffer);
                } else {
                    targetPos = null;
                }
                averageClient = buffer.readLong();
                updateLaser();
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        left.add("battery = " + battery.getDebugString());
        left.add("target = " + targetPos);
        left.add("laser = " + laserPos);
        left.add("average = " + LocaleUtil.localizeMjFlow(averageClient == 0 ? (long) avgPower.getAverage() : averageClient));
    }

    @Nonnull
    @Override
    public AABB getRenderBoundingBox() {
        return new Box(this).extendToEncompass(targetPos).getBoundingBox();
    }

    @Override
    public IDetachedRenderer getDebugRenderer() {
        return new AdvDebuggerLaser(this);
    }
}
