/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.wire;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import buildcraft.lib.net.BCPacketContext;

public class MessageWireSystemsPowered {
    private static final int MAX_SYSTEMS = 4096;

    Map<Integer, Boolean> hashesPowered = new HashMap<>();

    @SuppressWarnings("unused")
    public MessageWireSystemsPowered() {
    }

    public MessageWireSystemsPowered(Map<Integer, Boolean> hashesPowered) {
        this.hashesPowered = hashesPowered;
    }

    public MessageWireSystemsPowered(FriendlyByteBuf buf) {
        hashesPowered.clear();
        int count = buf.readInt();
        if (count < 0 || count > MAX_SYSTEMS) {
            throw new DecoderException("Invalid powered wire system count: " + count);
        }
        for (int i = 0; i < count; i++) {
            hashesPowered.put(buf.readInt(), buf.readBoolean());
        }
    }

    public static void toBytes(MessageWireSystemsPowered msg, FriendlyByteBuf buf) {
        if (msg.hashesPowered.size() > MAX_SYSTEMS) {
            throw new IllegalStateException("Too many powered wire systems: " + msg.hashesPowered.size());
        }
        buf.writeInt(msg.hashesPowered.size());
        msg.hashesPowered.forEach((wiresHashCode, powered) -> {
            buf.writeInt(wiresHashCode);
            buf.writeBoolean(powered);
        });
    }

    public static final BiConsumer<MessageWireSystemsPowered, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MessageWireSystemsPoweredClientHandler.handle(message, ctx));
        ctx.get().setPacketHandled(true);
    };
}
