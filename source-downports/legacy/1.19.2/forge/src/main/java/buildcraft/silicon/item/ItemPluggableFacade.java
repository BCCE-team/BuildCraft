/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.item;

import java.util.List;
import javax.annotation.Nonnull;

import buildcraft.transport.internal.IItemPluggable;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.silicon.BCSilicon;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconPlugs;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.silicon.plug.FacadeType;
import buildcraft.silicon.plug.PluggableFacade;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

public class ItemPluggableFacade extends Item implements IItemPluggable {
    public ItemPluggableFacade() {
        super(new Item.Properties().stacksTo(64).tab(BCSilicon.tabFacades));
//        setHasSubtypes(true);
    }

    @Nonnull
    public ItemStack createItemStack(FacadeInstance state) {
        ItemStack item = new ItemStack(this);
        CompoundTag nbt = NBTUtilBC.getItemData(item);
        nbt.put("facade", state.writeToNbt());
        return item;
    }

    public static FacadeInstance getStates(@Nonnull ItemStack item) {
        CompoundTag nbt = NBTUtilBC.getItemData(item);

        String strPreview = nbt.getString("preview");
        if ("basic".equalsIgnoreCase(strPreview)) {
            return FacadeInstance.createSingle(FacadeStateManager.previewState, false);
        }

        if (!nbt.contains("facade") && nbt.contains("states")) {
            ListTag states = nbt.getList("states", Tag.TAG_COMPOUND);
            if (states.size() > 0) {
                // Only migrate if we actually have a facade to migrate.
                boolean isHollow = states.getCompound(0).getBoolean("isHollow");
                CompoundTag tagFacade = new CompoundTag();
                tagFacade.putBoolean("isHollow", isHollow);
                tagFacade.put("states", states);
                nbt.put("facade", tagFacade);
            }
        }

        return FacadeInstance.readFromNbt(nbt.getCompound("facade"));
    }


    @Override
    public PipePluggable onPlace(@Nonnull ItemStack stack, IPipeHolder holder, Direction side, Player player,
        InteractionHand hand) {
        if (!BCSiliconConfig.enableFacades) {
            return PipePluggable.EMPTY;
        }
        FacadeInstance fullState = getStates(stack);
        SoundUtil.playBlockPlace(holder.getPipeWorld(), holder.getPipePos(), fullState.phasedStates[0].stateInfo.state);
        return new PluggableFacade(BCSiliconPlugs.facade, holder, side, fullState);
    }
    
    

    @Override
	public void fillItemCategory(CreativeModeTab tab, NonNullList<ItemStack> subItems) {
		if (!BCSiliconConfig.enableFacades) {
			return;
		}
    	if(!this.allowedIn(tab))
    		return;
		// Add a single phased facade as a default
        // check if the data is present as we only process in post-init
        FacadeBlockStateInfo stone = FacadeStateManager.getInfoForBlock(Blocks.STONE);
        if (stone != null) {
            FacadePhasedState[] states = { //
                FacadeStateManager.getInfoForBlock(Blocks.STONE).createPhased(null), //
                FacadeStateManager.getInfoForBlock(Blocks.OAK_PLANKS).createPhased(DyeColor.RED), //
                FacadeStateManager.getInfoForBlock(Blocks.OAK_LOG).createPhased(DyeColor.CYAN),//
            };
            FacadeInstance inst = new FacadeInstance(states, false);
            subItems.add(createItemStack(inst));

            for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
                if (!ForgeRegistries.BLOCKS.containsValue(info.state.getBlock())) {
                    // Forge can de-register blocks if the server a client is connected to
                    // doesn't have the mods that created them.
                    continue;
                }
                if (info.isVisible) {
                    subItems.add(createItemStack(FacadeInstance.createSingle(info, false)));
                    subItems.add(createItemStack(FacadeInstance.createSingle(info, true)));
                }
            }
        }
	}

	@Override
	public Component getName(ItemStack stack) {
        FacadeInstance fullState = getStates(stack);
        if (fullState.type == FacadeType.Basic) {
            return Component.translatable(
                "item.buildcraftsilicon.facade.named",
                Component.translatable("item.buildcraftsilicon.facade"),
                getFacadeStateDisplayName(fullState.phasedStates[0])
            );
        } else {
            return Component.translatable("item.buildcraftsilicon.facade_phased");
        }
	}

    public static Component getFacadeStateDisplayName(FacadePhasedState state) {
        ItemStack assumedStack = state.stateInfo.requiredStack;
        return assumedStack.getHoverName();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
    	 FacadeInstance states = getStates(stack);
         if (states.type == FacadeType.Phased) {
             FacadePhasedState defaultState = null;
             for (FacadePhasedState state : states.phasedStates) {
                 if (state.activeColour == null) {
                     defaultState = state;
                     continue;
                 }
                 tooltip.add(Component.translatable(
                     "item.buildcraftsilicon.facade_phased.state",
                     LocaleUtil.localizeColourComponent(state.activeColour),
                     getFacadeStateDisplayName(state)
                 ));
             }
             if (defaultState != null) {
                 tooltip.add(1, Component.translatable(
                     "item.buildcraftsilicon.facade_phased.state_default",
                     getFacadeStateDisplayName(defaultState)
                 ));
             }
         } else {
             if (flag.isAdvanced()) {
                 tooltip.add(Component.literal(ForgeRegistries.BLOCKS.getKey(states.phasedStates[0].stateInfo.state.getBlock()).toString()));
             }
             String propertiesStart = ChatFormatting.GRAY + "" + ChatFormatting.ITALIC;
             FacadeBlockStateInfo info = states.phasedStates[0].stateInfo;
             BlockUtil.getPropertiesStringMap(info.state, info.varyingProperties)
                 .forEach((name, value) -> tooltip.add(Component.literal(propertiesStart + name + " = " + value)));
         }
	}

}
