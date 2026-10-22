package buildcraft.silicon.recipe;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class FacadeAssemblyRecipesTester {
    @Test
    void assemblyTableUsesRecipeHolderDatapackIdForSync() throws IOException {
        // Since 1.21 recipe ids belong to RecipeHolder rather than Recipe itself. Loading the full Recipe
        // hierarchy in a plain JUnit JVM also reaches loader hooks before FML/NeoForge has been started.
        // Verify the actual regression point without bootstrapping the game: the assembly table must carry
        // holder.id() into its instructions and resolve that same id through RecipeManager on load/sync.
        Path sourcePath = Path.of("src/main/java/buildcraft/silicon/tile/TileAssemblyTable.java");
        String source = Files.readString(sourcePath);

        Assertions.assertTrue(
            source.contains("ResourceLocation recipeId = holder.id();"),
            "Assembly recipes must use the datapack id from RecipeHolder"
        );
        Assertions.assertTrue(
            source.contains("new AssemblyInstruction(recipeId, recipe, out.copy())"),
            "AssemblyInstruction must retain the RecipeHolder id"
        );
        Assertions.assertTrue(
            source.contains("level.getRecipeManager().byKey(recipeId)"),
            "Saved/synced assembly recipe ids must resolve through RecipeManager"
        );
    }
}
