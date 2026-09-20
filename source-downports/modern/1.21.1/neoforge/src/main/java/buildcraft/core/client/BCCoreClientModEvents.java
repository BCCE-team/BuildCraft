package buildcraft.core.client;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.core.BCCoreClientRenderers;
import buildcraft.core.BCCore;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreItems;
import buildcraft.core.BCCoreSprites;
import buildcraft.core.list.GuiList;
import buildcraft.core.client.model.FragileFluidContainerModel;
import buildcraft.core.client.model.ModelEngine;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.core.client.render.RenderMarkerVolume;
import buildcraft.core.client.render.RenderVolumeBoxes;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent.ModifyBakingResult;
import net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional;
import net.neoforged.neoforge.client.event.ModelEvent.RegisterGeometryLoaders;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-only Core bootstrap. Kept physically separate from the common @Mod entrypoint. */
@EventBusSubscriber(modid = BCCore.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BCCoreClientModEvents {
    public static final ResourceLocation TRUNK_LIGHT = ResourceLocation.parse("buildcraftcore:blocks/engine/trunk_light");
    public static final ResourceLocation CHAMBER = ResourceLocation.parse("buildcraftlib:blocks/engine/chamber_base");
    public static final ResourceLocation TRUNK = ResourceLocation.parse("buildcraftcore:blocks/engine/trunk");
    public static final ResourceLocation ENGINE_MODEL = ResourceLocation.parse("buildcraftlib:block/engine_base");
    public static final ResourceLocation ENGINE_REDSTONE_TEXTURE_MODEL = ResourceLocation.parse("buildcraftcore:item/engine_redstone");
    public static final ResourceLocation ENGINE_CREATIVE_TEXTURE_MODEL = ResourceLocation.parse("buildcraftcore:item/engine_creative");
    public static final ResourceLocation ENGINE_STONE_TEXTURE_MODEL = ResourceLocation.parse("buildcraftenergy:item/engine_stone");
    public static final ResourceLocation ENGINE_IRON_TEXTURE_MODEL = ResourceLocation.parse("buildcraftenergy:item/engine_iron");
    public static final ResourceLocation ENGINE_FE_TEXTURE_MODEL = ResourceLocation.parse("buildcraftenergy:item/engine_fe");
    public static final ResourceLocation DYNAMO_MJ_TEXTURE_MODEL = ResourceLocation.parse("buildcraftenergy:block/mj_dynamo_texture_probe");
    public static final ResourceLocation ENGINE_LIGHT_SPRITE_MODEL = ResourceLocation.parse("buildcraftcore:block/engine_trunk_light_sprite");
    public static final ResourceLocation ENGINE_CHAMBER_SPRITE_MODEL = ResourceLocation.parse("buildcraftlib:block/engine_chamber_sprite");

    private BCCoreClientModEvents() {}

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        PlatformClientRegistration.screens(event).register(BCCore.LIST_MENU.get(), GuiList::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        BCCoreSprites.init();
        DetachedRenderer.INSTANCE.addRenderer(RenderMatrixType.FROM_WORLD_ORIGIN, RenderVolumeBoxes.INSTANCE);
        NeoForge.EVENT_BUS.addListener(RenderTickListener::renderOverlay);
        NeoForge.EVENT_BUS.addListener(RenderTickListener::renderLast);
        event.enqueueWork(BCCoreItems::registerItemProperties);
    }

    @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers event) {
        BCCoreClientRenderers.register(PlatformClientRegistration.renderers(event));
    }

    @SubscribeEvent
    public static void onModelBakePre(RegisterAdditional event) {
        event.register(new ModelResourceLocation(ENGINE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_REDSTONE_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_CREATIVE_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_STONE_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_IRON_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_FE_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(DYNAMO_MJ_TEXTURE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_LIGHT_SPRITE_MODEL, "standalone"));
        event.register(new ModelResourceLocation(ENGINE_CHAMBER_SPRITE_MODEL, "standalone"));
    }

    @SubscribeEvent
    public static void onModelBake(ModifyBakingResult event) {
        RenderEngine_BC8.reloadSprites(
            event.getModels().get(new ModelResourceLocation(ENGINE_LIGHT_SPRITE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_CHAMBER_SPRITE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_REDSTONE_TEXTURE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_CREATIVE_TEXTURE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_STONE_TEXTURE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_IRON_TEXTURE_MODEL, "standalone")),
            event.getModels().get(new ModelResourceLocation(ENGINE_FE_TEXTURE_MODEL, "standalone"))
        );
        RenderEngine_BC8.reloadDynamoSprites(
            event.getModels().get(new ModelResourceLocation(DYNAMO_MJ_TEXTURE_MODEL, "standalone"))
        );
        ModelEngine.init(event.getModels().get(new ModelResourceLocation(ENGINE_MODEL, "standalone")));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "engine"), "type=wood"), new ModelEngine(RenderEngine_BC8.REDSTONE_BACK, RenderEngine_BC8.REDSTONE_SIDE));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "engine"), "type=creative"), new ModelEngine(RenderEngine_BC8.CREATIVE_BACK, RenderEngine_BC8.CREATIVE_SIDE));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "engine"), "type=stone"), new ModelEngine(RenderEngine_BC8.STONE_BACK, RenderEngine_BC8.STONE_SIDE));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "engine"), "type=iron"), new ModelEngine(RenderEngine_BC8.IRON_BACK, RenderEngine_BC8.IRON_SIDE));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "engine"), "type=fe"), new ModelEngine(RenderEngine_BC8.FE_BACK, RenderEngine_BC8.FE_SIDE));
        event.getModels().put(new ModelResourceLocation(ResourceLocation.fromNamespaceAndPath("buildcraftenergy", "mj_dynamo"), ""), new ModelEngine(RenderEngine_BC8.DYNAMO_BACK, RenderEngine_BC8.DYNAMO_SIDE));
        ModelEngine.release();
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(RegisterGeometryLoaders event) {
        event.register(ResourceLocation.fromNamespaceAndPath(BCCore.MODID, "fragile_fluid_container"), FragileFluidContainerModel.Loader.INSTANCE);
    }

    @SubscribeEvent
    public static void registerItemColor(RegisterColorHandlersEvent.Item event) {
        event.register(new FragileFluidContainerModel.Colors(), BCCoreItems.FRAGILE_FLUID_SHARD.get());
    }
}
