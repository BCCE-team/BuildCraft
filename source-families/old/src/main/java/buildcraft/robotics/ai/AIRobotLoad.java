package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationProvideItems;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;

public class AIRobotLoad extends AIRobot {
    public static final int ANY_QUANTITY = -1;
    private static final int LOAD_DELAY_TICKS = 10;

    private IStackFilter filter;
    private int quantity;
    private int waitedCycles;

    public AIRobotLoad(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotLoad(EntityRobotBase robot, IStackFilter filter, int quantity) {
        this(robot);
        this.filter = filter;
        this.quantity = quantity;
    }

    @Override
    public void update() {
        if (filter == null) {
            setSuccess(false);
            terminate();
            return;
        }

        waitedCycles++;
        if (waitedCycles > LOAD_DELAY_TICKS) {
            setSuccess(load(robot, robot.getDockingStation(), filter, quantity, true));
            terminate();
        }
    }

    public static ItemStack takeSingle(DockingStation station, IStackFilter filter, boolean doTake) {
        if (station == null || filter == null) {
            return ItemStack.EMPTY;
        }

        Container container = station.getItemInput();
        if (container == null) {
            return ItemStack.EMPTY;
        }

        Direction side = station.getItemInputSide();
        for (int slot : getSlots(container, side)) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !canTake(container, slot, stack, side) || !filter.matches(stack)) {
                continue;
            }
            if (!canStationProvide(station, stack, filter)) {
                continue;
            }

            if (doTake) {
                // A modded Container may refuse extraction despite exposing an item.
                // Return only the stack the source actually removed.
                ItemStack extracted = container.removeItem(slot, 1);
                if (extracted.isEmpty()) continue;
                container.setChanged();
                return extracted;
            }
            ItemStack preview = stack.copy();
            preview.setCount(1);
            return preview;
        }
        return ItemStack.EMPTY;
    }

    public static boolean load(EntityRobotBase robot, DockingStation station, IStackFilter filter, int quantity, boolean doLoad) {
        if (robot == null || station == null || filter == null) {
            return false;
        }

        Container container = station.getItemInput();
        if (container == null) {
            return false;
        }

        int loaded = 0;
        int targetQuantity = quantity == ANY_QUANTITY ? robot.getMaxStackSize() : quantity;
        Direction side = station.getItemInputSide();
        for (int slot : getSlots(container, side)) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || !canTake(container, slot, stack, side) || !filter.matches(stack)) {
                continue;
            }
            if (!canStationProvide(station, stack, filter)) {
                continue;
            }

            int toMove = Math.min(stack.getCount(), targetQuantity - loaded);
            if (toMove <= 0) {
                return true;
            }

            ItemStack candidate = stack.copy();
            candidate.setCount(toMove);
            // First reserve capacity without mutating the robot, then debit the
            // source. Never insert a copied preview before removeItem succeeds.
            ItemStack simulatedRemainder = insertIntoRobot(robot, candidate, false);
            int canMove = candidate.getCount() - simulatedRemainder.getCount();
            if (canMove <= 0) continue;

            int moved;
            if (doLoad) {
                ItemStack extracted = container.removeItem(slot, canMove);
                if (extracted.isEmpty()) continue;
                ItemStack remainder = insertIntoRobot(robot, extracted, true);
                moved = extracted.getCount() - remainder.getCount();
                container.setChanged();
                // The preflight should make this impossible under normal
                // server-thread Container contracts, but preserve any excess.
                if (!remainder.isEmpty()) {
                    ItemStack sourceAfter = container.getItem(slot);
                    if (sourceAfter.isEmpty()) {
                        container.setItem(slot, remainder.copy());
                    } else if (sourceAfter.getCount() + remainder.getCount() <= container.getMaxStackSize()) {
                        // Only restore into a compatible stack; never merge
                        // different items just to hide a broken handler.
                        if (compatibleStacks(sourceAfter, remainder)) {
                            ItemStack restored = sourceAfter.copy();
                            restored.grow(remainder.getCount());
                            container.setItem(slot, restored);
                        }
                    }
                }
            } else {
                moved = canMove;
            }
            if (moved <= 0) continue;
            loaded += moved;

            if (loaded >= targetQuantity) {
                return true;
            }
        }
        return loaded > 0;
    }


    private static boolean canStationProvide(DockingStation station, ItemStack stack, IStackFilter requestedFilter) {
        return ActionStationProvideItems.canExtractItem(station, stack)
                && ActionRobotFilter.canInteractWithItem(station, requestedFilter, ActionStationProvideItems.class);
    }

    private static int[] getSlots(Container container, Direction side) {
        if (container instanceof WorldlyContainer sided && side != null) {
            return sided.getSlotsForFace(side);
        }
        int[] slots = new int[container.getContainerSize()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        return slots;
    }

    private static boolean canTake(Container container, int slot, ItemStack stack, Direction side) {
        if (container instanceof WorldlyContainer sided && side != null) {
            return sided.canTakeItemThroughFace(slot, stack, side);
        }
        return true;
    }

    private static boolean compatibleStacks(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameTags(a, b);
    }

    private static ItemStack insertIntoRobot(EntityRobotBase robot, ItemStack stack, boolean doInsert) {
        ItemStack remaining = stack.copy();

        for (int slot = 0; slot < robot.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = robot.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameTags(existing, remaining) && existing.isStackable()) {
                int limit = Math.min(existing.getMaxStackSize(), robot.getMaxStackSize());
                int move = Math.min(limit - existing.getCount(), remaining.getCount());
                if (move > 0) {
                    if (doInsert) {
                        existing.grow(move);
                        robot.setChanged();
                    }
                    remaining.shrink(move);
                }
            }
        }

        for (int slot = 0; slot < robot.getContainerSize() && !remaining.isEmpty(); slot++) {
            if (robot.getItem(slot).isEmpty() && robot.canPlaceItem(slot, remaining)) {
                int move = Math.min(Math.min(remaining.getMaxStackSize(), robot.getMaxStackSize()), remaining.getCount());
                if (doInsert) {
                    ItemStack inserted = remaining.copy();
                    inserted.setCount(move);
                    robot.setItem(slot, inserted);
                    robot.setChanged();
                }
                remaining.shrink(move);
            }
        }

        return remaining;
    }

    @Override
    public int getEnergyCost() {
        return 8;
    }
}
