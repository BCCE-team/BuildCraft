/**
 * Copyright (c) 2011-2017, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * The BuildCraft API is distributed under the terms of the MIT License.
 */
package buildcraft.robotics.internal.legacy.boards;

import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;

public abstract class RedstoneBoardRobot extends AIRobot implements IRedstoneBoard<EntityRobotBase> {
    public RedstoneBoardRobot(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public abstract RedstoneBoardRobotNBT getNBTHandler();

    @Override
    public final void updateBoard(EntityRobotBase container) {
    }

    /** Board logic may return a persisted API task instead of the board itself. */
    public AIRobot createWorkAI() {
        return this;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }
}
