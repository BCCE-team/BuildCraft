package buildcraft.energy;

import buildcraft.lib.platform.registry.RegistryBinding;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import buildcraft.energy.fluid.BCFluidType;
import buildcraft.energy.fluid.BCLiquidBlock;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FluidCompatRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class BCEnergyFluids {
    public static final int COOL_TEM = 300;
    public static final int HOT_TEM = 400;
    public static final int SEARING_TEM = 500;
    public static final int[] TEMS = { COOL_TEM, HOT_TEM, SEARING_TEM };
    public static final String[] HEAT_NAMES = { "cool", "hot", "searing" };

    public static final BCFluid[] crudeOil = new BCFluid[3];
    public static final BCFluid[] oilDistilled = new BCFluid[3];
    public static final BCFluid[] oilHeavy = new BCFluid[3];
    public static final BCFluid[] fuelMixedLight = new BCFluid[3];
    public static final BCFluid[] fuelMixedHeavy = new BCFluid[3];
    public static final BCFluid[] oilDense = new BCFluid[3];
    public static final BCFluid[] fuelGaseous = new BCFluid[3];
    public static final BCFluid[] fuelLight = new BCFluid[3];
    public static final BCFluid[] fuelDense = new BCFluid[3];
    public static final BCFluid[] oilResidue = new BCFluid[3];

    public static final List<BCFluid> allFluids = new ArrayList<>();

    private static final int[][] DATA = {
        // density, viscosity, boil, spread, tex light, tex dark, sticky, ignite, burn
        { 900, 2000, 3, 6, 0xFF505050, 0x05_05_05, 1, 10, 40 },
        { 1200, 4000, 3, 4, 0x10_0F_10, 0x42_10_42, 1, 0, 0 },
        { 850, 1800, 3, 6, 0xA0_8F_1F, 0x42_35_20, 1, 5, 5 },
        { 950, 1600, 3, 5, 0x87_6E_77, 0x42_24_24, 1, 7, 7 },
        { 750, 1400, 2, 8, 0xE4_AF_78, 0xB4_7F_00, 0, 7, 7 },
        { 600, 800, 2, 7, 0xFF_AF_3F, 0xE0_7F_00, 0, 30, 60 },
        { 700, 1000, 2, 7, 0xF2_A7_00, 0xC4_87_00, 0, 30, 60 },
        { 400, 600, 1, 8, 0xFF_FF_30, 0xE4_CF_00, 0, 60, 100 },
        { 650, 900, 1, 9, 0xF6_D7_00, 0xC4_B7_00, 0, 60, 100 },
        { 300, 500, 0, 10, 0xFA_F6_30, 0xE0_D9_00, 0, 100, 250 }
    };

    public static final List<BCRegistryEntry<BCFluidType>> OIL_TYPE = new ArrayList<>();
    public static final List<BCRegistryEntry<BCFluid>> OIL_SOURCE = new ArrayList<>();
    public static final List<BCRegistryEntry<BucketItem>> OIL_BUCKET = new ArrayList<>();
    public static final List<BCRegistryEntry<LiquidBlock>> OIL_BLOCK = new ArrayList<>();

    public static final BCDeferredRegister<Fluid> FLUIDS = BCDeferredRegister.create("minecraft:fluid", BCEnergy.MODID);
    public static final BCDeferredRegister<FluidType> FLUID_TYPES =
        BCDeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, BCEnergy.MODID);

    public static final TagKey<Fluid> IS_OIL = TagKey.create(
        Registries.FLUID, ResourceLocation.fromNamespaceAndPath(BCEnergy.MODID, "is_oil")
    );
    public static final TagKey<Fluid> IS_FUEL = TagKey.create(
        Registries.FLUID, ResourceLocation.fromNamespaceAndPath(BCEnergy.MODID, "is_fuel")
    );

    public static final String[] NAME = {
        "oil", "oil_residue", "oil_heavy", "oil_dense", "oil_distilled",
        "fuel_dense", "fuel_mixed_heavy", "fuel_light", "fuel_mixed_light", "fuel_gaseous"
    };

    private BCEnergyFluids() {
    }

    public static void registry(IEventBus bus) {
        registryFluid();
        RegistryBinding.register(FLUID_TYPES, bus);
        RegistryBinding.register(FLUIDS, bus);
    }

    public static void init() {
        int id = 0;
        id = copyTriple(crudeOil, id);
        id = copyTriple(oilResidue, id);
        id = copyTriple(oilHeavy, id);
        id = copyTriple(oilDense, id);
        id = copyTriple(oilDistilled, id);
        id = copyTriple(fuelDense, id);
        id = copyTriple(fuelMixedHeavy, id);
        id = copyTriple(fuelLight, id);
        id = copyTriple(fuelMixedLight, id);
        copyTriple(fuelGaseous, id);
        registerFluidCompatibility();
    }

    private static int copyTriple(BCFluid[] target, int index) {
        for (int heat = 0; heat < target.length; heat++) {
            target[heat] = OIL_SOURCE.get(index++).get();
        }
        return index;
    }

    private static void registerFluidCompatibility() {
        int index = 0;
        for (String name : NAME) {
            for (int heat = 0; heat < HEAT_NAMES.length; heat++) {
                String commonName = heat == 0 ? name : HEAT_NAMES[heat] + "_" + name;
                TagKey<Fluid> tag = TagKey.create(
                    Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", commonName)
                );
                FluidCompatRegistry.registerCanonical(tag, OIL_SOURCE.get(index++).get());
            }
        }
    }

    public static List<ItemStack> getCreativeTabItems() {
        List<ItemStack> items = new ArrayList<>(OIL_BUCKET.size());
        for (BCRegistryEntry<BucketItem> bucket : OIL_BUCKET) {
            items.add(bucket.get().getDefaultInstance());
        }
        return items;
    }

    private static void registryFluid() {
        for (int id = 0; id < NAME.length; id++) {
            defineFluids(DATA[id], NAME[id]);
        }
    }

    private static void defineFluids(int[] data, String name) {
        for (int heat = 0; heat < HEAT_NAMES.length; heat++) {
            defineFluid(data, heat, name);
        }
    }

    private static void defineFluid(int[] data, int heat, String name) {
        int density = data[0];
        int baseViscosity = data[1];
        int boilPoint = data[2];
        boolean sticky = data[6] == 1;
        int igniteOdds = data[7];
        int burnOdds = data[8];

        String fullName = name + (heat == 0 ? "" : "_heat_" + heat);
        int tempAdjustedViscosity = baseViscosity * (4 - heat) / 4;
        int boilAdjustedDensity = density * (heat >= boilPoint ? -1 : 1);
        int tint = 0xFFFFFFFF;
        String texture = BCEnergy.MODID + ":blocks/fluids/" + name + "/" + HEAT_NAMES[heat];

        BCRegistryEntry<BCFluidType> type = FLUID_TYPES.register(fullName, () -> new BCFluidType(
            FluidType.Properties.create()
                .canSwim(false)
                .density(boilAdjustedDensity)
                .viscosity(tempAdjustedViscosity)
                .temperature(300 + 50 * heat)
                .rarity(Rarity.UNCOMMON),
            ResourceLocation.parse(texture + "_still"),
            ResourceLocation.parse(texture + "_flow"),
            tint
        ));

        FluidReferences refs = new FluidReferences();
        Supplier<BCFluid> source = () -> refs.source.get();
        Supplier<BCFluid> flowing = () -> refs.flowing.get();

        BCRegistryEntry<BucketItem> bucket = BCEnergy.ITEMS.register(
            name + "/" + HEAT_NAMES[heat] + "_bucket",
            () -> new BucketItem(source.get(), new Item.Properties().stacksTo(1).craftRemainder(Items.BUCKET))
        );
        BCRegistryEntry<LiquidBlock> block = BCEnergyBlocks.BLOCKS.register(fullName, () -> new BCLiquidBlock(
            source.get(),
            BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .replaceable()
                .noCollission()
                .strength(100.0F)
                .noLootTable()
                .liquid()
                .pushReaction(PushReaction.DESTROY),
            sticky,
            igniteOdds,
            burnOdds
        ));

        BaseFlowingFluid.Properties properties = new BaseFlowingFluid.Properties(type, source, flowing)
            .bucket(bucket)
            .block(block)
            .tickRate(10 + 10 * (2 - heat));

        refs.source = FLUIDS.register(fullName, () -> new BCFluid.Source(properties).setHeat(heat));
        refs.flowing = FLUIDS.register(fullName + "_flowing", () -> new BCFluid.Flowing(properties).setHeat(heat));

        OIL_TYPE.add(type);
        OIL_SOURCE.add(refs.source);
        OIL_BUCKET.add(bucket);
        OIL_BLOCK.add(block);
    }

    private static final class FluidReferences {
        private BCRegistryEntry<BCFluid> source;
        private BCRegistryEntry<BCFluid> flowing;
    }
}
