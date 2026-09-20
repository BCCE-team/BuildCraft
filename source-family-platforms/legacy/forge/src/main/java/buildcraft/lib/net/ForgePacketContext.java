/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkEvent;

/** Forge implementation of the loader-neutral BuildCraft packet context. */
public final class ForgePacketContext implements BCPacketContext {
    private final NetworkEvent.Context context;

    public ForgePacketContext(NetworkEvent.Context context) {
        this.context = context;
    }

    @Override
    public BCNetworkSide side() {
        return context.getDirection().getReceptionSide() == LogicalSide.CLIENT
            ? BCNetworkSide.CLIENT
            : BCNetworkSide.SERVER;
    }

    @Override
    public @Nullable Player player() {
        return context.getSender();
    }

    @Override
    public void enqueueWork(Runnable task) {
        context.enqueueWork(task);
    }

    @Override
    public void setPacketHandled(boolean handled) {
        context.setPacketHandled(handled);
    }
}
