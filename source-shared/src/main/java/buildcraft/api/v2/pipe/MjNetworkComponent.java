package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjTransferResult;
import net.minecraft.core.Direction;

/** Typed MJ demand/receive hook for components such as remote-transfer pipes. */
public interface MjNetworkComponent extends PipeComponent {
    default MjAmount queryDemand(MjNetworkContext context, Direction from, MjAmount maximum) {
        return MjAmount.ZERO;
    }

    default MjTransferResult receive(MjNetworkContext context, Direction from, MjAmount offered) {
        if (context.mode() == OperationMode.SIMULATE || context.mode() == OperationMode.EXECUTE) {
            return MjTransferResult.none(offered);
        }
        return MjTransferResult.none(offered);
    }
}
