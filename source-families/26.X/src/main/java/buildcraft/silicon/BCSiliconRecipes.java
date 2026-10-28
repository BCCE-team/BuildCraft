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
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.mojang.serialization.MapCodec;

public final class BCSiliconRecipes {
    public static final BCDeferredRegister<RecipeType<?>> TYPES =
        BCDeferredRegister.create("minecraft:recipe_type", BCSilicon.MODID);
    public static final BCDeferredRegister<RecipeSerializer<?>> SERIALIZERS =
        BCDeferredRegister.create("minecraft:recipe_serializer", BCSilicon.MODID);

    public static final BCRegistryEntry<RecipeType<AssemblyRecipeBasic>> ASSEMBLY_TYPE = TYPES.register(
        "assembly", () -> RecipeType.simple(Identifier.fromNamespaceAndPath(BCSilicon.MODID, "assembly")));
    public static final BCRegistryEntry<RecipeSerializer<AssemblyRecipe>> ASSEMBLY_SERIALIZER =
        SERIALIZERS.register("assembly", () -> AssemblyRecipe.SERIALIZER);
    public static final BCRegistryEntry<RecipeSerializer<GateLogicChangeRecipe>> GATE_CHANGE_SERIALIZER =
        SERIALIZERS.register("gate_logic_change", () -> GateLogicChangeRecipe.SERIALIZER);
    public static final BCRegistryEntry<RecipeSerializer<FacadeAssemblyRecipes>> FACADE_SERIALIZER =
        SERIALIZERS.register("facade", () -> FacadeAssemblyRecipes.SERIALIZER);
    public static final BCRegistryEntry<RecipeSerializer<FacadeSwapRecipe>> FACADE_SWAP_SERIALIZER =
        SERIALIZERS.register("facade_swap", () -> FacadeSwapRecipe.SERIALIZER);

    public static final BCRegistryEntry<RecipeSerializer<MachinePlaceholder>> INTEGRATION_SERIALIZER =
        SERIALIZERS.register("integration", () -> MachinePlaceholder.INTEGRATION_SERIALIZER);
    public static final BCRegistryEntry<RecipeSerializer<MachinePlaceholder>> PROGRAMMING_SERIALIZER =
        SERIALIZERS.register("programming", () -> MachinePlaceholder.PROGRAMMING_SERIALIZER);
    public static final BCRegistryEntry<RecipeSerializer<MachinePlaceholder>> ROBOT_INTEGRATION_SERIALIZER =
        SERIALIZERS.register("robot_board_integration", () -> MachinePlaceholder.ROBOT_SERIALIZER);

    /** Native reader for JSON-only machine definitions; never matches a crafting grid. */
    public static final class MachinePlaceholder extends CustomRecipe {
        // A distinct placeholder must be decoded for each JSON recipe ID.
        static final RecipeSerializer<MachinePlaceholder> INTEGRATION_SERIALIZER = serializer(false, false);
        static final RecipeSerializer<MachinePlaceholder> PROGRAMMING_SERIALIZER = serializer(true, false);
        static final RecipeSerializer<MachinePlaceholder> ROBOT_SERIALIZER = serializer(false, true);
        private static RecipeSerializer<MachinePlaceholder> serializer(boolean programming, boolean robot) {
            return new RecipeSerializer<>(
                MapCodec.unit(() -> new MachinePlaceholder(programming, robot)),
                StreamCodec.<RegistryFriendlyByteBuf, MachinePlaceholder>of(
                    (buffer, recipe) -> {}, buffer -> new MachinePlaceholder(programming, robot)));
        }
        private final boolean programming;
        private final boolean robot;
        private MachinePlaceholder(boolean programming, boolean robot) {
            super(); this.programming = programming; this.robot = robot;
        }
        @Override public boolean matches(CraftingInput input, Level world) { return false; }
        @Override public ItemStack assemble(CraftingInput input) { return ItemStack.EMPTY; }
        @Override public boolean canCraftInDimensions(int width, int height) { return false; }
        @Override public RecipeSerializer<? extends CustomRecipe> getSerializer() {
            return programming ? BCSiliconRecipes.PROGRAMMING_SERIALIZER.get()
                : robot ? BCSiliconRecipes.ROBOT_INTEGRATION_SERIALIZER.get() : BCSiliconRecipes.INTEGRATION_SERIALIZER.get();
        }
    }

    private BCSiliconRecipes() {
    }

    public static void preInit(BCRegistryBinder modEventBus) {
        TYPES.register(modEventBus);
        SERIALIZERS.register(modEventBus);
    }
}
