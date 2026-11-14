/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.list;

import javax.annotation.Nonnull;
import buildcraft.api.v2.list.ListMatchType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;

/**
 * Native Fabric 26.3 list-tool category matcher.
 *
 * NeoForge's ItemAbility is a loader-specific provider interface. Fabric
 * exposes the canonical vanilla tool tags; Fabric addons may register their
 * tools in those tags (including the common shears tag). The exact BCCE bit
 * mask and precise/non-precise semantics remain unchanged.
 */
public final class ListMatchHandlerTools extends ListMatchHandlerBackend {
    private static final int AXE = 1 << 0;
    private static final int PICKAXE = 1 << 1;
    private static final int SHOVEL = 1 << 2;
    private static final int HOE = 1 << 3;
    private static final int SWORD = 1 << 4;
    private static final int SHEARS = 1 << 5;

    private static final TagKey<Item> COMMON_SHEARS =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "tools/shear"));
    private static final TagKey<Item> COMMON_SHEARS_PLURAL =
        TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "tools/shears"));

    private static int getToolTypes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int types = 0;
        if (stack.is(ItemTags.AXES)) types |= AXE;
        if (stack.is(ItemTags.PICKAXES)) types |= PICKAXE;
        if (stack.is(ItemTags.SHOVELS)) types |= SHOVEL;
        if (stack.is(ItemTags.HOES)) types |= HOE;
        if (stack.is(ItemTags.SWORDS)) types |= SWORD;
        if (stack.getItem() instanceof ShearsItem
            || stack.is(COMMON_SHEARS) || stack.is(COMMON_SHEARS_PLURAL)) types |= SHEARS;
        return types;
    }

    public boolean matches(ListMatchType type, @Nonnull ItemStack stack,
                           @Nonnull ItemStack target, boolean precise) {
        if (type != ListMatchType.TYPE) return false;
        int sourceTypes = getToolTypes(stack);
        int targetTypes = getToolTypes(target);
        if (sourceTypes == 0 || targetTypes == 0) return false;
        return precise
            ? sourceTypes == targetTypes
            : (targetTypes & sourceTypes) == sourceTypes;
    }

    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        return type == ListMatchType.TYPE && getToolTypes(stack) != 0;
    }
}
