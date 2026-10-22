package buildcraft.silicon.recipe;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

public class FacadeAssemblyRecipesTester {
    @Test
    void serializerFactoryPreservesDatapackRecipeId() {
        ResourceLocation id = new ResourceLocation("buildcraftsilicon", "special/facade");
        FacadeAssemblyRecipes recipe = FacadeAssemblyRecipes.getInstance(id);

        Assertions.assertEquals(id, recipe.getId());
        Assertions.assertNotSame(FacadeAssemblyRecipes.INSTANCE, recipe);
    }
}
