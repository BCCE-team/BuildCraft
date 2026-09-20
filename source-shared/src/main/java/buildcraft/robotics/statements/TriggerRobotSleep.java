package buildcraft.robotics.statements;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.network.chat.Component;

public class TriggerRobotSleep extends BCStatement implements ITriggerInternal {

    public TriggerRobotSleep() {
        super("buildcraft:robot.sleep");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.robot.sleep");
    }

    @Override
    public boolean isTriggerActive(IStatementContainer container, IStatementParameter[] parameters) {
        DockingStation station = RobotStatementUtils.getStation(container);
        if (station == null) return false;
        EntityRobotBase robot = station.robotTaking();
        if (robot == null || robot.getDockingStation() != station) return false;
        if (robot instanceof EntityRobot er) {
            return er.isAsleepForRendering();
        }
        return false;
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.TRIGGER_ROBOT_SLEEP;
    }
}
