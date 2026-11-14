/* Fabric 26.3 bootstrap of the BuildCraft Energy module. */
package buildcraft.energy;

import buildcraft.core.BCCore;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.energy.tile.TileEngineFE;
import buildcraft.energy.tile.TileEngineIron_BC8;
import buildcraft.energy.tile.TileEngineStone_BC8;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.energy.fluid.BCLiquidBlock;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import buildcraft.lib.internal.capabilities.BCCapabilityRegistration;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.core.BuildCraftAPI;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.enums.EnumSpring;
import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.FluidUtilBC;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.RegistryBinding;
import java.util.Map;
import java.util.WeakHashMap;
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

/** All Energy registry names and gameplay logic match the 26.3 NeoForge module. */
public final class BCEnergy {
    public static final String MODID = "buildcraftenergy";
    public static final BCDeferredRegister<Item> ITEMS =
        BCDeferredRegister.create("minecraft:item", MODID);
    public static final BCDeferredRegister<MenuType<?>> MENUS =
        BCDeferredRegister.create("minecraft:menu", MODID);

    private static final Identifier ADVANCEMENT_FIND_OIL_SPOT =
        Identifier.fromNamespaceAndPath(MODID, "fine_riches");
    private static final int OIL_SPOT_CHECK_INTERVAL = 40;
    private static final int OIL_SPOT_CHECK_RADIUS_XZ = 12;
    private static final int OIL_SPOT_CHECK_RADIUS_Y = 8;
    private static final Map<ServerPlayer, NegativeOilScan> NEGATIVE_OIL_SCANS = new WeakHashMap<>();
    private static boolean bootstrapped;

