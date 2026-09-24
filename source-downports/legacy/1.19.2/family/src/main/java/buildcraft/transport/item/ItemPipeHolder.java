/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.item;

import java.util.Collection;
import java.util.Collections;
import java.util.List;


import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.BCTransport;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.lib.misc.LocaleUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class ItemPipeHolder extends BlockItem implements IItemPipe {
    public static final String PIPE_COLOR_TAG = "color";

    public static int getPipeColorId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(PIPE_COLOR_TAG)) {
            return 0;
        }
        int colorId = tag.getInt(PIPE_COLOR_TAG);
        return colorId >= 1 && colorId <= 16 ? colorId : 0;
    }

    public static DyeColor getPipeColor(ItemStack stack) {
        int colorId = getPipeColorId(stack);
        return colorId == 0 ? null : DyeColor.byId(colorId - 1);
    }

    public static void setPipeColor(ItemStack stack, DyeColor color) {
        if (color == null) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(PIPE_COLOR_TAG);
                if (tag.isEmpty()) {
                    stack.setTag(null);
                }
            }
        } else {
            stack.getOrCreateTag().putInt(PIPE_COLOR_TAG, color.getId() + 1);
        }
    }

    public static void copyPipeColor(ItemStack source, ItemStack target) {
        setPipeColor(target, getPipeColor(source));
    }
    public final PipeDefinition definition;
    private String unlocalizedName;

    public ItemPipeHolder(PipeDefinition definition) {
        super(BCTransportBlocks.pipeHolder.get(), new Item.Properties().tab(BCTransport.tabPipes));
        this.definition = definition;
        this.unlocalizedName = definition.identifier.toLanguageKey("pipe");
    }



    @Override
    public PipeDefinition getDefinition() {
        return definition;
    }
    

	@Override
	public void fillItemCategory(CreativeModeTab tab, NonNullList<ItemStack> list) {
	      if (this.allowedIn(tab)) {
	          list.add(new ItemStack(this));
	       }
	}

	@Override
    public Collection<CreativeModeTab> getCreativeTabs() {
        return Collections.singletonList(BCTransport.tabPipes);
    }
	
	

    // Misc usefulness

		@Override
	public Component getName(ItemStack p_41458_) {
		return Component.translatable(unlocalizedName);
	}
		
	@Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
        String tipName = "tip." + unlocalizedName;
        if (I18n.exists(tipName)) {
            tooltip.add(Component.translatable(tipName).setStyle(Style.EMPTY.applyFormat(ChatFormatting.GRAY)));
        }
        if (definition.flowType == PipeApi.flowFluids) {
            tooltip.add(LocaleUtil.localizeFluidFlow(PipeApi.getFluidTransferInfo(definition).transferPerTick));
        } else if (definition.flowType == PipeApi.flowPower) {
            tooltip.add(LocaleUtil.localizeMjFlow(PipeApi.getPowerTransferInfo(definition).transferPerTick));
        } else if (definition.flowType == PipeApi.flowForgeEnergy) {
            tooltip.add(LocaleUtil.localizeFeFlow(PipeApi.getForgeEnergyTransferInfo(definition).transferPerTick));
        }
		super.appendHoverText(stack, world, tooltip, flag);
	}
	
	




	
}
