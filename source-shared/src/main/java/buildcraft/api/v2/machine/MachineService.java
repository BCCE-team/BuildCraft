package buildcraft.api.v2.machine;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public interface MachineService {
    /** Creates a live component host for an addon-owned block entity, without a BCCE subclass. */
    default MachineHost createHost(MachineType type, MachineHostOwner owner) {
        throw new UnsupportedOperationException("Machine hosts are unavailable in this runtime");
    }
    Optional<MachineView> machine(Level level, BlockPos pos);
}
