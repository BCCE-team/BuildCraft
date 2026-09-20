package buildcraft.lib.platform.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.misc.CapUtil;
import net.neoforged.neoforge.fluids.FluidStack;

/** Fresh sided lookups. NeoForge standard/legacy/transaction fallback order remains owned by CapUtil. */
public final class PlatformStorage {
    private PlatformStorage() {}
    public static ItemStorage localInventory(net.minecraft.world.Container inventory) {
        return StorageAdapters.fromNativeItems(new net.neoforged.neoforge.items.wrapper.InvWrapper(inventory));
    }
    public static ItemStorage items(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : StorageAdapters.fromNativeItems(CapUtil.getItemHandler(level, pos, face));
    }
    public static FluidStorage<FluidStack> fluids(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : StorageAdapters.fromNativeFluids(CapUtil.getFluidHandler(level, pos, face));
    }
    public static EnergyStorage energy(Level level, BlockPos pos, Direction face) {
        return level == null || pos == null ? null : StorageAdapters.fromNativeEnergy(CapUtil.getEnergyStorage(level, pos, face));
    }
    public static EnergyStorage energy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        //? if >=1.21.11 {
        var handler = net.neoforged.neoforge.transfer.access.ItemAccess.forStack(stack)
            .getCapability(net.neoforged.neoforge.capabilities.Capabilities.Energy.ITEM);
        return handler == null ? null : StorageAdapters.fromNativeEnergy(
            net.neoforged.neoforge.energy.IEnergyStorage.of(handler));
        //? } else {
        return StorageAdapters.fromNativeEnergy(stack.getCapability(
            net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM));
        //? }
    }
    public static ItemStorage items(Entity entity, Direction face) {
        if (entity == null) return null;
        //? if >=1.21.11 {
        return StorageAdapters.fromNativeItems(CapUtil.getItemHandler(entity, face));
        //? } else {
        var handler = entity.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY_AUTOMATION, face);
        if (handler == null) handler = entity.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY);
        return StorageAdapters.fromNativeItems(handler);
        //? }
    }
}
