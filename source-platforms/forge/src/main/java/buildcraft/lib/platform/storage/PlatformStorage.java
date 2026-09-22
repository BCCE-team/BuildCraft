package buildcraft.lib.platform.storage;

import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.misc.CapUtil;
import buildcraft.transport.internal.pipe.IPipeHolder;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;

/** Fresh sided Forge capability lookups. No handle caching or invalidation policy is added. */
public final class PlatformStorage {
    private PlatformStorage() {}
    public static ItemStorage localInventory(net.minecraft.world.Container inventory) {
        return StorageAdapters.fromNativeItems(new net.minecraftforge.items.wrapper.InvWrapper(inventory));
    }
    public static ItemStorage items(ICapabilityProvider provider, Direction face) {
        return provider == null ? null : StorageAdapters.fromNativeItems(provider.getCapability(CapUtil.CAP_ITEMS, face).orElse(null));
    }
    public static ItemStorage items(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : items(level.getBlockEntity(pos), face);
    }
    public static FluidStorage<FluidStack> fluids(ICapabilityProvider provider, Direction face) {
        return provider == null ? null : StorageAdapters.fromNativeFluids(buildcraft.compat.CompatCapTransfromer.INSTANCE.getCap(provider, CapUtil.CAP_FLUIDS, face).orElse(null));
    }
    public static FluidStorage<FluidStack> fluids(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : fluids(level.getBlockEntity(pos), face);
    }
    public static FluidStorage<FluidStack> pipeFluids(IPipeHolder holder, Direction face) {
        LazyOptional<net.minecraftforge.fluids.capability.IFluidHandler> capability =
            holder == null ? null : holder.getCapabilityFromPipe(face, CapUtil.CAP_FLUIDS);
        return StorageAdapters.fromNativeFluids(capability == null ? null : capability.orElse(null));
    }
    public static EnergyStorage energy(ICapabilityProvider provider, Direction face) {
        return provider == null ? null : StorageAdapters.fromNativeEnergy(provider.getCapability(CapUtil.CAP_FE, face).orElse(null));
    }
    public static EnergyStorage energy(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : energy(level.getBlockEntity(pos), face);
    }
    public static EnergyStorage pipeEnergy(IPipeHolder holder, Direction face) {
        LazyOptional<net.minecraftforge.energy.IEnergyStorage> capability =
            holder == null ? null : holder.getCapabilityFromPipe(face, CapUtil.CAP_FE);
        return StorageAdapters.fromNativeEnergy(capability == null ? null : capability.orElse(null));
    }
    public static EnergyStorage energy(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : StorageAdapters.fromNativeEnergy(
            stack.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY).orElse(null));
    }
}
