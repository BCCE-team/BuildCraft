package buildcraft.api.v2.machine;

import java.util.Optional;
import net.minecraft.core.Direction;

/** Sided laser receiver discovery for addon-owned block entities and multi-target machines. */
@FunctionalInterface
public interface LaserTargetProvider {
    /** Return empty to reject this incoming face; the query must not transfer power or load chunks. */
    Optional<LaserTarget> laserTarget(Direction incomingSide);
}
