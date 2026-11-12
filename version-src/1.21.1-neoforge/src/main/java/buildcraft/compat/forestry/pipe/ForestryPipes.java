package buildcraft.compat.forestry.pipe;

import buildcraft.compat.BuildCraftCompat;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.PlatformMenus;
import buildcraft.transport.BCTransport;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeDefinition.PipeDefinitionBuilder;
import buildcraft.transport.item.ItemPipeHolder;
import buildcraft.transport.pipe.PipeRegistry;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.Collection;
import java.util.List;

/** Registers the Apiarist's item pipe only when Forestry is installed. */
public final class ForestryPipes {
    private static final BCDeferredRegister<Item> ITEMS =
        BCDeferredRegister.create("minecraft:item", BuildCraftCompat.MODID);
    private static final BCDeferredRegister<MenuType<?>> MENUS =
        BCDeferredRegister.create("minecraft:menu", BuildCraftCompat.MODID);

    public static PipeDefinition PROPOLIS_PIPE;
    public static BCRegistryEntry<ItemPipeHolder> PROPOLIS_PIPE_ITEM;
    public static BCRegistryEntry<MenuType<ContainerPropolisPipe>> PROPOLIS_PIPE_MENU;

    private ForestryPipes() {}

    public static void register(BCRegistryBinder binder) {
        PROPOLIS_PIPE_ITEM = ITEMS.register("pipe_item_propolis",
            () -> PipeRegistry.INSTANCE.createItemForPipe(getOrCreatePropolisPipe()));
        PROPOLIS_PIPE_MENU = MENUS.register("propolis_pipe",
            () -> PlatformMenus.create(ContainerPropolisPipe::fromNetwork));
        ITEMS.register(binder);
        MENUS.register(binder);
        BCTransport.tabPipes.addItemProvider(ForestryPipes::getCreativeTabItems);
    }

    public static Collection<ItemStack> getCreativeTabItems() {
        if (PROPOLIS_PIPE_ITEM == null || !PROPOLIS_PIPE_ITEM.isPresent()) return List.of();
        return List.of(PROPOLIS_PIPE_ITEM.get().getDefaultInstance());
    }

    public static synchronized PipeDefinition getOrCreatePropolisPipe() {
        if (PROPOLIS_PIPE != null) return PROPOLIS_PIPE;
        if (PipeApi.pipeRegistry == null || PipeApi.flowItems == null) {
            throw new IllegalStateException("Transport must initialize before the Apiarist's Pipe definition");
        }
        String[] suffixes = new String[8];
        suffixes[0] = "";
        suffixes[7] = "_itemstack";
        for (Direction face : Direction.values()) suffixes[face.ordinal() + 1] = "_" + face.getName();

        PipeDefinitionBuilder definition = new PipeDefinitionBuilder();
        definition.identifier = ResourceLocation.fromNamespaceAndPath(BuildCraftCompat.MODID, "forestry_propolis");
        definition.texturePrefix = BuildCraftCompat.MODID + ":pipes/propolis";
        definition.textureSuffixes = suffixes;
        definition.logicConstructor = PipeBehaviourPropolis::new;
        definition.logicLoader = PipeBehaviourPropolis::new;
        definition.flowType = PipeApi.flowItems;
        definition.enableColouring();
        definition.itemTex(7);
        PROPOLIS_PIPE = definition.define();
        return PROPOLIS_PIPE;
    }
}
