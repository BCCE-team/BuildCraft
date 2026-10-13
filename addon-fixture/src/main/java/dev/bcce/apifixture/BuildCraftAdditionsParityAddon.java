package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;

/** A porting fixture for BCA-style fluid canisters and transactional machine tanks. */
public final class BuildCraftAdditionsParityAddon implements FluidItemAdapter, FluidPort {
    private final ItemMatcher canister;
    private final long capacity;
    private FluidVolume content = FluidVolume.empty();

    public BuildCraftAdditionsParityAddon(ItemMatcher canister, long capacity) {
        this.canister = Objects.requireNonNull(canister, "canister");
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override public boolean supports(ItemStack stack) { return canister.matches(stack); }
    @Override public FluidVolume fluid(ItemStack stack) {
        return supports(stack) ? content : FluidVolume.empty();
    }

    public FluidVolume content() { return content; }

    @Override public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(mode, "mode");
        if (offered.isEmpty() || (!content.isEmpty() && !content.sameVariant(offered))) {
            return FluidTransferResult.ofInsertion(offered, FluidAmount.ZERO);
        }
        long accepted = Math.min(offered.amount().milliBuckets(), capacity - content.amount().milliBuckets());
        if (mode == OperationMode.EXECUTE && accepted > 0) {
            FluidVariant fluid = offered.requireVariant();
            content = FluidVolume.of(fluid, content.amount().milliBuckets() + accepted);
        }
        return FluidTransferResult.ofInsertion(offered, FluidAmount.of(accepted));
    }

    @Override public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maximum, OperationMode mode) {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(maximum, "maximum");
        Objects.requireNonNull(mode, "mode");
        if (content.isEmpty() || !matcher.matches(content.requireVariant(), (fluid, tag) -> false)) {
            return FluidTransferResult.nothing(maximum);
        }
        long extracted = Math.min(content.amount().milliBuckets(), maximum.milliBuckets());
        FluidVolume moved = content.withAmount(FluidAmount.of(extracted));
        if (mode == OperationMode.EXECUTE) {
            content = content.withAmount(FluidAmount.of(content.amount().milliBuckets() - extracted));
        }
        return FluidTransferResult.ofExtraction(maximum, moved);
    }
}
