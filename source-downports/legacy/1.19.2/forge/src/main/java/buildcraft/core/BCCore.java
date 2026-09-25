package buildcraft.core;

import buildcraft.lib.platform.client.PlatformClientRegistration;
import buildcraft.core.BCCoreClientRenderers;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.config.ConfigBinding;
import net.minecraftforge.fml.DistExecutor;

import java.util.HashMap;
import java.util.Map;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.core.client.RenderTickListener;
import buildcraft.core.client.model.ModelEngine;
import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.core.client.render.RenderMarkerVolume;
import buildcraft.core.client.render.RenderVolumeBoxes;
import buildcraft.core.list.ContainerList;
import buildcraft.core.list.GuiList;
import buildcraft.core.marker.PathCache;
import buildcraft.core.marker.VolumeCache;
import buildcraft.core.marker.volume.MessageVolumeBoxes;
import buildcraft.energy.BCEnergyFluids;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.BCLibEventDist;
import buildcraft.lib.CreativeTabManager;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.LegacyNetworkCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent.BakingCompleted;
import net.minecraftforge.client.event.ModelEvent.RegisterAdditional;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.DynamicFluidContainerModel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BCCore.MODID)
public class BCCore {
    public static final String MODID = "buildcraftcore";
    public static final CreativeTabBC BUILDCRAFT_TAB = CreativeTabManager.createTab("buildcraft.main");
    public static final CreativeTabBC tabFluids = CreativeTabManager.createTab("buildcraft.fluid");

    public static final Map<String,Object> ENGINE_MAP = new HashMap<>();
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", BCCore.MODID);
    public static final BCRegistryEntry<MenuType<ContainerList>> LIST_MENU = MENUS.register("list_menu", () -> BCContainerFactory.create(ContainerList::new));

    public BCCore() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::init);
        ConfigBinding.listen(modEventBus, BCCoreConfig::onLoadConfig, BCCoreConfig::onReloadConfig);

