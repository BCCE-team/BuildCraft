/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.list;

import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.list.ListMatchHandlerBackend;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;

public class ListMatchHandlerClass extends ListMatchHandlerBackend {
    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise) {
        if (type != ListMatchType.TYPE) {
            return false;
        }

        // ItemFood no longer exists in modern Minecraft. Item#isEdible is the
        // closest equivalent to the "all food items are one type" compatibility rule.
        if (stack.has(DataComponents.FOOD)) {
            return target.has(DataComponents.FOOD);
        }

        return false;
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        if (type != ListMatchType.TYPE) {
            return false;
        }
        return stack.has(DataComponents.FOOD);
    }
}
