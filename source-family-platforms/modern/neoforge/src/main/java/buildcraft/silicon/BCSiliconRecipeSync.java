//? source if >=1.21.11
package buildcraft.silicon;

import java.util.List;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

/** Synchronize server-authoritative assembly recipes on login and datapack reload. */
@EventBusSubscriber(modid = BCSilicon.MODID)
public final class BCSiliconRecipeSync {
    private BCSiliconRecipeSync() {}

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        event.sendRecipes(BCSiliconRecipes.ASSEMBLY_TYPE.get());
    }

    /** Only this nested subscriber references client classes; dedicated servers never load it. */
    @EventBusSubscriber(modid = BCSilicon.MODID, value = Dist.CLIENT)
    public static final class Client {
        private static List<RecipeHolder<AssemblyRecipeBasic>> assembly = List.of();

        private Client() {}

        public static List<RecipeHolder<AssemblyRecipeBasic>> assemblyRecipes() {
            return assembly;
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onRecipes(net.neoforged.neoforge.client.event.RecipesReceivedEvent event) {
            if (event.getRecipeTypes().contains(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
                assembly = List.copyOf(event.getRecipeMap().byType(BCSiliconRecipes.ASSEMBLY_TYPE.get()));
            }
        }

        @SubscribeEvent
        public static void onLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            assembly = List.of();
        }
    }
}
