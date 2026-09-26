package buildcraft.energy;

import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.ArrayList;
import java.util.List;

import buildcraft.core.BCCore;
import buildcraft.energy.fluid.BCFluidType;
import buildcraft.energy.fluid.BCLiquidBlock;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FluidCompatRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.material.MaterialColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.ForgeRegistries;

public class BCEnergyFluids {
    public static final int COOL_TEM = 300;
    public static final int HOT_TEM = 400;
    public static final int SEARING_TEM = 500;
    public static final int[] TEMS = {COOL_TEM, HOT_TEM, SEARING_TEM};
    public static final String[] HEAT_NAMES = {"cool", "hot", "searing"};
    public static final int SPOUT_OIL_SPREAD_LIMIT = 5;

    public static BCFluid[] crudeOil = new BCFluid[3];
    /** All 3 fuels (no residue) */
    public static BCFluid[] oilDistilled = new BCFluid[3];
    /** The 3 heaviest components (fuelLight, fuelDense and oilResidue) */
    public static BCFluid[] oilHeavy = new BCFluid[3];
    /** The 2 lightest fuels (no dense fuel) */
    public static BCFluid[] fuelMixedLight = new BCFluid[3];
    /** The 2 heaviest fuels (no gaseous fuel) */
    public static BCFluid[] fuelMixedHeavy = new BCFluid[3];
    /** The 2 heaviest products (fuelDense and oilResidue) */
    public static BCFluid[] oilDense = new BCFluid[3];

    // End products in order from least to most dense
    public static BCFluid[] fuelGaseous = new BCFluid[3];
    public static BCFluid[] fuelLight = new BCFluid[3];
    public static BCFluid[] fuelDense = new BCFluid[3];
    public static BCFluid[] oilResidue = new BCFluid[3];

//    public static BCFluid tar;

    public static final List<BCFluid> allFluids = new ArrayList<>();

    private static int[][] data = { //@formatter:off
            // Tabular form of all the fluid values
            // density, viscosity, boil, spread,  tex_light,   tex_dark, sticky, igniteOdds, burnOdds
            {      900,      2000,    3,      6, 0xFF505050, 0x05_05_05,      1,         10,      40},// Crude Oil
            {     1200,      4000,    3,      4, 0x10_0F_10, 0x42_10_42,      1,         0,        0},// Residue
            {      850,      1800,    3,      6, 0xA0_8F_1F, 0x42_35_20,      1,         5,        5},// Heavy Oil
            {      950,      1600,    3,      5, 0x87_6E_77, 0x42_24_24,      1,         7,        7},// Dense Oil
            {      750,      1400,    2,      8, 0xE4_AF_78, 0xB4_7F_00,      0,         7,        7},// Distilled Oil
            {      600,       800,    2,      7, 0xFF_AF_3F, 0xE0_7F_00,      0,         30,      60},// Dense Fuel
            {      700,      1000,    2,      7, 0xF2_A7_00, 0xC4_87_00,      0,         30,      60},// Mixed Heavy Fuels
            {      400,       600,    1,      8, 0xFF_FF_30, 0xE4_CF_00,      0,         60,     100},// Light Fuel
            {      650,       900,    1,      9, 0xF6_D7_00, 0xC4_B7_00,      0,         60,     100},// Mixed Light Fuels
            {      300,       500,    0,     10, 0xFA_F6_30, 0xE0_D9_00,      0,         100,    250},// Gas Fuel
        };//@formatter:on


    public static final Material FLAMMABLELIQUID = new Material(MaterialColor.COLOR_BLACK,true,false,true,false,true,true,PushReaction.DESTROY);


    public static final List<BCRegistryEntry<BCFluidType>> OIL_TYPE = new ArrayList<>();
    public static final List<BCRegistryEntry<BCFluid>> OIL_SOURCE = new ArrayList<>();
    public static final List<BCRegistryEntry<BucketItem>> OIL_BUCKET = new ArrayList<>();
    public static final List<BCRegistryEntry<LiquidBlock>> OIL_BLOCK = new ArrayList<>();
    public static BCRegistryEntry<BCFluid> SPOUT_OIL_SOURCE;
    public static BCRegistryEntry<BCFluid> SPOUT_OIL_FLOWING;
    public static BCRegistryEntry<LiquidBlock> SPOUT_OIL_BLOCK;

