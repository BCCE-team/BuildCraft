/*
 * Copyright (c) 2017-2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.recipe;

import java.util.function.Supplier;

import buildcraft.lib.BCLib;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** NeoForge-only ingredient serializers needed to preserve legacy BuildCraft recipes. */
public final class BCLibIngredientTypes {
    private static final DeferredRegister<IngredientType<?>> INGREDIENT_TYPES =
        DeferredRegister.create(NeoForgeRegistries.Keys.INGREDIENT_TYPES, BCLib.MODID);

    public static final Supplier<IngredientType<LegacyStrictNbtIngredient>> STRICT_NBT =
        INGREDIENT_TYPES.register("strict_nbt", () -> new IngredientType<>(LegacyStrictNbtIngredient.CODEC));

    private BCLibIngredientTypes() {
    }

    public static void register(IEventBus modEventBus) {
        INGREDIENT_TYPES.register(modEventBus);
    }
}
