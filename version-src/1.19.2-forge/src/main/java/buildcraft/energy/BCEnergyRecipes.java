/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy;

import java.util.function.Consumer;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.fuels.CoolantProfile;
import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.fuels.FluidSelector;
import buildcraft.api.v2.fuels.FuelProfile;
import buildcraft.api.v2.fuels.SolidCoolantProfile;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FuelApiBridge;

import net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;

public class BCEnergyRecipes {

    private static final int TIME_BASE = 240_000;
    private static final DefinitionProvenance BUILTIN =
        new DefinitionProvenance("buildcraftenergy", "built-in", 0);
    private static boolean initialized;

    private BCEnergyRecipes() {}

    public static synchronized void init() {
        if (initialized) return;

        registerCoolant("coolant/water", Fluids.WATER, 0.0023);
        registerSolidCoolant("solid_coolant/ice", Blocks.ICE.asItem(), 1.5);
        registerSolidCoolant("solid_coolant/packed_ice", Blocks.PACKED_ICE.asItem(), 2.0);

        final int oil = 8;
        final int gas = 16;
        final int light = 4;
        final int dense = 2;
        final int residue = 1;
        final int gasLight = 10;
        final int lightDense = 5;
        final int denseResidue = 2;
        final int lightDenseResidue = 3;
        final int gasLightDense = 8;

        addFuel(BCEnergyFluids.fuelGaseous, gas, 8, 4);
        addFuel(BCEnergyFluids.fuelLight, light, 6, 6);
        addFuel(BCEnergyFluids.fuelDense, dense, 4, 12);
        addFuel(BCEnergyFluids.fuelMixedLight, gasLight, 3, 5);
        addFuel(BCEnergyFluids.fuelMixedHeavy, lightDense, 5, 8);
        addDirtyFuel(BCEnergyFluids.oilDense, denseResidue, 4, 4);
        addFuel(BCEnergyFluids.oilDistilled, gasLightDense, 1, 5);
        addDirtyFuel(BCEnergyFluids.oilHeavy, lightDenseResidue, 2, 4);
        addDirtyFuel(BCEnergyFluids.crudeOil, oil, 3, 4);

        // The identical packaged JSON is also used for local client-side recipe previews.
        // An authoritative server datapack reload replaces this fallback catalogue.
        buildcraft.lib.recipe.MachineRecipeDatapackReloadListener.bootstrapBundled();
        initialized = true;
    }

