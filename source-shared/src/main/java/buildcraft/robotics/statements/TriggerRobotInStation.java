package buildcraft.robotics.statements;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import net.minecraft.network.chat.Component;

public class TriggerRobotInStation extends BCStatement implements ITriggerInternal {

    public TriggerRobotInStation() {
        super("buildcraft:robot.in.station");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.trigger.robot.inStation");
    }

    @Override
    public boolean isTriggerActive(IStatementContainer container, IStatementParameter[] parameters) {
        DockingStation station = RobotStatementUtils.getStation(container);
        if (station == null) return false;
        EntityRobotBase robot = station.robotTaking();
        return robot != null && robot.getDockingStation() == station;
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.TRIGGER_ROBOT_IN_STATION;
    }
}
