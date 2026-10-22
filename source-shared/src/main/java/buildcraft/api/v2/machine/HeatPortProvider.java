package buildcraft.api.v2.machine;

import java.util.Optional;
import net.minecraft.core.Direction;

/** Explicit sided heat access for an addon-owned block entity with no MachineHost. */
@FunctionalInterface
public interface HeatPortProvider {
    Optional<HeatPort> heatPort(Direction side);
}
