package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.drop.FluidDropContext;
import buildcraft.api.v2.drop.FluidDropProvider;
import buildcraft.api.v2.drop.FluidDropService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Aggregates registered fluid-drop providers and returns defensive stack copies. */
public final class FluidDropServiceImpl implements FluidDropService {
    public Collection<ItemStack> createDrops(FluidDropContext context) {
        if (context.fluid().isEmpty()) return List.of();
        ArrayList<ItemStack> result = new ArrayList<>();
        for (FluidDropProvider provider : BuildCraftApi.registry(BuildCraftRegistries.FLUID_DROP_PROVIDERS).values()) {
            Collection<ItemStack> drops = provider.createDrops(context);
            if (drops == null) continue;
            for (ItemStack stack : drops) {
                if (stack != null && !stack.isEmpty()) result.add(stack.copy());
            }
        }
        return List.copyOf(result);
    }
}
