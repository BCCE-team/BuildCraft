/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.blockEntity;

import buildcraft.api.v2.energy.MjAmount;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.core.BCCoreBlocks;
import buildcraft.lib.engine.EngineConnector;
import buildcraft.lib.engine.TileEngineBase_BC8;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class TileEngineRedstone_BC8 extends TileEngineBase_BC8 {
//    private static final ResourceLocation ADVANCEMENT = ResourceLocation.parse("buildcraftcore:free_power");
    private boolean givenAdvancement = false;

    public TileEngineRedstone_BC8(BlockPos pos, BlockState state) {
    	super(BCCoreBlocks.ENGINE_REDSTONE_TILE_BC8.get(), pos, state);
    }

    @Nonnull
    @Override
    protected IMjConnector createConnector() {
        return new EngineConnector(false);
    }

    @Override
    public boolean isBurning() {
        return isRedstonePowered;
    }

    @Override
    protected void engineUpdate() {
        super.engineUpdate();
        if (isRedstonePowered) {
            addPower(getCurrentOutput());
            if (level.getGameTime() % 16 == 0) {
                if (getHeatLevel() < 0.8) {
                    heat += 4;
                }
                if (isPumping && !givenAdvancement) {
//                    givenAdvancement = AdvancementUtil.unlockAdvancement(this.getOwner().getId(), ADVANCEMENT);
                }
            }
        } else {
            power = 0;
        }
    }

    @Override
    public double getPistonSpeed() {
        return super.getPistonSpeed() / 2;
    }

    @Override
    public void updateHeatLevel() {
        if (heat > MIN_HEAT) {
            heat -= 0.2f;
            if (heat < MIN_HEAT) {
                heat = MIN_HEAT;
            }
        }
    }

    @Override
    protected int getMaxChainLength() {
        return 0;
    }

    @Override
    public long getMaxPower() {
        return MjAmount.MICRO_MJ_PER_MJ * 1;
    }

    @Override
    public long minPowerReceived() {
        return MjAmount.MICRO_MJ_PER_MJ / 10;
    }

    @Override
    public long maxPowerReceived() {
        return 4 * MjAmount.MICRO_MJ_PER_MJ;
    }

    @Override
    public long maxPowerExtracted() {
        return MjAmount.MICRO_MJ_PER_MJ;
    }

    @Override
    public float explosionRange() {
        return 0;
    }

    @Override
    public long getCurrentOutput() {
        return MjAmount.MICRO_MJ_PER_MJ / 20;
    }
}
