/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.internal.recipes;

import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

public final class IngredientStack {
    public final Ingredient ingredient;
    public final int count;

    public IngredientStack(Ingredient ingredient, int count) {
        if (ingredient == null) throw new NullPointerException("ingredient");
        if (count <= 0) throw new IllegalArgumentException("count must be > 0");
        this.ingredient = ingredient;
        this.count = count;
    }

    public IngredientStack(Ingredient ingredient) {
        this(ingredient, 1);
    }

    public static IngredientStack of(JsonElement o) {
        return new IngredientStack(Ingredient.CODEC.parse(JsonOps.INSTANCE, o).getOrThrow());
    }
    
    public static IngredientStack of(Object o) {
        if (o == null) throw new IllegalArgumentException("IngredientStack: ingredient must not be null");
    	if(o instanceof ItemLike item) {
    		return new IngredientStack(Ingredient.of(item));
    	}
    	if(o instanceof ItemStack item) {
    		return new IngredientStack(Ingredient.of(item));
    	}
    	if(o instanceof Stream item) {
    		return new IngredientStack(Ingredient.of(item));
    	}
    	if(o instanceof TagKey item) {
    		return new IngredientStack(Ingredient.of(item));
    	}
        throw new IllegalArgumentException("IngredientStack: not a valid ingredient parameter " + o.getClass().getName());
    }
}
