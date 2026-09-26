//? source if >=1.21.1
package buildcraft.energy;

import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.config.ConfigScreenRegistration;
import buildcraft.lib.internal.mj.MjCapabilities;

import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.core.BCCore;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.FluidUtilBC;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import buildcraft.lib.misc.CapUtil;

@Mod(BCEnergy.MODID)
public class BCEnergy {
    public static final String MODID = "buildcraftenergy";

    private static final Identifier ADVANCEMENT_FIND_OIL_SPOT =
        Identifier.fromNamespaceAndPath(MODID, "fine_riches");
    private static final int OIL_SPOT_CHECK_INTERVAL = 40;
    private static final int OIL_SPOT_CHECK_RADIUS_XZ = 12;
    private static final int OIL_SPOT_CHECK_RADIUS_Y = 8;

    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", MODID);
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", MODID);

    public BCEnergy(IEventBus modEventBus, ModContainer modContainer) {
        ConfigBinding.listen(modEventBus, BCEnergyConfig::onLoadConfig, BCEnergyConfig::onReloadConfig);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);

        BCEnergyFluids.registry(modEventBus);
        BCEnergyBlocks.init(RegistryBinding.on(modEventBus));
        BCEnergyGuis.init();
        BCEnergyWorldGen.preInit(RegistryBinding.on(modEventBus));
        BCEnergyConfig.preInit();

        BCCore.BUILDCRAFT_TAB.addItemProvider(BCEnergyBlocks::getCreativeTabItems);
        BCCore.tabFluids.addItemProvider(BCEnergyFluids::getCreativeTabItems);

        modContainer.registerConfig(Type.COMMON, ConfigBinding.bind(BCEnergyConfig.config));
        ConfigScreenRegistration.register(modContainer);
        RegistryBinding.register(ITEMS, modEventBus);
        RegistryBinding.register(MENUS, modEventBus);
        registerGameplayEvents();
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        BCEnergyFluids.init();
        BCCore.tabFluids.setItem(BCEnergyFluids.OIL_BUCKET.get(0).get());
        BCEnergyRecipes.init();
        BCEnergyConfig.reloadConfig(MODID);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerEngineCapabilities(event, BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get());
        registerEngineCapabilities(event, BCEnergyBlocks.ENGINE_IRON_TILE_BC8.get());
        registerEngineCapabilities(event, BCEnergyBlocks.ENGINE_FE_TILE_BC8.get());
        registerEngineCapabilities(event, BCEnergyBlocks.DYNAMO_MJ_TILE.get());

        BCCapabilityRegistration.registerBlockEntity(
            event, CapUtil.CAP_ITEMS, BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get()
        );
        BCCapabilityRegistration.registerBlockEntity(
            event, CapUtil.CAP_FLUIDS, BCEnergyBlocks.ENGINE_IRON_TILE_BC8.get()
        );
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerEngineCapabilities(
        RegisterCapabilitiesEvent event, BlockEntityType<BE> blockEntityType
    ) {
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_CONNECTOR, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_RECEIVER, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_REDSTONE_RECEIVER, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_READABLE, blockEntityType);
        BCCapabilityRegistration.registerBlockEntity(event, MjCapabilities.CAP_PASSIVE_PROVIDER, blockEntityType);
    }



    public void onPlayerTick(BCEvents.PlayerTick event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
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

    private static boolean hasAdvancement(ServerPlayer player, Identifier advancementName) {
        if (player.level().getServer() == null) {
            return false;
        }
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(advancementName);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static boolean isNearOilSpot(ServerPlayer player) {
        Level level = player.level();
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
        if (fluid != Fluids.EMPTY && BCEnergyFluids.crudeOil[0] != null
            && FluidUtilBC.areFluidsEqual(fluid, BCEnergyFluids.crudeOil[0])) {
            return true;
        }
        if (level.getBlockState(pos).hasBlockEntity()) {
            BlockEntity tile = level.getBlockEntity(pos);
            return tile instanceof TileSpringOil;
        }
        return false;
    }
    private boolean gameplayEventsRegistered;
    public synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.playerTick(BCEvents.Phase.END, this::onPlayerTick);
    }
}
