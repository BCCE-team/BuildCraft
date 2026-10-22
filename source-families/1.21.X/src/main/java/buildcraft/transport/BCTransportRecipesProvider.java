/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport;

import java.util.concurrent.CompletableFuture;

import buildcraft.core.BCCoreItems;
import buildcraft.energy.BCEnergyFluids;
import net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance;
import net.minecraft.data.PackOutput;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

public class BCTransportRecipesProvider extends RecipeProvider{

	private final Item waterProof ;
	
	public BCTransportRecipesProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
		super(output, registries);
		waterProof = BCTransportItems.WATER_PROOF.get();
	}

	@Override
	protected void buildRecipes(RecipeOutput writer) {
        // Coloured base-pipe recipes and colour-preserving upgrades are hand-authored
        // with BCTransport's custom serializer under data/buildcrafttransport/recipe.
        creatStructurePipeRecipes(writer, Items.COBBLESTONE, Items.GRAVEL, BCTransportItems.PIPE_STRUCTURE.get());
        creatPowerAdapterRecipes(writer);
        creatPlugBlockerRecipes(writer);
        creatWaterProofRecipe(writer);
        creatFilterBufferRecipe(writer);
	}
	
	private void creatPipeRecipes(RecipeOutput writer, ItemLike mat, Item itemOutput, Item fluidOutput, Item powerOutput) {
    	ShapedRecipeBuilder builder6 = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, itemOutput, 8);
        builder6
        .pattern("mgm")
        .define('g', Items.GLASS)
        .define('m', mat)
        .unlockedBy("has_"+Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
        .save(writer);
        if(fluidOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, fluidOutput)
		  .requires(itemOutput) // 将物品加入配方
		  .requires(waterProof)
		  .unlockedBy("has_"+waterProof.getDescriptionId(), TriggerInstance.hasItems(waterProof)) // 该配方如何解锁
		  .save(writer); // 将数据加入生成器
        if(powerOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, powerOutput)
        		  .requires(itemOutput) // 将物品加入配方
        		  .requires(Items.REDSTONE)
        		  .unlockedBy("has_"+Items.REDSTONE, TriggerInstance.hasItems(Items.REDSTONE)) // 该配方如何解锁
        		  .save(writer); // 将数据加入生成器
	}
	
	private void creatPipeRecipes(RecipeOutput writer, TagKey<Item> mat, Item itemOutput, Item fluidOutput, Item powerOutput) {
    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, itemOutput, 8);
        builder
        .pattern("mgm")
        .define('g', Items.GLASS)
        .define('m', mat)
        .unlockedBy("has_"+Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
        .save(writer);
        if(fluidOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, fluidOutput)
        		  .requires(itemOutput) // 将物品加入配方
        		  .requires(waterProof)
        		  .unlockedBy("has_"+waterProof.getDescriptionId(), TriggerInstance.hasItems(waterProof)) // 该配方如何解锁
        		  .save(writer); // 将数据加入生成器
        if(powerOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, powerOutput)
        		  .requires(itemOutput) // 将物品加入配方
        		  .requires(Items.REDSTONE)
        		  .unlockedBy("has_"+Items.REDSTONE, TriggerInstance.hasItems(Items.REDSTONE)) // 该配方如何解锁
        		  .save(writer); // 将数据加入生成器
	}
	
	private void creatSpecPipeRecipes(RecipeOutput writer, ItemLike left, ItemLike right, Item itemOutput, Item fluidOutput, Item powerOutput) {
    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, itemOutput, 8);
        builder
        .pattern("mgn")
        .define('g', Items.GLASS)
        .define('m', left)
        .define('n', right)
        .unlockedBy("has_"+Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
        .save(writer);
        if(fluidOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, fluidOutput)
        		  .requires(itemOutput) // 将物品加入配方
        		  .requires(waterProof)
        		  .unlockedBy("has_"+waterProof.getDescriptionId(), TriggerInstance.hasItems(waterProof)) // 该配方如何解锁
        		  .save(writer); // 将数据加入生成器
        if(powerOutput != null)
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, powerOutput)
        		  .requires(itemOutput) // 将物品加入配方
        		  .requires(Items.REDSTONE)
        		  .unlockedBy("has_"+Items.REDSTONE, TriggerInstance.hasItems(Items.REDSTONE)) // 该配方如何解锁
        		  .save(writer); // 将数据加入生成器
	}
	
	private void creatStructurePipeRecipes(RecipeOutput writer, ItemLike stone,ItemLike center, Item itemOutput) {
    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, itemOutput, 8);
        builder
        .pattern("mgm")
        .define('g', center)
        .define('m', stone)
        .unlockedBy("has_"+Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
        .save(writer);
	}
	
	private void creatPowerAdapterRecipes(RecipeOutput writer) {
    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, BCTransportItems.plugPowerAdaptor.get(), 4);
        builder
        .pattern("sgs")
        .pattern("ses")
        .pattern("srs")
        .define('g', Items.GOLD_INGOT)
        .define('s', BCTransportItems.PIPE_STRUCTURE.get())
        .define('e', BCCoreItems.GEAR_STONE.get())
        .define('r', Items.REDSTONE)
        .unlockedBy("has_"+Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
        .save(writer);
	}
	
	private void creatPlugBlockerRecipes(RecipeOutput writer) {
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, BCTransportItems.plugBlocker.get(), 4)
		  .requires(BCTransportItems.PIPE_STRUCTURE.get()) // 将物品加入配方
		  .unlockedBy("has_"+BCTransportItems.PIPE_STRUCTURE.get(), TriggerInstance.hasItems(BCTransportItems.PIPE_STRUCTURE.get())) // 该配方如何解锁
		  .save(writer); // 将数据加入生成器
	}
	
	private void creatWaterProofRecipe(RecipeOutput writer) {
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, BCTransportItems.WATER_PROOF.get(), 1)
		  .requires(Ingredient.of(Items.SLIME_BALL,Items.GREEN_DYE)) // 将物品加入配方
		  .unlockedBy("has_"+BCTransportItems.PIPE_ITEM_WOOD.get(), TriggerInstance.hasItems(BCTransportItems.PIPE_ITEM_WOOD.get())) // 该配方如何解锁
		  .save(writer); // 将数据加入生成器
        ShapelessRecipeBuilder.shapeless(RecipeCategory.MISC, BCTransportItems.WATER_PROOF.get(), 8)
		  .requires(BCEnergyFluids.OIL_BUCKET.get(12).get()) // 将物品加入配方
		  .unlockedBy("has_"+BCTransportItems.PIPE_ITEM_WOOD.get(), TriggerInstance.hasItems(BCTransportItems.PIPE_ITEM_WOOD.get())) // 该配方如何解锁
		  .save(writer, ResourceLocation.fromNamespaceAndPath("buildcrafttransport", "residue_to_waterproof")); // 将数据加入生成器*/
/*    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, BCTransportItems.WATER_PROOF.get(), 8);
        builder.pattern("s");
        builder.define('s', BCEnergyFluids.OIL_BUCKET.get(12).get());
        builder.unlockedBy("has_"+BCTransportItems.PIPE_ITEM_WOOD.get(), TriggerInstance.hasItems(BCTransportItems.PIPE_ITEM_WOOD.get()));
        builder.save(writer);//*/
        
	}
	
	private void creatFilterBufferRecipe(RecipeOutput writer) {
    	ShapedRecipeBuilder builder = ShapedRecipeBuilder.shaped(RecipeCategory.TRANSPORTATION, BCTransportBlocks.FILTERED_BUFFER_ITEM.get(), 1);
        builder
        .pattern("sgs")
        .pattern("ses")
        .pattern("srs")
        .define('g', BCTransportItems.PIPE_ITEM_DIAMOND.get())
        .define('s', ItemTags.PLANKS)
        .define('e', Items.CHEST)
        .define('r', Items.PISTON)
        .unlockedBy("has_"+BCTransportItems.PIPE_ITEM_DIAMOND.get(), TriggerInstance.hasItems(BCTransportItems.PIPE_ITEM_DIAMOND.get()))
        .save(writer);
	}
}
