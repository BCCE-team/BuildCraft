package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.IStationFilter;
import buildcraft.lib.inventory.filter.AggregateFilter;
import buildcraft.robotics.statements.ActionRobotFilterTool;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.core.Direction;
import buildcraft.api.v2.OperationMode;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationProvideItems;
import net.minecraft.world.item.ItemStack;

/** Fetches a matching stack from a station-side inventory and equips it as the robot held item. */
public class AIRobotFetchAndEquipItemStack extends AIRobot {
    private static final int FETCH_AND_EQUIP_DELAY_TICKS = 10;
    private IStackFilter filter;
    private int maxStackSize = 1;
    private int delay;

    public AIRobotFetchAndEquipItemStack(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotFetchAndEquipItemStack(EntityRobotBase robot, IStackFilter filter) {
        this(robot, filter, 1);
    }

    public AIRobotFetchAndEquipItemStack(EntityRobotBase robot, IStackFilter filter, int maxStackSize) {
        this(robot);
        this.filter = filter == null ? null : new AggregateFilter(ActionRobotFilterTool.getGateFilter(robot.getLinkedStation()), filter);
        this.maxStackSize = Math.max(1, maxStackSize);
    }

    @Override
    public void start() {
        if (filter == null) {
            setSuccess(false);
            terminate();
            return;
        }
        if (robot.getDockingStation() == null || !canTakeSingle(robot.getDockingStation(), filter)) {
            startDelegateAI(new AIRobotSearchAndGotoStation(robot, new StationToolFilter(), robot.getZoneToLoadUnload()));
        }
    }

    @Override
    public void update() {
        if (filter == null || robot.getDockingStation() == null) {
            setSuccess(false);
            terminate();
            return;
        }
        if (delay++ > FETCH_AND_EQUIP_DELAY_TICKS) {
            ItemStack stack = takeMatching(robot, robot.getDockingStation(), filter, maxStackSize, true);
            if (!stack.isEmpty()) {
                robot.setItemInUse(stack);
                terminate();
            } else {
                delay = 0;
                startDelegateAI(new AIRobotSearchAndGotoStation(robot, new StationToolFilter(), robot.getZoneToLoadUnload()));
            }
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoStation && !ai.success()) {
            setSuccess(false);
            terminate();
        }
    }

    private boolean canTakeSingle(DockingStation station, IStackFilter filter) {
        return !takeMatching(robot, station, filter, maxStackSize, false).isEmpty();
    }

    private static ItemStack takeMatching(EntityRobotBase robot, DockingStation station,
                                          IStackFilter filter, int maxCount, boolean doTake) {
        if (station == null || filter == null || !RobotAutomationSupport.permitsInventory(robot, station, true,
                doTake ? OperationMode.EXECUTE : OperationMode.SIMULATE)) return ItemStack.EMPTY;
        Container inventory = station.getItemInput();
        if (inventory == null) return ItemStack.EMPTY;

        int remaining = Math.max(1, maxCount);
        ItemStack result = ItemStack.EMPTY;

        Direction side = station.getItemInputSide();
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !filter.matches(stack)) continue;
            // Reuse the station's provide gate contract, not only the recipe filter.
            if (!ActionStationProvideItems.canExtractItem(station, stack)
                || !ActionRobotFilter.canInteractWithItem(station, filter, ActionStationProvideItems.class)) continue;
            if (inventory instanceof WorldlyContainer sided) {
                if (side == null || !sided.canTakeItemThroughFace(slot, stack, side)) continue;
                boolean allowedSlot = false;
                for (int allowed : sided.getSlotsForFace(side)) {
                    if (allowed == slot) { allowedSlot = true; break; }
                }
                if (!allowedSlot) continue;
            }
            // Check compatibility before extraction; skipping a mismatched
            // source after removeItem() would destroy the returned item.
            if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, stack)) continue;

            int toTake = Math.min(remaining, stack.getCount());
            ItemStack actual = doTake ? inventory.removeItem(slot, toTake) : stack.copy();
            if (actual.isEmpty()) continue; // Extraction can be refused by foreign Containers.
            if (result.isEmpty()) {
                result = actual.copy();
                result.setCount(0);
            } else if (!ItemStack.isSameItemSameComponents(result, actual)) {
                continue;
            }
            int accepted = Math.min(toTake, actual.getCount());
            result.grow(accepted);
            remaining -= accepted;
        }

        if (doTake && !result.isEmpty()) {
            inventory.setChanged();
        }
        return result;
    }

    private class StationToolFilter implements IStationFilter {
        @Override
        public boolean matches(DockingStation station) {
            return canTakeSingle(station, filter);
        }
    }
}
