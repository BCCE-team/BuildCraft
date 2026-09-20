package buildcraft.compat.forestry.pipe.client;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.compat.BuildCraftCompat;
import buildcraft.compat.forestry.pipe.ContainerPropolisPipe;
import buildcraft.compat.forestry.pipe.ForestryPipes;
import buildcraft.compat.forestry.pipe.ForestryPropolisNetwork;
import buildcraft.transport.client.model.ModelPipeItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
//? if >=1.20 {
/*?
import net.minecraft.resources.ResourceLocation;
?*/
//?}
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ForestryCompatClient {
    private ForestryCompatClient() {
    }

    /** Registers client listeners early enough for the initial model bake. */
    public static void register(IEventBus modBus) {
        modBus.addListener(ForestryCompatClient::clientSetup);
        modBus.addListener(ForestryCompatClient::onModelBake);
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ForestryCompatClient::registerScreens);
    }

    public static void registerScreens() {
        PlatformClientRegistration.directScreens().register(ForestryPipes.PROPOLIS_PIPE_MENU.get(), GuiPropolisPipe::new);
    }

    //? if <1.20 {
    private static void onModelBake(ModelEvent.BakingCompleted event) {
    //?} else {
    /*?
    private static void onModelBake(ModelEvent.ModifyBakingResult event) {
    ?*/
    //?}
        // Pipe items use BuildCraft's dynamic 3D model. The base transport module only replaces
        // models from its own item map, so compat-owned pipe items must opt in here explicitly.
        event.getModels().put(
            //? if <1.20 {
            new ModelResourceLocation(BuildCraftCompat.MODID + ":pipe_item_propolis#inventory"),
            //?} else {
            /*?
            new ModelResourceLocation(new ResourceLocation(BuildCraftCompat.MODID, "pipe_item_propolis"), "inventory"),
            ?*/
            //?}
            ModelPipeItem.INSTANCE
        );
    }

    public static void handleFilterState(BlockPos pos, CompoundTag tag) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null
                && minecraft.player.containerMenu instanceof ContainerPropolisPipe menu
                && menu.hasSamePipe(pos)) {
            menu.applyFilterState(tag);
        }
        if (minecraft.level != null) {
            var behaviour = ForestryPropolisNetwork.findBehaviour(minecraft.level, pos);
            if (behaviour != null) {
                behaviour.getFilter().read(tag);
            }
        }
    }
}
