package buildcraft.robotics.statements;

import javax.annotation.Nullable;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementParameterItemStack;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.item.ItemRobot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public class StatementParameterRobot extends StatementParameterItemStack {

    public static final String TAG = "buildcraft:robot";

    public StatementParameterRobot() {
        super();
    }

    public StatementParameterRobot(ItemStack stack) {
        super(stack);
    }

    public StatementParameterRobot(CompoundTag nbt) {
        super(nbt);
    }

    @Override
    public String getUniqueTag() {
        return TAG;
    }

    @Override
    public StatementParameterRobot onClick(
        buildcraft.lib.internal.statement.IStatementContainer source,
        buildcraft.lib.internal.statement.IStatement stmt,
        ItemStack clicked,
        buildcraft.lib.internal.statement.StatementMouseClick mouse
    ) {
        if (clicked.isEmpty()) {
            return new StatementParameterRobot();
        }
        ItemStack copy = clicked.copy();
        copy.setCount(1);
        return new StatementParameterRobot(copy);
    }

    /** Matches robot board items, list filters, and robot wearables as BC7/BC8 did. */
    public static boolean matches(@Nullable IStatementParameter param, EntityRobotBase robot) {
        if (!(param instanceof StatementParameterRobot robotParameter) || robot == null) {
            return false;
        }

        ItemStack filterStack = robotParameter.getItemStack();
        if (filterStack.isEmpty()) {
            return false;
        }

        ItemStack robotStack = createRobotStack(robot);
        var lists = BuildCraftApi.service(BuildCraftServices.ITEM_LISTS);
        if (lists.isList(filterStack)) {
            if (!robotStack.isEmpty() && lists.matches(filterStack, robotStack)) {
                return true;
            }
            if (robot instanceof EntityRobot entityRobot) {
                for (ItemStack wearable : entityRobot.getWearables()) {
                    if (!wearable.isEmpty() && lists.matches(filterStack, wearable)) {
                        return true;
                    }
                }
            }
            return false;
        }

        if (filterStack.getItem() instanceof ItemRobot) {
            String filterBoard = BCRoboticsBoards.getRobotBoard(filterStack).id();
            return robot.getBoard() != null
                && robot.getBoard().getNBTHandler() != null
                && filterBoard.equals(robot.getBoard().getNBTHandler().getID());
        }

        if (robot instanceof EntityRobot entityRobot) {
            for (ItemStack wearable : entityRobot.getWearables()) {
                if (!wearable.isEmpty() && ItemStack.isSameItemSameComponents(filterStack, wearable)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ItemStack createRobotStack(EntityRobotBase robot) {
        if (robot instanceof EntityRobot entityRobot) {
            return entityRobot.asItemStack();
        }
        if (robot.getBoard() == null || robot.getBoard().getNBTHandler() == null) {
            return ItemStack.EMPTY;
        }
        return ItemRobot.createRobotStack(
            BCRoboticsBoards.getById(robot.getBoard().getNBTHandler().getID()),
            robot.getEnergy()
        );
    }

    public static StatementParameterRobot readFromNbt(CompoundTag nbt) {
        return new StatementParameterRobot(nbt);
    }
}
