package buildcraft.api.v2.robot;

import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import java.util.Optional;
import java.util.Objects;
import net.minecraft.world.level.Level;

public record RobotTaskContext(Level level, RobotHandle robot, AutomationActor actor) {
    public RobotTaskContext {
        Objects.requireNonNull(level, "level"); Objects.requireNonNull(robot, "robot"); Objects.requireNonNull(actor, "actor");
    }

    /** Acquires a robotics resource; leases are released when the executing task ends. */
    public Optional<RobotResourceLease> acquire(RobotResourceRequest request) {
        Objects.requireNonNull(request, "request");
        return BuildCraftApi.service(BuildCraftServices.ROBOTS).acquire(level, robot.id(), request);
    }
}
