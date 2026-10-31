/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import io.netty.handler.codec.DecoderException;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.guide.GuideFaultIsolation;
import buildcraft.silicon.BCSiliconRecipes;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/**
 * Synchronises the crafting and assembly recipe displays used by the client Guide Book.
 *
 * The client recipe book exposes recipe displays rather than the server RecipeManager. It only
 * contains recipes the player has unlocked, which is intentionally insufficient for BuildCraft's reference guide.
 * The datapack recipe id is sent alongside each display so API/addon GuidePage.Recipe entries can resolve reliably.
 */
public final class MessageGuideRecipeDisplays {
    private static final int MAX_ENTRIES = 32768;
    private static final int MAX_PACKET_BYTES = 128 * 1024;
    private static final int MAX_ENTRIES_PER_PACKET = 128;
    private static final int MAX_LOGGED_SKIPS = 5;
    /** Negative prefix prevents silent misinterpretation of the new per-entry framed format. */
    private static final int WIRE_MAGIC = -1;
    private static final int WIRE_VERSION = 2;
    private static final int MAX_RAW_ENTRY_BYTES = MAX_PACKET_BYTES - 5;

    private final int partIndex;
    private final int partCount;

    private final List<GuideRecipeDisplayCache.Entry> entries;

    public MessageGuideRecipeDisplays(List<GuideRecipeDisplayCache.Entry> entries) {
        this(entries, 0, 1);
    }

