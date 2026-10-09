/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.blockEntity;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.enums.EnumPowerStage;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.misc.WrenchUtil;
import buildcraft.core.BCCoreBlocks;
import buildcraft.lib.engine.EngineConnector;
import buildcraft.lib.engine.TileEngineBase_BC8;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileEngineCreative extends TileEngineBase_BC8 {
	
	
    public TileEngineCreative(BlockPos pos, BlockState state) {
		super(BCCoreBlocks.ENGINE_CREATIVE_TILE_BC8.get(), pos, state);
	}

	public static final long[] outputs = { 1, 2, 4, 8, 16, 32, 64, 128, 256 };
    public int currentOutputIndex = 0;

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (side == BCNetworkSide.SERVER) {
            if (id == NET_RENDER_DATA) {
                buffer.writeByte(currentOutputIndex);
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == BCNetworkSide.CLIENT) {
            if (id == NET_RENDER_DATA) {
                currentOutputIndex = buffer.readUnsignedByte() % outputs.length;
            }
        }
    }

    @Override
    protected void engineUpdate() {
        if (isBurning()) {
            power += getCurrentOutput();
            long max = getMaxPower();
            if (power > max) {
                power = getMaxPower();
            }
        } else {
            power = 0;
        }
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
    public double getPistonSpeed() {
        final double max = 0.08;
        final double min = 0.01;
        double interp = currentOutputIndex / (double) (outputs.length - 1);
        return Mth.lerp(interp, min, max);
    }

    @Override
    protected EnumPowerStage computePowerStage() {
        return EnumPowerStage.BLACK;
    }

    @Override
    public long getMaxPower() {
        return getCurrentOutput() * 10_000;
    }

    @Override
    public long maxPowerReceived() {
        return 2_000 * MjAmount.MICRO_MJ_PER_MJ;
    }

    @Override
    public long maxPowerExtracted() {
        return 20 * getCurrentOutput();
    }

    @Override
    public float explosionRange() {
        return 0;
    }

    @Override
    public long getCurrentOutput() {
        return outputs[Mth.clamp(currentOutputIndex, 0, outputs.length - 1)] * MjAmount.MICRO_MJ_PER_MJ;
    }

    @Override
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (WrenchUtil.isWrench(stack)) {
            if (!level.isClientSide) {
                currentOutputIndex++;
                currentOutputIndex %= outputs.length;
                player.displayClientMessage(Component.translatable("chat.pipe.power.iron.mode", outputs[currentOutputIndex]), true);
                sendNetworkUpdate(NET_RENDER_DATA);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
	}

	@Override
    protected void writeData(BCValueOutput bcData) {
        super.writeData(bcData);
        bcData.writeInt("currentOutputIndex", currentOutputIndex);
    }

    @Override
    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        currentOutputIndex = bcData.readInt("currentOutputIndex");
        currentOutputIndex = Mth.clamp(currentOutputIndex, 0, outputs.length - 1);
    }
}
