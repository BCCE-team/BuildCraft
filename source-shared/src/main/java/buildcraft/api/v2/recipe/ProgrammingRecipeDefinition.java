package buildcraft.api.v2.recipe;

import java.util.Objects;

/** Data-owned Programming Table option. The referenced board behavior remains registered in Java. */
public record ProgrammingRecipeDefinition(String boardKey, long requiredMicroJoules) implements RecipeDefinition {
    public ProgrammingRecipeDefinition {
        Objects.requireNonNull(boardKey, "boardKey");
        if (boardKey.isBlank() || boardKey.length() > 256) throw new IllegalArgumentException("Invalid board key");
        if (requiredMicroJoules <= 0) throw new IllegalArgumentException("Programming energy must be positive");
    }
    @Override public Kind kind() { return Kind.PROGRAMMING; }
}
