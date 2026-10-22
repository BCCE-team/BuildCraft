package buildcraft.api.v2.machine;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public interface MachineService {
    /** Creates a live component host for an addon-owned block entity, without a BCCE subclass. */
    default MachineHost createHost(MachineType type, MachineHostOwner owner) {
        throw new UnsupportedOperationException("Machine hosts are unavailable in this runtime");
    }
    Optional<MachineView> machine(Level level, BlockPos pos);

    /** A loaded work endpoint, including native BuildCraft machines that expose the work capability. */
    default Optional<Boolean> hasWork(Level level, BlockPos pos, Direction side) {
        return machine(level, pos).map(MachineView::hasWork);
    }

    /** A loaded heat endpoint; empty does not imply a writable temperature control. */
    default Optional<HeatPort> heat(Level level, BlockPos pos, Direction side) {
        return machine(level, pos).flatMap(view -> view.heatPort());
    }
}
