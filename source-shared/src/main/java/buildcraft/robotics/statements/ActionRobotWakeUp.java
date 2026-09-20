package buildcraft.robotics.statements;

import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import net.minecraft.network.chat.Component;

public class ActionRobotWakeUp extends BCStatement implements IActionInternal {

    public ActionRobotWakeUp() {
        super("buildcraft:robot.wakeup");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.robot.wakeUp");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {
        DockingStation station = RobotStatementUtils.getStation(container);
        if (station == null) return;
        EntityRobotBase robot = station.robotTaking();
        if (robot == null) return;
        // Clear sleep override so robot resumes normal AI
        robot.setMainAIOverride(null);
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.ACTION_ROBOT_WAKEUP;
    }
}
