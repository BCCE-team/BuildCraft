/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.list;

import java.util.EnumSet;

import javax.annotation.Nonnull;

import buildcraft.api.v2.list.ListMatchType;
import buildcraft.lib.list.ListMatchHandlerBackend;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

public class ListMatchHandlerArmor extends ListMatchHandlerBackend {
    private static EnumSet<EquipmentSlot> getArmorTypes(ItemStack stack) {
        EnumSet<EquipmentSlot> types = EnumSet.noneOf(EquipmentSlot.class);

        Equipable equipable = Equipable.get(stack);
        if (equipable != null) {
            EquipmentSlot slot = equipable.getEquipmentSlot();
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                types.add(slot);
            }
        }

        return types;
    }

    @Override
    public boolean matches(ListMatchType type, @Nonnull ItemStack stack, @Nonnull ItemStack target, boolean precise) {
        if (type == ListMatchType.TYPE) {
            EnumSet<EquipmentSlot> armorTypeIDSource = getArmorTypes(stack);
            if (armorTypeIDSource.size() > 0) {
                EnumSet<EquipmentSlot> armorTypeIDTarget = getArmorTypes(target);
                if (precise) {
                    return armorTypeIDSource.equals(armorTypeIDTarget);
                } else {
                    armorTypeIDSource.removeAll(EnumSet.complementOf(armorTypeIDTarget));
                    return armorTypeIDSource.size() > 0;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isValidSource(ListMatchType type, @Nonnull ItemStack stack) {
        return getArmorTypes(stack).size() > 0;
    }
}
