package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.item.ItemLabelAdapter;
import buildcraft.api.v2.item.ItemLabelService;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** Runtime resolver for stack-owned and registry-provided label adapters. */
public final class ItemLabelServiceImpl implements ItemLabelService {
    public Optional<ItemLabelAdapter> adapter(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        if (stack.getItem() instanceof ItemLabelAdapter direct && direct.supports(stack)) {
            return Optional.of(direct);
        }
        for (ItemLabelAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.ITEM_LABEL_ADAPTERS).values()) {
            if (adapter.supports(stack)) return Optional.of(adapter);
        }
        return Optional.empty();
    }
}
