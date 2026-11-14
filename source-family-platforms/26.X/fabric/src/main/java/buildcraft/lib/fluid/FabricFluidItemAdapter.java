package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidVolume;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Static contained-fluid inspection (buckets/cells/tanks) without mutating the
 * item. Decorated fluid variants remain unsupported rather than losing data.
 */
public final class FabricFluidItemAdapter implements FluidItemAdapter {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

    public boolean supports(ItemStack stack) {
        return !fluid(stack).isEmpty();
    }

    public FluidVolume fluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FluidVolume.empty();
        Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage =
            FluidStorage.ITEM.find(stack, ContainerItemContext.withConstant(stack));
        if (storage == null) return FluidVolume.empty();
        for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view : storage) {
            if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
            var resource = view.getResource();
            var apiVariant = FabricFluidVariants.toApi(
                resource, ItemStackUtil.requireActiveRegistryProvider()).orElse(null);
            if (apiVariant == null) continue;
            long milliBuckets = view.getAmount() / DROPLETS_PER_MB;
            if (milliBuckets > 0) {
                return FluidVolume.of(apiVariant, FluidAmount.of(milliBuckets));
            }
        }
        return FluidVolume.empty();
    }
}
