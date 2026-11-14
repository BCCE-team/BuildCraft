/*
 * BCCE 26.3 Fabric fluid registry. Names, heat tiers, density and flow ticks
 * match the NeoForge 26.3 data; native Fluid and BucketItem replace FluidType.
 */
package buildcraft.energy;

import buildcraft.energy.fluid.BCLiquidBlock;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FabricFluidProperties;
import net.fabricmc.fabric.api.registry.fluid.EntityFluidInteractionRegistry;
import net.fabricmc.fabric.api.registry.fluid.FluidBehavior;
import buildcraft.lib.compat.RegistryCompat;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class BCEnergyFluids {
    public static final int COOL_TEM = 300;
    public static final int HOT_TEM = 400;
    public static final int SEARING_TEM = 500;
    public static final int[] TEMS = {COOL_TEM, HOT_TEM, SEARING_TEM};
    public static final String[] HEAT_NAMES = {"cool", "hot", "searing"};
    public static final int SPOUT_OIL_SPREAD_LIMIT = 5;
    public static final String[] NAME = {
        "oil", "oil_residue", "oil_heavy", "oil_dense", "oil_distilled",
        "fuel_dense", "fuel_mixed_heavy", "fuel_light", "fuel_mixed_light", "fuel_gaseous"
    };
    /** Density, viscosity, boil tier, spread, light/dark tint, sticky, ignite, burn. */
    private static final int[][] DATA = {
        {900,2000,3,6,0xFF505050,0x050505,1,10,40},
        {1200,4000,3,4,0x100F10,0x421042,1,0,0},
        {850,1800,3,6,0xA08F1F,0x423520,1,5,5},
        {950,1600,3,5,0x876E77,0x422424,1,7,7},
        {750,1400,2,8,0xE4AF78,0xB47F00,0,7,7},
        {600,800,2,7,0xFFAF3F,0xE07F00,0,30,60},
        {700,1000,2,7,0xF2A700,0xC48700,0,30,60},
        {400,600,1,8,0xFFFF30,0xE4CF00,0,60,100},
        {650,900,1,9,0xF6D700,0xC4B700,0,60,100},
        {300,500,0,10,0xFAF630,0xE0D900,0,100,250}
    };

    public static final BCDeferredRegister<Fluid> FLUIDS =
        BCDeferredRegister.create("minecraft:fluid", BCEnergy.MODID);
    public static final TagKey<Fluid> IS_OIL = TagKey.create(Registries.FLUID,
        Identifier.fromNamespaceAndPath(BCEnergy.MODID, "is_oil"));
    public static final TagKey<Fluid> IS_FUEL = TagKey.create(Registries.FLUID,
        Identifier.fromNamespaceAndPath(BCEnergy.MODID, "is_fuel"));
    /** Physically liquid heat tiers only; gas variants keep their distinct behavior. */
    public static final TagKey<Fluid> ENTITY_LIQUID = TagKey.create(Registries.FLUID,
        Identifier.fromNamespaceAndPath(BCEnergy.MODID, "entity_liquid"));

    public static final BCFluid[] crudeOil = new BCFluid[3];
    public static final BCFluid[] oilResidue = new BCFluid[3];
    public static final BCFluid[] oilHeavy = new BCFluid[3];
    public static final BCFluid[] oilDense = new BCFluid[3];
    public static final BCFluid[] oilDistilled = new BCFluid[3];
    public static final BCFluid[] fuelDense = new BCFluid[3];
    public static final BCFluid[] fuelMixedHeavy = new BCFluid[3];
    public static final BCFluid[] fuelLight = new BCFluid[3];
    public static final BCFluid[] fuelMixedLight = new BCFluid[3];
    public static final BCFluid[] fuelGaseous = new BCFluid[3];

    public static final List<BCFluid> allFluids = new ArrayList<>();
    public static final List<BCRegistryEntry<BCFluid>> OIL_SOURCE = new ArrayList<>();
    public static final List<BCRegistryEntry<BucketItem>> OIL_BUCKET = new ArrayList<>();
    public static final List<BCRegistryEntry<LiquidBlock>> OIL_BLOCK = new ArrayList<>();
    public static BCRegistryEntry<BCFluid> SPOUT_OIL_SOURCE;
    public static BCRegistryEntry<BCFluid> SPOUT_OIL_FLOWING;
    public static BCRegistryEntry<LiquidBlock> SPOUT_OIL_BLOCK;
    private static boolean defined;
    private static boolean initialized;

    private BCEnergyFluids() {}

    public static synchronized void registry(BCRegistryBinder binder) {
        if (defined) return;
        defined = true;
        for (int i=0; i<NAME.length; i++) {
            for (int heat=0; heat<HEAT_NAMES.length; heat++) defineFluid(DATA[i], heat, NAME[i]);
        }
        defineSpoutOil();
        FLUIDS.register(binder);
    }

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        int index = 0;
        index = copyTriple(crudeOil, index);
        index = copyTriple(oilResidue, index);
        index = copyTriple(oilHeavy, index);
        index = copyTriple(oilDense, index);
        index = copyTriple(oilDistilled, index);
        index = copyTriple(fuelDense, index);
        index = copyTriple(fuelMixedHeavy, index);
        index = copyTriple(fuelLight, index);
        index = copyTriple(fuelMixedLight, index);
        copyTriple(fuelGaseous, index);
        for (int i=0; i<OIL_SOURCE.size(); i++) {
            int heat = i % HEAT_NAMES.length;
            String name = NAME[i / HEAT_NAMES.length];
            String commonName = heat == 0 ? name : HEAT_NAMES[heat] + "_" + name;
            FluidCompatRegistry.registerCanonical(TagKey.create(Registries.FLUID,
                Identifier.fromNamespaceAndPath("c", commonName)), OIL_SOURCE.get(i).get());
            // Pump and Mining Well retain NeoForge's viscosity threshold/heat behaviour.
            FabricFluidProperties.registerViscosity(OIL_SOURCE.get(i).get(),
                DATA[i / HEAT_NAMES.length][1] * (4 - heat) / 4);
        }
        FabricFluidProperties.registerViscosity(SPOUT_OIL_SOURCE.get(), DATA[0][1]);
        FluidCompatRegistry.registerCanonical(TagKey.create(Registries.FLUID,
            Identifier.fromNamespaceAndPath("c", "oil")), OIL_SOURCE.get(0).get(),
            Identifier.fromNamespaceAndPath(BCEnergy.MODID, "spout_oil"),
            Identifier.fromNamespaceAndPath(BCEnergy.MODID, "spout_oil_flowing"));
        // NeoForge BCFluidType.move uses a water-style motion step, but canSwim(false)
        // deliberately keeps oil out of vanilla water/swimming/drowning/mob AI.
        // Reproduce motion separately instead of WATER_LIKE (which enables unrelated rules).
        // The block's sticky slowdown is still applied by BCLiquidBlock.entityInside.
        EntityFluidInteractionRegistry.register(ENTITY_LIQUID, FluidBehavior.simple()
            .movementSpeed(0.02F)
            .movementSlowdown(0.8F, 0.8F)
            .flowingPushScale(0.014D)
            .allowSwimming(false)
            .enableDrowning(false)
            .makeMobsFloat(false)
            .makeRiddenMobsFloat(false)
            .build());
    }

    private static int copyTriple(BCFluid[] target, int index) {
        for (int heat=0; heat<target.length; heat++) {
            target[heat] = OIL_SOURCE.get(index++).get();
            allFluids.add(target[heat]);
        }
        return index;
    }

    public static List<ItemStack> getCreativeTabItems() {
        List<ItemStack> result = new ArrayList<>(OIL_BUCKET.size());
        for (BCRegistryEntry<BucketItem> bucket : OIL_BUCKET) result.add(bucket.get().getDefaultInstance());
        return result;
    }

    private static void defineFluid(int[] data, int heat, String name) {
        String fluidName = name + (heat == 0 ? "" : "_heat_" + heat);
        int density = heat >= data[2] ? -data[0] : data[0];
        // NeoForge 26.3 dynamically adjusts viscosity by heat tier.
        int viscosity = data[1] * (4 - heat) / 4;
        boolean sticky = data[6] != 0;
        References refs = new References();
        Supplier<BCFluid> source = () -> refs.source.get();
        Supplier<BCFluid> flowing = () -> refs.flowing.get();
        BCRegistryEntry<BucketItem> bucket = RegistryCompat.registerItem(BCEnergy.ITEMS,
            name + "/" + HEAT_NAMES[heat] + "_bucket",
            () -> new BucketItem(source.get(), RegistryCompat.itemProperties(
                new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET))));
        BCRegistryEntry<LiquidBlock> block = RegistryCompat.registerBlock(BCEnergyBlocks.BLOCKS,
            fluidName, () -> new BCLiquidBlock(source.get(),
                RegistryCompat.blockProperties(BlockBehaviour.Properties.of())
                    .overrideDescription("fluid_type.buildcraftenergy." + fluidName)
                    .mapColor(MapColor.COLOR_BLACK).replaceable().noCollision()
                    .strength(100.0f).noLootTable().liquid().pushReaction(PushReaction.POPPED),
                sticky, data[7], data[8]));
        // The old NeoForge viscosity remains material data; the 26.3 vanilla
        // FlowingFluid flow algorithm uses slope distance, drop-off and tick rate.
        // Keep the exact per-heat tick rate from the original oil registry.
        BCFluid.Properties properties = new BCFluid.Properties(source, flowing, block::get, bucket::get)
            .tickDelay(10 + 10 * (2 - heat)).density(density).heat(heat)
            .color(0xFFFFFFFF).flammable(data[8] > 0);
        // Like NeoForge, ordinary oil has no special source-radius restriction.
        // Only the spout oil defined below has SPOUT_OIL_SPREAD_LIMIT.
        refs.source = FLUIDS.register(fluidName,
            () -> new BCFluid.Source(properties).setColour(data[4], data[5]));
        refs.flowing = FLUIDS.register(fluidName + "_flowing",
            () -> new BCFluid.Flowing(properties).setColour(data[4], data[5]));
        OIL_SOURCE.add(refs.source);
        OIL_BUCKET.add(bucket);
        OIL_BLOCK.add(block);
    }

    private static void defineSpoutOil() {
        References refs = new References();
        Supplier<BCFluid> source = () -> refs.source.get();
        Supplier<BCFluid> flowing = () -> refs.flowing.get();
        SPOUT_OIL_BLOCK = RegistryCompat.registerBlock(BCEnergyBlocks.BLOCKS, "spout_oil",
            () -> new BCLiquidBlock(source.get(),
                RegistryCompat.blockProperties(BlockBehaviour.Properties.of())
                    .overrideDescription("fluid_type.buildcraftenergy.oil")
                    .mapColor(MapColor.COLOR_BLACK).replaceable().noCollision()
                    .strength(100.0f).noLootTable().liquid().pushReaction(PushReaction.POPPED),
                true, 10, 40));
        BCFluid.Properties properties = new BCFluid.Properties(source, flowing,
            SPOUT_OIL_BLOCK::get, () -> OIL_BUCKET.get(0).get())
                .tickDelay(30).density(DATA[0][0]).heat(0)
                .maxSourceSpreadDistance(SPOUT_OIL_SPREAD_LIMIT).flammable(true);
        refs.source = FLUIDS.register("spout_oil",
            () -> new BCFluid.Source(properties).setColour(DATA[0][4], DATA[0][5]));
        refs.flowing = FLUIDS.register("spout_oil_flowing",
            () -> new BCFluid.Flowing(properties).setColour(DATA[0][4], DATA[0][5]));
        SPOUT_OIL_SOURCE = refs.source;
        SPOUT_OIL_FLOWING = refs.flowing;
    }

    private static final class References {
        BCRegistryEntry<BCFluid> source;
        BCRegistryEntry<BCFluid> flowing;
    }
}
