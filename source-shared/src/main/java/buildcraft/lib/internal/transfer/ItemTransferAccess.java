package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemTransferResult;
import net.minecraft.world.item.ItemStack;

/** Internal slotless item-transfer endpoint used between gameplay and loader adapters. */
public interface ItemTransferAccess {
    ItemTransferResult insert(ItemStack offered, OperationScope scope);
    ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationScope scope);
}
