/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.recipe.AssemblyRecipe;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import buildcraft.silicon.recipe.FacadeSwapRecipe;
import buildcraft.silicon.recipe.GateLogicChangeRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

public final class BCSiliconRecipes {
    public static final BCDeferredRegister<RecipeType<?>> TYPES =
        BCDeferredRegister.create("minecraft:recipe_type", BCSilicon.MODID);
    public static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCSilicon.MODID);

    public static final BCRegistryEntry<RecipeType<AssemblyRecipeBasic>> ASSEMBLY_TYPE = TYPES.register(
        "assembly", () -> RecipeType.simple(new ResourceLocation(BCSilicon.MODID, "assembly")));
    public static final BCRegistryEntry<RecipeSerializer<AssemblyRecipe>> ASSEMBLY_SERIALIZER =
        SERIALIZERS.register("assembly", AssemblyRecipe.Serializer::new);
    public static final BCRegistryEntry<SimpleCraftingRecipeSerializer<GateLogicChangeRecipe>> GATE_CHANGE_SERIALIZER =
        SERIALIZERS.register("gate_logic_change", () -> new SimpleCraftingRecipeSerializer<>(GateLogicChangeRecipe::new));
    public static final BCRegistryEntry<RecipeSerializer<FacadeAssemblyRecipes>> FACADE_SERIALIZER =
        SERIALIZERS.register("facade", FacadeAssemblyRecipes.Serializer::new);
    public static final BCRegistryEntry<RecipeSerializer<FacadeSwapRecipe>> FACADE_SWAP_SERIALIZER =
        SERIALIZERS.register("facade_swap", () -> FacadeSwapRecipe.SERIALIZER);

    private BCSiliconRecipes() {
    }

    public static void preInit(BCRegistryBinder modEventBus) {
        TYPES.register(modEventBus);
        SERIALIZERS.register(modEventBus);
    }
}
