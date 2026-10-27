package buildcraft.lib.recipe;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only active programming variants. Distinct options are based on complete decoded contents,
 * not just their board key: two recipes making the same board at different costs both survive. */
public final class ProgrammingRecipeAccess {
    private ProgrammingRecipeAccess() {}

    /** All active, non-blacklisted alternatives, one per distinct board/energy combination. */
    public static List<ProgrammingRecipeDefinition> options(boolean client) {
        List<ProgrammingRecipeDefinition> recipes;
        if (client) {
            recipes = ClientMachineRecipeCatalog.visibleRecipes(ProgrammingRecipeDefinition.class);
        } else {
            recipes = BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES)
                .recipes(ProgrammingRecipeDefinition.class).stream().map(match -> match.recipe()).toList();
        }
        // The record's equality includes both the board key and micro-MJ cost.
        // Deduplicate only *after* applying the blacklist to individual recipe IDs.
        return recipes.stream().distinct()
            .sorted(Comparator.comparingLong(ProgrammingRecipeDefinition::requiredMicroJoules)
                .thenComparing(ProgrammingRecipeDefinition::boardKey))
            .toList();
    }

    /** Inclusive MJ-cost bounds for every *active* recipe producing this board.
     * Blacklisted definitions are already excluded by {@link #options(boolean)}.
     * An empty result means this board currently has no available programming recipe.
     */
    public record CostRange(long minMicroMj, long maxMicroMj) {
        public CostRange {
            if (minMicroMj <= 0 || maxMicroMj < minMicroMj) {
                throw new IllegalArgumentException("Invalid programming cost range");
            }
        }
    }

    public static java.util.Optional<CostRange> costRange(String boardKey, boolean client) {
        long min = Long.MAX_VALUE;
        long max = 0;
        for (ProgrammingRecipeDefinition definition : options(client)) {
            if (!definition.boardKey().equals(boardKey)) continue;
            long energy = definition.requiredMicroJoules();
            min = Math.min(min, energy);
            max = Math.max(max, energy);
        }
        return max == 0 ? java.util.Optional.empty()
            : java.util.Optional.of(new CostRange(min, max));
    }

    /** Legacy single-value view. Never use it for recipe selection or a board tooltip
     * when alternatives with different costs exist. */
    public static Map<String, Long> costs(boolean client) {
        Map<String, Long> result = new LinkedHashMap<>();
        // The lowest surviving cost is a representative tooltip value, not a winning recipe.
        for (ProgrammingRecipeDefinition definition : options(client)) {
            result.putIfAbsent(definition.boardKey(), definition.requiredMicroJoules());
        }
        return Collections.unmodifiableMap(result);
    }
}