    private BCEnergy() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        BuildCraftAPI.withModule(MODID, BCEnergy::bootstrapInternal);
        bootstrapped = true;
    }

    private static void bootstrapInternal() {
        // Register fluids before their blocks/buckets, since all three hold
        // lazy links to the same original registry entries.
        BCEnergyFluids.registry(RegistryBinding.direct());
        BCEnergyBlocks.init(RegistryBinding.direct());
        BCEnergyGuis.init();
        BCEnergyWorldGen.preInit(RegistryBinding.direct());
        BCEnergyConfig.preInit();
        ITEMS.register(RegistryBinding.direct());
        MENUS.register(RegistryBinding.direct());

        BCEnergyFluids.init();
        EnumSpring.OIL.liquidBlock = BCEnergyFluids.SPOUT_OIL_BLOCK.get().defaultBlockState();
        EnumSpring.OIL.tileConstructor = TileSpringOil::new;

        BlockEngine_BC8 engines = (BlockEngine_BC8) BCCoreBlocks.ENGINE_BC8.get();
        engines.registerEngine(EnumEngineType.STONE, TileEngineStone_BC8::new);
        engines.registerEngine(EnumEngineType.IRON, TileEngineIron_BC8::new);
        engines.registerEngine(EnumEngineType.FE, TileEngineFE::new);
        BCCore.BUILDCRAFT_TAB.addItemProvider(BCEnergyBlocks::getCreativeTabItems);
        BCCore.tabFluids.addItemProvider(BCEnergyFluids::getCreativeTabItems);
        BCCore.tabFluids.setItem(BCEnergyFluids.OIL_BUCKET.get(0).get());
        registerCapabilities();

        ConfigBinding.listen(MODID, BCEnergyConfig::onLoadConfig, BCEnergyConfig::onReloadConfig);
        ConfigBinding.bind(MODID, BCEnergyConfig.config);
        BCEnergyRecipes.init();
        BCEnergyConfig.reloadConfig(MODID);
        PlatformEvents.playerTick(BCEvents.Phase.END, BCEnergy::onPlayerTick);
    }

    /** Fabric owns fire spread in FlammableBlockRegistry, not the NeoForge-only
     * Block#getFlammability hook. Refresh on energy config reload too. */
    static void refreshFlammability() {
        FlammableBlockRegistry fire = FlammableBlockRegistry.getDefaultInstance();
        for (var entry : BCEnergyFluids.OIL_BLOCK) {
            BCLiquidBlock block = (BCLiquidBlock) entry.get();
            fire.add(block, BCEnergyConfig.enableOilBurn ? block.igniteOdds : 0,
                BCEnergyConfig.enableOilBurn ? block.burnOdds : 0);
        }
        if (BCEnergyFluids.SPOUT_OIL_BLOCK != null) {
            BCLiquidBlock block = (BCLiquidBlock) BCEnergyFluids.SPOUT_OIL_BLOCK.get();
            fire.add(block, BCEnergyConfig.enableOilBurn ? block.igniteOdds : 0,
                BCEnergyConfig.enableOilBurn ? block.burnOdds : 0);
        }
    }

    private static void registerCapabilities() {
        registerEngineCapabilities(BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get());
        registerEngineCapabilities(BCEnergyBlocks.ENGINE_IRON_TILE_BC8.get());
        registerEngineCapabilities(BCEnergyBlocks.ENGINE_FE_TILE_BC8.get());
        registerEngineCapabilities(BCEnergyBlocks.DYNAMO_MJ_TILE.get());
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_ITEMS,
            BCEnergyBlocks.ENGINE_STONE_TILE_BC8.get());
        BCCapabilityRegistration.registerBlockEntity(CapUtil.CAP_FLUIDS,
            BCEnergyBlocks.ENGINE_IRON_TILE_BC8.get());
    }

    private static <BE extends BlockEntity & IBCCapabilityProvider> void registerEngineCapabilities(
        BlockEntityType<BE> type) {
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_CONNECTOR, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_REDSTONE_RECEIVER, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_READABLE, type);
        BCCapabilityRegistration.registerBlockEntity(MjCapabilities.CAP_PASSIVE_PROVIDER, type);
    }

    private static void onPlayerTick(BCEvents.PlayerTick event) {
        if (event.getEntity().level().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.tickCount % OIL_SPOT_CHECK_INTERVAL != 0) return;
        if (hasAdvancement(player, ADVANCEMENT_FIND_OIL_SPOT)) return;
        if (isNearOilSpot(player)) AdvancementUtil.unlockAdvancement(player, ADVANCEMENT_FIND_OIL_SPOT);
    }

    private static boolean hasAdvancement(ServerPlayer player, Identifier id) {
        if (player.level().getServer() == null) return false;
        AdvancementHolder advancement = player.level().getServer().getAdvancements().get(id);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private record NegativeOilScan(Level level, BlockPos center, long tick) {}

    private static boolean isNearOilSpot(ServerPlayer player) {
        Level level = player.level();
        BlockPos center = player.blockPosition();
        long now = level.getGameTime();
        NegativeOilScan previous = NEGATIVE_OIL_SCANS.get(player);
        if (previous != null && previous.level() == level
            && now >= previous.tick() && now - previous.tick() < 200
            && center.distSqr(previous.center()) <= 16) return false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = -OIL_SPOT_CHECK_RADIUS_Y; y <= OIL_SPOT_CHECK_RADIUS_Y; y++) {
            for (int x = -OIL_SPOT_CHECK_RADIUS_XZ; x <= OIL_SPOT_CHECK_RADIUS_XZ; x++) {
                for (int z = -OIL_SPOT_CHECK_RADIUS_XZ; z <= OIL_SPOT_CHECK_RADIUS_XZ; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (isOilSpotBlock(level, pos)) {
                        NEGATIVE_OIL_SCANS.remove(player);
                        return true;
                    }
                }
            }
        }
        NEGATIVE_OIL_SCANS.put(player, new NegativeOilScan(level, center.immutable(), now));
        return false;
    }

    private static boolean isOilSpotBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        Fluid fluid = level.getFluidState(pos).getType();
        if (fluid != Fluids.EMPTY && BCEnergyFluids.crudeOil[0] != null
            && FluidUtilBC.areFluidsEqual(fluid, BCEnergyFluids.crudeOil[0])) return true;
        if (level.getBlockState(pos).hasBlockEntity()) {
            return level.getBlockEntity(pos) instanceof TileSpringOil;
        }
        return false;
    }
}
