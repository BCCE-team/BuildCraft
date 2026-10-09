package buildcraft.lib.recipe;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.recipe.FluidIngredient;
import buildcraft.api.v2.recipe.HeatExchangeRecipeDefinition;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FuelApiBridge;
import net.neoforged.neoforge.fluids.FluidStack;

/** NeoForge runtime bridge between factory machine recipes and the API 2 recipe service. */
public final class MachineRecipeApiBridge {
    private MachineRecipeApiBridge() {}

    public static MachineRecipeService service() {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES);
    }

    public static DistillationRecipeDefinition findDistillation(FluidStack input) {
        if (input == null || input.isEmpty()) return null;
        FluidStack canonical = FluidCompatRegistry.canonicalize(input);
        if (canonical == null || canonical.isEmpty()) return null;
        return service().findDistillation(FuelApiBridge.variantOf(canonical), FuelApiBridge.MATCH_CONTEXT)
            .map(match -> match.recipe()).orElse(null);
    }

    public static HeatExchangeRecipeDefinition findHeating(FluidStack input) {
        if (input == null || input.isEmpty()) return null;
        FluidStack canonical = FluidCompatRegistry.canonicalize(input);
        if (canonical == null || canonical.isEmpty()) return null;
        return service().findHeating(FuelApiBridge.variantOf(canonical), FuelApiBridge.MATCH_CONTEXT)
            .map(match -> match.recipe()).orElse(null);
    }

    public static HeatExchangeRecipeDefinition findCooling(FluidStack input) {
        if (input == null || input.isEmpty()) return null;
        FluidStack canonical = FluidCompatRegistry.canonicalize(input);
        if (canonical == null || canonical.isEmpty()) return null;
        return service().findCooling(FuelApiBridge.variantOf(canonical), FuelApiBridge.MATCH_CONTEXT)
            .map(match -> match.recipe()).orElse(null);
    }

    public static FluidStack inputStack(FluidIngredient ingredient, FluidStack actual) {
        if (ingredient == null || actual == null || actual.isEmpty()) return FluidStack.EMPTY;
        long amount = ingredient.amount().milliBuckets();
        if (amount <= 0 || amount > Integer.MAX_VALUE) return FluidStack.EMPTY;
        FluidStack result = actual.copy();
        result.setAmount((int) amount);
        return result;
    }

    public static FluidStack representativeInput(FluidIngredient ingredient) {
        if (ingredient == null) return FluidStack.EMPTY;
        long amount = ingredient.amount().milliBuckets();
        if (amount <= 0 || amount > Integer.MAX_VALUE) return FluidStack.EMPTY;
        return ingredient.representativeVariant()
            .map(variant -> FuelApiBridge.stackOfVariant(variant, (int) amount))
            .orElse(FluidStack.EMPTY);
    }

    public static FluidStack outputStack(FluidVolume volume) {
        return FuelApiBridge.stackOf(volume);
    }
}
