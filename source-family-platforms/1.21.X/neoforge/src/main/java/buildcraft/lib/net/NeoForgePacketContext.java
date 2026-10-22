/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import javax.annotation.Nullable;

import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** NeoForge implementation of the loader-neutral BuildCraft packet context. */
public final class NeoForgePacketContext implements BCPacketContext {
    private final IPayloadContext context;

    public NeoForgePacketContext(IPayloadContext context) {
        this.context = context;
    }

    @Override
    public BCNetworkSide side() {
        return context.flow() == PacketFlow.CLIENTBOUND ? BCNetworkSide.CLIENT : BCNetworkSide.SERVER;
    }

    @Override
    public @Nullable Player player() {
        return context.player();
    }

    @Override
    public void enqueueWork(Runnable task) {
        context.enqueueWork(task);
    }

    @Override
    public void setPacketHandled(boolean handled) {
        // NeoForge payloads are considered handled by the registered payload handler.
    }
}
