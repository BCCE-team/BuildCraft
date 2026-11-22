package buildcraft.robotics.item;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}

/** Marks the unfinished central robot-control system as experimental in item tooltips. */
public final class ItemRobotProcessor extends BlockItem {
    public ItemRobotProcessor(Block block, Item.Properties properties) {
        super(block, properties);
    }

    //? if >=1.21.5 {
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
    //?} else if >=1.21.1 {
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        Consumer<Component> lines = tooltip::add;
    //?} else {
    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.level.Level level,
            List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        Consumer<Component> lines = tooltip::add;
    //?}
        lines.accept(Component.literal("Experimental").withStyle(ChatFormatting.RED));
    }
}
