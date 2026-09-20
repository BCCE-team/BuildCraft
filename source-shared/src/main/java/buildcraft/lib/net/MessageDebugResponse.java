/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.debug.ClientDebuggables;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;

public class MessageDebugResponse {
    private static final int MAX_LINES = 1024;
    private static final int MAX_LINE_LENGTH = 1024;
    private final List<String> left = new ArrayList<>();
    private final List<String> right = new ArrayList<>();

    public MessageDebugResponse() {}

    public MessageDebugResponse(List<String> left, List<String> right) {
        this.left.addAll(left);
        this.right.addAll(right);
    }

    public static void toBytes(MessageDebugResponse msg, FriendlyByteBuf buffer) {
        if (msg.left.size() > MAX_LINES || msg.right.size() > MAX_LINES) {
            throw new IllegalStateException("Too many debugger response lines");
        }
        buffer.writeInt(msg.left.size());
        msg.left.forEach(line -> buffer.writeUtf(line, MAX_LINE_LENGTH));
        buffer.writeInt(msg.right.size());
        msg.right.forEach(line -> buffer.writeUtf(line, MAX_LINE_LENGTH));
    }

    public MessageDebugResponse(FriendlyByteBuf buffer) {
        int leftCount = NetworkSecurity.requireCount(buffer.readInt(), MAX_LINES, "debug left line count");
        for (int i = 0; i < leftCount; i++) left.add(buffer.readUtf(MAX_LINE_LENGTH));
        int rightCount = NetworkSecurity.requireCount(buffer.readInt(), MAX_LINES, "debug right line count");
        for (int i = 0; i < rightCount; i++) right.add(buffer.readUtf(MAX_LINE_LENGTH));
    }

    public static final BiConsumer<MessageDebugResponse, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
		if (ctx.get().side() != BCNetworkSide.CLIENT)
    		return;
        ClientDebuggables.SERVER_LEFT.clear();
        ClientDebuggables.SERVER_LEFT.addAll(message.left);
        ClientDebuggables.SERVER_RIGHT.clear();
        ClientDebuggables.SERVER_RIGHT.addAll(message.right);
        ctx.get().setPacketHandled(true);
    };
}
