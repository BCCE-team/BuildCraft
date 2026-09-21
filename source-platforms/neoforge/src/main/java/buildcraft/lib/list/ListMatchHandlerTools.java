/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.list;

import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbility;

public class ListMatchHandlerTools extends ListMatchHandlerBackend {
    private static final int AXE = 1 << 0;
    private static final int PICKAXE = 1 << 1;
    private static final int SHOVEL = 1 << 2;
    private static final int HOE = 1 << 3;
    private static final int SWORD = 1 << 4;
    private static final int SHEARS = 1 << 5;

    private static final ItemAbility AXE_DIG = ItemAbility.get("axe_dig");
    private static final ItemAbility PICKAXE_DIG = ItemAbility.get("pickaxe_dig");
    private static final ItemAbility SHOVEL_DIG = ItemAbility.get("shovel_dig");
    private static final ItemAbility HOE_DIG = ItemAbility.get("hoe_dig");
    private static final ItemAbility SWORD_DIG = ItemAbility.get("sword_dig");
    private static final ItemAbility SHEARS_DIG = ItemAbility.get("shears_dig");

    private static int getToolTypes(ItemStack stack) {
        int types = 0;
        if (stack.is(ItemTags.AXES) || stack.canPerformAction(AXE_DIG)) types |= AXE;
        if (stack.is(ItemTags.PICKAXES) || stack.canPerformAction(PICKAXE_DIG)) types |= PICKAXE;
        if (stack.is(ItemTags.SHOVELS) || stack.canPerformAction(SHOVEL_DIG)) types |= SHOVEL;
        if (stack.is(ItemTags.HOES) || stack.canPerformAction(HOE_DIG)) types |= HOE;
        if (stack.is(ItemTags.SWORDS) || stack.canPerformAction(SWORD_DIG)) types |= SWORD;
        if (stack.canPerformAction(SHEARS_DIG)) types |= SHEARS;
        return types;
    }

    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise) {
        if (type != ListMatchType.TYPE) {
            return false;
        }

        int sourceTypes = getToolTypes(stack);
        int targetTypes = getToolTypes(target);
        if (sourceTypes == 0 || targetTypes == 0) {
            return false;
        }

        return precise ? sourceTypes == targetTypes : (targetTypes & sourceTypes) == sourceTypes;
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        return type == ListMatchType.TYPE && getToolTypes(stack) != 0;
    }
}
