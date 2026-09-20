/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.internal.mj.MjCapabilities;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjRedstoneReceiver;
import buildcraft.lib.internal.mj.MjBattery;
import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeEventActionActivate;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventItem;
import buildcraft.transport.internal.pipe.PipeEventStatement;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import buildcraft.transport.BCTransportStatements;
import buildcraft.transport.pipe.StripesRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import buildcraft.lib.net.BCNetworkSide;
import net.neoforged.fml.util.thread.SidedThreadGroups;
import buildcraft.lib.net.BCPacketContext;

public class PipeBehaviourStripes extends PipeBehaviour implements StripesOutput, IMjRedstoneReceiver {
    private final MjBattery battery = new MjBattery(256 * MjAmount.MICRO_MJ_PER_MJ);

    @Nullable
    public Direction direction = null;
    private long progress;
    @Nullable
    private BlockPos progressTarget;
    @Nullable
    private String progressStateKey;

    public PipeBehaviourStripes(IPipe pipe) {
        super(pipe);
    }

    public PipeBehaviourStripes(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        battery.deserializeNBT(nbt.getCompound("battery"));
        setDirection(NBTUtilBC.readEnum(nbt.get("direction"), Direction.class));
        progress = Math.max(0L, nbt.getLong("progress"));
        if (progress > 0 && nbt.contains("progressTarget") && nbt.contains("progressState")) {
            progressTarget = BlockPos.of(nbt.getLong("progressTarget"));
            progressStateKey = nbt.getString("progressState");
        } else {
            progress = 0;
            progressTarget = null;
            progressStateKey = null;
        }
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.put("battery", battery.serializeNBT());
        nbt.put("direction", NBTUtilBC.writeEnum(direction));
        if (progress > 0 && progressTarget != null && progressStateKey != null) {
            nbt.putLong("progress", progress);
            nbt.putLong("progressTarget", progressTarget.asLong());
            nbt.putString("progressState", progressStateKey);
        }
        return nbt;
    }

    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(buffer, side, ctx);
        direction = MessageUtil.readEnumOrNull(buffer, Direction.class);
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(buffer, side);
        MessageUtil.writeEnumOrNull(buffer, direction);
    }

    // Sides

    private void setDirection(@Nullable Direction newValue) {
        if (direction != newValue) {
            resetProgress();
            direction = newValue;
            if (Thread.currentThread().getThreadGroup() == SidedThreadGroups.SERVER) {
                pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
            }
        }
    }

    private void resetProgress() {
        if (progressTarget != null) {
            Level world = pipe.getHolder().getPipeWorld();
            if (!world.isClientSide()) {
                world.destroyBlockProgress(progressTarget.hashCode(), progressTarget, -1);
            }
        }
        progress = 0;
        progressTarget = null;
        progressStateKey = null;
    }

    private void bindProgressTarget(BlockPos target, BlockState state) {
        progressTarget = target.immutable();
        progressStateKey = state.toString();
    }

    private boolean matchesProgressTarget(BlockPos target, BlockState state) {
        return progressTarget != null && progressTarget.equals(target)
            && progressStateKey != null && progressStateKey.equals(state.toString());
    }

    // Actions

    @PipeEventHandler
    public void addInternalActions(PipeEventStatement.AddActionInternal event) {
        for (Direction face : Direction.values()) {
            if (!pipe.isConnected(face)) {
                PipePluggable plug = pipe.getHolder().getPluggable(face);
                if (plug == PipePluggable.EMPTY || !plug.isBlocking()) {
                    event.actions.add(BCTransportStatements.ACTION_PIPE_DIRECTION[face.ordinal()]);
                }
            }
        }
    }

    @PipeEventHandler
    public void onActionActivate(PipeEventActionActivate event) {
        for (Direction face : Direction.values()) {
            if (event.action == BCTransportStatements.ACTION_PIPE_DIRECTION[face.ordinal()]) {
                setDirection(face);
            }
        }
    }

    // IMjRedstoneReceiver

    @Override
    public boolean canConnect(@Nonnull IMjConnector other) {
        return true;
    }

    @Override
    public long getPowerRequested() {
        return battery.getCapacity() - battery.getStored();
    }

    @Override
    public long receivePower(long microJoules, FluidAction simulate) {
        return battery.addPowerChecking(microJoules, simulate);
    }

    // Stripes

    @Override
    public boolean requiresPeriodicSave() {
        return progress > 0;
    }

    @Override
    public boolean canConnect(Direction face, PipeBehaviour other) {
        return !(other instanceof PipeBehaviourStripes);
    }

    @Override
    public void onTick() {
        Level world = pipe.getHolder().getPipeWorld();
        if (world.isClientSide()) {
            return;
        }
        BlockPos pos = pipe.getHolder().getPipePos();
        if (direction == null || pipe.isConnected(direction)) {
            int sides = 0;
            Direction dir = null;
            for (Direction face : Direction.values()) {
                if (pipe.isConnected(face)) {
                    sides++;
                    dir = face;
                }
            }
            if (sides == 1) {
                setDirection(dir.getOpposite());
            } else {
                setDirection(null);
            }
        }
        battery.tick(world, pipe.getHolder().getPipePos());
        if (direction != null) {
            var blockPlayer = FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) world, pipe.getHolder().getOwner(), pos);
            if (StripesRegistry.INSTANCE.handleBlock(world, pos, direction, blockPlayer, this)) {
                resetProgress();
                return;
            }
            BlockPos offset = pos.relative(direction);
            int offsetHash = offset.hashCode();
            BlockState targetState = world.getBlockState(offset);
            if (progress > 0 && !matchesProgressTarget(offset, targetState)) {
                resetProgress();
            }
            if (targetState.isAir()) {
                resetProgress();
                return;
            }
            if (progress <= 0) {
                bindProgressTarget(offset, targetState);
            }
            if (!AutomationPermissionUtil.mayBlock(
                world, pos, offset, pipe.getHolder().getOwner(), AutomationPermissionUtil.SOURCE_STRIPES_PIPE,
                WorldOperationKind.BLOCK_BREAK, OperationMode.SIMULATE
            )) {
                resetProgress();
                return;
            }

            // Do the loader/protection preflight before spending the first micro-MJ on this target.
            // The final break still performs its own event check, so protection changes during charging remain safe.
            if (progress <= 0) {
                var actor = FakePlayerProvider.INSTANCE.getFakePlayer(
                    (ServerLevel) world, pipe.getHolder().getOwner(), pos
                );
                if (!BlockUtil.canBreakBlock((ServerLevel) world, offset, actor)) {
                    world.destroyBlockProgress(offsetHash, offset, -1);
                    return;
                }
            }

            long target = BlockUtil.computeBlockBreakPower(world, offset);
            if (target <= 0) {
                resetProgress();
                return;
            }
            if (progress < target) {
                progress += battery.extractPower(0, Math.min(target - progress, MjAmount.MICRO_MJ_PER_MJ * 10));
                if (progress > 0) {
                    world.destroyBlockProgress(offsetHash, offset, (int) (progress * 9 / target));
                }
            } else {
                if (!AutomationPermissionUtil.mayBlock(
                    world, pos, offset, pipe.getHolder().getOwner(), AutomationPermissionUtil.SOURCE_STRIPES_PIPE,
                    WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
                )) {
                    // Keep the completed work instead of charging the same protected target again next tick.
                    world.destroyBlockProgress(offsetHash, offset, -1);
                    return;
                }
                var drops = BlockUtil.breakBlockAndGetDrops(
                    (ServerLevel) world,
                    offset,
                    new ItemStack(Items.DIAMOND_PICKAXE),
                    pipe.getHolder().getOwner()
                );
                if (drops.isPresent()) {
                    drops.get().forEach(stack -> sendItem(stack, direction));
                    resetProgress();
                }
                world.destroyBlockProgress(offsetHash, offset, -1);
            }
        } else {
            progress = 0;
        }
    }

    @PipeEventHandler
    public void onDrop(PipeEventItem.Drop event) {
        if (direction == null) {
            return;
        }
        IPipeHolder holder = pipe.getHolder();
        Level world = holder.getPipeWorld();
        BlockPos pos = holder.getPipePos();
        ServerPlayer player = buildcraft.lib.misc.FakePlayerProvider.INSTANCE.getFakePlayer((ServerLevel) world, holder.getOwner(), pos);
        player.getInventory().clearContent();;
        // set the main hand of the fake player to the stack
        player.getInventory().setItem(player.getInventory().selected, event.getStack());
        if (StripesRegistry.INSTANCE.handleItem(world, pos, direction, event.getStack(), player, this)) {
            event.setStack(StackUtil.EMPTY);
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().removeItemNoUpdate(i);
                if (!stack.isEmpty()) {
                    sendItem(stack, direction);
                }
            }
        }
    }

    @Override
    public void dropItem(@Nonnull ItemStack stack, Direction direction) {
        InventoryUtil.drop(pipe.getHolder().getPipeWorld(), pipe.getHolder().getPipePos(), stack);
    }

    @Override
    public boolean sendItem(@Nonnull ItemStack stack, Direction from) {
        PipeFlow flow = pipe.getFlow();
        if (flow instanceof IFlowItems) {
            ((IFlowItems) flow).insertItemsForce(stack, from, null, 0.02);
            return true;
        } else {
            return false;
        }
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> T getCapability(
        BlockCapability<T, Direction> capability, @Nullable Direction facing
    ) {
        if (capability == MjCapabilities.CAP_REDSTONE_RECEIVER
            || capability == MjCapabilities.CAP_RECEIVER
            || capability == MjCapabilities.CAP_CONNECTOR) {
            return (T) this;
        }
        return super.getCapability(capability, facing);
    }

	@Override
	public void rotate(Rotation rot) {
		if(direction == null || direction.ordinal() <2) {
			return;
		}
		direction = rot.rotate(direction);
	}
    
    
}
