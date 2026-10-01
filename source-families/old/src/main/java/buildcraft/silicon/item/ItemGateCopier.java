package buildcraft.silicon.item;

import java.util.List;

import javax.annotation.Nonnull;

//? if <1.20 {
import buildcraft.core.BCCore;
//?}
import buildcraft.lib.misc.NBTUtilBC;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

public class ItemGateCopier extends Item {
    public static final String NBT_DATA = "gate_data";

    public ItemGateCopier() {
        //? if <1.20 {
        super(new Item.Properties().stacksTo(1).tab(BCCore.BUILDCRAFT_TAB));
        //?} else {
        /*?
        super(new Item.Properties().stacksTo(1));
        ?*/
        //?}
    }


    @Override
	public void appendHoverText(ItemStack stack, Level world, List<Component> tooltip, TooltipFlag flag) {
		super.appendHoverText(stack, world, tooltip, flag);
        if (getCopiedGateData(stack) != null) {
            tooltip.add(Component.translatable("buildcraft.item.nonclean.usage"));
        }
	}


	@Override
	public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (player.isDescending()) {
            return clearData(stack);
        }
        return new InteractionResultHolder<>(InteractionResult.PASS, stack);
	}

    private InteractionResultHolder<ItemStack> clearData(@Nonnull ItemStack stack) {
        if (getCopiedGateData(stack) == null) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        CompoundTag nbt = NBTUtilBC.getItemData(stack);
        nbt.remove(NBT_DATA);
        if (nbt.isEmpty()) {
            stack.setTag(null);;
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }


    public static CompoundTag getCopiedGateData(ItemStack stack) {
        return stack.getTagElement(NBT_DATA);
    }

    public static void setCopiedGateData(ItemStack stack, CompoundTag nbt) {
    	stack.getOrCreateTag().put(NBT_DATA, nbt);
    }
}
