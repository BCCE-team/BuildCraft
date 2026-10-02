package buildcraft.lib.list;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** Registers BuildCraft's built-in list semantics into the public API2 registry. */
public final class VanillaListHandlers {
    private static boolean registered;

    private VanillaListHandlers() {}

    public static synchronized void fmlInit() {
        if (registered) return;
        registered = true;
        register("class", new ListMatchHandlerClass());
        register("fluid", new ListMatchHandlerFluid());
        register("tools", new ListMatchHandlerTools());
        register("armor", new ListMatchHandlerArmor());
        register("tags", new ListMatchHandlerOreDictionary());
    }

    private static void register(String path, buildcraft.api.v2.list.ListMatchAdapter adapter) {
        BuildCraftApi.registry(BuildCraftRegistries.LIST_MATCH_ADAPTERS).register(
            Objects.requireNonNull(Identifier.tryParse("buildcraft:list_match/" + path)), adapter
        );
    }
}
