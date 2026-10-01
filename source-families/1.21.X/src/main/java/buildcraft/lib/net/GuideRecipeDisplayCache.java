//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** Complete server-synchronised recipe-display index used by the 1.21.11 Guide Book. */
public final class GuideRecipeDisplayCache {
    private static final AtomicLong REVISION = new AtomicLong();
    private static volatile boolean synchronised;
    private static volatile List<Entry> entries = List.of();

    private GuideRecipeDisplayCache() {
    }

    public record Entry(Identifier recipeId, RecipeDisplayEntry display) {
    }

    public static void replace(List<Entry> newEntries) {
        entries = List.copyOf(newEntries);
        synchronised = true;
        REVISION.incrementAndGet();
    }

    public static void clear() {
        entries = List.of();
        synchronised = false;
        REVISION.incrementAndGet();
    }

    public static long revision() {
        return REVISION.get();
    }

    /**
     * Uses the complete server index once it has arrived, including a legitimately empty index. Before then the
     * vanilla unlocked-recipe list is enough to render a useful preview during the first few client ticks.
     */
    public static List<Entry> entriesOr(List<RecipeDisplayEntry> fallback) {
        if (synchronised) return entries;
        List<Entry> converted = new ArrayList<>(fallback.size());
        for (RecipeDisplayEntry display : fallback) {
            converted.add(new Entry(clientRecipeKey(display), display));
        }
        return List.copyOf(converted);
    }

    private static Identifier clientRecipeKey(RecipeDisplayEntry display) {
        return Identifier.fromNamespaceAndPath("buildcraft", "client_recipe/"
            + Integer.toUnsignedString(display.id().index(), 16));
    }
}
