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
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
//? if <1.20 {
import net.minecraft.world.item.CreativeModeTab;
//?} else {
/*?
import buildcraft.lib.CreativeTabManager;
?*/
//?}
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

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
                    if (!precise || Objects.equals(s.getTag(), target.getTag())) {
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

        public static Line fromNBT(CompoundTag data) {
            Line line = new Line();

            if (data != null && data.contains("st")) {
                ListTag l = data.getList("st", 10);
                int count = Math.min(l.size(), WIDTH);
                for (int i = 0; i < count; i++) {
                    line.stacks.set(i, ItemStack.of(l.getCompound(i)));
                }

                line.precise = data.getBoolean("Fp");
                line.byType = data.getBoolean("Ft");
                line.byMaterial = data.getBoolean("Fm");
            }

            return line;
        }

        public CompoundTag toNBT() {
            CompoundTag data = new CompoundTag();
            ListTag stackList = new ListTag();
            for (ItemStack stack1 : stacks) {
                CompoundTag stack = new CompoundTag();
                if (stack1 != null) {
                    stack1.save(stack);
                }
                stackList.add(stack);
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
                for (Item i : ForgeRegistries.ITEMS) {
                    NonNullList<ItemStack> examples = NonNullList.create();
                    //? if <1.20 {
                    i.fillItemCategory(CreativeModeTab.TAB_SEARCH, examples);
                    //?} else {
                    /*?
                    CreativeTabManager.addItemVariants(i, examples::add);
                    ?*/
                    //?}
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
        if (!stack.hasTag()) return false;
        for (Line l : getLines(stack)) {
            if (l.hasItems()) return true;
        }
        return false;
    }

    public static boolean isDefault(@Nonnull ItemStack stack) {
        if (!stack.hasTag()) return true;
        for (Line l : getLines(stack)) {
            if (!l.isDefault()) return false;
        }
        return true;
    }

    public static Line[] getLines(@Nonnull ItemStack item) {
        Line[] lines = new Line[HEIGHT];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = new Line();
        }

        if (!item.hasTag()) {
            return lines;
        }
        CompoundTag data = item.getTag();
        if (data != null && data.contains("written") && data.contains("lines")) {
            ListTag list = data.getList("lines", 10);
            int count = Math.min(list.size(), HEIGHT);
            for (int i = 0; i < count; i++) {
                lines[i] = Line.fromNBT(list.getCompound(i));
            }
        }
        return lines;
    }

    public static void saveLines(@Nonnull ItemStack stackList, Line[] lines) {
        boolean hasLine = false;

        for (Line l : lines) {
            if (!l.isDefault()) {
                hasLine = true;
                break;
            }
        }

        if (hasLine) {
            CompoundTag data = NBTUtilBC.getItemData(stackList);
            data.putBoolean("written", true);
            ListTag lineList = new ListTag();
            for (int i = 0; i < HEIGHT; i++) {
                Line saving = i < lines.length && lines[i] != null ? lines[i] : new Line();
                lineList.add(saving.toNBT());
            }
            data.put("lines", lineList);
        } else if (stackList.hasTag()) {
            CompoundTag data = NBTUtilBC.getItemData(stackList);
            // No non-default lines, so redundant list NBT can be removed
            data.remove("written");
            data.remove("lines");
            if (data.isEmpty()) {
                // We can safely remove the
                stackList.setTag(null);;
            }
        }
    }

    public static boolean matches(@Nonnull ItemStack stackList, @Nonnull ItemStack item) {
        CompoundTag data = stackList.getTag();
        if (data != null && data.contains("written") && data.contains("lines")) {
            ListTag list = data.getList("lines", 10);
            int count = Math.min(list.size(), HEIGHT);
            for (int i = 0; i < count; i++) {
                Line line = Line.fromNBT(list.getCompound(i));
                if (line.matches(item)) {
                    return true;
                }
            }
        }

        return false;
    }
}
