package buildcraft.lib.compat.neoforge2612.client.model.geometry;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelState;

public final class UnbakedGeometryHelper {
    private UnbakedGeometryHelper() {}
    public static List<Object> createUnbakedItemElements(int layer, TextureAtlasSprite sprite) { return Collections.emptyList(); }
    public static List<Object> createUnbakedItemMaskElements(int layer, TextureAtlasSprite sprite) { return Collections.emptyList(); }
    public static List<BakedQuad> bakeElements(List<Object> elements, Function<Object, TextureAtlasSprite> spriteGetter,
            ModelState modelState) { return Collections.emptyList(); }
}
