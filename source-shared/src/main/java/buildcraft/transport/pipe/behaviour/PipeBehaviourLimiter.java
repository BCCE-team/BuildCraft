/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import buildcraft.api.v2.energy.MjAmount;

import java.io.IOException;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IFlowForgeEnergy;
import buildcraft.transport.internal.pipe.IFlowPower;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder.PipeMessageReceiver;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeEventActionActivate;
import buildcraft.transport.internal.pipe.PipeApi.ForgeEnergyTransferInfo;
import buildcraft.transport.internal.pipe.PipeApi.PowerTransferInfo;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeEventHandler;
import buildcraft.transport.internal.pipe.PipeEventForgeEnergy;
import buildcraft.transport.internal.pipe.PipeEventPower;
import buildcraft.lib.misc.EntityUtil;
import buildcraft.transport.statements.ActionPowerLimit;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class PipeBehaviourLimiter extends PipeBehaviour {
    public static final int MAX_SHIFT = 6;

    private int limitShift = 0;

    public PipeBehaviourLimiter(IPipe pipe) {
        super(pipe);
    }

    public PipeBehaviourLimiter(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
        limitShift = clampShift(nbt.getInt("limitShift"));
    }

    @Override
    public CompoundTag writeToNbt() {
        CompoundTag nbt = super.writeToNbt();
        nbt.putInt("limitShift", limitShift);
        return nbt;
    }

    @Override
    public void writePayload(FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(buffer, side);
        buffer.writeByte(limitShift);
    }

    @Override
    public void readPayload(FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(buffer, side, ctx);
        limitShift = clampShift(buffer.readUnsignedByte());
    }

    @PipeEventHandler
    public void configurePower(PipeEventPower.Configure event) {
        if (limitShift == MAX_SHIFT) {
            event.disableTransfer();
        } else {
            event.setMaxPower(event.getMaxPower() >> limitShift);
        }
    }

    @PipeEventHandler
    public void configureForgeEnergy(PipeEventForgeEnergy.Configure event) {
        if (limitShift == MAX_SHIFT) {
            event.disableTransfer();
        } else {
            event.setMaxPower(event.getMaxPower() >> limitShift);
        }
    }

    @PipeEventHandler
    public void onActionActivate(PipeEventActionActivate event) {
        if (event.action instanceof ActionPowerLimit action) {
            limitShift = action.limitShift;
            requestReconfigure();
        }
    }

    @Override
    public boolean onPipeActivate(Player player, BlockHitResult trace, Level level, EnumPipePart part) {
        if (EntityUtil.getWrenchHand(player) == null) {
            return false;
        }

        if (!level.isClientSide()) {
            EntityUtil.activateWrench(player, trace);
            limitShift++;
            if (limitShift > MAX_SHIFT) {
                limitShift = 0;
            }

            if (pipe.getFlow() instanceof IFlowForgeEnergy) {
                ForgeEnergyTransferInfo transferInfo = PipeApi.getForgeEnergyTransferInfo(pipe.getDefinition());
                int limit = limitShift == MAX_SHIFT ? 0 : transferInfo.transferPerTick >> limitShift;
                player.displayClientMessage(Component.translatable("chat.pipe.fe.iron.mode", limit), true);
            } else {
                PowerTransferInfo transferInfo = PipeApi.getPowerTransferInfo(pipe.getDefinition());
                long limit = limitShift == MAX_SHIFT ? 0 : (transferInfo.transferPerTick >> limitShift) / MjAmount.MICRO_MJ_PER_MJ;
                player.displayClientMessage(Component.translatable("chat.pipe.power.iron.mode", limit), true);
            }

            requestReconfigure();
        }
        return true;
    }

    private void requestReconfigure() {
        if (pipe.getFlow() instanceof IFlowPower powerFlow) {
            powerFlow.reconfigure();
            pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        } else if (pipe.getFlow() instanceof IFlowForgeEnergy feFlow) {
            feFlow.reconfigure();
            pipe.getHolder().scheduleNetworkUpdate(PipeMessageReceiver.BEHAVIOUR);
        }
    }

    @Override
    public int getTextureIndex(Direction face) {
        return MAX_SHIFT - limitShift;
    }

    private static int clampShift(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, MAX_SHIFT);
    }
}
