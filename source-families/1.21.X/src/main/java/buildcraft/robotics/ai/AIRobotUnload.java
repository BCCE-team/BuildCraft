package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.transport.internal.IInjectable;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.inventory.filter.ArrayStackOrListFilter;
import buildcraft.robotics.DockingStationPipe;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationAcceptItems;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public class AIRobotUnload extends AIRobot {
    private static final int UNLOAD_DELAY_TICKS = 10;
    private int waitedCycles;
    private boolean requireAcceptAction;

    public AIRobotUnload(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotUnload(EntityRobotBase robot, boolean requireAcceptAction) {
        this(robot);
        this.requireAcceptAction = requireAcceptAction;
    }

    @Override
    public void update() {
        waitedCycles++;
        if (waitedCycles > UNLOAD_DELAY_TICKS) {
            if (unload(robot, robot.getDockingStation(), true, requireAcceptAction)) {
                waitedCycles = 0;
            } else {
                DockingStation station = robot.getDockingStation();
                if (robot.containsItems() && isTemporarilyBlocked(station)) {
                    waitedCycles = 0;
                    return;
                }
                setSuccess(!robot.containsItems());
                terminate();
            }
        }
    }

    public static boolean unload(EntityRobotBase robot, DockingStation station, boolean doUnload) {
        return unload(robot, station, doUnload, false);
    }

    public static boolean unload(EntityRobotBase robot, DockingStation station, boolean doUnload, boolean requireAcceptAction) {
        if (robot == null || station == null) return false;

        for (int slot = 0; slot < robot.getContainerSize(); slot++) {
            ItemStack stack = robot.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!canUnloadStack(station, stack, requireAcceptAction)) {
                continue;
            }

            int before = stack.getCount();
            ItemStack remaining = offer(station, stack, doUnload);
            int used = before - remaining.getCount();
            if (used > 0) {
                if (doUnload) robot.removeItem(slot, used);
                return true;
            }
        }

        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty()) {
            if (!canUnloadStack(station, held, requireAcceptAction)) {
                return false;
            }

            ItemStack remaining = offer(station, held, doUnload);
            if (remaining.getCount() < held.getCount()) {
                if (doUnload) {
                    robot.setItemInUse(remaining.isEmpty() ? ItemStack.EMPTY : remaining);
                }
                return true;
            }
        }

        return false;
    }

    private static boolean isTemporarilyBlocked(DockingStation station) {
        return station instanceof DockingStationPipe pipeStation && pipeStation.isItemOutputBusy();
    }

    private static boolean canUnloadStack(DockingStation station, ItemStack stack, boolean requireAcceptAction) {
        if (station == null || stack.isEmpty()) {
            return false;
        }

        boolean hasExplicitAcceptAction = false;
        for (StatementSlot slot : station.getActiveActions()) {
            if (slot.statement instanceof ActionStationAcceptItems) {
                hasExplicitAcceptAction = true;
                break;
            }
        }

        if (hasExplicitAcceptAction) {
            return ActionRobotFilter.canInteractWithItem(station, new ArrayStackOrListFilter(stack), ActionStationAcceptItems.class);
        }

        if (requireAcceptAction) {
            return false;
        }

        // Classic gates can explicitly filter/allow unloading, but a plain robot station mounted on a normal item pipe
        // is still a useful unload point. Do not use wooden item pipes as an implicit unload target though: in BC they
        // are the loading/extraction side of a station, and treating them as automatic output makes picker robots dump
        // cargo into the extraction line by accident. A wooden pipe can still be used for unloading when the player
        // explicitly enables station.accept_items on its gate.
        if (station instanceof DockingStationPipe pipeStation && pipeStation.isWoodenItemPipe()) {
            return false;
        }
        return station.getItemOutput() != null;
    }

    private static ItemStack offer(DockingStation station, ItemStack stack, boolean doAdd) {
        IInjectable output = station.getItemOutput();
        Direction side = station.getItemOutputSide();
        if (output != null && side != null && output.canInjectItems(side)) {
            ItemStack remaining = output.injectItem(stack.copy(), doAdd, side, null, 0.08D);
            if (remaining.getCount() < stack.getCount()) {
                return remaining;
            }
        }
        Container container = station.getItemInput();
        if (container != null) {
            return insertIntoContainer(container, stack, doAdd);
        }
        return stack;
    }

    private static ItemStack insertIntoContainer(Container container, ItemStack stack, boolean doAdd) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining) && existing.isStackable()) {
                int limit = Math.min(existing.getMaxStackSize(), container.getMaxStackSize());
                int move = Math.min(limit - existing.getCount(), remaining.getCount());
                if (move > 0) {
                    if (doAdd) {
                        existing.grow(move);
                        container.setChanged();
                    }
                    remaining.shrink(move);
                }
            }
        }
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (container.getItem(i).isEmpty() && container.canPlaceItem(i, remaining)) {
                int move = Math.min(Math.min(remaining.getMaxStackSize(), container.getMaxStackSize()), remaining.getCount());
                if (doAdd) {
                    ItemStack inserted = remaining.copy();
                    inserted.setCount(move);
                    container.setItem(i, inserted);
                    container.setChanged();
                }
                remaining.shrink(move);
            }
        }
        return remaining;
    }

    @Override
    public int getEnergyCost() {
        return 10;
    }
}
