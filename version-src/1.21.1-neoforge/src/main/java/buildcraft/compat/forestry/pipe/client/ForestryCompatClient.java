package buildcraft.compat.forestry.pipe.client;

import buildcraft.compat.BuildCraftCompat;
import buildcraft.compat.forestry.pipe.ContainerPropolisPipe;
import buildcraft.compat.forestry.pipe.ForestryPipes;
import buildcraft.transport.client.model.ModelPipeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Registered only on a physical client with Forestry present. */
public final class ForestryCompatClient {
    private ForestryCompatClient() {}

    public static void register(IEventBus bus) {
        bus.addListener(ForestryCompatClient::registerScreens);
        bus.addListener(ForestryCompatClient::onModelBake);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ForestryPipes.PROPOLIS_PIPE_MENU.get(), GuiPropolisPipe::new);
    }

    private static void onModelBake(ModelEvent.ModifyBakingResult event) {
        event.getModels().put(new ModelResourceLocation(
            ResourceLocation.fromNamespaceAndPath(BuildCraftCompat.MODID, "pipe_item_propolis"), "inventory"),
            ModelPipeItem.INSTANCE);
    }

    public static void applyFilterState(BlockPos pos, CompoundTag data) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return;
        if (client.player != null && client.player.containerMenu instanceof ContainerPropolisPipe menu
                && menu.hasSamePipe(pos)) menu.applyFilterState(data, client.level.registryAccess());
        var live = ContainerPropolisPipe.findBehaviour(client.level, pos);
        if (live != null) live.getFilter().read(data, client.level.registryAccess());
    }
}
