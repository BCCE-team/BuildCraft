/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.tile;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.permission.WorldOperationKind;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.internal.core.SafeTimeTracker;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.core.BCCoreConfig;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.inventory.AutomaticProvidingTransactor;
import buildcraft.lib.internal.api.v2.MachineDefinitionLookup;
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.AutomationPermissionUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.internal.mj.MjBatteryReceiver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.BlockPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

public class TileMiningWell extends TileMiner implements MachineRuntimeView {
	private final BlockPositionSource blockPosSource = new BlockPositionSource(this.worldPosition);
    private boolean shouldCheck = true;
    private final SafeTimeTracker tracker = new SafeTimeTracker(256);
    public final GameEventListener worldEventListener = new GameEventListener() {
    	@Override
    	public PositionSource getListenerSource() {
    		return blockPosSource;
    	}
    	@Override
    	public int getListenerRadius() {
            int limit = BCCoreConfig.miningMaxDepth;
            int high = worldPosition.getY() + 64;
            return high < limit ? high : limit;
    	}
    	@Override
	public boolean handleGameEvent(ServerLevel serverLevel, Holder<GameEvent> event, GameEvent.Context context, Vec3 pos) {
            if (event != GameEvent.BLOCK_PLACE && event != GameEvent.BLOCK_DESTROY) {
                return false;
            }
            BlockPos eventPos = BlockPos.containing(pos);
            if (eventPos.getX() == worldPosition.getX()
                && eventPos.getY() <= worldPosition.getY()
                && eventPos.getZ() == worldPosition.getZ()) {
                shouldCheck = true;
                return true;
            }
            return false;
    	}
    };

    public TileMiningWell(BlockPos pos, BlockState state) {
    	super(BCFactoryBlocks.ENTITYBLOCKMININGWELL.get(), pos, state);
        caps.addCapabilityInstance(CapUtil.CAP_ITEM_TRANSACTOR, AutomaticProvidingTransactor.INSTANCE, EnumPipePart.VALUES);
    }

    @Override
    protected void mine() {
        if (currentPos != null && canBreak()) {
            shouldCheck = true;
            long target = Math.max(0L, Math.round(BlockUtil.computeBlockBreakPower(level, currentPos) * MachineDefinitionLookup.energyCostMultiplier(BuildCraftContentIds.Machines.MINING_WELL)));
            progress += battery.extractPower(0, target - progress);
            if (progress >= target) {
                if (!AutomationPermissionUtil.mayBlock(
                    level, worldPosition, currentPos, getOwner(), AutomationPermissionUtil.SOURCE_MINING_WELL,
                    WorldOperationKind.BLOCK_BREAK, OperationMode.EXECUTE
                )) {
                    // Keep the completed work. A protected target must not consume the full cost again every tick.
                    level.destroyBlockProgress(currentPos.hashCode(), currentPos, -1);
                    return;
                }
                level.destroyBlockProgress(currentPos.hashCode(), currentPos, -1);
                var drops = BlockUtil.breakBlockAndGetDrops(
                    (ServerLevel) level,
                    currentPos,
                    new ItemStack(Items.DIAMOND_PICKAXE),
                    getOwner()
                );
                if (drops.isPresent()) {
                    drops.get().forEach(stack -> InventoryUtil.addToBestAcceptor(level, worldPosition, null, stack));
                    progress = 0;
                    nextPos();
                }
            } else {
                if (!level.getBlockState(currentPos).isAir()) {
                    level.destroyBlockProgress(currentPos.hashCode(), currentPos, (int) ((progress * 9) / target));
                }
            }
        } else if (shouldCheck || tracker.markTimeIfDelay(level)) {
            nextPos();
            if (currentPos == null) {
                shouldCheck = false;
            }
        }
    }

    private boolean canBreak() {
        BlockState state = level.getBlockState(currentPos);
        if (state.isAir() || isStandaloneWaterBlock(state)
            || BlockUtil.isUnbreakableBlock(level, currentPos, getOwner())) {
            return false;
        }

        if (!AutomationPermissionUtil.mayBlock(
            level, worldPosition, currentPos, getOwner(), AutomationPermissionUtil.SOURCE_MINING_WELL,
            WorldOperationKind.BLOCK_BREAK, OperationMode.SIMULATE
        )) {
            return false;
        }

        Fluid fluid = BlockUtil.getFluidWithFlowing(level, currentPos);
        return fluid == Fluids.EMPTY || fluid.getFluidType().getViscosity() <= 1000;
    }

    private static boolean isStandaloneWaterBlock(BlockState state) {
        Fluid fluid = BlockUtil.getFluidWithFlowing(state.getBlock());
        return fluid != Fluids.EMPTY && fluid.defaultFluidState().is(FluidTags.WATER);
    }

    private void nextPos() {
    	currentPos = worldPosition;
        while (true) {
        	currentPos = currentPos.below();
            if (level.isOutsideBuildHeight(currentPos)) {
                break;
            }
            if (worldPosition.getY() - currentPos.getY() > BCCoreConfig.miningMaxDepth) {
                break;
            }
            BlockState state = level.getBlockState(currentPos);
            if (canBreak()) {
                updateLength();
                return;
            } else if (isStandaloneWaterBlock(state)) {
                continue;
            } else if (!state.isAir() && state.getBlock() != BCFactoryBlocks.TUBE_BLOCK.get()) {
                break;
            }
        }
        currentPos = null;
        updateLength();
    }

	@Override
	public void onRemove(boolean dropSelf) {
        if (!level.isClientSide) {
            if (currentPos != null) {
                level.destroyBlockProgress(currentPos.hashCode(), currentPos, -1);
            }
        }
		super.onRemove(dropSelf);
	}

    @Override
    protected long getBatteryCapacity() {
        return MachineDefinitionLookup.capacityMicroMj(BuildCraftContentIds.Machines.MINING_WELL, 500 * MjAmount.MICRO_MJ_PER_MJ);
    }

	@Override
    protected IMjReceiver createMjReceiver() {
        return new MjBatteryReceiver(battery);
    }
    @Override
    public net.minecraft.resources.ResourceLocation api2MachineTypeId() {
        return BuildCraftContentIds.Machines.MINING_WELL;
    }

}
