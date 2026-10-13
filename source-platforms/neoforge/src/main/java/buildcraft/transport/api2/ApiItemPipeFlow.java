package buildcraft.transport.api2;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.pipe.ItemInjectionRequest;
import buildcraft.api.v2.pipe.ItemPipePort;
import buildcraft.api.v2.pipe.ItemTransitData;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.internal.pipe.IFlowItems;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.pipe.Pipe;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
//? if >=26.3 {
/*?
import buildcraft.transport.internal.pipe.FluidAction;
?*/
//?} else {
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
//?}

/** Internal IFlowItems compatibility adapter for an addon-defined ItemPipePort.
 * Its state and transport algorithm are owned by the registered PipeFlowComponent.
 */
public final class ApiItemPipeFlow extends ApiPipeFlow implements IFlowItems {
    public ApiItemPipeFlow(IPipe pipe, net.minecraft.resources.ResourceLocation flowId) {
        super(pipe, flowId);
    }

    private Optional<ItemPipePort> port(Direction from) {
        if (!(pipe instanceof Pipe runtime) || from == null || !runtime.isServerSide()
            || !runtime.isActive()) return Optional.empty();
        return runtime.itemPipePort(from);
    }

    @Override public boolean canInjectItems(Direction from) {
        return port(from).isPresent();
    }

    @Override public ItemStack injectItem(ItemStack stack, boolean doAdd, Direction from,
                                           DyeColor color, double speed) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack rejected = stack.copy();
        Optional<ItemPipePort> target = port(from);
        if (target.isEmpty()) return rejected;
        try {
            ItemInjectionRequest request = new ItemInjectionRequest(stack, from,
                new ItemTransitData(Optional.ofNullable(color), Math.max(0, speed)));
            ItemTransferResult result = Objects.requireNonNull(
                target.get().inject(request, doAdd ? OperationMode.EXECUTE : OperationMode.SIMULATE),
                "Item port result");
            int accepted = result.transferredCount();
            if (result.requestedCount() != stack.getCount() || accepted < 0
                || accepted > stack.getCount() || (accepted != 0
                    && !StackUtil.canMerge(stack, result.transferred()))) {
                throw new IllegalStateException("Custom item pipe port returned an invalid transfer result");
            }
            rejected.shrink(accepted);
            return rejected;
        } catch (RuntimeException error) {
            BCLog.logger.warn("Custom item flow injection failed", error);
            return rejected;
        }
    }

    @Override public int tryExtractItems(int max, Direction from, DyeColor colour,
                                         IStackFilter filter, FluidAction action) {
        // Extracting inventory items into the custom flow is explicitly controlled by
        // its component via worldAccess/ItemPort; do not bypass its admission rules.
        return 0;
    }

    @Override public void insertItemsForce(ItemStack stack, Direction from, DyeColor colour, double speed) {
        if (!stack.isEmpty() && !injectItem(stack, true, from, colour, speed).isEmpty()) {
            throw new IllegalStateException("Custom item flow refused forced item insertion");
        }
    }

    @Override public void sendPhantomItem(ItemStack stack, Direction from, Direction to, DyeColor colour) {
        // Item-flow presentation is a separate client-side SPI (BC8-P09).
    }
}
