//? source if >=1.21.1
package buildcraft.core.client;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.core.BCCoreClientRenderers;
import buildcraft.core.BCCore;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreItems;
import buildcraft.core.BCCoreSprites;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.core.client.render.RenderMarkerVolume;
import buildcraft.core.client.render.RenderVolumeBoxes;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType;
import buildcraft.core.list.GuiList;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only Core bootstrap for the 1.21.11 renderer transition layer. */
@EventBusSubscriber(modid = BCCore.MODID, value = Dist.CLIENT)
public final class BCCoreClientModEvents {
    private BCCoreClientModEvents() {}

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        PlatformClientRegistration.screens(event).register(BCCore.LIST_MENU.get(), GuiList::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BCCoreSprites.init();
        DetachedRenderer.INSTANCE.addRenderer(RenderMatrixType.FROM_WORLD_ORIGIN, RenderVolumeBoxes.INSTANCE);
        NeoForge.EVENT_BUS.addListener(RenderTickListener::renderLast);
        NeoForge.EVENT_BUS.addListener(MarkerSubmitRenderer121111::submit);
    }

    @SubscribeEvent
    public static void registerItemModelProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        BCCoreItems.registerItemModelProperties(PlatformClientRegistration.rangeProperties(event));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        BCCoreClientRenderers.register(PlatformClientRegistration.renderers(event));
    }
}
