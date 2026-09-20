package buildcraft.robotics.statements;

import java.util.ArrayList;
import java.util.Collection;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementParameterItemStack;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.inventory.filter.ArrayStackOrListFilter;
import buildcraft.lib.inventory.filter.PassThroughStackFilter;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class ActionRobotFilterTool extends BCStatement implements IActionInternal {

    public ActionRobotFilterTool() {
        super("buildcraft:robot.work_filter_tool");
    }

    @Override
    public int maxParameters() { return 1; }
    @Override
    public int minParameters() { return 1; }

    @Override
    public IStatementParameter createParameter(int index) {
        return StatementParameterItemStack.EMPTY;
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.robot.filterTool");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {}

    public static ItemStack getToolFilter(IStatementParameter[] parameters) {
        if (parameters == null || parameters.length == 0) return ItemStack.EMPTY;
        IStatementParameter p = parameters[0];
        if (p instanceof StatementParameterItemStack s) return s.getItemStack();
        return ItemStack.EMPTY;
    }

    public static IStackFilter getGateFilter(DockingStation station) {
        Collection<ItemStack> stacks = new ArrayList<>();
        if (station != null) {
            for (StatementSlot slot : station.getActiveActions()) {
                if (slot.statement instanceof ActionRobotFilterTool && slot.parameters != null) {
                    ItemStack stack = getToolFilter(slot.parameters);
                    if (!stack.isEmpty()) {
                        stacks.add(stack.copy());
                    }
                }
            }
        }
        return stacks.isEmpty()
                ? new PassThroughStackFilter()
                : new ArrayStackOrListFilter(stacks.toArray(new ItemStack[0]));
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.ACTION_ROBOT_FILTER_TOOL;
    }
}
