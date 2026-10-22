package buildcraft.lib.compat.neoforge2612.client.model.geometry;

import java.util.function.Function;

import buildcraft.lib.compat.mc2612.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc2612.client.resources.model.BakedModel;
//? if neoforge {
import net.minecraft.client.resources.model.sprite.Material;
//? } else {
import net.minecraft.client.resources.model.Material;
//?}
import net.minecraft.client.resources.model.ModelBaker;
//? if neoforge {
import net.minecraft.client.renderer.block.dispatch.ModelState;
//? } else {
import net.minecraft.client.resources.model.ModelState;
//?}

public interface IUnbakedGeometry<T> {
    BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
            Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState, ItemOverrides overrides);
}
