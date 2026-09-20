/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.item;

import buildcraft.lib.internal.enums.EnumRedstoneChipset;
import buildcraft.lib.item.ItemByEnum;
import net.minecraft.world.item.ItemStack;

public class ItemRedstoneChipset extends ItemByEnum<EnumRedstoneChipset> {
	
    public ItemRedstoneChipset(Properties prop, EnumRedstoneChipset type) {
        super(prop, type);
        //setHasSubtypes(true);
    }

/*    @Override
    @OnlyI(Side.CLIENT)
    public void addModelVariants(TIntObjectHashMap<ModelResourceLocation> variants) {
        for (EnumRedstoneChipset type : EnumRedstoneChipset.values()) {
            addVariant(variants, type.ordinal(), type.getName());
        }
    }*/
    
    //? if >=1.21.11 {
    @Override
    public net.minecraft.network.chat.Component getName(ItemStack stack) {
        return net.minecraft.network.chat.Component.translatable(getDescriptionId(stack));
    }
    //?}

    @Override
	public String getDescriptionId(ItemStack p_41455_) {
		return "item.buildcraftsilicon.redstone_" + type.getSerializedName() + "_chipset";
	}
}
