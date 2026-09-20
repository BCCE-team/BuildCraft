package buildcraft.lib.platform.client;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Converts native events without queuing twice or retaining an event across reload generations. */
public final class PlatformClientRegistration {
    private PlatformClientRegistration() {}
    public static ClientRegistration.Layers layers() {
        return (block, layer) -> ItemBlockRenderTypes.setRenderLayer(block, switch (layer) {
            case CUTOUT -> RenderType.cutout();
            case TRANSLUCENT -> RenderType.translucent();
        });
    }
    public static ClientRegistration.Setup setup(FMLClientSetupEvent event) { return work -> event.enqueueWork(work); }
    public static ClientRegistration.Renderers renderers(EntityRenderersEvent.RegisterRenderers event) {
        return new ClientRegistration.Renderers() {
            public <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> factory) {
                event.registerBlockEntityRenderer(type, factory);
            }
            public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type, EntityRendererProvider<T> factory) {
                event.registerEntityRenderer(type, factory);
            }
        };
    }
    public static ClientRegistration.BlockColours blockColours(RegisterColorHandlersEvent.Block event) { return event::register; }
    public static ClientRegistration.Screens screens(RegisterMenuScreensEvent event) {
        return new ClientRegistration.Screens() {
            public <M extends AbstractContainerMenu, S extends Screen & MenuAccess<M>> void register(MenuType<? extends M> type, ClientRegistration.ScreenFactory<M, S> factory) {
                event.register(type, factory::create);
            }
        };
    }
    public static ClientItemColours itemColours(RegisterColorHandlersEvent.Item event) { return event::register; }

    public static ClientAtlas.After atlas(TextureAtlasStitchedEvent event) { return new ClientAtlas.After(event.getAtlas()); }
}
