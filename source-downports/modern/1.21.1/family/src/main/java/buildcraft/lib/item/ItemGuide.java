/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.item;

import javax.annotation.Nullable;

import buildcraft.lib.client.guide.GuiGuide;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** The native BuildCraft guide book, using the original BC8 book artwork. */
public class ItemGuide extends Item {
    private static final ResourceLocation ADVANCEMENT = ResourceLocation.fromNamespaceAndPath("buildcraftcore", "guide");

    public ItemGuide(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT);
        } else {
            ClientHooks.openGuide(stack, hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        // Preserve the original BuildCraft 8 translation key.
        return "item.buildcraft.guide.name";
    }

    /** Reads the UI state stored on this exact guide stack. */
    public static GuideState readGuideState(ItemStack stack) {
        return GuideBookStateCodec.read(ItemStackUtil.getCustomDataOrNull(stack));
    }

    /** Writes UI state to this exact guide stack. */
    public static void writeGuideState(ItemStack stack, GuideState state) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemGuide)) {
            return;
        }
        net.minecraft.nbt.CompoundTag tag = ItemStackUtil.getCustomData(stack);
        GuideBookStateCodec.write(tag, state);
        ItemStackUtil.setCustomData(stack, tag);
    }

    /** Immutable payload shared by the screen, item NBT and the client-to-server update packet. */
    public static final class GuideState {
        public static final GuideState DEFAULT = new GuideState(true, false, "TYPE", false, null, 0);

        public final boolean showLore;
        public final boolean showHints;
        public final String sortMode;
        public final boolean document;
        public final @Nullable ResourceLocation entry;
        public final int spread;

        public GuideState(boolean showLore, boolean showHints, String sortMode, boolean document,
            @Nullable ResourceLocation entry, int spread) {
            this.showLore = showLore;
            this.showHints = showHints;
            this.sortMode = sortMode == null || sortMode.isEmpty() ? "TYPE" : sortMode;
            this.document = document && entry != null;
            this.entry = entry;
            this.spread = Math.max(0, spread);
        }
    }
    private static final class ClientHooks {
        private static void openGuide(ItemStack stack, InteractionHand hand) {
            GuiGuide.open(stack, hand);
        }
    }
}
