package buildcraft.robotics.item;

import buildcraft.lib.misc.ItemStackUtil;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import buildcraft.robotics.BCRoboticsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import buildcraft.lib.item.ICreativeTabItemProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.level.Level;

public class ItemRedstoneBoard extends Item implements ICreativeTabItemProvider {
    public ItemRedstoneBoard(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(BoardEntry board) {
        ItemStack stack = new ItemStack(BCRoboticsItems.REDSTONE_BOARD.get());
        CompoundTag tag = new CompoundTag();
        board.nbt().createBoard(tag);
        tag.putString("board_key", board.key());
        tag.putString("board_color", board.boardColor());
        ItemStackUtil.setCustomData(stack, tag);
        stack.set(DataComponents.MAX_STACK_SIZE, board == BCRoboticsBoards.EMPTY ? 16 : 1);
        return stack;
    }

    @Override
    public void addCreativeTabItems(Consumer<ItemStack> output) {
        BCRoboticsBoards.init();
        for (BoardEntry board : BCRoboticsBoards.entriesWithEmpty()) {
            output.accept(createStack(board));
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        BoardEntry board = BCRoboticsBoards.getBoard(stack);
        return Component.translatable("item.buildcraftrobotics.redstone_board." + board.key());
    }

    @Override
    //? if >=1.21.5 {
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    //?} else {
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipLines, TooltipFlag flag) {
        Consumer<Component> tooltip = tooltipLines::add;
    //?}
        BoardEntry board = BCRoboticsBoards.getBoard(stack);
        if (board != BCRoboticsBoards.EMPTY) {
            String legacyKey = board.legacyLangKey();
            tooltip.accept(Component.translatable("buildcraft." + legacyKey).withStyle(ChatFormatting.BOLD));
            tooltip.accept(Component.translatable("buildcraft." + legacyKey + ".desc").withStyle(ChatFormatting.GRAY));
            if (board.isInDev()) {
                tooltip.accept(Component.translatable("tooltip.buildcraftrobotics.in_dev").withStyle(ChatFormatting.RED));
            }
            tooltip.accept(Component.translatable("tooltip.buildcraftrobotics.board.energy", BCRoboticsBoards.formatBoardEnergyCost(board.energyCost())).withStyle(ChatFormatting.GRAY));
        }
    }
}
