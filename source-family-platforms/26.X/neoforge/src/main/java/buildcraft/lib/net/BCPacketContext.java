/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Loader-neutral view of a received BuildCraft packet.
 *
 * Loader network objects must be converted to this type at the MessageManager boundary instead of
 * leaking Forge/NeoForge/Fabric context classes into gameplay code.
 */
public interface BCPacketContext {
    BCNetworkSide side();

    @Nullable
    Player player();

    default @Nullable ServerPlayer getSender() {
        Player player = player();
        return player instanceof ServerPlayer serverPlayer ? serverPlayer : null;
    }

    void enqueueWork(Runnable task);

    /**
     * Forge needs an explicit handled flag while newer transports do not. The platform adapter owns
     * that distinction; gameplay may call this without knowing which loader is underneath.
     */
    void setPacketHandled(boolean handled);
}
