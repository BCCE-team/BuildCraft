//? source if >=26.2
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.item;

import java.util.List;
import java.util.function.Consumer;


import buildcraft.transport.internal.pipe.IItemPipe;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.ItemStackUtil;

import buildcraft.lib.compat.minecraft.text.BCTextFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.compat.RegistryCompat;

public class ItemPipeHolder extends BlockItem implements IItemPipe {
    public static final String PIPE_COLOR_TAG = "color";

    public static int getPipeColorId(ItemStack stack) {
        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        if (tag == null || !tag.contains(PIPE_COLOR_TAG)) {
            return 0;
        }
        int colorId = NbtCompat.getInt(tag, PIPE_COLOR_TAG);
        return colorId >= 1 && colorId <= 16 ? colorId : 0;
    }

    public static DyeColor getPipeColor(ItemStack stack) {
        int colorId = getPipeColorId(stack);
        return colorId == 0 ? null : DyeColor.byId(colorId - 1);
    }

    public static void setPipeColor(ItemStack stack, DyeColor color) {
        if (color == null) {
            CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
            if (tag != null) {
                tag.remove(PIPE_COLOR_TAG);
                ItemStackUtil.setCustomData(stack, tag);
            }
        } else {
            CompoundTag tag = ItemStackUtil.getCustomData(stack);
            tag.putInt(PIPE_COLOR_TAG, color.getId() + 1);
            ItemStackUtil.setCustomData(stack, tag);
        }
    }

    public static void copyPipeColor(ItemStack source, ItemStack target) {
        setPipeColor(target, getPipeColor(source));
    }
    public final PipeDefinition definition;
    private String unlocalizedName;

    public ItemPipeHolder(PipeDefinition definition) {
        super(BCTransportBlocks.pipeHolder.get(), RegistryCompat.itemProperties(new Item.Properties()));
        this.definition = definition;
        this.unlocalizedName = definition.identifier.toLanguageKey("pipe");
    }



    public PipeDefinition getDefinition() {
        return definition;
    }
    


	
	

    // Misc usefulness

	public Component getName(ItemStack p_41458_) {
		return Component.translatable(unlocalizedName);
	}
		
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        String tipName = "tip." + unlocalizedName;
        if (net.minecraft.locale.Language.getInstance().has(tipName)) {
            tooltip.accept(Component.translatable(tipName).setStyle(BCTextFormat.GRAY.apply(Style.EMPTY)));
        }
        if (definition.flowType == PipeApi.flowFluids) {
            tooltip.accept(LocaleUtil.localizeFluidFlow(PipeApi.getFluidTransferInfo(definition).transferPerTick));
        } else if (definition.flowType == PipeApi.flowPower) {
            tooltip.accept(LocaleUtil.localizeMjFlow(PipeApi.getPowerTransferInfo(definition).transferPerTick));
        } else if (definition.flowType == PipeApi.flowForgeEnergy) {
            tooltip.accept(LocaleUtil.localizeFeFlow(PipeApi.getForgeEnergyTransferInfo(definition).transferPerTick));
        }
		super.appendHoverText(stack, context, display, tooltip, flag);
	}
	
	




	
}
