//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model.geometry;

import java.util.function.Function;

import buildcraft.lib.compat.mc121111.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc121111.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;

public interface IUnbakedGeometry<T> {
    BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides);
}
