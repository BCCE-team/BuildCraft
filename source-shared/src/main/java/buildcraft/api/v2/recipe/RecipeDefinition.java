package buildcraft.api.v2.recipe;

/** Marker for definitions owned by the API 2 machine-recipe service. */
public interface RecipeDefinition {
    Kind kind();

    enum Kind {
        INTEGRATION,
        DISTILLATION,
        HEATING,
        COOLING,
        ASSEMBLY
    }
}
