/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.wire;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.net.MessageManager;
import net.minecraft.world.entity.player.Player;

public class MessageWireSystems {
    // Keep individual wire packets bounded; a full snapshot can span any number of frames.
    private static final int MAX_SYSTEMS = 64;
    private static final int MAX_ELEMENTS_PER_PACKET = 16384;
    private static final int MAX_ELEMENTS_PER_SYSTEM = 16384;

    private Map<Integer, WireSystem> wireSystems = new LinkedHashMap<>();
    private boolean firstPage = true;
    private boolean lastPage = true;

    // Only published on the client after the last page. TCP ordering is guaranteed
    // within one BuildCraft connection, but render state must never expose a partial map.
    private static final Map<Integer, WireSystem> incomingSnapshot = new HashMap<>();
    private static boolean receivingSnapshot;

    @SuppressWarnings("unused")
    public MessageWireSystems() {
    }

    public MessageWireSystems(Map<Integer, WireSystem> wireSystems) {
        this(wireSystems, true, true);
    }

    private MessageWireSystems(Map<Integer, WireSystem> wireSystems, boolean firstPage, boolean lastPage) {
        this.wireSystems = new LinkedHashMap<>(wireSystems);
        this.firstPage = firstPage;
        this.lastPage = lastPage;
    }

    /** Break the complete visible network map into bounded, ordered snapshot frames. */
    public static void sendSnapshot(Map<Integer, WireSystem> systems, Player player) {
        Map<Integer, WireSystem> page = new LinkedHashMap<>();
        int partCount = 0;
        boolean first = true;
        for (Map.Entry<Integer, WireSystem> entry : systems.entrySet()) {
            int parts = (int) entry.getValue().elements.stream()
                .filter(element -> element.type == WireSystem.WireElement.Type.WIRE_PART)
                .limit(MAX_ELEMENTS_PER_SYSTEM).count();
            if (!page.isEmpty() && (page.size() >= MAX_SYSTEMS
                || partCount + parts > MAX_ELEMENTS_PER_PACKET)) {
                MessageManager.sendTo(new MessageWireSystems(page, first, false), player);
                first = false;
                page = new LinkedHashMap<>();
                partCount = 0;
            }
            page.put(entry.getKey(), entry.getValue());
            partCount += parts;
        }
        // Even an empty snapshot must clear networks no longer visible to this player.
        MessageManager.sendTo(new MessageWireSystems(page, first, true), player);
    }

    public static void toBytes(MessageWireSystems msg, FriendlyByteBuf buf) {
        FriendlyByteBuf pb = new FriendlyByteBuf(buf);
        pb.writeBoolean(msg.firstPage);
        pb.writeBoolean(msg.lastPage);
        if (msg.wireSystems.size() > MAX_SYSTEMS) {
            throw new IllegalStateException("Too many wire systems: " + msg.wireSystems.size());
        }
        pb.writeInt(msg.wireSystems.size());
        msg.wireSystems.forEach((wiresHashCode, wireSystem) -> {
            pb.writeInt(wiresHashCode);
            List<WireSystem.WireElement> elements = wireSystem.elements.stream()
                    .filter(element -> element.type == WireSystem.WireElement.Type.WIRE_PART)
                    .limit(MAX_ELEMENTS_PER_SYSTEM)
                    .collect(Collectors.toList());
            pb.writeInt(elements.size());
            elements.forEach(element -> element.toBytes(pb));
        });
    }

    public MessageWireSystems(FriendlyByteBuf buf) {
        FriendlyByteBuf pb = new FriendlyByteBuf(buf);
        firstPage = pb.readBoolean();
        lastPage = pb.readBoolean();
        wireSystems.clear();
        int count = pb.readInt();
        if (count < 0 || count > MAX_SYSTEMS) {
            throw new DecoderException("Invalid wire system count: " + count);
        }
        for (int i = 0; i < count; i++) {
            int wiresHashCode = pb.readInt();
            int localCount = pb.readInt();
            if (localCount < 0 || localCount > MAX_ELEMENTS_PER_SYSTEM) {
                throw new DecoderException("Invalid wire element count: " + localCount);
            }

            ImmutableList.Builder<WireSystem.WireElement> elements = ImmutableList.builder();
            for (int j = 0; j < localCount; j++) {
                elements.add(new WireSystem.WireElement(pb));
            }
            WireSystem wireSystem = new WireSystem(elements.build(), null);

            wireSystems.put(wiresHashCode, wireSystem);
        }
    }

    public static final BiConsumer<MessageWireSystems, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        ctx.get().enqueueWork(() -> {
            if (message.firstPage) {
                incomingSnapshot.clear();
                receivingSnapshot = true;
            }
            if (!receivingSnapshot) return; // Never publish pages received without the start frame.
            incomingSnapshot.putAll(message.wireSystems);
            if (message.lastPage) {
                ClientWireSystems.INSTANCE.wireSystems.clear();
                ClientWireSystems.INSTANCE.wireSystems.putAll(incomingSnapshot);
                incomingSnapshot.clear();
                receivingSnapshot = false;
            }
        });
        ctx.get().setPacketHandled(true);
    };
}
