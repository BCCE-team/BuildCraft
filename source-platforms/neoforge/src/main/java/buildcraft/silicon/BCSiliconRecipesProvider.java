/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon;

import buildcraft.api.v2.energy.MjAmount;

import java.util.concurrent.CompletableFuture;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;

import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.lib.internal.enums.EnumRedstoneChipset;
import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreItems;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.lib.recipe.AssemblyRecipeBuilder;
import buildcraft.lib.recipe.NbtShapedRecipeBuilder;
import buildcraft.silicon.gate.EnumGateLogic;
import buildcraft.silicon.gate.EnumGateMaterial;
import buildcraft.silicon.gate.EnumGateModifier;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import buildcraft.silicon.recipe.FacadeSwapRecipe;
import buildcraft.silicon.recipe.GateLogicChangeRecipe;
import buildcraft.transport.BCTransportItems;
import net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.SpecialRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.minecraft.core.registries.BuiltInRegistries;

public class BCSiliconRecipesProvider extends RecipeProvider{

    /** Converts the canonical BuildCraft 8 assembly-table costs to micro-MJ. */
    private static long assemblyCost(long wholeMj) {
        return wholeMj * MjAmount.MICRO_MJ_PER_MJ;
    }

    public BCSiliconRecipesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput writer) {
        if (BCSiliconItems.PLUG_GATE_ITEM.isBound()) {
            // You can craft some of the basic gate types in a normal crafting table


            // Base craftable types

            makeGateRecipe(writer, Ingredient.of(Items.BRICK), EnumGateMaterial.CLAY_BRICK, EnumGateModifier.NO_MODIFIER);

            makeGateRecipe(writer, Ingredient.of(Tags.Items.INGOTS_IRON), EnumGateMaterial.IRON, EnumGateModifier.NO_MODIFIER);

            makeGateRecipe(writer, Ingredient.of(Items.NETHER_BRICK), EnumGateMaterial.NETHER_BRICK, EnumGateModifier.NO_MODIFIER);
            // Iron modifier addition

            makeGateRecipe0(writer, Ingredient.of(Tags.Items.DYES_BLUE), EnumGateMaterial.IRON, EnumGateModifier.LAPIS);

            makeGateRecipe0(writer, Ingredient.of(Tags.Items.GEMS_QUARTZ), EnumGateMaterial.IRON, EnumGateModifier.QUARTZ);

            // And Gate <-> Or Gate (shapeless)
            // @see{GateLogicChangeRecipe}
            SpecialRecipeBuilder.special(GateLogicChangeRecipe::new).save(writer, "buildcraftsilicon:special/gate_logic_change");
            if (BCSiliconItems.PLUG_PULSAR_ITEM.get() != null) {
                ItemStack output = new ItemStack(BCSiliconItems.PLUG_PULSAR_ITEM.get());

                ItemStack redstoneEngine;
                if (BCCoreBlocks.ENGINE_BC8.get() != null) {
                    redstoneEngine = new ItemStack(BCCoreItems.ENGINE_ITEM_MAP.get(EnumEngineType.WOOD));
                } else {
                    redstoneEngine = new ItemStack(Blocks.REDSTONE_BLOCK);
                }

                ImmutableSet.Builder<IngredientStack> input = ImmutableSet.builder();
                input.add(new IngredientStack(Ingredient.of(redstoneEngine)));
                input.add(new IngredientStack(Ingredient.of(Tags.Items.INGOTS_IRON), 2));
                AssemblyRecipeBuilder assemblyBuilder = new AssemblyRecipeBuilder(assemblyCost(1000), input.build(), output);
                assemblyBuilder.unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
                .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "assembly/plug_pulsar"));
            }
            if (BCSiliconItems.PLUG_GATE_ITEM.isBound()) {
                IngredientStack lapis = new IngredientStack(Ingredient.of(Tags.Items.GEMS_LAPIS));
                makeGateAssembly(writer, 20_000, EnumGateMaterial.IRON, EnumGateModifier.NO_MODIFIER, EnumRedstoneChipset.IRON);
                makeGateAssembly(writer, 40_000, EnumGateMaterial.NETHER_BRICK, EnumGateModifier.NO_MODIFIER,
                    EnumRedstoneChipset.IRON, IngredientStack.of(Blocks.NETHER_BRICKS));
                makeGateAssembly(writer, 80_000, EnumGateMaterial.GOLD, EnumGateModifier.NO_MODIFIER, EnumRedstoneChipset.GOLD);

                makeGateModifierAssembly(writer, 40_000, EnumGateMaterial.IRON, EnumGateModifier.LAPIS, lapis);
                makeGateModifierAssembly(writer, 60_000, EnumGateMaterial.IRON, EnumGateModifier.QUARTZ,
                    IngredientStack.of(EnumRedstoneChipset.QUARTZ.getStack()));
                makeGateModifierAssembly(writer, 80_000, EnumGateMaterial.IRON, EnumGateModifier.DIAMOND,
                    IngredientStack.of(EnumRedstoneChipset.DIAMOND.getStack()));

                makeGateModifierAssembly(writer, 80_000, EnumGateMaterial.NETHER_BRICK, EnumGateModifier.LAPIS, lapis);
                makeGateModifierAssembly(writer, 100_000, EnumGateMaterial.NETHER_BRICK, EnumGateModifier.QUARTZ,
                    IngredientStack.of(EnumRedstoneChipset.QUARTZ.getStack()));
                makeGateModifierAssembly(writer, 120_000, EnumGateMaterial.NETHER_BRICK, EnumGateModifier.DIAMOND,
                    IngredientStack.of(EnumRedstoneChipset.DIAMOND.getStack()));

                makeGateModifierAssembly(writer, 100_000, EnumGateMaterial.GOLD, EnumGateModifier.LAPIS, lapis);
                makeGateModifierAssembly(writer, 140_000, EnumGateMaterial.GOLD, EnumGateModifier.QUARTZ,
                    IngredientStack.of(EnumRedstoneChipset.QUARTZ.getStack()));
                makeGateModifierAssembly(writer, 180_000, EnumGateMaterial.GOLD, EnumGateModifier.DIAMOND,
                    IngredientStack.of(EnumRedstoneChipset.DIAMOND.getStack()));
            }
        }
        if (BCSiliconItems.PLUG_LIGHT_SENSOR_ITEM.get() != null) {
           new AssemblyRecipeBuilder(assemblyCost(500), ImmutableSet.of(IngredientStack.of(Blocks.DAYLIGHT_DETECTOR)),
                new ItemStack(BCSiliconItems.PLUG_LIGHT_SENSOR_ITEM.get()))
           .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
           .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "assembly/light_sensor"));
        }

        if (BCSiliconItems.PLUG_TIMER_ITEM.get() != null) {
           new AssemblyRecipeBuilder(assemblyCost(500), ImmutableSet.of(IngredientStack.of(net.minecraft.world.item.Items.CLOCK)),
                new ItemStack(BCSiliconItems.PLUG_TIMER_ITEM.get()))
           .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
           .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "assembly/plug_timer"));
        }

        if (BCSiliconItems.PLUG_FACADE_ITEM.isBound()) {
            writer.accept(
                FacadeAssemblyRecipes.ID,
                FacadeAssemblyRecipes.INSTANCE,
                null
            );
            writer.accept(FacadeSwapRecipe.ID, FacadeSwapRecipe.INSTANCE, null);
        }

        if (BCSiliconItems.PLUG_LENS_ITEM.get() != null) {
            for (DyeColor colour : ColourUtil.COLOURS) {
                String name = String.format("assembly/lens/regular/%s", colour.getSerializedName());
                IngredientStack stainedGlass = IngredientStack.of(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(colour.getName()+"_stained_glass")));
                ImmutableSet<IngredientStack> input = ImmutableSet.of(stainedGlass);
                ItemStack output = BCSiliconItems.PLUG_LENS_ITEM.get().getStack(colour, false);
                new AssemblyRecipeBuilder(assemblyCost(500), input, output)
                .group("buildcraftsilicon:lens_regulars")
                .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
                .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon",name));

                name = String.format("assembly/lens/filter/%s", colour.getSerializedName());
                output = BCSiliconItems.PLUG_LENS_ITEM.get().getStack(colour, true);
                input = ImmutableSet.of(stainedGlass, IngredientStack.of(Blocks.IRON_BARS));
                new AssemblyRecipeBuilder(assemblyCost(500), input, output)
                .group("buildcraftsilicon:lens_filters")
                .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
                .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon",name));
            }

            IngredientStack glass = IngredientStack.of(Tags.Blocks.GLASS_BLOCKS);
            ImmutableSet<IngredientStack> input = ImmutableSet.of(glass);
            ItemStack output = BCSiliconItems.PLUG_LENS_ITEM.get().getStack(null, false);
            new AssemblyRecipeBuilder(assemblyCost(500), input, output)
            .group("buildcraftsilicon:lens_regulars")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "assembly/lens/lens_regular"));

            output = BCSiliconItems.PLUG_LENS_ITEM.get().getStack(null, true);
            input = ImmutableSet.of(glass, IngredientStack.of(Blocks.IRON_BARS));
            new AssemblyRecipeBuilder(assemblyCost(500), input, output)
            .group("buildcraftsilicon:lens_regulars")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", "assembly/lens/lens_filter"));
        }

        if (!BCSiliconItems.REDSTONE_CHIPSET_ITEMS.isEmpty()) {
            ImmutableSet<IngredientStack> input = ImmutableSet.of(IngredientStack.of(Tags.Items.DUSTS_REDSTONE));
            ItemStack output = EnumRedstoneChipset.RED.getStack(1);
            new AssemblyRecipeBuilder(assemblyCost(10000), input, output)
            .group("buildcraftsilicon:chipsets")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/redstone_chipset"));

            input = ImmutableSet.of(IngredientStack.of(Tags.Items.DUSTS_REDSTONE), IngredientStack.of(Tags.Items.INGOTS_IRON));
            output = EnumRedstoneChipset.IRON.getStack(1);
            new AssemblyRecipeBuilder(assemblyCost(20000), input, output)
            .group("buildcraftsilicon:chipsets")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/iron_chipset"));

            input = ImmutableSet.of(IngredientStack.of(Tags.Items.DUSTS_REDSTONE), IngredientStack.of(Tags.Items.INGOTS_GOLD));
            output = EnumRedstoneChipset.GOLD.getStack(1);
            new AssemblyRecipeBuilder(assemblyCost(40000), input, output)
            .group("buildcraftsilicon:chipsets")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/gold_chipset"));

            input = ImmutableSet.of(IngredientStack.of(Tags.Items.DUSTS_REDSTONE), IngredientStack.of(Tags.Items.GEMS_QUARTZ));
            output = EnumRedstoneChipset.QUARTZ.getStack(1);
            new AssemblyRecipeBuilder(assemblyCost(60000), input, output)
            .group("buildcraftsilicon:chipsets")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/quartz_chipset"));

            input = ImmutableSet.of(IngredientStack.of(Tags.Items.DUSTS_REDSTONE), IngredientStack.of(Tags.Items.GEMS_DIAMOND));
            output = EnumRedstoneChipset.DIAMOND.getStack(1);
            new AssemblyRecipeBuilder(assemblyCost(80000), input, output)
            .group("buildcraftsilicon:chipsets")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/diamond_chipset"));
        }

        if (BCSiliconItems.GATE_COPIER_ITEM.isBound()) {
            ImmutableSet.Builder<IngredientStack> input = ImmutableSet.builder();
            if (BCCoreItems.WRENCH.isBound()) {
                input.add(IngredientStack.of(BCCoreItems.WRENCH.get()));
            } else {
                input.add(IngredientStack.of(Tags.Items.RODS_WOODEN));
                input.add(IngredientStack.of(Tags.Items.INGOTS_IRON));
            }

            if (!BCSiliconItems.REDSTONE_CHIPSET_ITEMS.isEmpty()) {
                input.add(IngredientStack.of(EnumRedstoneChipset.IRON.getStack(1)));
            } else {
                input.add(IngredientStack.of(Tags.Items.DUSTS_REDSTONE));
                input.add(IngredientStack.of(Tags.Items.DUSTS_REDSTONE));
                input.add(IngredientStack.of(Tags.Items.INGOTS_GOLD));
            }

            new AssemblyRecipeBuilder(assemblyCost(500), input.build(), new ItemStack(BCSiliconItems.GATE_COPIER_ITEM.get()))
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon","assembly/gate_copier"));
        }
    }


    private void makeGateRecipe0(RecipeOutput writer, Ingredient m, EnumGateMaterial material,
            EnumGateModifier modifier) {
        GateVariant baseVariant = new GateVariant(EnumGateLogic.AND, EnumGateMaterial.IRON, EnumGateModifier.NO_MODIFIER);
        ItemStack ironGateBase = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(baseVariant);
        GateVariant outputVariant = new GateVariant(EnumGateLogic.AND, material, modifier);
        ItemStack output = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(outputVariant);
        NbtShapedRecipeBuilder builder = new NbtShapedRecipeBuilder(output);
        builder
        .pattern(" m ")
        .pattern("mgm")
        .pattern(" m ")
        .define('g', DataComponentIngredient.of(true, ironGateBase))
        .define('m', m)
        .group("buildcraftsilicon:iron_pluggate")
        .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()));
        builder.save(writer, ResourceLocation.parse("buildcraftsilicon:plug_gate_create/" + material.toString().toLowerCase() + "_" + modifier.toString().toLowerCase()));

    }

    private void makeGateRecipe(RecipeOutput writer, Ingredient m, EnumGateMaterial material,
            EnumGateModifier modifier) {
        GateVariant variant = new GateVariant(EnumGateLogic.AND, material, modifier);
        ItemStack output = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(variant);
        NbtShapedRecipeBuilder builder = new NbtShapedRecipeBuilder(output);
        builder
        .pattern(" m ")
        .pattern("mrm")
        .pattern(" b ")
        .define('r', Tags.Items.DUSTS_REDSTONE)
        .define('b', BCTransportItems.plugBlocker.isBound() ? BCTransportItems.plugBlocker.get() : Blocks.COBBLESTONE)
        .define('m', m)
        .group("buildcraftsilicon:basic_pluggate")
        .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()));
        builder.save(writer, ResourceLocation.parse("buildcraftsilicon:plug_gate_create/" + material.toString().toLowerCase() + "_" + modifier.toString().toLowerCase()));
       // builder.registerNbtAware("buildcraftsilicon:plug_gate_create_" + material + "_" + modifier);
    }

    private static void makeGateAssembly(RecipeOutput writer, int multiplier, EnumGateMaterial material, EnumGateModifier modifier,
            EnumRedstoneChipset chipset, IngredientStack... additional) {
            ImmutableSet.Builder<IngredientStack> temp = ImmutableSet.builder();
            temp.add(new IngredientStack(Ingredient.of(chipset.getStack())));
            temp.add(additional);
            ImmutableSet<IngredientStack> input = temp.build();

            String name = String.format("assembly/gate/and_%s_%s", material.toString().toLowerCase(), modifier.toString().toLowerCase());
            ItemStack output = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(new GateVariant(EnumGateLogic.AND, material, modifier));
            AssemblyRecipeBuilder andBuilder = new AssemblyRecipeBuilder(assemblyCost(multiplier), input, output);
            andBuilder.group("buildcraftsilicon:gate_and")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", name));

            name = String.format("assembly/gate/or_%s_%s", material.toString().toLowerCase(), modifier.toString().toLowerCase());
            output = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(new GateVariant(EnumGateLogic.OR, material, modifier));
            AssemblyRecipeBuilder orBuilder = new AssemblyRecipeBuilder(assemblyCost(multiplier), input, output);
            orBuilder.group("buildcraftsilicon:gate_or")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", name));
    }

    private static void makeGateModifierAssembly(RecipeOutput writer, int multiplier, EnumGateMaterial material, EnumGateModifier modifier,
            IngredientStack... mods) {
        for (EnumGateLogic logic : EnumGateLogic.VALUES) {
            String name = String.format("assembly/gate/modifier/%s_%s_%s", logic.toString().toLowerCase(), material.toString().toLowerCase(), modifier.toString().toLowerCase());
            GateVariant variantFrom = new GateVariant(logic, material, EnumGateModifier.NO_MODIFIER);
            ItemStack toUpgrade = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(variantFrom);
            ItemStack output = BCSiliconItems.PLUG_GATE_ITEM.get().getStack(new GateVariant(logic, material, modifier));
            Builder<IngredientStack> inputBuilder = new ImmutableSet.Builder<>();
            inputBuilder.add(new IngredientStack(DataComponentIngredient.of(true, toUpgrade)));
            inputBuilder.add(mods);
            ImmutableSet<IngredientStack> input = inputBuilder.build();
            AssemblyRecipeBuilder builder = new AssemblyRecipeBuilder(assemblyCost(multiplier), input, output);
            builder.group("buildcraftsilicon:gate_modifier")
            .unlockedBy("has_"+BCSiliconItems.ASSEMBLY_TABLE_ITEM.get().getDescriptionId(), TriggerInstance.hasItems(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()))
            .save(writer, ResourceLocation.fromNamespaceAndPath("buildcraftsilicon", name));
        }
    }
}
