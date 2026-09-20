package buildcraft.robotics.statements;

import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import net.minecraft.network.chat.Component;

public class ActionStationRequestItemsMachine extends BCStatement implements IActionInternal {

    public ActionStationRequestItemsMachine() {
        super("buildcraft:station.provide_machine_request");
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.station.machineRequest");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {}

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.ACTION_STATION_MACHINE_REQUEST;
    }
}
