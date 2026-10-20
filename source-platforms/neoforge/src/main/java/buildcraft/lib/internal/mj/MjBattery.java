package buildcraft.lib.internal.mj;

import io.netty.buffer.ByteBuf;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.energy.PowerLossContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Provides a basic implementation of a simple battery. Note that you should call {@link #tick(Level, BlockPos)} or
 * {@link #tick(Level, Vec3)} every tick to allow for losing excess power. */
public class MjBattery implements INBTSerializable<CompoundTag>, buildcraft.api.v2.energy.MjStorage {
    private final long capacity;
    private long microJoules = 0;

    public MjBattery(long capacity) {
        this.capacity = Math.max(0L, capacity);
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.putLong("stored", microJoules);
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) {
        microJoules = clampStored(nbt.getLong("stored"));
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider registries) {
        return serializeNBT();
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider registries, CompoundTag nbt) {
        deserializeNBT(nbt);
    }

    public void writeToBuffer(ByteBuf buffer) {
        buffer.writeLong(microJoules);
    }

    public void readFromBuffer(ByteBuf buffer) {
        microJoules = clampStored(buffer.readLong());
    }

    public long addPower(long microJoulesToAdd, FluidAction simulate) {
        if (simulate == FluidAction.EXECUTE) {
            if (microJoulesToAdd > 0) {
                this.microJoules = microJoulesToAdd > Long.MAX_VALUE - this.microJoules
                    ? Long.MAX_VALUE : this.microJoules + microJoulesToAdd;
            }
        }
        return 0;
    }

    /** Attempts to add power, but only if this is not already full.
     * 
     * @param microJoulesToAdd The power to add.
     * @return The excess power. */
    public long addPowerChecking(long microJoulesToAdd, FluidAction simulate) {
        if (microJoulesToAdd <= 0 || capacity <= 0 || isFull()) {
            return microJoulesToAdd;
        }
        // Keep the checked API within advertised capacity; legacy addPower retains overfill semantics.
        long free = capacity - Math.max(0L, microJoules);
        long accepted = Math.min(microJoulesToAdd, free);
        addPower(accepted, simulate);
        return microJoulesToAdd - accepted;
    }

    public long extractAll() {
        return extractPower(0, microJoules);
    }

    /** Attempts to extract exactly the given amount of power.
     * 
     * @param power The amount of power to extract.
     * @return True if the power was removed, false if not. */
    public boolean extractPower(long power) {
        return extractPower(power, power) > 0;
    }

    public long extractPower(long min, long max) {
        // Invalid bounds must never turn a subtraction into free generated MJ.
        if (min < 0 || max < 0 || min > max) return 0;
        if (microJoules < min) return 0;
        long extracting = Math.min(microJoules, max);
        microJoules -= extracting;
        return extracting;
    }

    @Override
    public MjTransferResult insert(MjAmount offered, OperationMode mode) {
        long remainder = addPowerChecking(offered.microMj(), mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
        remainder = Math.max(0L, Math.min(offered.microMj(), remainder));
        return MjTransferResult.of(offered, MjAmount.ofMicro(offered.microMj() - remainder));
    }

    @Override
    public MjTransferResult extract(MjAmount requested, OperationMode mode) {
        long moved = Math.min(microJoules, requested.microMj());
        if (mode == OperationMode.EXECUTE && moved > 0) {
            microJoules -= moved;
        }
        return MjTransferResult.of(requested, MjAmount.ofMicro(moved));
    }

    @Override
    public MjAmount stored() {
        return MjAmount.ofMicro(Math.max(0L, microJoules));
    }

    @Override
    public MjAmount capacity() {
        return MjAmount.ofMicro(Math.max(0L, capacity));
    }

    public boolean isFull() {
        return microJoules >= capacity;
    }

    public long getStored() {
        return microJoules;
    }

    public long getCapacity() {
        return capacity;
    }

    public void tick(Level world, BlockPos position) {
        tick(world, new Vec3(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5));
    }

    public void tick(Level world, Vec3 position) {
        if (microJoules > safeOverfillLimit()) {
            losePower(world, position);
        }
    }

    protected void losePower(Level world, Vec3 position) {
        long diff = Math.max(0L, microJoules - safeOverfillLimit());
        if (diff == 0) return;
        long lost = ceilDivide(diff, 32);
        microJoules -= lost;
        BuildCraftApi.service(BuildCraftServices.POWER_LOSS_EFFECTS).onPowerLost(
            new PowerLossContext(world, new BlockPos((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z)), MjAmount.ofMicro(lost))
        );
    }

    /** Saturate the legacy double-capacity overfill allowance instead of overflowing long. */
    private long safeOverfillLimit() {
        return capacity > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : capacity * 2;
    }

    private long clampStored(long value) {
        return Math.max(0L, Math.min(value, safeOverfillLimit()));
    }

    private static long ceilDivide(long val, long by) {
        return (val / by) + (val % by == 0 ? 0 : 1);
    }

    public String getDebugString() {
        return BuildCraftApi.service(BuildCraftServices.MJ_FORMATTER).formatAmount(stored()) + " / " + BuildCraftApi.service(BuildCraftServices.MJ_FORMATTER).formatAmount(capacity());
    }
}