    private static EnergyFluidService energyFluids() {
        return BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS);
    }

    private static ResourceLocation id(String path) {
        ResourceLocation id = ResourceLocation.tryParse("buildcraftenergy:" + path);
        if (id == null) throw new IllegalArgumentException("Invalid built-in energy id: " + path);
        return id;
    }

    private static void registerCoolant(String path, Fluid fluid, double degreesPerMb) {
        FluidVariant variant = FuelApiBridge.variantOf(new FluidStack(fluid, 1));
        energyFluids().register(id(path), CoolantProfile.constant(FluidSelector.fluid(variant.fluidId()), degreesPerMb), BUILTIN);
    }

    private static void registerSolidCoolant(String path, net.minecraft.world.item.Item item, double multiplier) {
        FluidVariant water = FuelApiBridge.variantOf(new FluidStack(Fluids.WATER, 1));
        SolidCoolantProfile profile = new SolidCoolantProfile(
            stack -> stack != null && !stack.isEmpty() && stack.getItem() == item,
            stack -> {
                long amount = Math.round(stack.getCount() * 1000.0 * multiplier);
                return amount <= 0 ? FluidVolume.empty() : FluidVolume.of(water, FluidAmount.of(amount));
            }
        );
        energyFluids().register(id(path), profile, BUILTIN);
    }

    private static Fluid getFirstOrNull(Fluid[] fluids) {
        return fluids == null || fluids.length == 0 ? null : fluids[0];
    }

    private static void addFuel(Fluid[] input, int amountDifference, int multiplier, int boostOverFour) {
        registerFuel(input, amountDifference, multiplier, boostOverFour, false);
    }

    private static void addDirtyFuel(Fluid[] input, int amountDifference, int multiplier, int boostOverFour) {
        registerFuel(input, amountDifference, multiplier, boostOverFour, true);
    }

    private static void registerFuel(
        Fluid[] input, int amountDifference, int multiplier, int boostOverFour, boolean dirty
    ) {
        Fluid fuel = getFirstOrNull(input);
        if (fuel == null) return;
        long powerPerTick = multiplier * MjAmount.MICRO_MJ_PER_MJ;
        int totalTime = TIME_BASE * boostOverFour / 4 / multiplier / amountDifference;
        FluidVariant fuelVariant = FuelApiBridge.variantOf(new FluidStack(fuel, 1));
        FuelProfile profile;
        Fluid residue = dirty ? getFirstOrNull(BCEnergyFluids.oilResidue) : null;
        if (residue == null) {
            profile = FuelProfile.clean(FluidSelector.fluid(fuelVariant.fluidId()), powerPerTick, totalTime);
        } else {
            FluidVolume residuePerBucket = FluidVolume.of(
                FuelApiBridge.variantOf(new FluidStack(residue, 1)), FluidAmount.of(1000L / amountDifference)
            );
            profile = FuelProfile.dirty(
                FluidSelector.fluid(fuelVariant.fluidId()), powerPerTick, totalTime, residuePerBucket
            );
        }
        energyFluids().register(id("fuel/" + fuelVariant.fluidId().getNamespace() + "/" + fuelVariant.fluidId().getPath()), profile, BUILTIN);
    }

    public static class BCEnergyRecipeProvider extends RecipeProvider{

        public BCEnergyRecipeProvider(DataGenerator p_125973_) {
    		super(p_125973_);
    	}

    		@Override
    		protected void buildCraftingRecipes(Consumer<FinishedRecipe> writer) {                
            	ShapedRecipeBuilder builder6 = ShapedRecipeBuilder.shaped(BCCoreItems.ENGINE_ITEM_MAP.get(EnumEngineType.STONE), 1);
                builder6.pattern("www");
                builder6.pattern(" g ");
                builder6.pattern("GpG");
                builder6.define('w', ItemTags.STONE_TOOL_MATERIALS);
                builder6.define('g', Items.GLASS);
                builder6.define('G', BCCoreItems.GEAR_STONE.get());
                builder6.define('p', Blocks.PISTON);
                builder6.unlockedBy("has_"+BCCoreItems.GEAR_STONE.getId().getPath(), TriggerInstance.hasItems(BCCoreItems.GEAR_STONE.get()));
                builder6.save(writer);
                
            	ShapedRecipeBuilder builder7 = ShapedRecipeBuilder.shaped(BCCoreItems.ENGINE_ITEM_MAP.get(EnumEngineType.IRON), 1);
            	builder7.pattern("www");
            	builder7.pattern(" g ");
            	builder7.pattern("GpG");
                builder7.define('w', Items.IRON_INGOT);
                builder7.define('g', Items.GLASS);
                builder7.define('G', BCCoreItems.GEAR_IRON.get());
                builder7.define('p', Blocks.PISTON);
                builder7.unlockedBy("has_"+BCCoreItems.GEAR_IRON.getId().getPath(), TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()));
                builder7.save(writer);

                ShapedRecipeBuilder feEngine = ShapedRecipeBuilder.shaped(BCEnergyBlocks.ENGINE_FE_ITEM.get(), 1);
                feEngine.pattern("www");
                feEngine.pattern(" g ");
                feEngine.pattern("GpG");
                feEngine.define('w', Items.REDSTONE);
                feEngine.define('g', Items.GLASS);
                feEngine.define('G', BCCoreItems.GEAR_IRON.get());
                feEngine.define('p', Blocks.PISTON);
                feEngine.unlockedBy("has_"+BCCoreItems.GEAR_IRON.getId().getPath(), TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()));
                feEngine.save(writer, new ResourceLocation(BCEnergy.MODID, "fe_engine"));

                ShapedRecipeBuilder mjDynamo = ShapedRecipeBuilder.shaped(BCEnergyBlocks.DYNAMO_MJ_ITEM.get(), 1);
                mjDynamo.pattern("wgw");
                mjDynamo.pattern(" p ");
                mjDynamo.pattern("GwG");
                mjDynamo.define('w', Items.REDSTONE);
                mjDynamo.define('g', Items.GLASS);
                mjDynamo.define('G', BCCoreItems.GEAR_IRON.get());
                mjDynamo.define('p', Blocks.PISTON);
                mjDynamo.unlockedBy("has_"+BCCoreItems.GEAR_IRON.getId().getPath(), TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()));
                mjDynamo.save(writer, new ResourceLocation(BCEnergy.MODID, "mj_dynamo"));

                super.buildCraftingRecipes(writer);
    		}
    }
}
