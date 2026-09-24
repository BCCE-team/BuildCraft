/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.item;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

import buildcraft.transport.internal.IItemPluggable;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableDefinition;
import buildcraft.lib.item.ICreativeTabItemProvider;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.silicon.BCSiliconPlugs;
import buildcraft.silicon.plug.PluggableLens;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public class ItemPluggableLens extends Item implements IItemPluggable, ICreativeTabItemProvider {
    public ItemPluggableLens() {
        super(new Item.Properties());
      //  setMaxDamage(0);
        //setHasSubtypes(true);
    }

    public static LensData getData(ItemStack stack) {
        return new LensData(stack);
    }

    @Nonnull
    public ItemStack getStack(DyeColor colour, boolean isFilter) {
        return getStack(new LensData(colour, isFilter));
    }

    @Nonnull
    public ItemStack getStack(LensData variant) {
        ItemStack stack = new ItemStack(this);
        variant.writeToStack(stack);
        return stack;
    }

    @Override
    public PipePluggable onPlace(@Nonnull ItemStack stack, IPipeHolder holder, Direction side, Player player,
        InteractionHand hand) {
        IPipe pipe = holder.getPipe();
        if (pipe == Pipe.EMPTY || !(pipe.getFlow() instanceof IFlowItems)) {
            return PipePluggable.EMPTY;
        }
        LensData data = getData(stack);
        SoundUtil.playBlockPlace(holder.getPipeWorld(), holder.getPipePos(), Blocks.STONE.defaultBlockState());
        PluggableDefinition def = BCSiliconPlugs.lens;
        return new PluggableLens(def, holder, side, data.colour, data.isFilter);
    }

    @Override
	public String getDescriptionId(ItemStack stack) {
        LensData data = new LensData(stack.getDamageValue());//getData(stack);
        String colour = data.colour == null ? "clear" : data.colour.getName();
        String first = data.isFilter ? "item.filter." : "item.lens.";
        return first+colour;
	}

/*	@Override
    @OnlyIn(Dist.CLIENT)
    public FontRenderer getFontRenderer(ItemStack stack) {
        return SpecialColourFontRenderer.INSTANCE;
    }*/

    @Override
    public void addCreativeTabItems(Consumer<ItemStack> output) {
        for (int i = 0; i < 34; i++) {
            ItemStack item = new ItemStack(this);
            item.setDamageValue(i);
            output.accept(item);
        }
    }

	public static class LensData {
        public final DyeColor colour;
        public final boolean isFilter;

        public LensData(DyeColor colour, boolean isFilter) {
            this.colour = colour;
            this.isFilter = isFilter;
        }

        public LensData(ItemStack stack) {
            this(stack.getDamageValue());
        }

        public LensData(int damage) {
            if (damage >= 32) {
                colour = null;
                isFilter = damage == 33;
            } else {
                colour = DyeColor.byId(damage & 15);
                isFilter = damage >= 16;
            }
        }

        public int getItemDamage() {
            if (colour == null) {
                return isFilter ? 33 : 32;
            } else {
                return colour.getId() + (isFilter ? 16 : 0);
            }
        }

        public ItemStack writeToStack(ItemStack stack) {
            stack.setDamageValue(getItemDamage());
            return stack;
        }
    }
}
