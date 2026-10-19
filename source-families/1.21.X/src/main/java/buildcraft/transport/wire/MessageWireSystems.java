/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.wire;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
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
    private static final int MAX_ELEMENTS_PER_FRAGMENT = 4096;

    private Map<Integer, WireSystem> wireSystems = new LinkedHashMap<>();
    /** IDs that continue a wire started on an earlier snapshot page. */
    private Set<Integer> continuingSystems = new HashSet<>();
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

    private MessageWireSystems(Map<Integer, WireSystem> wireSystems, Set<Integer> continuing,
            boolean firstPage, boolean lastPage) {
        this.wireSystems = new LinkedHashMap<>(wireSystems);
        this.continuingSystems = new HashSet<>(continuing);
        this.firstPage = firstPage;
        this.lastPage = lastPage;
    }

    private MessageWireSystems(Map<Integer, WireSystem> wireSystems, boolean firstPage, boolean lastPage) {
        this(wireSystems, Set.of(), firstPage, lastPage);
    }

    /** Stream all wire parts, including systems longer than the old 16,384-part limit. */
    public static void sendSnapshot(Map<Integer, WireSystem> systems, Player player) {
        Map<Integer, WireSystem> page = new LinkedHashMap<>();
        Set<Integer> continuing = new HashSet<>();
        int partCount = 0;
        boolean first = true;
        for (Map.Entry<Integer, WireSystem> entry : systems.entrySet()) {
            List<WireSystem.WireElement> wires = entry.getValue().elements.stream()
                .filter(element -> element.type == WireSystem.WireElement.Type.WIRE_PART)
                .collect(Collectors.toList());
            int offset = 0;
            do {
                int end = Math.min(wires.size(), offset + MAX_ELEMENTS_PER_FRAGMENT);
                int count = end - offset;
                // Repeated IDs must be sent in different frames so each is assembled in order.
                if (!page.isEmpty() && (page.containsKey(entry.getKey()) || page.size() >= MAX_SYSTEMS
                    || partCount + count > MAX_ELEMENTS_PER_PACKET)) {
                    MessageManager.sendTo(new MessageWireSystems(page, continuing, first, false), player);
                    first = false;
                    page = new LinkedHashMap<>();
                    continuing = new HashSet<>();
                    partCount = 0;
                }
                page.put(entry.getKey(), new WireSystem(ImmutableList.copyOf(wires.subList(offset, end)), null));
                if (offset > 0) continuing.add(entry.getKey());
                partCount += count;
                offset = end;
            } while (offset < wires.size());
        }
        // Empty snapshots still revoke all no-longer-visible wire networks.
        MessageManager.sendTo(new MessageWireSystems(page, continuing, first, true), player);
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
            pb.writeBoolean(msg.continuingSystems.contains(wiresHashCode));
            List<WireSystem.WireElement> elements = wireSystem.elements.stream()
                    .filter(element -> element.type == WireSystem.WireElement.Type.WIRE_PART)
                    .collect(Collectors.toList());
            if (elements.size() > MAX_ELEMENTS_PER_FRAGMENT) {
                throw new IllegalStateException("Wire fragment exceeds packet budget: " + elements.size());
            }
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
            if (pb.readBoolean()) continuingSystems.add(wiresHashCode);
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
            for (Map.Entry<Integer, WireSystem> chunk : message.wireSystems.entrySet()) {
                int id = chunk.getKey();
                WireSystem previous = incomingSnapshot.get(id);
                if (message.continuingSystems.contains(id)) {
                    if (previous == null) {
                        // A continuation without its beginning is not a usable network snapshot.
                        incomingSnapshot.clear();
                        receivingSnapshot = false;
                        return;
                    }
                    ImmutableList.Builder<WireSystem.WireElement> merged = ImmutableList.builder();
                    merged.addAll(previous.elements);
                    merged.addAll(chunk.getValue().elements);
                    incomingSnapshot.put(id, new WireSystem(merged.build(), null));
                } else {
                    incomingSnapshot.put(id, chunk.getValue());
                }
            }
            if (message.lastPage) {
                ClientWireSystems.INSTANCE.wireSystems.clear();
                ClientWireSystems.INSTANCE.wireSystems.putAll(incomingSnapshot);
                incomingSnapshot.clear();
                receivingSnapshot = false;
            }
        });
        
    };
}
