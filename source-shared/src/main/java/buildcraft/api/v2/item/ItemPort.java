package buildcraft.api.v2.item;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral item insertion/extraction endpoint.
 * Implementations MUST NOT mutate state during SIMULATE.
 */
public interface ItemPort {
    ItemTransferResult insert(ItemStack offered, OperationMode mode);

    ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode);

    /**
     * Policy-aware insertion with explicit transfer semantics. Implementations may override this
     * to provide a truly atomic exact transfer.
     */
    default ItemTransferResult insert(ItemStack offered, ItemTransferPolicy policy, OperationMode mode) {
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(mode, "mode");
        if (policy == ItemTransferPolicy.PARTIAL) return insert(offered, mode);
        ItemTransferResult simulated = insert(offered, OperationMode.SIMULATE);
        if (!simulated.completed()) return ItemTransferResult.nothing(offered.getCount());
        // SIMULATE cannot reserve capacity. A second call could accept fewer items and
        // violate ALL_OR_NOTHING. Only implementations with transactions may override.
        return mode == OperationMode.SIMULATE ? simulated : ItemTransferResult.nothing(offered.getCount());
    }

    /**
     * Range-aware extraction replacing the legacy min/max contract. Implementations may override this to make
     * the minimum guarantee atomic when their state can change between simulation and execution.
     */
    default ItemTransferResult extract(ItemMatcher matcher, int minCount, int maxCount, OperationMode mode) {
        Objects.requireNonNull(matcher, "matcher");
        Objects.requireNonNull(mode, "mode");
        if (minCount < 0 || maxCount < minCount) throw new IllegalArgumentException("invalid extraction range");
        ItemTransferResult simulated = extract(matcher, maxCount, OperationMode.SIMULATE);
        if (simulated.transferredCount() < minCount) return ItemTransferResult.nothing(maxCount);
        // No generic rollback exists if a provider extracts less than the guaranteed minimum.
        return mode == OperationMode.SIMULATE || minCount == 0
            ? (mode == OperationMode.SIMULATE ? simulated : extract(matcher, maxCount, mode))
            : ItemTransferResult.nothing(maxCount);
    }
}
