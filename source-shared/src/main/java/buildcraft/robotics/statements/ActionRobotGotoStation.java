package buildcraft.robotics.statements;

import java.util.List;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.lib.internal.statement.IActionInternal;
import buildcraft.lib.internal.statement.IStatementContainer;
import buildcraft.lib.internal.statement.IStatementParameter;
import buildcraft.core.statements.BCStatement;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.robotics.BCRoboticsSprites;
import buildcraft.robotics.ai.AIRobotGoAndLinkToDock;
import buildcraft.robotics.plug.RobotStationPluggable;
import buildcraft.transport.internal.pipe.IPipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class ActionRobotGotoStation extends BCStatement implements IActionInternal {

    public ActionRobotGotoStation() {
        super("buildcraft:robot.goto_station");
    }

    @Override
    public int maxParameters() {
        return 1;
    }

    @Override
    public IStatementParameter createParameter(int index) {
        return new StatementParameterMapLocation();
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gate.action.robot.gotoStation");
    }

    @Override
    public void actionActivate(IStatementContainer container, IStatementParameter[] parameters) {
        if (!(container.getTile() instanceof IPipeHolder holder) || RobotManager.registryProvider == null) {
            return;
        }

        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(holder.getPipeWorld());
        DockingStation requestedStation = getRequestedStation(parameters, registry);

        for (DockingStation station : getStations(holder)) {
            EntityRobotBase robot = station.robotTaking();
            if (robot == null) {
                continue;
            }
            DockingStation target = requestedStation == null ? station : requestedStation;
            if (target != null) {
                robot.setMainAIOverride(new AIRobotGoAndLinkToDock(robot, target));
            }
        }
    }

    private static List<DockingStation> getStations(IPipeHolder holder) {
        java.util.ArrayList<DockingStation> stations = new java.util.ArrayList<>();
        for (Direction side : Direction.values()) {
            if (holder.getPluggable(side) instanceof RobotStationPluggable plug) {
                DockingStation station = plug.getStation();
                if (station != null) {
                    stations.add(station);
                }
            }
        }
        return stations;
    }

    private static DockingStation getRequestedStation(IStatementParameter[] parameters, IRobotRegistry registry) {
        if (parameters == null || parameters.length == 0 || !(parameters[0] instanceof StatementParameterMapLocation param)) {
            return null;
        }
        ItemStack stack = param.getItemStack();
        if (stack.isEmpty()) return null;
        var location = BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).read(stack);
        if (location.isEmpty() || location.get().point().isEmpty()) return null;
        BlockPos pos = location.get().point().get();
        Direction side = location.get().pointSide().orElse(Direction.UP);
        return registry.getStation(pos, side);
    }

    @Override
    public ISprite getSprite() {
        return BCRoboticsSprites.ACTION_ROBOT_GOTO_STATION;
    }
}
