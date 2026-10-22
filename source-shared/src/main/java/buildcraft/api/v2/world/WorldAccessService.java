package buildcraft.api.v2.world;

import buildcraft.api.v2.permission.AutomationActor;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** A common loader-neutral adapter for addons with their own blocks, machines, and robots. */
public interface WorldAccessService {
    /** Bind a read facade to a world. Its read and port operations still enforce load/thread guards. */
    WorldAccessContext open(Level level, BlockPos origin, AutomationActor actor);

    /**
     * Bind a callback-scoped read facade. All later reads and transfers are denied when
     * {@code valid} is false, including operations on previously obtained ports.
     * Callers must not use this to extend the lifetime of a callback.
     */
    WorldAccessContext scoped(Level level, BlockPos origin, AutomationActor actor, BooleanSupplier valid);
}
