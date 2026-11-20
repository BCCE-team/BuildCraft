package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.scheduler.RobotLogisticsQueue;
import buildcraft.robotics.scheduler.RobotLogisticsScheduler;

public class AIRobotGotoStationAndLoad extends AIRobot {
    private IStackFilter filter;
    private int quantity;
    private boolean ignoreStationZone;
    private DockingStation reservedSource;
    private long sourceToken;

    public AIRobotGotoStationAndLoad(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGotoStationAndLoad(EntityRobotBase robot, IStackFilter filter, int quantity) {
        this(robot, filter, quantity, false);
    }

    public AIRobotGotoStationAndLoad(EntityRobotBase robot, IStackFilter filter, int quantity, boolean ignoreStationZone) {
        this(robot);
        this.filter = filter;
        this.quantity = quantity;
        this.ignoreStationZone = ignoreStationZone;
    }

    /** Directed source chosen by a controller; only the legacy load AI transfers items. */
    public AIRobotGotoStationAndLoad(EntityRobotBase robot, IStackFilter filter, int quantity,
                                     DockingStation source, long token) {
        this(robot, filter, quantity, false);
        reservedSource = source;
        sourceToken = token;
    }

    @Override
    public void start() {
        if (reservedSource == null) {
            startDelegateAI(new AIRobotGotoStationToLoad(robot, filter, quantity, ignoreStationZone));
        } else {
            startDelegateAI(new AIRobotGotoStation(robot, reservedSource));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationToLoad || ai instanceof AIRobotGotoStation) {
            if (filter != null && ai.success() && (reservedSource == null
                    || RobotLogisticsScheduler.ownsSource(robot, reservedSource,
                            RobotLogisticsQueue.Kind.ITEM_SOURCE, sourceToken))) {
                startDelegateAI(new AIRobotLoad(robot, filter, quantity));
            } else {
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotLoad) {
            setSuccess(ai.success());
            terminate();
        }
    }
}
