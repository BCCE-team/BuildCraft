package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.DockingStation;

public class AIRobotGotoStationAndUnloadFluids extends AIRobot {
    private boolean requireAcceptAction;
    private DockingStation station;

    public AIRobotGotoStationAndUnloadFluids(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGotoStationAndUnloadFluids(EntityRobotBase robot, boolean requireAcceptAction) {
        this(robot);
        this.requireAcceptAction = requireAcceptAction;
    }

    /** Destination previously simulated and leased by the controller. */
    public AIRobotGotoStationAndUnloadFluids(EntityRobotBase robot,
                                              DockingStation station, boolean requireAcceptAction) {
        this(robot, requireAcceptAction);
        this.station = station;
    }

    @Override
    public void start() {
        if (station != null) startDelegateAI(new AIRobotGotoStation(robot, station));
        else startDelegateAI(new AIRobotGotoStationToUnloadFluids(robot, requireAcceptAction));
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationToUnloadFluids || ai instanceof AIRobotGotoStation) {
            if (ai.success()) {
                startDelegateAI(new AIRobotUnloadFluids(robot, requireAcceptAction));
            } else {
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotUnloadFluids) {
            setSuccess(ai.success());
            terminate();
        }
    }
}