    public static final BCDeferredRegister<Fluid> FLUIDS = BCDeferredRegister.create("minecraft:fluid", BCEnergy.MODID);
    public static final BCDeferredRegister<FluidType> FLUID_TYPES = BCDeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, BCEnergy.MODID);

    public static final TagKey<Fluid> IS_OIL = TagKey.create(Registry.FLUID_REGISTRY, new ResourceLocation(BCEnergy.MODID, "is_oil"));
    public static final TagKey<Fluid> IS_FUEL = TagKey.create(Registry.FLUID_REGISTRY, new ResourceLocation(BCEnergy.MODID, "is_fuel"));

    public static final String[] NAME = {"oil","oil_residue","oil_heavy","oil_dense","oil_distilled",
              "fuel_dense","fuel_mixed_heavy","fuel_light","fuel_mixed_light","fuel_gaseous"};



    public static void registry(IEventBus bus) {
        registryFluid();
        RegistryBinding.register(FLUID_TYPES, bus);
        RegistryBinding.register(FLUIDS, bus);
    }

    public static void init() {
        int id = 0;
        crudeOil[0] = OIL_SOURCE.get(id++).get();
        crudeOil[1] = OIL_SOURCE.get(id++).get();
        crudeOil[2] = OIL_SOURCE.get(id++).get();
        oilResidue[0] = OIL_SOURCE.get(id++).get();
        oilResidue[1] = OIL_SOURCE.get(id++).get();
        oilResidue[2] = OIL_SOURCE.get(id++).get();
        oilHeavy[0] = OIL_SOURCE.get(id++).get();
        oilHeavy[1] = OIL_SOURCE.get(id++).get();
        oilHeavy[2] = OIL_SOURCE.get(id++).get();
        oilDense[0] = OIL_SOURCE.get(id++).get();
        oilDense[1] = OIL_SOURCE.get(id++).get();
        oilDense[2] = OIL_SOURCE.get(id++).get();
        oilDistilled[0] = OIL_SOURCE.get(id++).get();
        oilDistilled[1] = OIL_SOURCE.get(id++).get();
        oilDistilled[2] = OIL_SOURCE.get(id++).get();
        fuelDense[0] = OIL_SOURCE.get(id++).get();
        fuelDense[1] = OIL_SOURCE.get(id++).get();
        fuelDense[2] = OIL_SOURCE.get(id++).get();
        fuelMixedHeavy[0] = OIL_SOURCE.get(id++).get();
        fuelMixedHeavy[1] = OIL_SOURCE.get(id++).get();
        fuelMixedHeavy[2] = OIL_SOURCE.get(id++).get();
        fuelLight[0] = OIL_SOURCE.get(id++).get();
        fuelLight[1] = OIL_SOURCE.get(id++).get();
        fuelLight[2] = OIL_SOURCE.get(id++).get();
        fuelMixedLight[0] = OIL_SOURCE.get(id++).get();
        fuelMixedLight[1] = OIL_SOURCE.get(id++).get();
        fuelMixedLight[2] = OIL_SOURCE.get(id++).get();
        fuelGaseous[0] = OIL_SOURCE.get(id++).get();
        fuelGaseous[1] = OIL_SOURCE.get(id++).get();
        fuelGaseous[2] = OIL_SOURCE.get(id++).get();

        registerFluidCompatibility();
    }

    private static void registerFluidCompatibility() {
        int index = 0;
        for (String name : NAME) {
            for (int heat = 0; heat < HEAT_NAMES.length; heat++) {
                String fullName = name + (heat == 0 ? "" : "_heat_" + heat);
                String commonName = heat == 0 ? name : HEAT_NAMES[heat] + "_" + name;
                TagKey<Fluid> tag = TagKey.create(Registry.FLUID_REGISTRY,
                    new ResourceLocation("forge", commonName));

                FluidCompatRegistry.registerCanonical(tag, OIL_SOURCE.get(index++).get(),
                    new ResourceLocation("ic2", fullName),
                    new ResourceLocation("ic2", commonName),
                    new ResourceLocation("ic2", name + "_" + HEAT_NAMES[heat]));
            }
        }
        TagKey<Fluid> crudeOilTag = TagKey.create(Registry.FLUID_REGISTRY, new ResourceLocation("forge", "oil"));
        FluidCompatRegistry.registerCanonical(crudeOilTag, OIL_SOURCE.get(0).get(),
            new ResourceLocation(BCEnergy.MODID, "spout_oil"),
            new ResourceLocation(BCEnergy.MODID, "spout_oil_flowing"));
    }

    public static void registryFluid() {
        for(int id=0;id<NAME.length;id++)
                defineFluids(data[id], NAME[id]);
        defineSpoutOil();
    }

    private static void defineFluids(int[] data, String name) {
        for (int h = 0; h < 3; h++) {
            defineFluid(data, h, name);
        }
    }

    private static void defineFluid(int[] data, int heat, String name) {
        final int density = data[0];
        final int baseViscosity = data[1];
        final int boilPoint = data[2];
        final int baseQuanta = data[3];
        final int texLight = data[4];
        final int texDark = data[5];
        final boolean sticky = data[6] == 1;

        int igniteOdds = data[7];
        int burnOdds = data[8];

        String fullName = name + (heat == 0 ? "" : "_heat_" + heat);
        int tempAdjustedViscosity = baseViscosity * (4 - heat) / 4;
        int boilAdjustedDensity = density * (heat >= boilPoint ? -1 : 1);

        String fluidTexture = "buildcraftenergy:blocks/fluids/" + name + "/"+ HEAT_NAMES[heat];

        BCRegistryEntry<BCFluidType> TYPE = FLUID_TYPES.register(fullName, () ->
            new BCFluidType(FluidType.Properties.create().canSwim(false).density(boilAdjustedDensity).viscosity(tempAdjustedViscosity).temperature(300 + 50*heat).rarity(Rarity.UNCOMMON)
                    , new ResourceLocation(fluidTexture + "_still"), new ResourceLocation(fluidTexture + "_flow"), /*(texLight + texDark)/2)*/0xFFFFFFFF));
        FluidReferences refs = new FluidReferences();
        java.util.function.Supplier<BCFluid> SOURCE = () -> refs.source.get();
        java.util.function.Supplier<BCFluid> FLOWING = () -> refs.flowing.get();
        BCRegistryEntry<BucketItem> BUCKET = BCEnergy.ITEMS.register(name+"/"+HEAT_NAMES[heat]+"_bucket", () -> new BucketItem(SOURCE,new Item.Properties().stacksTo(1).tab(BCCore.tabFluids).craftRemainder(Items.BUCKET)));
        BCRegistryEntry<LiquidBlock> FUEL_GAS_COOL_BLOCK = BCEnergyBlocks.BLOCKS.register(fullName, () ->
            new BCLiquidBlock(SOURCE, BlockBehaviour.Properties.of(FLAMMABLELIQUID).noCollission().strength(100.0F).noLootTable(), sticky, igniteOdds, burnOdds));
        ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(TYPE, SOURCE, FLOWING).bucket(BUCKET).block(FUEL_GAS_COOL_BLOCK).tickRate(10 + 10*(2 - heat))/*.levelDecreasePerBlock(boilAdjustedDensity)*/;//.slopeFindDistance(0);
        refs.source = FLUIDS.register(fullName, () -> new BCFluid.Source(properties).setHeat(heat));
        refs.flowing = FLUIDS.register(fullName+"_flowing", () -> new BCFluid.Flowing(properties).setHeat(heat));
        OIL_TYPE.add(TYPE);
        OIL_SOURCE.add(refs.source);
        OIL_BUCKET.add(BUCKET);
        OIL_BLOCK.add(FUEL_GAS_COOL_BLOCK);


    }
    private static void defineSpoutOil() {
        FluidReferences refs = new FluidReferences();
        java.util.function.Supplier<BCFluid> source = () -> refs.source.get();
        java.util.function.Supplier<BCFluid> flowing = () -> refs.flowing.get();
        SPOUT_OIL_BLOCK = BCEnergyBlocks.BLOCKS.register("spout_oil", () ->
            new BCLiquidBlock(source, BlockBehaviour.Properties.of(FLAMMABLELIQUID).noCollission().strength(100.0F).noLootTable(), true, 10, 40));
        ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(OIL_TYPE.get(0), source, flowing)
            .bucket(OIL_BUCKET.get(0)).block(SPOUT_OIL_BLOCK).tickRate(30);
        refs.source = FLUIDS.register("spout_oil", () ->
            new BCFluid.Source(properties).setHeat(0).setMaxSourceSpreadDistance(SPOUT_OIL_SPREAD_LIMIT));
        refs.flowing = FLUIDS.register("spout_oil_flowing", () ->
            new BCFluid.Flowing(properties).setHeat(0).setMaxSourceSpreadDistance(SPOUT_OIL_SPREAD_LIMIT));
        SPOUT_OIL_SOURCE = refs.source;
        SPOUT_OIL_FLOWING = refs.flowing;
    }

    private static final class FluidReferences {
        private BCRegistryEntry<BCFluid> source;
        private BCRegistryEntry<BCFluid> flowing;
    }
}