    private MessageGuideRecipeDisplays(List<GuideRecipeDisplayCache.Entry> entries, int index, int count) {
        this.partIndex = index;
        this.partCount = count;
        if (entries.size() > MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Too many guide entries per packet");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many guide recipe displays: " + entries.size());
        }
        this.entries = List.copyOf(entries);
    }

    public MessageGuideRecipeDisplays(RegistryFriendlyByteBuf buffer) {
        int magic = buffer.readVarInt();
        int version = buffer.readVarInt();
        if (magic != WIRE_MAGIC || version != WIRE_VERSION) {
            throw new DecoderException("Unsupported Guide Book recipe sync format " + magic + "/" + version);
        }
        partIndex = buffer.readVarInt();
        partCount = buffer.readVarInt();
        if (partCount <= 0 || partCount > MAX_ENTRIES || partIndex < 0 || partIndex >= partCount) {
            throw new DecoderException("Invalid guide index chunk " + partIndex + "/" + partCount);
        }
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES_PER_PACKET) {
            throw new DecoderException("Invalid guide recipe display count " + size);
        }
        List<GuideRecipeDisplayCache.Entry> decoded = new ArrayList<>(size);
        int packetBytes = 0;
        for (int index = 0; index < size; index++) {
            // Each entry is length-framed. A missing modded recipe/slot display type must
            // not desynchronise the following entries or disconnect the client.
            int length = buffer.readVarInt();
            if (length < 1 || length > MAX_RAW_ENTRY_BYTES || length > buffer.readableBytes()
                || packetBytes + length > MAX_PACKET_BYTES) {
                GuideFaultIsolation.warn("recipe packet", "part=" + partIndex + " entry=" + index,
                    new IllegalArgumentException("Invalid framed recipe length " + length));
                // Invalid framing cannot be recovered safely. Keep all entries decoded before
                // this one and discard only the rest of this packet, not the whole connection.
                buffer.skipBytes(buffer.readableBytes());
                break;
            }
            packetBytes += length;
            byte[] payload = new byte[length];
            buffer.readBytes(payload);
            RegistryFriendlyByteBuf entryBuffer = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(payload), buffer.registryAccess());
            try {
                Identifier recipeId = entryBuffer.readIdentifier();
                RecipeDisplayEntry display = RecipeDisplayEntry.STREAM_CODEC.decode(entryBuffer);
                if (entryBuffer.readableBytes() != 0) {
                    throw new IllegalArgumentException("Trailing bytes in recipe entry");
                }
                decoded.add(new GuideRecipeDisplayCache.Entry(recipeId, display));
            } catch (RuntimeException | LinkageError exception) {
                GuideFaultIsolation.warn("recipe decode", "part=" + partIndex + " entry=" + index,
                    exception);
            } finally {
                entryBuffer.release();
            }
        }
        entries = List.copyOf(decoded);
    }

    /**
     * Encode into independent frames. The number of frames is written AFTER validation
     * so even a late modded codec failure cannot corrupt the surrounding packet.
     */
    public static void toBytes(MessageGuideRecipeDisplays message, RegistryFriendlyByteBuf buffer) {
        List<byte[]> frames = new ArrayList<>(message.entries.size());
        int used = 0;
        for (GuideRecipeDisplayCache.Entry entry : message.entries) {
            RegistryFriendlyByteBuf staging = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), buffer.registryAccess());
            try {
                staging.writeIdentifier(entry.recipeId());
                RecipeDisplayEntry.STREAM_CODEC.encode(staging, entry.display());
                int length = staging.writerIndex();
                int framed = length + varIntBytes(length);
                if (length < 1 || length > MAX_RAW_ENTRY_BYTES || used + framed > MAX_PACKET_BYTES) {
                    GuideFaultIsolation.warn("recipe encode", String.valueOf(entry.recipeId()),
                        new IllegalArgumentException("Oversized framed recipe display (" + length + " bytes)"));
                    continue;
                }
                byte[] frame = new byte[length];
                staging.getBytes(0, frame);
                frames.add(frame);
                used += framed;
            } catch (RuntimeException | LinkageError exception) {
                GuideFaultIsolation.warn("recipe encode", String.valueOf(entry.recipeId()), exception);
            } finally {
                staging.release();
            }
        }
        buffer.writeVarInt(WIRE_MAGIC);
        buffer.writeVarInt(WIRE_VERSION);
        buffer.writeVarInt(message.partIndex);
        buffer.writeVarInt(message.partCount);
        buffer.writeVarInt(frames.size());
        for (byte[] frame : frames) {
            buffer.writeVarInt(frame.length);
            buffer.writeBytes(frame);
        }
    }

    private static int varIntBytes(int number) {
        int bytes = 1;
        while ((number & ~0x7f) != 0) {
            bytes++;
            number >>>= 7;
        }
        return bytes;
    }

    /**
     * Measure recipe displays with the same network codec used by toBytes().
     * Mods can expose server-side crafting displays that have no registered network type;
     * these must be excluded before packet batching, rather than crashing world join.
     */
    private record MeasuredDisplay(GuideRecipeDisplayCache.Entry entry, int bytes) {}

    private static List<MeasuredDisplay> collectEncodableDisplays(ServerPlayer player) {
        RecipeManager manager = ((net.minecraft.server.level.ServerLevel) player.level()).getServer().getRecipeManager();
        List<GuideRecipeDisplayCache.Entry> displays = new ArrayList<>();
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
            if (displays.size() >= MAX_ENTRIES) {
                BCLog.logger.warn("Guide recipe index reached the {}-entry safety limit; remaining recipes omitted", MAX_ENTRIES);
                break;
            }
            Identifier recipeId = GuideFaultIsolation.value("recipe id", "recipe list", () ->
                recipe.id().identifier(), null);
            if (recipeId == null) continue;
            Boolean selected = GuideFaultIsolation.value("recipe type", String.valueOf(recipeId), () ->
                recipe.value().getType() == RecipeType.CRAFTING
                    || recipe.value().getType() == BCSiliconRecipes.ASSEMBLY_TYPE.get(), false);
            if (!selected) continue;
            // Some add-ons throw while enumerating their custom displays. Roll back only
            // this recipe; previously collected vanilla / BCCE entries remain intact.
            int before = displays.size();
            try {
                manager.listDisplaysForRecipe(recipe.id(), display -> {
                    if (displays.size() < MAX_ENTRIES) {
                        displays.add(new GuideRecipeDisplayCache.Entry(recipeId, display));
                    }
                });
            } catch (RuntimeException | LinkageError exception) {
                displays.subList(before, displays.size()).clear();
                GuideFaultIsolation.warn("recipe enumeration", String.valueOf(recipeId), exception);
            }
        }

        List<MeasuredDisplay> compatible = new ArrayList<>(displays.size());
        int skipped = 0;
        RegistryFriendlyByteBuf measuring = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            for (GuideRecipeDisplayCache.Entry entry : displays) {
                measuring.clear();
                int estimated;
                try {
                    measuring.writeIdentifier(entry.recipeId());
                    RecipeDisplayEntry.STREAM_CODEC.encode(measuring, entry.display());
                    int raw = measuring.writerIndex();
                    estimated = raw + varIntBytes(raw);
                } catch (RuntimeException | LinkageError exception) {
                    // A mod's RecipeDisplay.Type may exist only server-side (e.g. AE2's
                    // StorageCellUpgradeDisplay). Do not let one unsupported display kill the server.
                    skipped++;
                    if (skipped <= MAX_LOGGED_SKIPS) {
                        BCLog.logger.warn("Skipping unserializable Guide Book recipe display {}",
                            entry.recipeId(), exception);
                    }
                    continue;
                }
                if (estimated > MAX_PACKET_BYTES) {
                    skipped++;
                    if (skipped <= MAX_LOGGED_SKIPS) {
                        BCLog.logger.warn("Skipping oversized Guide Book recipe display {} ({} bytes)",
                            entry.recipeId(), estimated);
                    }
                    continue;
                }
                compatible.add(new MeasuredDisplay(entry, estimated));
            }
        } finally {
            measuring.release();
        }
        if (skipped > 0) {
            BCLog.logger.warn("Skipped {} unsupported Guide Book recipe display(s) out of {}",
                skipped, displays.size());
        }
        return compatible;
    }

    public static MessageGuideRecipeDisplays create(ServerPlayer player) {
        List<MeasuredDisplay> compatible = collectEncodableDisplays(player);
        // The single-packet API must not omit recipes silently. Use sendAll() for a full index.
        if (compatible.size() > MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Use sendAll for large guide indexes");
        }
        List<GuideRecipeDisplayCache.Entry> entries = new ArrayList<>(compatible.size());
        for (MeasuredDisplay display : compatible) entries.add(display.entry());
        return new MessageGuideRecipeDisplays(entries);
    }

    /** Stream the complete server index in byte-bounded packets; never silently truncate the index. */
    public static void sendAll(ServerPlayer player) {
        // Never let a broken third-party recipe index abort the join tick. An
        // empty sync is preferable to a world crash and also clears stale client data.
        List<MeasuredDisplay> compatible = GuideFaultIsolation.value("recipe index", "server", () ->
            collectEncodableDisplays(player), List.of());
        List<List<GuideRecipeDisplayCache.Entry>> batches = new ArrayList<>();
        List<GuideRecipeDisplayCache.Entry> current = new ArrayList<>();
        int bytes = 0;
        for (MeasuredDisplay display : compatible) {
            GuideRecipeDisplayCache.Entry entry = display.entry();
            int estimated = display.bytes();
            if (!current.isEmpty() && (current.size() >= MAX_ENTRIES_PER_PACKET
                || bytes + estimated > MAX_PACKET_BYTES)) {
                batches.add(List.copyOf(current));
                current.clear();
                bytes = 0;
            }
            current.add(entry);
            bytes += estimated;
        }
        // An empty first packet is intentional: it clears the client's stale recipe index.
        if (!current.isEmpty() || batches.isEmpty()) batches.add(List.copyOf(current));
        for (int i = 0; i < batches.size(); i++) {
            MessageManager.sendTo(new MessageGuideRecipeDisplays(batches.get(i), i, batches.size()), player);
        }
    }

    public static final BiConsumer<MessageGuideRecipeDisplays, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        if (context.side() != BCNetworkSide.CLIENT) {
            return;
        }
        context.enqueueWork(() -> GuideRecipeDisplayCache.appendChunk(message.entries,
            message.partIndex, message.partCount));
    };
}
