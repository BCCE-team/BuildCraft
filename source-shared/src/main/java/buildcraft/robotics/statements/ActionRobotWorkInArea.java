package buildcraft.robotics.statements;

import buildcraft.lib.internal.area.IZone;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
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
import net.minecraft.world.item.ItemStack;

public class ActionRobotWorkInArea extends BCStatement implements IActionInternal {

    public enum AreaType { WORK, LOAD_UNLOAD }

    public final boolean loadUnload;

    public ActionRobotWorkInArea(boolean loadUnload) {
        super(loadUnload ? "buildcraft:robot.load_unload_area" : "buildcraft:robot.work_in_area");
        this.loadUnload = loadUnload;
    }

    @Override
    public int maxParameters() { return 1; }
    @Override
    public int minParameters() { return 1; }

    @Override
    public IStatementParameter createParameter(int index) {
        return new StatementParameterMapLocation();
    }

    @Override
    public Component getDescription() {
        return Component.translatable(loadUnload ? "gate.action.robot.load_unload_area" : "gate.action.robot.work_in_area");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {
        // The area constraint is read by the robot AI boards
    }

    public static IZone getArea(StatementSlot slot) {
        if (slot == null || slot.parameters == null || slot.parameters.length == 0) {
            return null;
        }
        IStatementParameter parameter = slot.parameters[0];
        if (!(parameter instanceof StatementParameterMapLocation mapParam)) {
            return null;
        }
        ItemStack stack = mapParam.getItemStack();
        if (stack.isEmpty()) return null;
        var location = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).read(stack);
        if (location.isEmpty()) return null;
        var zone = location.get().zone().orElse(null);
        return zone instanceof IZone internalZone ? internalZone : null;
    }

    public AreaType getAreaType() {
        return loadUnload ? AreaType.LOAD_UNLOAD : AreaType.WORK;
    }

    @Override
    public IStatement[] getPossible() {
        return new IStatement[] {
            BCRoboticsStatements.ACTION_ROBOT_WORK_IN_AREA,
            BCRoboticsStatements.ACTION_ROBOT_LOAD_UNLOAD_AREA
        };
    }

    @Override
    public ISprite getSprite() {
        return loadUnload ? BCRoboticsSprites.ACTION_ROBOT_LOAD_UNLOAD_AREA : BCRoboticsSprites.ACTION_ROBOT_WORK_IN_AREA;
    }
}
