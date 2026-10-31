package buildcraft.robotics.ai;

import java.util.HashSet;
import java.util.Set;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.RobotTrace;

public class AIRobotSearchAndGotoStation extends AIRobot {
    private IStationFilter filter;
    private IZone zone;
    private final Set<AIRobotSearchStation.StationKey> failedStations = new HashSet<>();
    private DockingStation currentTarget;
    private static final int WAIT_RETRY_TICKS = 20;
    private int retryTicks;
    private boolean waitingForStation;
    private int unavailableSearches;

    public AIRobotSearchAndGotoStation(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchAndGotoStation(EntityRobotBase robot, IStationFilter filter, IZone zone) {
        this(robot);
        this.filter = filter;
        this.zone = zone;
    }

    @Override
    public void start() {
        searchNextStation();
    }

    private void searchNextStation() {
        waitingForStation = false;
        if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_SEARCH_START", "excluded=%d", failedStations.size());
        startDelegateAI(new AIRobotSearchStation(robot, filter, zone, failedStations));
    }

    @Override
    public void update() {
        if (waitingForStation && --retryTicks <= 0) {
            searchNextStation();
        }
    }

    @Override
    public int getEnergyCost() {
        // Queueing for a station must not drain the robot's emergency energy reserve.
        return waitingForStation ? 0 : 1;
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchStation search) {
            if (ai.success()) {
                currentTarget = search.targetStation;
                startDelegateAI(new AIRobotGotoStation(robot, currentTarget));
            } else if (search.hasOccupiedMatch() || ++unavailableSearches <= (search.shouldRetry() ? 5 : 2)) {
                // Contention may last indefinitely; a disappearing/unconfigured station
                // only gets a bounded retry window before returning control to the board.
                waitingForStation = true;
                retryTicks = WAIT_RETRY_TICKS;
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_SEARCH_WAIT",
                        "all matching stations occupied or temporarily unavailable; retryIn=%d", retryTicks);
            } else {
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_SEARCH_FAILED", "no eligible stations");
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotGotoStation) {
            if (ai.success()) {
                setSuccess(true);
                terminate();
            } else {
                if (currentTarget != null) {
                    failedStations.add(AIRobotSearchStation.StationKey.of(currentTarget));
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_ROUTE_FAILED", "station=%s", currentTarget);
                }
                currentTarget = null;
                searchNextStation();
            }
        }
    }
}
