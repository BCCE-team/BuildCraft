/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.item;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Serialises guide UI state without touching {@link net.minecraft.world.item.Item} or Forge registries.
 * <p>
 * Keeping the codec separate from {@link ItemGuide} also lets ordinary JVM tests verify per-stack NBT state
 * without bootstrapping the Minecraft runtime.
 */
final class GuideBookStateCodec {
    private static final String TAG_GUIDE_STATE = "BuildCraftGuideState";
    private static final String TAG_VERSION = "Version";
    private static final String TAG_SHOW_LORE = "ShowLore";
    private static final String TAG_SHOW_HINTS = "ShowHints";
    private static final String TAG_SORT_MODE = "SortMode";
    private static final String TAG_DOCUMENT = "Document";
    private static final String TAG_ENTRY = "Entry";
    private static final String TAG_SPREAD = "Spread";
    private static final int GUIDE_STATE_VERSION = 1;

    private GuideBookStateCodec() {
    }

    static ItemGuide.GuideState read(@Nullable CompoundTag root) {
        if (root == null || !root.contains(TAG_GUIDE_STATE, Tag.TAG_COMPOUND)) {
            return ItemGuide.GuideState.DEFAULT;
        }
        CompoundTag tag = root.getCompound(TAG_GUIDE_STATE);
        if (tag.getInt(TAG_VERSION) != GUIDE_STATE_VERSION) {
            return ItemGuide.GuideState.DEFAULT;
        }

        boolean showLore = !tag.contains(TAG_SHOW_LORE) || tag.getBoolean(TAG_SHOW_LORE);
        boolean showHints = tag.getBoolean(TAG_SHOW_HINTS);
        String sortMode = tag.contains(TAG_SORT_MODE) ? tag.getString(TAG_SORT_MODE) : "TYPE";
        boolean document = tag.getBoolean(TAG_DOCUMENT);
        ResourceLocation entry = null;
        if (document && tag.contains(TAG_ENTRY)) {
            try {
                entry = new ResourceLocation(tag.getString(TAG_ENTRY));
            } catch (RuntimeException ignored) {
                document = false;
            }
        }
        int spread = Math.max(0, tag.getInt(TAG_SPREAD));
        return new ItemGuide.GuideState(showLore, showHints, sortMode, document, entry, spread);
    }

    static void write(CompoundTag root, ItemGuide.GuideState state) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_VERSION, GUIDE_STATE_VERSION);
        tag.putBoolean(TAG_SHOW_LORE, state.showLore);
        tag.putBoolean(TAG_SHOW_HINTS, state.showHints);
        tag.putString(TAG_SORT_MODE, state.sortMode);
        tag.putBoolean(TAG_DOCUMENT, state.document && state.entry != null);
        if (state.document && state.entry != null) {
            tag.putString(TAG_ENTRY, state.entry.toString());
        }
        tag.putInt(TAG_SPREAD, Math.max(0, state.spread));
        root.put(TAG_GUIDE_STATE, tag);
    }
}
