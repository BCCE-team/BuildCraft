package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.scheduler.RobotLogisticsQueue;
import buildcraft.robotics.scheduler.RobotLogisticsScheduler;

public class AIRobotGotoStationAndLoadFluids extends AIRobot {
    private IFluidFilter filter;
    private DockingStation reservedSource;
    private long sourceToken;

    public AIRobotGotoStationAndLoadFluids(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGotoStationAndLoadFluids(EntityRobotBase robot, IFluidFilter filter) {
        this(robot);
        this.filter = filter;
    }

    /** Directed source chosen by a controller; only the legacy fluid AI transfers fluid. */
    public AIRobotGotoStationAndLoadFluids(EntityRobotBase robot, IFluidFilter filter,
                                           DockingStation source, long token) {
        this(robot, filter);
        reservedSource = source;
        sourceToken = token;
    }

    @Override
    public void start() {
        if (reservedSource == null) {
            startDelegateAI(new AIRobotGotoStationToLoadFluids(robot, filter));
        } else {
            startDelegateAI(new AIRobotGotoStation(robot, reservedSource));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationToLoadFluids || ai instanceof AIRobotGotoStation) {
            if (filter != null && ai.success() && (reservedSource == null
                    || RobotLogisticsScheduler.ownsSource(robot, reservedSource,
                            RobotLogisticsQueue.Kind.FLUID_SOURCE, sourceToken))) {
                startDelegateAI(new AIRobotLoadFluids(robot, filter));
            } else {
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotLoadFluids) {
            setSuccess(ai.success());
            terminate();
        }
    }
}
