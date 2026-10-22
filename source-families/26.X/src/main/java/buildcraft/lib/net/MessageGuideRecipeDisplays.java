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
        int start = buffer.readerIndex();
        for (int index = 0; index < size; index++) {
            Identifier recipeId = buffer.readIdentifier();
            RecipeDisplayEntry display = RecipeDisplayEntry.STREAM_CODEC.decode(buffer);
            decoded.add(new GuideRecipeDisplayCache.Entry(recipeId, display));
            if (buffer.readerIndex() - start > MAX_PACKET_BYTES) {
                throw new DecoderException("Guide recipe packet exceeded byte budget");
            }
        }
        entries = List.copyOf(decoded);
    }

    public static void toBytes(MessageGuideRecipeDisplays message, RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(message.partIndex);
        buffer.writeVarInt(message.partCount);
        buffer.writeVarInt(message.entries.size());
        int start = buffer.writerIndex();
        for (GuideRecipeDisplayCache.Entry entry : message.entries) {
            buffer.writeIdentifier(entry.recipeId());
            RecipeDisplayEntry.STREAM_CODEC.encode(buffer, entry.display());
            if (buffer.writerIndex() - start > MAX_PACKET_BYTES) {
                throw new IllegalArgumentException("Guide recipe packet exceeded byte budget");
            }
        }
    }

    public static MessageGuideRecipeDisplays create(ServerPlayer player) {
        RecipeManager manager = ((net.minecraft.server.level.ServerLevel) player.level()).getServer().getRecipeManager();
        List<GuideRecipeDisplayCache.Entry> displays = new ArrayList<>();
//? if neoforge && (>=26.3) {
        for (RecipeHolder<?> recipe : manager.recipeMap().values()) {
//? } else {
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
//?}
            if (recipe.value().getType() != RecipeType.CRAFTING
                && recipe.value().getType() != BCSiliconRecipes.ASSEMBLY_TYPE.get()) {
                continue;
            }
            Identifier recipeId = recipe.id().identifier();
            manager.listDisplaysForRecipe(recipe.id(), display ->
                displays.add(new GuideRecipeDisplayCache.Entry(recipeId, display)));
        }
        // Legacy single-packet API remains valid only for a small index.
        // Call sendAll() for a full index, never silently omit remaining recipes.
        if (displays.size() > MAX_ENTRIES_PER_PACKET) {
            throw new IllegalArgumentException("Use sendAll for large guide indexes");
        }
        return new MessageGuideRecipeDisplays(displays);
    }

    /** Stream the complete server index in byte-bounded packets; never silently truncate the index. */
    public static void sendAll(ServerPlayer player) {
        RecipeManager manager = ((net.minecraft.server.level.ServerLevel) player.level()).getServer().getRecipeManager();
        List<GuideRecipeDisplayCache.Entry> displays = new ArrayList<>();
//? if neoforge && (>=26.3) {
        for (RecipeHolder<?> recipe : manager.recipeMap().values()) {
//? } else {
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
//?}
            if (recipe.value().getType() != RecipeType.CRAFTING
                && recipe.value().getType() != BCSiliconRecipes.ASSEMBLY_TYPE.get()) continue;
            Identifier recipeId = recipe.id().identifier();
            manager.listDisplaysForRecipe(recipe.id(), display ->
                displays.add(new GuideRecipeDisplayCache.Entry(recipeId, display)));
            if (displays.size() > MAX_ENTRIES) throw new IllegalArgumentException("Guide recipe index too large");
        }
        List<List<GuideRecipeDisplayCache.Entry>> batches = new ArrayList<>();
        List<GuideRecipeDisplayCache.Entry> current = new ArrayList<>();
        int bytes = 0;
        RegistryFriendlyByteBuf measuring = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            for (GuideRecipeDisplayCache.Entry entry : displays) {
                measuring.clear();
                measuring.writeIdentifier(entry.recipeId());
                RecipeDisplayEntry.STREAM_CODEC.encode(measuring, entry.display());
                int estimated = measuring.writerIndex();
                if (estimated > MAX_PACKET_BYTES) throw new IllegalArgumentException("Oversized recipe display");
                if (!current.isEmpty() && (current.size() >= MAX_ENTRIES_PER_PACKET
                    || bytes + estimated > MAX_PACKET_BYTES)) {
                    batches.add(List.copyOf(current));
                    current.clear();
                    bytes = 0;
                }
                current.add(entry);
                bytes += estimated;
            }
        } finally {
            measuring.release();
        }
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
