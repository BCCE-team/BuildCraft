package buildcraft.energy;


import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.config.ConfigBinding;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import buildcraft.core.BCCore;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.FluidUtilBC;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(BCEnergy.MODID)
public class BCEnergy {
    public static final String MODID = "buildcraftenergy";
    static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation ADVANCEMENT_FIND_OIL_SPOT = new ResourceLocation(MODID, "fine_riches");
    private static final int OIL_SPOT_CHECK_INTERVAL = 40;
    private static final int OIL_SPOT_CHECK_RADIUS_XZ = 12;
    private static final int OIL_SPOT_CHECK_RADIUS_Y = 8;

    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", MODID);
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", MODID);


    public BCEnergy() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::gatherData);
        ConfigBinding.listen(modEventBus, BCEnergyConfig::onLoadConfig, BCEnergyConfig::onReloadConfig);

        BCEnergyFluids.registry(modEventBus);
        registerOptionalCompatItems();
        BCEnergyBlocks.init(RegistryBinding.on(modEventBus));
        BCEnergyGuis.init();
        BCEnergyWorldGen.preInit(RegistryBinding.on(modEventBus));
        BCEnergyConfig.preInit();
        ModLoadingContext.get().registerConfig(Type.COMMON, ConfigBinding.bind(BCEnergyConfig.config));
        registerGameplayEvents();
        // Register the Deferred Register to the mod event bus so blocks get registered
        // Register the Deferred Register to the mod event bus so items get registered
        RegistryBinding.register(ITEMS, modEventBus);
        RegistryBinding.register(MENUS, modEventBus);

        // Register ourselves for server and other game events we are interested in

    }
    private static void registerOptionalCompatItems() {
        if (!ModList.get().isLoaded("ic2")) {
            return;
        }
        try {
            Class.forName("buildcraft.compat.ic2.Ic2Compat")
                .getMethod("registerItems")
                .invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("Failed to register optional IC2 Classic items", e);
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        BCCore.tabFluids.setItem(BCEnergyFluids.OIL_BUCKET.get(0).get());
        BCEnergyFluids.init();
        BCEnergyRecipes.init();
        BCEnergyConfig.reloadConfig(MODID);

    }
    public void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeServer(),
            new BCEnergyRecipes.BCEnergyRecipeProvider(event.getGenerator()));
        event.getGenerator().addProvider(event.includeClient(),
            new BCEnergyProvider.BlockModel(event.getGenerator(), event.getExistingFileHelper()));
        event.getGenerator().addProvider(event.includeClient(),
            new BCEnergyProvider.BlockState(event.getGenerator(), event.getExistingFileHelper()));
        event.getGenerator().addProvider(event.includeClient(),
                new BCEnergyProvider.ItemModel(event.getGenerator(), event.getExistingFileHelper()));
    }

    public void onPlayerTick(BCEvents.PlayerTick event) {
        if (event.phase() != BCEvents.Phase.END || event.player().level.isClientSide) {
            return;
        }
        if (!(event.player() instanceof ServerPlayer player)) {
            return;
        }
        if (player.tickCount % OIL_SPOT_CHECK_INTERVAL != 0) {
            return;
        }
        if (hasAdvancement(player, ADVANCEMENT_FIND_OIL_SPOT)) {
            return;
        }
        if (isNearOilSpot(player)) {
            AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_FIND_OIL_SPOT);
        }
    }

    private static boolean hasAdvancement(ServerPlayer player, ResourceLocation advancementName) {
        Advancement advancement = player.getServer().getAdvancements().getAdvancement(advancementName);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static boolean isNearOilSpot(ServerPlayer player) {
        Level level = player.level;
        BlockPos center = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int y = -OIL_SPOT_CHECK_RADIUS_Y; y <= OIL_SPOT_CHECK_RADIUS_Y; y++) {
            for (int x = -OIL_SPOT_CHECK_RADIUS_XZ; x <= OIL_SPOT_CHECK_RADIUS_XZ; x++) {
                for (int z = -OIL_SPOT_CHECK_RADIUS_XZ; z <= OIL_SPOT_CHECK_RADIUS_XZ; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (isOilSpotBlock(level, pos)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isOilSpotBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }

        Fluid fluid = level.getFluidState(pos).getType();
        if (fluid != Fluids.EMPTY && FluidUtilBC.areFluidsEqual(fluid, BCEnergyFluids.crudeOil[0])) {
            return true;
        }

        if (level.getBlockState(pos).hasBlockEntity()) {
            BlockEntity tile = level.getBlockEntity(pos);
            return tile instanceof TileSpringOil;
        }
        return false;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event)
    {
        // Do something when the server starts
    }








    private boolean gameplayEventsRegistered;
    public synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.playerTick(BCEvents.Phase.END, this::onPlayerTick);
    }
}
