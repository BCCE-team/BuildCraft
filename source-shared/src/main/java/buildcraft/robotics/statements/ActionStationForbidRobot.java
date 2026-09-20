package buildcraft.robotics.statements;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatement;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import buildcraft.robotics.BCRoboticsStatements;
import net.minecraft.network.chat.Component;

public class ActionStationForbidRobot extends BCStatement implements IActionInternal {

    /** false = forbid, true = force/require */
    public final boolean invert;

    public ActionStationForbidRobot(boolean invert) {
        super(invert ? "buildcraft:station.force_robot" : "buildcraft:station.forbid_robot");
        this.invert = invert;
    }

    @Override
    public int maxParameters() { return 3; }
    @Override
    public int minParameters() { return 1; }

    @Override
    public IStatementParameter createParameter(int index) {
        return new StatementParameterRobot();
    }

    @Override
    public Component getDescription() {
        return Component.translatable(invert ? "gate.action.station.forceRobot" : "gate.action.station.forbidRobot");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {
        // Passive: robot AI checks via matchesRobot before docking
    }

    /** Returns true if the given robot is forbidden (or forced) by this action. */
    public boolean matchesRobot(IStatementParameter[] parameters, EntityRobotBase robot) {
        if (parameters == null) return true;
        for (IStatementParameter p : parameters) {
            if (StatementParameterRobot.matches(p, robot)) return true;
        }
        return false;
    }

    public static boolean isForbidden(DockingStation station, EntityRobotBase robot) {
        if (station == null || robot == null) {
            return false;
        }

        for (StatementSlot slot : station.getActiveActions()) {
            if (slot.statement instanceof ActionStationForbidRobot action) {
                if (action.invert ^ matchesSlot(slot, robot)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesSlot(StatementSlot slot, EntityRobotBase robot) {
        if (slot.parameters == null) {
            return false;
        }
        for (IStatementParameter parameter : slot.parameters) {
            if (StatementParameterRobot.matches(parameter, robot)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IStatement[] getPossible() {
        return new IStatement[] {
            BCRoboticsStatements.ACTION_STATION_FORBID_ROBOT,
            BCRoboticsStatements.ACTION_STATION_FORCE_ROBOT
        };
    }

    @Override
    public ISprite getSprite() {
        return invert ? BCRoboticsSprites.ACTION_STATION_ROBOT_MANDATORY : BCRoboticsSprites.ACTION_STATION_ROBOT_FORBIDDEN;
    }
}
