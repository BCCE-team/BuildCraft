package buildcraft.lib.recipe;

import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.recipe.RecipeMatch;

/**
 * Stable work identity: a half-paid Distiller operation must never be completed
 * using different inputs, outputs, energy cost or a different recipe ID.
 *
 * <p>Purely dynamic Java fluid matchers cannot be serialized. Their identity is
 * deliberately process-local; unfinished work is refunded on world reload.</p>
 */
public final class DistillationProgressIdentity {
    private DistillationProgressIdentity() {}

    public static String of(RecipeMatch<DistillationRecipeDefinition> match) {
        if (match == null) return "";
        DistillationRecipeDefinition recipe = match.recipe();
        String fluid = recipe.input().representativeVariant()
            .map(variant -> Long.toUnsignedString(variant.stableHash64(), 16))
            .orElse("dynamic@" + Integer.toHexString(System.identityHashCode(recipe)));
        return match.id() + "|" + fluid + ":" + recipe.input().amount().milliBuckets()
            + "|" + output(recipe.gasOutput()) + "|" + output(recipe.liquidOutput())
            + "|" + recipe.powerRequiredMicroMj();
    }

    private static String output(FluidVolume volume) {
        return volume.isEmpty() ? "-" : Long.toUnsignedString(volume.requireVariant().stableHash64(), 16)
            + ":" + volume.amount().milliBuckets();
    }

    /** A code-only dynamic matcher must always be revalidated on a fresh JVM. */
    public static String fromSaved(String identity) {
        return identity == null || identity.contains("|dynamic@") ? "" : identity;
    }
}
