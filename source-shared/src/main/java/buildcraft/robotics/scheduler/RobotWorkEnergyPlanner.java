package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import buildcraft.lib.balance.BalanceConfig;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * Bridges the pure work-energy estimate to BuildCraft's registered charging
 * docks and per-board balance multipliers. Read only, no chunk loads and no
 * physical dock reservations. Captured at most once per controller/robot/20t.
 */
public final class RobotWorkEnergyPlanner {
    public record Context(List<RobotWorkEnergyBudget.Charger> chargers,
                          double speedMultiplier, double costMultiplier) {
        public RobotWorkEnergyBudget.Estimate evaluate(EntityRobotBase robot,
                                                       BlockPos target, int usefulBlocks) {
            return RobotWorkEnergyBudget.estimate(
                    new RobotWorkEnergyBudget.Point(robot.getX(), robot.getY(), robot.getZ()),
                    new RobotWorkEnergyBudget.Point(target.getX() + 0.5D,
                            target.getY() + 0.5D, target.getZ() + 0.5D),
                    chargers, robot.getEnergy(), EntityRobotBase.SAFETY_ENERGY,
                    speedMultiplier, costMultiplier, usefulBlocks);
        }
    }

    private RobotWorkEnergyPlanner() { }

    public static Context capture(EntityRobotBase robot) {
        List<RobotWorkEnergyBudget.Charger> chargers = new ArrayList<>();
        Set<DockingStation> known = new HashSet<>();
        DockingStation home = robot.getLinkedStation();
        if (home != null) known.add(home);
        if (robot.getRegistry() != null) known.addAll(robot.getRegistry().getStations());
        Level level = robotLevel(robot);
        for (DockingStation station : known) {
            if (station == null || !station.isInitialized() || !station.providesPower()
                    || ActionStationForbidRobot.isForbidden(station, robot)
                    || !level.hasChunkAt(new BlockPos(station.x(), station.y(), station.z()))) continue;
            Direction side = station.side();
            double x = station.x() + 0.5D + (side == null ? 0 : 0.5D * side.getStepX());
            double y = station.y() + 0.5D + (side == null ? 0 : 0.5D * side.getStepY());
            double z = station.z() + 0.5D + (side == null ? 0 : 0.5D * side.getStepZ());
            chargers.add(new RobotWorkEnergyBudget.Charger(
                    new RobotWorkEnergyBudget.Point(x, y, z),
                    !station.isTaken() || station.robotIdTaking() == robot.getRobotId()));
        }
        String key = robot.getBoard() == null ? "empty" : robot.getBoard().getNBTHandler().getID();
        return new Context(List.copyOf(chargers),
                BalanceConfig.robot(key, "movement_speed_multiplier", 1.0),
                BalanceConfig.robot(key, "energy_cost_multiplier", 1.0));
    }

    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }
}
