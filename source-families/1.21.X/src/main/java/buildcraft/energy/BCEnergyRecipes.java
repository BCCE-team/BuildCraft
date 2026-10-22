/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy;

import java.util.concurrent.CompletableFuture;

import buildcraft.lib.internal.module.BCModules;
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
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.recipe.FluidIngredient;
import buildcraft.api.v2.recipe.HeatExchangeRecipeDefinition;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.recipe.RecipeDefinition;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.misc.MathUtil;
import net.minecraft.advancements.criterion.InventoryChangeTrigger.TriggerInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

public final class BCEnergyRecipes {

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

        if (BCModules.FACTORY.isLoaded()) {
            FluidRecipeValue[] gasLightDenseResidue = createFluidValues(BCEnergyFluids.crudeOil, oil);
            FluidRecipeValue[] gasLightDenseStacks = createFluidValues(BCEnergyFluids.oilDistilled, gasLightDense);
            FluidRecipeValue[] gasLightStacks = createFluidValues(BCEnergyFluids.fuelMixedLight, gasLight);
            FluidRecipeValue[] gasStacks = createFluidValues(BCEnergyFluids.fuelGaseous, gas);
            FluidRecipeValue[] lightDenseResidueStacks = createFluidValues(BCEnergyFluids.oilHeavy, lightDenseResidue);
            FluidRecipeValue[] lightDenseStacks = createFluidValues(BCEnergyFluids.fuelMixedHeavy, lightDense);
            FluidRecipeValue[] lightStacks = createFluidValues(BCEnergyFluids.fuelLight, light);
            FluidRecipeValue[] denseResidueStacks = createFluidValues(BCEnergyFluids.oilDense, denseResidue);
            FluidRecipeValue[] denseStacks = createFluidValues(BCEnergyFluids.fuelDense, dense);
            FluidRecipeValue[] residueStacks = createFluidValues(BCEnergyFluids.oilResidue, residue);

            addDistillation(gasLightDenseResidue, gasStacks, lightDenseResidueStacks, 0, 32 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(gasLightDenseResidue, gasLightStacks, denseResidueStacks, 1, 16 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(gasLightDenseResidue, gasLightDenseStacks, residueStacks, 2, 12 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(gasLightDenseStacks, gasStacks, lightDenseStacks, 0, 24 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(gasLightDenseStacks, gasLightStacks, denseStacks, 1, 16 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(gasLightStacks, gasStacks, lightStacks, 0, 24 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(lightDenseResidueStacks, lightStacks, denseResidueStacks, 1, 16 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(lightDenseResidueStacks, lightDenseStacks, residueStacks, 2, 12 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(lightDenseStacks, lightStacks, denseStacks, 1, 16 * MjAmount.MICRO_MJ_PER_MJ);
            addDistillation(denseResidueStacks, denseStacks, residueStacks, 2, 12 * MjAmount.MICRO_MJ_PER_MJ);

            addHeatExchange(BCEnergyFluids.crudeOil);
            addHeatExchange(BCEnergyFluids.oilDistilled);
            addHeatExchange(BCEnergyFluids.oilHeavy);
            addHeatExchange(BCEnergyFluids.oilDense);
            addHeatExchange(BCEnergyFluids.fuelMixedLight);
            addHeatExchange(BCEnergyFluids.fuelMixedHeavy);
            addHeatExchange(BCEnergyFluids.fuelGaseous);
            addHeatExchange(BCEnergyFluids.fuelLight);
            addHeatExchange(BCEnergyFluids.fuelDense);
            addHeatExchange(BCEnergyFluids.oilResidue);

            registerHeatRecipe("heating/minecraft/water_consumed", RecipeDefinition.Kind.HEATING,
                fluidValue(Fluids.WATER, 10), null, 0, 1);
            registerHeatRecipe("cooling/minecraft/lava_consumed", RecipeDefinition.Kind.COOLING,
                fluidValue(Fluids.LAVA, 5), null, 4, 2);
        }

        initialized = true;
    }

    private static EnergyFluidService energyFluids() {
        return BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS);
    }

    private static MachineRecipeService machineRecipes() {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES);
    }

    private static Identifier id(String path) {
        Identifier id = Identifier.tryParse("buildcraftenergy:" + path);
        if (id == null) throw new IllegalArgumentException("Invalid built-in energy id: " + path);
        return id;
    }

    private static Identifier fluidId(Fluid fluid) {
        Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
        if (id == null || fluid == Fluids.EMPTY) {
            throw new IllegalArgumentException("Unregistered or empty built-in fluid: " + fluid);
        }
        return id;
    }

    private static FluidVariant variantOf(Fluid fluid) {
        return FluidVariant.of(fluidId(fluid));
    }

    private static FluidRecipeValue fluidValue(Fluid fluid, int amount) {
        return new FluidRecipeValue(variantOf(fluid), amount);
    }

    private static void registerCoolant(String path, Fluid fluid, double degreesPerMb) {
        energyFluids().register(
            id(path), CoolantProfile.constant(FluidSelector.fluid(fluidId(fluid)), degreesPerMb), BUILTIN
        );
    }

    private static void registerSolidCoolant(String path, net.minecraft.world.item.Item item, double multiplier) {
        FluidVariant water = variantOf(Fluids.WATER);
        SolidCoolantProfile profile = new SolidCoolantProfile(
            stack -> stack != null && !stack.isEmpty() && stack.getItem() == item,
            stack -> {
                long amount = Math.round(stack.getCount() * 1000.0 * multiplier);
                return amount <= 0 ? FluidVolume.empty() : FluidVolume.of(water, FluidAmount.of(amount));
            }
        );
        energyFluids().register(id(path), profile, BUILTIN);
    }

    private static FluidRecipeValue[] createFluidValues(Fluid[] fluids, int amount) {
        FluidRecipeValue[] result = new FluidRecipeValue[fluids.length];
        for (int i = 0; i < result.length; i++) result[i] = fluidValue(fluids[i], amount);
        return result;
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
        FluidVariant fuelVariant = variantOf(fuel);
        FuelProfile profile;
        Fluid residue = dirty ? getFirstOrNull(BCEnergyFluids.oilResidue) : null;
        if (residue == null) {
            profile = FuelProfile.clean(FluidSelector.fluid(fuelVariant.fluidId()), powerPerTick, totalTime);
        } else {
            FluidVolume residuePerBucket = FluidVolume.of(
                variantOf(residue), FluidAmount.of(1000L / amountDifference)
            );
            profile = FuelProfile.dirty(
                FluidSelector.fluid(fuelVariant.fluidId()), powerPerTick, totalTime, residuePerBucket
            );
        }
        energyFluids().register(id("fuel/" + fuelVariant.fluidId().getNamespace() + "/" + fuelVariant.fluidId().getPath()), profile, BUILTIN);
    }

    private static void addDistillation(
        FluidRecipeValue[] input, FluidRecipeValue[] outputGas, FluidRecipeValue[] outputLiquid, int heat, long mjCost
    ) {
        FluidRecipeValue inputValue = input[heat];
        FluidRecipeValue gasValue = outputGas[heat];
        FluidRecipeValue liquidValue = outputLiquid[heat];
        FluidVariant inputVariant = inputValue.variant();
        if (machineRecipes().findDistillation(inputVariant, buildcraft.lib.fluid.FuelApiBridge.MATCH_CONTEXT).isPresent()) {
            throw new IllegalStateException("Already added distillation recipe for " + inputVariant.fluidId());
        }
        int hcf = MathUtil.findHighestCommonFactor(inputValue.amount(), gasValue.amount());
        hcf = MathUtil.findHighestCommonFactor(hcf, liquidValue.amount());
        if (hcf > 1) {
            inputValue = inputValue.divide(hcf);
            gasValue = gasValue.divide(hcf);
            liquidValue = liquidValue.divide(hcf);
            mjCost /= hcf;
        }
        DistillationRecipeDefinition definition = new DistillationRecipeDefinition(
            FluidIngredient.exact(inputValue.variant(), inputValue.amount()),
            gasValue.volume(), liquidValue.volume(), mjCost
        );
        Identifier fluidId = inputValue.variant().fluidId();
        machineRecipes().register(id("distillation/" + fluidId.getNamespace() + "/" + fluidId.getPath()), definition, BUILTIN);
    }

    private static void addHeatExchange(BCFluid[] fluids) {
        for (int i = 0; i < fluids.length - 1; i++) {
            BCFluid cool = fluids[i];
            BCFluid hot = fluids[i + 1];
            FluidRecipeValue coolValue = fluidValue(cool, 10);
            FluidRecipeValue hotValue = fluidValue(hot, 10);
            int coolHeat = cool.getHeatValue();
            int hotHeat = hot.getHeatValue();
            Identifier coolId = coolValue.variant().fluidId();
            Identifier hotId = hotValue.variant().fluidId();
            registerHeatRecipe("heating/" + coolId.getNamespace() + "/" + coolId.getPath() + "_to_" + hotId.getPath(),
                RecipeDefinition.Kind.HEATING, coolValue, hotValue, coolHeat, hotHeat);
            registerHeatRecipe("cooling/" + hotId.getNamespace() + "/" + hotId.getPath() + "_to_" + coolId.getPath(),
                RecipeDefinition.Kind.COOLING, hotValue, coolValue, hotHeat, coolHeat);
        }
    }

    private static void registerHeatRecipe(
        String path, RecipeDefinition.Kind kind, FluidRecipeValue input, FluidRecipeValue output, int heatFrom, int heatTo
    ) {
        FluidVolume outputVolume = output == null ? FluidVolume.empty() : output.volume();
        HeatExchangeRecipeDefinition definition = new HeatExchangeRecipeDefinition(
            kind, FluidIngredient.exact(input.variant(), input.amount()), outputVolume, heatFrom, heatTo
        );
        machineRecipes().register(id(path), definition, BUILTIN);
    }

    private record FluidRecipeValue(FluidVariant variant, int amount) {
        private FluidRecipeValue {
            if (variant == null) throw new NullPointerException("variant");
            if (amount <= 0) throw new IllegalArgumentException("Fluid recipe amount must be > 0");
        }

        private FluidRecipeValue divide(int divisor) {
            return new FluidRecipeValue(variant, amount / divisor);
        }

        private FluidVolume volume() {
            return FluidVolume.of(variant, FluidAmount.of(amount));
        }
    }

}
