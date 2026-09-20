package buildcraft.api.v2.machine;

import buildcraft.api.v2.area.AreaProvider;
import net.minecraft.core.BlockPos;

/** Area provider with the machine-side validity check equivalent to ITileAreaProvider. */
public interface MachineAreaView extends AreaProvider {
    boolean validFrom(BlockPos machinePosition);
}
