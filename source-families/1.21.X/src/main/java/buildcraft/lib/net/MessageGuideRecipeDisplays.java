//? source if >=1.21.11
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
 * Minecraft 1.21.11 no longer exposes the server RecipeManager on the client. The vanilla ClientRecipeBook only
 * contains recipes the player has unlocked, which is intentionally insufficient for BuildCraft's reference guide.
 * The datapack recipe id is sent alongside each display so API/addon GuidePage.Recipe entries can resolve reliably.
 */
public final class MessageGuideRecipeDisplays {
    private static final int MAX_ENTRIES = 32768;

    private final List<GuideRecipeDisplayCache.Entry> entries;

    public MessageGuideRecipeDisplays(List<GuideRecipeDisplayCache.Entry> entries) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many guide recipe displays: " + entries.size());
        }
        this.entries = List.copyOf(entries);
    }

    public MessageGuideRecipeDisplays(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("Invalid guide recipe display count " + size);
        }
        List<GuideRecipeDisplayCache.Entry> decoded = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Identifier recipeId = buffer.readIdentifier();
            RecipeDisplayEntry display = RecipeDisplayEntry.STREAM_CODEC.decode(buffer);
            decoded.add(new GuideRecipeDisplayCache.Entry(recipeId, display));
        }
        entries = List.copyOf(decoded);
    }

    public static void toBytes(MessageGuideRecipeDisplays message, RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(message.entries.size());
        for (GuideRecipeDisplayCache.Entry entry : message.entries) {
            buffer.writeIdentifier(entry.recipeId());
            RecipeDisplayEntry.STREAM_CODEC.encode(buffer, entry.display());
        }
    }

    public static MessageGuideRecipeDisplays create(ServerPlayer player) {
        RecipeManager manager = ((net.minecraft.server.level.ServerLevel) player.level()).getServer().getRecipeManager();
        List<GuideRecipeDisplayCache.Entry> displays = new ArrayList<>();
        for (RecipeHolder<?> recipe : manager.getRecipes()) {
            if (recipe.value().getType() != RecipeType.CRAFTING
                && recipe.value().getType() != BCSiliconRecipes.ASSEMBLY_TYPE.get()) {
                continue;
            }
            Identifier recipeId = recipe.id().identifier();
            manager.listDisplaysForRecipe(recipe.id(), display ->
                displays.add(new GuideRecipeDisplayCache.Entry(recipeId, display)));
        }
        return new MessageGuideRecipeDisplays(displays);
    }

    public static final BiConsumer<MessageGuideRecipeDisplays, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        if (context.side() != BCNetworkSide.CLIENT) {
            return;
        }
        context.enqueueWork(() -> GuideRecipeDisplayCache.replace(message.entries));
    };
}
