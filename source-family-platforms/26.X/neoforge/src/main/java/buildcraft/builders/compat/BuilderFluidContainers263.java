//? source if >=26.3
package buildcraft.builders.compat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/** Extracts fluid requirements from a copy of an item, including containers that replace their item on draining. */
public final class BuilderFluidContainers263 {
    private BuilderFluidContainers263() {}

    public static List<FluidStack> drainContained(List<ItemStack> requiredItems) {
        List<FluidStack> fluids = new ArrayList<>();
        for (ItemStack item : requiredItems) {
            if (item.isEmpty()) continue;
            ItemStacksResourceHandler inventory = new ItemStacksResourceHandler(1);
            inventory.set(0, ItemResource.of(item), item.getCount());
            var handler = ItemAccess.forHandlerIndexStrict(inventory, 0).oneByOne()
                .getCapability(Capabilities.Fluid.ITEM);
            if (handler == null) continue;
            for (int index = 0, size = handler.size(); index < size; index++) {
                FluidResource resource = handler.getResource(index);
                if (resource.isEmpty()) continue;
                int amount = handler.getAmountAsInt(index);
                if (amount <= 0) continue;
                try (Transaction transaction = Transaction.openRoot()) {
                    int extracted = handler.extract(index, resource, amount, transaction);
                    if (extracted > 0) {
                        fluids.add(resource.toStack(extracted));
                        transaction.commit();
                    }
                }
                // A drained fluid container may turn into a different item; the old capability is then invalid.
                break;
            }
        }
        return fluids;
    }
}
