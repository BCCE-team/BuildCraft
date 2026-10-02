package buildcraft.silicon;

import java.util.function.Supplier;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class BCSiliconRecipes {
    public static final Supplier<RecipeSerializer<?>> ASSEMBLY_SERIALIZER = () -> null;
    public static final Supplier<RecipeType<?>> ASSEMBLY_TYPE = () -> null;
    private BCSiliconRecipes() {}
}
