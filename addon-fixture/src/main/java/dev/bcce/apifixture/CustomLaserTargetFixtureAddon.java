package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.machine.LaserTarget;
import buildcraft.api.v2.machine.LaserTargetProvider;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/** A sided laser endpoint defined by a standalone addon. */
public final class CustomLaserTargetFixtureAddon implements LaserTargetProvider {
    public static final ResourceLocation TABLE = ResourceLocation.tryParse("fixture:laser_workbench");
    private final long capacity;
    private long stored;

    public CustomLaserTargetFixtureAddon(long capacity) { this.capacity = capacity; }
    public long stored() { return stored; }

    private final MjPort power = new MjPort() {
        @Override public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            long accepted = Math.min(offered.microMj(), capacity - stored);
            if (mode == OperationMode.EXECUTE) stored += accepted;
            return MjTransferResult.of(offered, MjAmount.ofMicro(accepted));
        }
        @Override public MjTransferResult extract(MjAmount offered, OperationMode mode) {
            return MjTransferResult.none(offered);
        }
        @Override public MjAmount stored() { return MjAmount.ofMicro(stored); }
        @Override public MjAmount capacity() { return MjAmount.ofMicro(capacity); }
        @Override public boolean canExtract() { return false; }
    };
    private final LaserTarget receiver = new LaserTarget() {
        @Override public Optional<ResourceLocation> typeId() { return Optional.of(TABLE); }
        @Override public MjPort laserPort() { return power; }
    };

    @Override public Optional<LaserTarget> laserTarget(Direction incomingSide) {
        return incomingSide == Direction.NORTH ? Optional.of(receiver) : Optional.empty();
    }
}
