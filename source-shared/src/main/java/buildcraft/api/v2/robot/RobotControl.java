package buildcraft.api.v2.robot;

import buildcraft.api.v2.OperationMode;
import net.minecraft.core.BlockPos;
import java.util.Optional;

public interface RobotControl {
    boolean moveTo(BlockPos target, OperationMode mode);
    boolean assign(RobotTask task, OperationMode mode);
    boolean cancelTask(OperationMode mode);

    /** The state of the currently executing addon task, when available. */
    default Optional<RobotTaskLifecycle.Phase> taskPhase() { return Optional.empty(); }
}
