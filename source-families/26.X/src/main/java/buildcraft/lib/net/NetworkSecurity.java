/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * Copyright (c) 2026 the BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

/**
 * Small, allocation-free guards for data that crossed a network boundary.
 *
 * <p>This class intentionally contains no ownership/claim checks. BuildCraft ownership is execution attribution for
 * autonomous blocks/entities, not an access-control list for players. Packet authorization is based on the actual
 * sender, their currently open menu, distance/level context, and the normal loader/protection hooks.</p>
 */
public final class NetworkSecurity {
    private NetworkSecurity() {}

    public static int requireRange(int value, int minInclusive, int maxInclusive, String field) {
        if (value < minInclusive || value > maxInclusive) {
            throw new DecoderException("Invalid " + field + ": " + value
                + " (expected " + minInclusive + ".." + maxInclusive + ")");
        }
        return value;
    }

    public static int requireCount(int count, int maxInclusive, String field) {
        return requireRange(count, 0, maxInclusive, field);
    }

    public static void requireReadable(ByteBuf buffer, int bytes, String field) {
        if (bytes < 0 || bytes > buffer.readableBytes()) {
            throw new DecoderException("Truncated " + field + ": requested=" + bytes
                + " readable=" + buffer.readableBytes());
        }
    }

    /** Rejects framing smuggling and partially consumed packets without dumping attacker-controlled bytes to logs. */
    public static void requireFullyRead(ByteBuf buffer, String packet) {
        int remaining = buffer.readableBytes();
        if (remaining != 0) {
            throw new DecoderException("Trailing bytes in " + packet + ": " + remaining);
        }
    }
}
