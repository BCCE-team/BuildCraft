package buildcraft.compat.forestry.pipe;

import buildcraft.lib.platform.registry.PlatformMenus;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeDefinition.PipeDefinitionBuilder;
import buildcraft.compat.BuildCraftCompat;
import buildcraft.transport.BCTransport;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.pipe.PipeRegistry;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;

import java.util.Collection;
import java.util.List;

/** Content registered by the Forestry compatibility module. */
public final class ForestryPipes {
    private static final BCDeferredRegister<Item> ITEMS =
        BCDeferredRegister.create("minecraft:item", BuildCraftCompat.MODID);
    private static final BCDeferredRegister<MenuType<?>> MENUS =
        BCDeferredRegister.create("minecraft:menu", BuildCraftCompat.MODID);

    /**
     * Created lazily from the item registry supplier. Compat mod constructors may run before
     * BuildCraft Transport has initialised {@link PipeApi#flowItems}; defining the pipe from
     * the constructor would therefore permanently store a null flow type.
     */
    public static PipeDefinition PROPOLIS_PIPE;
    public static BCRegistryEntry<ItemPipeHolder> PROPOLIS_PIPE_ITEM;
    public static BCRegistryEntry<MenuType<ContainerPropolisPipe>> PROPOLIS_PIPE_MENU;

    private ForestryPipes() {
    }

    public static void register(BCRegistryBinder modBus) {
        PROPOLIS_PIPE_ITEM = ITEMS.register("pipe_item_propolis",
            () -> PipeRegistry.INSTANCE.createItemForPipe(getOrCreatePropolisPipe()));
        PROPOLIS_PIPE_MENU = MENUS.register("propolis_pipe",
            () -> PlatformMenus.create(ContainerPropolisPipe::fromNetwork));

        ITEMS.register(modBus);
        MENUS.register(modBus);

        // Compat-owned items are not part of BCTransportItems, so modern creative tabs will not
        // discover them automatically. Register the Apiarist's Pipe as an explicit BuildCraft
        // pipes-tab provider. On 1.19.2 the item also has the tab in Item.Properties; the tab's
        // duplicate filtering keeps this provider harmless and gives both Forge targets one path.
        BCTransport.tabPipes.addItemProvider(ForestryPipes::getCreativeTabItems);
    }

    public static Collection<ItemStack> getCreativeTabItems() {
        if (PROPOLIS_PIPE_ITEM == null || !PROPOLIS_PIPE_ITEM.isPresent()) {
            return List.of();
        }
        return List.of(PROPOLIS_PIPE_ITEM.get().getDefaultInstance());
    }

    public static synchronized PipeDefinition getOrCreatePropolisPipe() {
        if (PROPOLIS_PIPE != null) {
            return PROPOLIS_PIPE;
        }
        if (PipeApi.pipeRegistry == null || PipeApi.flowItems == null) {
            throw new IllegalStateException(
                "BuildCraft Transport API is not ready while registering the Forestry Apiarist's Pipe"
            );
        }

        String[] suffixes = new String[8];
        suffixes[0] = "";
        suffixes[7] = "_itemstack";
        for (Direction face : Direction.values()) {
            suffixes[face.ordinal() + 1] = "_" + face.getName();
        }

        PipeDefinitionBuilder builder = new PipeDefinitionBuilder();
        builder.identifier = new ResourceLocation(BuildCraftCompat.MODID, "forestry_propolis");
        builder.texturePrefix = BuildCraftCompat.MODID + ":pipes/propolis";
        builder.textureSuffixes = suffixes;
        builder.logicConstructor = PipeBehaviourPropolis::new;
        builder.logicLoader = PipeBehaviourPropolis::new;
        builder.flowType = PipeApi.flowItems;
        builder.enableColouring();
        builder.itemTex(7);
        PROPOLIS_PIPE = builder.define();
        return PROPOLIS_PIPE;
    }
}
