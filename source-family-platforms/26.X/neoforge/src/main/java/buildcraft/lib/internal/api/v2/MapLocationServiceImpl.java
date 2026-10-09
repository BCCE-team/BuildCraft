package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.map.MapLocationAdapter;
import buildcraft.api.v2.map.MapLocationService;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** Runtime resolver for map-location items and addon adapters. */
public final class MapLocationServiceImpl implements MapLocationService {
    public Optional<MapLocationAdapter> adapter(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        if (stack.getItem() instanceof MapLocationAdapter direct && direct.supports(stack)) {
            return Optional.of(direct);
        }
        for (MapLocationAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.MAP_LOCATION_ADAPTERS).values()) {
            if (adapter.supports(stack)) return Optional.of(adapter);
        }
        return Optional.empty();
    }
}
