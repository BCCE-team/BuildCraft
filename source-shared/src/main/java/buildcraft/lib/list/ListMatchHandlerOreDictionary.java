/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.list;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.list.ListMatchHandlerBackend;
import net.minecraft.core.NonNullList;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Modern replacement for the 1.12 Ore Dictionary matcher.
 *
 * Forge item tags normally use paths such as {@code ingots/iron},
 * {@code dusts/iron}, or {@code storage_blocks/iron}. The parent path is the
 * legacy ore-dictionary "type", while the final path component is its material.
 */
public class ListMatchHandlerOreDictionary extends ListMatchHandlerBackend {
    private static final class TagParts {
        final String type;
        final String material;

        TagParts(String type, String material) {
            this.type = type;
            this.material = material;
        }
    }

    private static List<TagKey<Item>> getTags(ItemStack stack) {
        return stack.getTags().collect(Collectors.toList());
    }

    private static TagParts getParts(TagKey<Item> tag) {
        String path = tag.location().getPath();
        int split = path.lastIndexOf('/');
        if (split <= 0 || split >= path.length() - 1) {
            return null;
        }

        String type = path.substring(0, split);
        String material = path.substring(split + 1);

        // These Forge tags describe a category, tool class, armour slot, or
        // world-generation property rather than an item form/material pair.
        // Treating them as Ore Dictionary names creates very broad false
        // matches (for example every axe becoming the same "material").
        if (type.contains("ores_in_ground") || type.contains("ore_rates")
            || type.equals("tools") || type.startsWith("tools/")
            || type.equals("armors") || type.startsWith("armors/")) {
            return null;
        }
        return new TagParts(type, material);
    }

    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise) {
        List<TagKey<Item>> sourceTags = getTags(stack);
        if (sourceTags.isEmpty()) {
            return false;
        }
        List<TagKey<Item>> targetTags = getTags(target);

        if (type == ListMatchType.CLASS) {
            for (TagKey<Item> source : sourceTags) {
                if (targetTags.contains(source)) {
                    return true;
                }
            }
            return false;
        }

        for (TagKey<Item> source : sourceTags) {
            TagParts sourceParts = getParts(source);
            if (sourceParts == null) {
                continue;
            }
            for (TagKey<Item> targetTag : targetTags) {
                TagParts targetParts = getParts(targetTag);
                if (targetParts == null) {
                    continue;
                }
                if (type == ListMatchType.TYPE && Objects.equals(sourceParts.type, targetParts.type)) {
                    return true;
                }
                if (type == ListMatchType.MATERIAL && Objects.equals(sourceParts.material, targetParts.material)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        List<TagKey<Item>> tags = getTags(stack);
        if (type == ListMatchType.CLASS) {
            return !tags.isEmpty();
        }
        for (TagKey<Item> tag : tags) {
            if (getParts(tag) != null) {
                return true;
            }
        }
        return false;
    }
    @Override
    public NonNullList<ItemStack> getClientExamples(ListMatchType type, @Nonnull ItemStack stack) {
        // Returning null asks ListHandler to enumerate creative-search stacks
        // and test them with matches(). Returning an empty list here made the
        // preview look as if there were no matching tag equivalents at all.
        return null;
    }
}
