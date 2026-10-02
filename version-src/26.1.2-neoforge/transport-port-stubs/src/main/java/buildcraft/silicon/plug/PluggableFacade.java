package buildcraft.silicon.plug;

import buildcraft.transport.internal.pluggable.PipePluggable;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Compile-only silicon bridge. It preserves the narrow transport-facing shape
 * without packaging a second facade implementation before silicon is ported.
 */
public class PluggableFacade extends PipePluggable {
    public int activeState = -1;
    public final States states = new States();

    public boolean setColour(DyeColor colour) {
        return false;
    }

    public static boolean isGlass(Object state) {
        return false;
    }

    public VoxelShape getBoundingBox() {
        return Shapes.empty();
    }

    public static final class States {
        public Phase[] phasedStates = new Phase[0];
    }

    public static final class Phase {
        public final StateInfo stateInfo = new StateInfo();
    }

    public static final class StateInfo {
        public Object state;
    }
}
