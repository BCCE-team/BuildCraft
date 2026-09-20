/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import buildcraft.lib.item.ItemGuide;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.net.BCPacketContext;

/** Persists the current guide screen state in the exact guide stack used to open it. */
public final class MessageGuideState {
    private static final Set<String> VALID_SORT_MODES = Set.of("TYPE", "MODULE", "ALPHABETICAL");
    private static final int MAX_SPREAD = 1_000_000;
    private final InteractionHand hand;
    private final boolean showLore;
    private final boolean showHints;
    private final String sortMode;
    private final boolean document;
    private final @Nullable ResourceLocation entry;
    private final int spread;

    public MessageGuideState(InteractionHand hand, ItemGuide.GuideState state) {
        this.hand = hand;
        this.showLore = state.showLore;
        this.showHints = state.showHints;
        this.sortMode = state.sortMode;
        this.document = state.document;
        this.entry = state.entry;
        this.spread = state.spread;
    }

    public MessageGuideState(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        showLore = buffer.readBoolean();
        showHints = buffer.readBoolean();
        sortMode = buffer.readUtf(32);
        if (!VALID_SORT_MODES.contains(sortMode)) {
            throw new DecoderException("Invalid guide sort mode " + sortMode);
        }
        document = buffer.readBoolean();
        entry = buffer.readBoolean() ? buffer.readResourceLocation() : null;
        spread = NetworkSecurity.requireRange(buffer.readVarInt(), 0, MAX_SPREAD, "guide spread");
    }

    public static void toBytes(MessageGuideState message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.hand);
        buffer.writeBoolean(message.showLore);
        buffer.writeBoolean(message.showHints);
        buffer.writeUtf(message.sortMode, 32);
        buffer.writeBoolean(message.document);
        buffer.writeBoolean(message.entry != null);
        if (message.entry != null) {
            buffer.writeResourceLocation(message.entry);
        }
        buffer.writeVarInt(NetworkSecurity.requireRange(message.spread, 0, MAX_SPREAD, "guide spread"));
    }

    private ItemGuide.GuideState state() {
        return new ItemGuide.GuideState(showLore, showHints, sortMode, document, entry, spread);
    }

    public static final BiConsumer<MessageGuideState, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        if (context.side() != BCNetworkSide.SERVER) {
            return;
        }
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = player.getItemInHand(message.hand);
            if (!(stack.getItem() instanceof ItemGuide)) {
                return;
            }
            ItemGuide.writeGuideState(stack, message.state());
            player.getInventory().setChanged();
        });
    };
}
