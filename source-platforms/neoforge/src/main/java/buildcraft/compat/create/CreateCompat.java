package buildcraft.compat.create;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

public final class CreateCompat {
    private CreateCompat() {
    }

    public static void register(IEventBus modBus) {
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_ITEM_ADAPTERS).register(
                ResourceLocation.fromNamespaceAndPath("buildcraftcompat", "create_potion"),
                new CreateFluidItemAdapter()
        );
    }
}