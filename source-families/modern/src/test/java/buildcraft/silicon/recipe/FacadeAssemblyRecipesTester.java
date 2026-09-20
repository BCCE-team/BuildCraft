//? source if >=1.21.1
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
        // The assembly table must carry holder.id() into its instructions and resolve that same id through
        // RecipeManager when persisted or synchronized state is reconstructed.
        Path sourcePath = Path.of("src/main/java/buildcraft/silicon/tile/TileAssemblyTable.java");
        String source = Files.readString(sourcePath);
        String compactSource = source.replaceAll("\\s+", "");

        Assertions.assertTrue(
            source.contains("Identifier recipeId = holder.id().identifier();"),
            "Assembly recipes must use the datapack id from RecipeHolder"
        );
        Assertions.assertTrue(
            source.contains("new AssemblyInstruction(recipeId, recipe, out.copy())"),
            "AssemblyInstruction must retain the RecipeHolder id"
        );
        Assertions.assertTrue(
            compactSource.contains(
                "getRecipeManager().byKey(ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,recipeId))"
            ),
            "Saved/synced assembly recipe ids must resolve through RecipeManager"
        );
    }
}
