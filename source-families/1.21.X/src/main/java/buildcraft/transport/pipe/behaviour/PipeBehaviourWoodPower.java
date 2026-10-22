/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.pipe.behaviour;

import buildcraft.lib.internal.mj.MjCapabilities;

import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.transport.internal.pipe.IFlowForgeEnergy;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.PipeBehaviour;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

public class PipeBehaviourWoodPower extends PipeBehaviour {

    public PipeBehaviourWoodPower(IPipe pipe) {
        super(pipe);
    }

    public PipeBehaviourWoodPower(IPipe pipe, CompoundTag nbt) {
        super(pipe, nbt);
    }

    @Override
    public boolean canConnect(Direction face, PipeBehaviour other) {
        return !(other instanceof PipeBehaviourWoodPower);
    }

    @Override
    public int getTextureIndex(Direction face) {
        if (face == null) {
            return 0;
        }
        if (pipe.getConnectedPipe(face) != null) {
            return 0;
        }
        BlockEntity tile = pipe.getConnectedTile(face);
        if (tile == null) {
            return 0;
        }
        if (pipe.getFlow() instanceof IFlowForgeEnergy feFlow) {
            return feFlow.isExternalEnergyReceiver(face) ? 0 : 1;
        }
        IMjReceiver recv = tile.getLevel() == null ? null : tile.getLevel().getCapability(
            MjCapabilities.CAP_RECEIVER, tile.getBlockPos(), face.getOpposite()
        );
        return recv == null ? 1 : recv.canReceive() ? 0 : 1;
    }
}
