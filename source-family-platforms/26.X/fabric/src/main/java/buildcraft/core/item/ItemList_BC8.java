/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.item;

import buildcraft.lib.platform.registry.FabricMenuOpening;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemLabelAdapter;
import buildcraft.api.v2.list.ItemListAdapter;
import buildcraft.core.list.ContainerList;
import buildcraft.lib.list.ListHandler;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.compat.minecraft.text.BCTextFormat;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import buildcraft.lib.compat.NbtCompat;

public class ItemList_BC8 extends Item implements ItemListAdapter, ItemLabelAdapter, MenuProvider {
    private static final Identifier ADVANCEMENT = Identifier.parse("buildcraftcore:list");
    public ItemList_BC8(Item.Properties prop) {//stack to 1
        super(prop);
    }
    
    

	public InteractionResult use(Level world, Player player, InteractionHand hand) {
        AdvancementUtil.unlockAdvancement(player, ADVANCEMENT);
        if (player instanceof ServerPlayer serverPlayer) {
            MenuProvider provider = new SimpleMenuProvider(
                (id, inventory, menuPlayer) -> new ContainerList(id, inventory, hand),
                getDisplayName()
            );
            FabricMenuOpening.open(serverPlayer, provider, buffer -> buffer.writeEnum(hand));
        }
        return InteractionResult.SUCCESS;
	}

/*    @Override
    public void addModelVariants(TIntObjectHashMap<ModelResourceLocation> variants) {
        addVariant(variants, 0, "clean");
        addVariant(variants, 1, "used");
    }*/

/*    @Override
    public int getMetadata(ItemStack stack) {
        return ListHandler.hasItems(StackUtil.asNonNull(stack)) ? 1 : 0;
    }*/
    

	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
		String name = getLabelName(stack);
        if (StringUtil.isNullOrEmpty(name)) return;
        tooltip.accept(Component.literal(name).withStyle(BCTextFormat.ITALIC));
	}



    public String getLabelName(@Nonnull ItemStack stack) {
        CompoundTag data = ItemStackUtil.getCustomDataOrNull(stack);
        return data == null ? "" : NbtCompat.getString(data, "label");
    }

    public boolean setLabelName(@Nonnull ItemStack stack, String name) {
        CompoundTag data = ItemStackUtil.getCustomData(stack);
        data.putString("label", name);
        ItemStackUtil.setCustomData(stack, data);
        return true;
    }

    public boolean supports(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == this;
    }

    public String label(ItemStack stack) {
        return getLabelName(stack);
    }

    public boolean setLabel(ItemStack stack, String label, OperationMode mode) {
        if (!supports(stack)) return false;
        if (mode == OperationMode.EXECUTE) {
            return setLabelName(stack, label);
        }
        return true;
    }

    public boolean matches(@Nonnull ItemStack stackList, @Nonnull ItemStack item) {
        return ListHandler.matches(stackList, item);
    }



	public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
		InteractionHand hand = player.getMainHandItem().getItem() == this
            ? InteractionHand.MAIN_HAND
            : InteractionHand.OFF_HAND;
        return new ContainerList(id, inv, hand);
	}



	public Component getDisplayName() {
		return Component.translatable(getDescriptionId());
	}
}
