package buildcraft.silicon.item;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;

import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.Level;

public class ItemGateCopier extends Item {
    public static final String NBT_DATA = "gate_data";

    public ItemGateCopier() {
        super(new Item.Properties().stacksTo(1));
    }


    @Override
    //? if >=1.21.5 {
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    //?} else {
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipLines, TooltipFlag flag) {
        Consumer<Component> tooltip = tooltipLines::add;
    //?}
        //? if >=1.21.5 {
        super.appendHoverText(stack, context, display, tooltip, flag);
        //?} else {
        super.appendHoverText(stack, context, tooltipLines, flag);
        //?}
        if (getCopiedGateData(stack) != null) {
            tooltip.accept(Component.translatable("buildcraft.item.nonclean.usage"));
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
        CompoundTag nbt = ItemStackUtil.getCustomData(stack);
        nbt.remove(NBT_DATA);
        ItemStackUtil.setCustomData(stack, nbt);
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
    }


    public static CompoundTag getCopiedGateData(ItemStack stack) {
        CompoundTag data = ItemStackUtil.getCustomData(stack);
        return data.contains(NBT_DATA) ? data.getCompound(NBT_DATA) : null;
    }

    public static void setCopiedGateData(ItemStack stack, CompoundTag nbt) {
        CompoundTag data = ItemStackUtil.getCustomData(stack);
        data.put(NBT_DATA, nbt.copy());
        ItemStackUtil.setCustomData(stack, data);
    }
}
