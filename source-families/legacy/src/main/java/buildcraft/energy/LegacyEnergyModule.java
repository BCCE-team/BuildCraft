package buildcraft.energy;

import buildcraft.core.LegacyCoreModule;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.internal.module.BCModuleBootstrapContext;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.registry.BCDeferredRegister;
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

/** Loader-neutral ownership of the legacy Energy module bootstrap. */
public final class LegacyEnergyModule {
    public static final String MODID = "buildcraftenergy";
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", MODID);
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", MODID);

    private static final ResourceLocation ADVANCEMENT_FIND_OIL_SPOT =
        java.util.Objects.requireNonNull(ResourceLocation.tryParse(MODID + ":fine_riches"));
    private static final int OIL_SPOT_CHECK_INTERVAL = 40;
    private static final int OIL_SPOT_CHECK_RADIUS_XZ = 12;
    private static final int OIL_SPOT_CHECK_RADIUS_Y = 8;
    private static boolean gameplayEventsRegistered;

    private LegacyEnergyModule() {
    }

    public static void bootstrap(BCModuleBootstrapContext context) {
        // This hook owns the ordering slot for native fluid-type/source definitions. Forge fills it today;
        // Fabric receives the equivalent implementation during Stage 5.10 without cloning this module initializer.
        context.registerPlatformContent(BCModules.ENERGY);

        BCEnergyBlocks.init(context.registries());
        BCEnergyGuis.init();
        BCEnergyWorldGen.preInit(context.registries());
        BCEnergyConfig.preInit();

        LegacyCoreModule.BUILDCRAFT_TAB.addItemProvider(BCEnergyBlocks::getCreativeTabItems);
        LegacyCoreModule.TAB_FLUIDS.addItemProvider(BCEnergyFluids::getCreativeTabItems);

        context.registerConfig(MODID, BCEnergyConfig.config, BCEnergyConfig::onLoadConfig, BCEnergyConfig::onReloadConfig);
        ITEMS.register(context.registries());
        MENUS.register(context.registries());
        registerGameplayEvents();
        context.onCommonSetup(LegacyEnergyModule::commonSetup);
    }

    private static void commonSetup() {
        BCEnergyFluids.init();
        LegacyCoreModule.TAB_FLUIDS.setItem(BCEnergyFluids.OIL_BUCKET.get(0).get());
        BCEnergyRecipes.init();
        BCEnergyConfig.reloadConfig(MODID);
    }

    private static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) {
            return;
        }
        gameplayEventsRegistered = true;
        PlatformEvents.playerTick(BCEvents.Phase.END, LegacyEnergyModule::onPlayerTick);
    }

    private static void onPlayerTick(BCEvents.PlayerTick event) {
        if (event.phase() != BCEvents.Phase.END || event.player().level().isClientSide) {
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
}
