package buildcraft.robotics.statements;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.ITriggerInternal;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import buildcraft.robotics.BCRoboticsStatements;
import net.minecraft.network.chat.Component;

public class TriggerRobotLinked extends BCStatement implements ITriggerInternal {

    public final boolean reserved;

    public TriggerRobotLinked(boolean reserved) {
        super(reserved ? "buildcraft:robot.reserved" : "buildcraft:robot.linked");
        this.reserved = reserved;
    }

    @Override
    public Component getDescription() {
        return Component.translatable(reserved ? "gate.trigger.robot.reserved" : "gate.trigger.robot.linked");
    }

    @Override
    public boolean isTriggerActive(IStatementContainer container, IStatementParameter[] parameters) {
        DockingStation station = RobotStatementUtils.getStation(container);
        if (station == null) return false;
        return station.isTaken() && (reserved || station.isMainStation());
    }

    @Override
    public IStatement[] getPossible() {
        return new IStatement[] {
            BCRoboticsStatements.TRIGGER_ROBOT_LINKED,
            BCRoboticsStatements.TRIGGER_ROBOT_RESERVED
        };
    }

    @Override
    public ISprite getSprite() {
        return reserved ? BCRoboticsSprites.TRIGGER_ROBOT_RESERVED : BCRoboticsSprites.TRIGGER_ROBOT_LINKED;
    }
}
