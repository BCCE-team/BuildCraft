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
    private boolean builderMaterialClaim;

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

    /** Directed Builder supply, independently tokened from logistics routes. */
    public AIRobotGotoStationAndLoad(EntityRobotBase robot, IStackFilter filter, int quantity,
                                     DockingStation source, long token, boolean builderMaterialClaim) {
        this(robot, filter, quantity, false);
        reservedSource = source;
        sourceToken = token;
        this.builderMaterialClaim = builderMaterialClaim;
    }

    @Override
    public void start() {
        if (builderMaterialClaim && !buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.owns(
                robot, reservedSource, sourceToken)) {
            setSuccess(false);
            terminate();
            return;
        }
        if (reservedSource == null) {
            startDelegateAI(new AIRobotGotoStationToLoad(robot, filter, quantity, ignoreStationZone));
        } else {
            startDelegateAI(new AIRobotGotoStation(robot, reservedSource));
        }
    }

    @Override
    public void end() {
        if (builderMaterialClaim)
            buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.release(robot, sourceToken);
    }

    @Override
    public void onUnload() {
        if (builderMaterialClaim)
            buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.release(robot, sourceToken);
        super.onUnload();
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationToLoad || ai instanceof AIRobotGotoStation) {
            if (filter != null && ai.success() && (reservedSource == null || (builderMaterialClaim
                    ? buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.owns(
                            robot, reservedSource, sourceToken)
                    : RobotLogisticsScheduler.ownsSource(robot, reservedSource,
                            RobotLogisticsQueue.Kind.ITEM_SOURCE, sourceToken)))) {
                startDelegateAI(builderMaterialClaim
                        ? new AIRobotLoad(robot, filter, quantity, reservedSource, sourceToken)
                        : new AIRobotLoad(robot, filter, quantity));
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
