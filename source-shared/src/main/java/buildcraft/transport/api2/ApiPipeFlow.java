package buildcraft.transport.api2;

import buildcraft.api.v2.pipe.PipeType;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.api.v2.pipe.PipeMedium;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.internal.pipe.PipeFlow;
import java.util.Collections;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceLocation;

/** Internal adapter. The actual addon flow is a persistent, synced PipeFlowComponent;
 * this class is only the legacy PipeFlow carrier and never owns its state.
 */
public class ApiPipeFlow extends PipeFlow {
    private final ResourceLocation flowId;

    public ApiPipeFlow(IPipe pipe, ResourceLocation flowId) {
        super(pipe);
        this.flowId = java.util.Objects.requireNonNull(flowId, "flowId");
    }

    public ResourceLocation flowId() { return flowId; }

    @Override
    public boolean canConnect(Direction face, PipeFlow other) {
        PipeType mine = pipe.getDefinition().getApiType();
        if (mine == null) return false;
        if (other instanceof ApiPipeFlow custom) {
            PipeType theirs = custom.pipe.getDefinition().getApiType();
            if (theirs == null) return false;
            // One registered flow may host different, incompatible media.
            return !Collections.disjoint(mine.customMedia(), theirs.customMedia())
                || !Collections.disjoint(mine.media(), theirs.media());
        }
        // The old item flow speaks IFlowItems. The private adapter below accepts
        // its hand-off through the public ItemPipePort, never through addon-private classes.
        return mine.media().contains(PipeMedium.ITEM) && this instanceof IFlowItems
            && other instanceof IFlowItems && pipe instanceof Pipe runtime
            && runtime.itemPipePort(face).isPresent();
    }

    @Override
    public boolean canConnect(Direction face, BlockEntity neighbour) {
        // A block entity is not by itself a valid port. Addons explicitly opt in
        // via PipeConnectionComponent / PipeConnectionRule ALLOW; no accidental
        // visual/transport links to unrelated block entities.
        return false;
    }
}
