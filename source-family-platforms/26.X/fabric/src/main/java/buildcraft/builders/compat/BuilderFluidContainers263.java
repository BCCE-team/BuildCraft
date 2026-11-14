/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.builders.compat;

import java.util.ArrayList;
import java.util.List;
import buildcraft.lib.compat.neoforge263.fluids.FluidUtil;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandlerItem;
import buildcraft.lib.fluid.FluidStack;
import net.minecraft.world.item.ItemStack;

/** Reads fluid requirements from copies of placement items without mutating the real inventory. */
public final class BuilderFluidContainers263 {
    private BuilderFluidContainers263() {}

    public static List<FluidStack> drainContained(List<ItemStack> requiredItems) {
        List<FluidStack> fluids = new ArrayList<>();
        for (ItemStack item : requiredItems) {
            if (item == null || item.isEmpty()) continue;
            IFluidHandlerItem handler = FluidUtil.getFluidHandler(item.copy()).orElse(null);
            if (handler == null) continue;
            for (int tank = 0, count = handler.getTanks(); tank < count; tank++) {
                FluidStack contained = handler.getFluidInTank(tank);
                if (contained == null || contained.isEmpty()) continue;
                FluidStack drained = handler.drain(contained, IFluidHandler.FluidAction.EXECUTE);
                if (drained != null && !drained.isEmpty()) fluids.add(drained);
                // A drained item may replace itself; do not query the invalidated handler again.
                break;
            }
        }
        return fluids;
    }
}
