/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.list;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nonnull;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.list.ListMatchAdapter;
import buildcraft.api.v2.list.ListMatchContext;
import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import buildcraft.lib.CreativeTabManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

public final class ListHandler {
    public static final int WIDTH = 9;
    public static final int HEIGHT = 2;

    public static class Line {
        public final NonNullList<ItemStack> stacks;
        public boolean precise, byType, byMaterial;

        public Line() {
            stacks = NonNullList.withSize(WIDTH, StackUtil.EMPTY);
        }

        /** Checks to see if this line is completely blank, and no data would be lost if this line was not saved. */
        public boolean isDefault() {
            if (precise || byType || byMaterial) return false;
            return !hasItems();
        }

        /** Checks to see if this line has any items */
        public boolean hasItems() {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) return true;
            }
            return false;
        }

        public boolean isOneStackMode() {
            return byType || byMaterial;
        }

        public boolean getOption(int id) {
            return id == 0 ? precise : (id == 1 ? byType : byMaterial);
        }

        public void toggleOption(int id) {
            if (!byType && !byMaterial && (id == 1 || id == 2)) {
                for (int i = 1; i < stacks.size(); i++) {
                    stacks.set(i, StackUtil.EMPTY);
                }
            }
            switch (id) {
                case 0:
                    precise = !precise;
                    break;
                case 1:
                    byType = !byType;
                    break;
                case 2:
                    byMaterial = !byMaterial;
                    break;
            }
        }

        public boolean matches(@Nonnull ItemStack target) {
            if (byType || byMaterial) {
                ItemStack compare = stacks.get(0);
                if (compare.isEmpty()) {
                    return false;
                }

                List<ListMatchAdapter> handlers = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.LIST_MATCH_ADAPTERS).values());
                ListMatchType type = getSortingType();
                boolean anyHandled = false;
                for (ListMatchAdapter h : handlers) {
                    ListMatchContext context = new ListMatchContext(type, compare, target, precise);
                    if (!h.supports(context)) {
                        continue;
                    }
                    anyHandled = true;
                    if (h.matches(context)) {
                        return true;
                    }
                }
                if (!anyHandled && type == ListMatchType.TYPE) {
                    // Modern item variants are generally separate Item instances.
                    // For an otherwise unhandled item, variation mode therefore
                    // falls back to the same item while ignoring damage and NBT.
                    return StackUtil.isMatchingItem(compare, target, false, false);
                }
            } else {
                for (ItemStack s : stacks) {
                    if (s.isEmpty() || !StackUtil.isMatchingItem(s, target, precise, false)) {
                        continue;
                    }
                    // StackUtil's legacy NBT comparison is asymmetric when the
                    // reference has no tag. Exact list matching must reject NBT
                    // present on either side unless both tags are identical.
                    if (!precise || ItemStack.isSameItemSameComponents(s, target)) {
                        return true;
                    }
                }
            }
            return false;
        }

        public ListMatchType getSortingType() {
            return byType ? (byMaterial ? ListMatchType.CLASS : ListMatchType.TYPE)
                : ListMatchType.MATERIAL;
        }

        public static Line fromNBT(CompoundTag data, HolderLookup.Provider registries) {
            Line line = new Line();

            if (data != null && data.contains("st")) {
                ListTag l = data.getList("st", 10);
                int count = Math.min(l.size(), WIDTH);
                for (int i = 0; i < count; i++) {
                    line.stacks.set(i, ItemStackUtil.parseOptional(registries, l.getCompound(i)));
                }

                line.precise = data.getBoolean("Fp");
                line.byType = data.getBoolean("Ft");
                line.byMaterial = data.getBoolean("Fm");
            }

            return line;
        }

        public CompoundTag toNBT(HolderLookup.Provider registries) {
            CompoundTag data = new CompoundTag();
            ListTag stackList = new ListTag();
            for (ItemStack stack1 : stacks) {
                stackList.add(stack1 == null ? new CompoundTag() : ItemStackUtil.saveOptional(stack1, registries));
            }
            data.put("st", stackList);
            data.putBoolean("Fp", precise);
            data.putBoolean("Ft", byType);
            data.putBoolean("Fm", byMaterial);
            return data;
        }

        public void setStack(int slotIndex, @Nonnull ItemStack stack) {
            if (slotIndex == 0 || (!byType && !byMaterial)) {
                if (stack.isEmpty()) {
                    stacks.set(slotIndex, StackUtil.EMPTY);
                } else {
                    stack = stack.copy();
                    stack.setCount(1);
                    stacks.set(slotIndex, stack);
                }
            }
        }

        @Nonnull
        public ItemStack getStack(int i) {
            if (i < 0 || i >= stacks.size()) {
                return StackUtil.EMPTY;
            } else {
                return stacks.get(i);
            }
        }
        public NonNullList<ItemStack> getExamples() {
            ItemStack firstStack = stacks.get(0);
            if (firstStack.isEmpty()) {
                return NonNullList.withSize(0, StackUtil.EMPTY);
            }
            NonNullList<ItemStack> stackList = NonNullList.create();
            List<ListMatchAdapter> handlers = new ArrayList<>(BuildCraftApi.registry(BuildCraftRegistries.LIST_MATCH_ADAPTERS).values());
            List<ListMatchAdapter> handlersCustom = new ArrayList<>();
            ListMatchType type = getSortingType();
            for (ListMatchAdapter h : handlers) {
                ListMatchContext context = new ListMatchContext(type, firstStack, ItemStack.EMPTY, false);
                if (h.supports(context)) {
                    java.util.Optional<java.util.List<ItemStack>> examples = h.examples(context);
                    if (examples.isPresent()) {
                        stackList.addAll(examples.get());
                    } else {
                        handlersCustom.add(h);
                    }
                }
            }
            if (handlersCustom.size() > 0) {
                for (Item i : BuiltInRegistries.ITEM) {
                    NonNullList<ItemStack> examples = NonNullList.create();
                    CreativeTabManager.addItemVariants(i, examples::add);
                    for (ItemStack s : examples) {
                        for (ListMatchAdapter mh : handlersCustom) {
                            if (mh.matches(new ListMatchContext(type, firstStack, s, false))) {
                                stackList.add(s);
                                break;
                            }
                        }
                    }
                }
            }
            Collections.shuffle(stackList);
            return stackList;
        }
    }

    private ListHandler() {

    }

    public static boolean hasItems(@Nonnull ItemStack stack) {
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(stack);
        if (data == null || !data.getBoolean("written") || !data.contains("lines")) {
            return false;
        }
        ListTag lines = data.getList("lines", Tag.TAG_COMPOUND);
        for (int lineIndex = 0; lineIndex < Math.min(lines.size(), HEIGHT); lineIndex++) {
            ListTag stacks = lines.getCompound(lineIndex).getList("st", Tag.TAG_COMPOUND);
            for (int stackIndex = 0; stackIndex < stacks.size(); stackIndex++) {
                if (!stacks.getCompound(stackIndex).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isDefault(@Nonnull ItemStack stack) {
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(stack);
        if (data == null || !data.getBoolean("written") || !data.contains("lines")) {
            return true;
        }
        ListTag lines = data.getList("lines", Tag.TAG_COMPOUND);
        for (int lineIndex = 0; lineIndex < Math.min(lines.size(), HEIGHT); lineIndex++) {
            CompoundTag line = lines.getCompound(lineIndex);
            if (line.getBoolean("Fp") || line.getBoolean("Ft") || line.getBoolean("Fm")) {
                return false;
            }
            ListTag stacks = line.getList("st", Tag.TAG_COMPOUND);
            for (int stackIndex = 0; stackIndex < stacks.size(); stackIndex++) {
                if (!stacks.getCompound(stackIndex).isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Line[] getLines(@Nonnull ItemStack item) {
        return getLines(item, ItemStackUtil.requireActiveRegistryProvider());
    }

    public static Line[] getLines(@Nonnull ItemStack item, @Nonnull HolderLookup.Provider registries) {
        Line[] lines = new Line[HEIGHT];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = new Line();
        }

        CompoundTag data = ItemStackUtil.getCustomDataOrNull(item);
        if (data == null) {
            return lines;
        }
        if (data != null && data.contains("written") && data.contains("lines")) {
            ListTag list = data.getList("lines", 10);
            int count = Math.min(list.size(), HEIGHT);
            for (int i = 0; i < count; i++) {
                lines[i] = Line.fromNBT(list.getCompound(i), registries);
            }
        }
        return lines;
    }

    public static void saveLines(@Nonnull ItemStack stackList, Line[] lines) {
        saveLines(stackList, lines, ItemStackUtil.requireActiveRegistryProvider());
    }

    public static void saveLines(@Nonnull ItemStack stackList, Line[] lines, @Nonnull HolderLookup.Provider registries) {
        boolean hasLine = false;

        for (Line l : lines) {
            if (!l.isDefault()) {
                hasLine = true;
                break;
            }
        }

        if (hasLine) {
            CompoundTag data = ItemStackUtil.getCustomData(stackList);
            data.putBoolean("written", true);
            ListTag lineList = new ListTag();
            for (int i = 0; i < HEIGHT; i++) {
                Line saving = i < lines.length && lines[i] != null ? lines[i] : new Line();
                lineList.add(saving.toNBT(registries));
            }
            data.put("lines", lineList);
            ItemStackUtil.setCustomData(stackList, data);
        } else if (ItemStackUtil.hasCustomData(stackList)) {
            CompoundTag data = ItemStackUtil.getCustomData(stackList);
            // No non-default lines, so redundant list NBT can be removed
            data.remove("written");
            data.remove("lines");
            ItemStackUtil.setCustomData(stackList, data);
        }
    }

    public static boolean matches(@Nonnull ItemStack stackList, @Nonnull ItemStack item) {
        HolderLookup.Provider registries = ItemStackUtil.getActiveRegistryProvider();
        return registries != null && matches(stackList, item, registries);
    }

    public static boolean matches(@Nonnull ItemStack stackList, @Nonnull ItemStack item, @Nonnull HolderLookup.Provider registries) {
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(stackList);
        if (data != null && data.contains("written") && data.contains("lines")) {
            ListTag list = data.getList("lines", 10);
            int count = Math.min(list.size(), HEIGHT);
            for (int i = 0; i < count; i++) {
                Line line = Line.fromNBT(list.getCompound(i), registries);
                if (line.matches(item)) {
                    return true;
                }
            }
        }

        return false;
    }
}