//        modEventBus.addListener(this::gatherData);//DataGenerator

        BCCoreBlocks.registry(RegistryBinding.on(modEventBus));
        BCCoreItems.registry(RegistryBinding.on(modEventBus));

        RegistryBinding.register(MENUS, modEventBus);
        BCCoreConfig.registry();
        ModLoadingContext.get().registerConfig(Type.COMMON, ConfigBinding.bind(BCCoreConfig.config));
        LegacyNetworkCatalog.registerCore(MessageManager::registerCatalogMessage);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(BCLibEventDist.class);
        BCLibEventDist.registerGameplayEvents();
        IEventBus eventBus = MinecraftForge.EVENT_BUS;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            eventBus.addListener(RenderTickListener::renderOverlay);
            eventBus.addListener(RenderTickListener::renderLast);
        });
        BCCoreStatements.preInit();
    }

    public void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(
            event.includeServer(),
            new BCCoreRecipes(event.getGenerator())
        );
    }

    public void init(final FMLCommonSetupEvent event)
    {
        MarkerCache.registerCache(VolumeCache.INSTANCE);
        MarkerCache.registerCache(PathCache.INSTANCE);
        EnumSpring.OIL.liquidBlock = BCEnergyFluids.OIL_BLOCK.get(0).get().defaultBlockState();
        EnumSpring.OIL.tileConstructor = TileSpringOil::new;
        BCCoreConfig.reloadConfig(MODID);
        BUILDCRAFT_TAB.setItem(BCCoreItems.WRENCH.get());
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_DROP_PROVIDERS).register(
            java.util.Objects.requireNonNull(net.minecraft.resources.ResourceLocation.tryParse("buildcraftcore:fragile_fluid_shard")),
            BCCoreItems.FRAGILE_FLUID_SHARD.get()
        );
    }


    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        public static final ResourceLocation TRUNK_LIGHT = new ResourceLocation("buildcraftcore:blocks/engine/trunk_light");
        public static final ResourceLocation CHAMBER = new ResourceLocation("buildcraftlib:blocks/engine/chamber_base");
        public static final ResourceLocation TRUNK = new ResourceLocation("buildcraftcore:blocks/engine/trunk");
        public static final ResourceLocation ENGINE_MODEL = new ResourceLocation("buildcraftlib:block/engine_base");

        public ClientModEvents() {

        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            BCCoreSprites.init();
            DetachedRenderer.INSTANCE.addRenderer(RenderMatrixType.FROM_WORLD_ORIGIN, RenderVolumeBoxes.INSTANCE);
            event.enqueueWork(
                    () -> {
                        BCCoreItems.registerItemProperties();
                        PlatformClientRegistration.directScreens().register(LIST_MENU.get(), GuiList::new);
                    }
            );
        }

        @SubscribeEvent
    public static void registryRender(EntityRenderersEvent.RegisterRenderers e) {
        BCCoreClientRenderers.register(PlatformClientRegistration.renderers(e));
    }

        @SubscribeEvent
        public static void registrtTexture(TextureStitchEvent.Pre e){
            if (InventoryMenu.BLOCK_ATLAS.equals(e.getAtlas().location())) {
                // IC2 Classic/CarbonConfig may cause texture stitching before FMLClientSetup.
                // Create all core laser holders now so BCLib's LOWEST-priority registration sees them.
                BCCoreSprites.init();
                BCCoreSprites.onTextureStitchPre(PlatformClientRegistration.atlas(e));
                e.addSprite(TRUNK_LIGHT);
                e.addSprite(CHAMBER);
            }
        }

        @SubscribeEvent
        public static void onTextureStitchPost(TextureStitchEvent.Post event) {
            if (InventoryMenu.BLOCK_ATLAS.equals(event.getAtlas().location())) {
                RenderEngine_BC8.reloadSprites(event.getAtlas());
            }
        }

        @SubscribeEvent
        public static void onModelBakePre(RegisterAdditional event) {
            event.register(ENGINE_MODEL);
        }

        @SubscribeEvent
        public static void onModelBake(BakingCompleted event) {
            // Baking runs for every resource reload. Ensure the model uses sprites from the new atlas.
            RenderEngine_BC8.reloadSprites(Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS));
            ModelEngine.init(event.getModels().get(ENGINE_MODEL));
            event.getModels().put(new ModelResourceLocation("buildcraftcore:engine#type=wood"), new ModelEngine(RenderEngine_BC8.REDSTONE_BACK, RenderEngine_BC8.REDSTONE_SIDE));
            event.getModels().put(new ModelResourceLocation("buildcraftcore:engine#type=creative"), new ModelEngine(RenderEngine_BC8.CREATIVE_BACK, RenderEngine_BC8.CREATIVE_SIDE));
            event.getModels().put(new ModelResourceLocation("buildcraftcore:engine#type=stone"), new ModelEngine(RenderEngine_BC8.STONE_BACK, RenderEngine_BC8.STONE_SIDE));
            event.getModels().put(new ModelResourceLocation("buildcraftcore:engine#type=iron"), new ModelEngine(RenderEngine_BC8.IRON_BACK, RenderEngine_BC8.IRON_SIDE));
            event.getModels().put(new ModelResourceLocation("buildcraftcore:engine#type=fe"), new ModelEngine(RenderEngine_BC8.FE_BACK, RenderEngine_BC8.FE_SIDE));
            event.getModels().put(new ModelResourceLocation("buildcraftenergy:mj_dynamo#"), new ModelEngine(RenderEngine_BC8.DYNAMO_BACK, RenderEngine_BC8.DYNAMO_SIDE));
            ModelEngine.release();
        }

        @SubscribeEvent
        public static void RegisterItemColor(RegisterColorHandlersEvent.Item event) {
            event.register(new DynamicFluidContainerModel.Colors(), BCCoreItems.FRAGILE_FLUID_SHARD.get());
        }

    }


}

