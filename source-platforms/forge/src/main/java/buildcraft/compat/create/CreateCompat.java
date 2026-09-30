package buildcraft.compat.create;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;

public final class CreateCompat {
    private CreateCompat() {}

    public static void register(IEventBus ignored) {
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_ITEM_ADAPTERS).register(
            new ResourceLocation("buildcraftcompat", "create_potion"),
            new CreateFluidItemAdapter()
        );
    }
}
