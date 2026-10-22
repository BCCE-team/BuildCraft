//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.client.resources.model;

import java.util.Collections;
import java.util.List;

import buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad;
import buildcraft.lib.compat.mc121111.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.model.data.ModelData;
import net.minecraft.client.renderer.rendertype.RenderType;

/** Legacy model API shim required by shared model code on 1.21.11. */
public interface BakedModel {
    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) { return Collections.emptyList(); }
    default List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType type) { return getQuads(state, side, rand); }
    default boolean useAmbientOcclusion() { return false; }
    default boolean isGui3d() { return false; }
    default boolean usesBlockLight() { return false; }
    default boolean isCustomRenderer() { return false; }
    default TextureAtlasSprite getParticleIcon() { return null; }
    default TextureAtlasSprite getParticleIcon(ModelData data) { return getParticleIcon(); }
    default ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
    default ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}
