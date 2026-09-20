//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client.model;

import java.util.Collections;
import java.util.List;

import buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad;
import buildcraft.lib.compat.mc121111.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc121111.client.resources.model.BakedModel;
import buildcraft.lib.compat.neoforge121111.client.model.geometry.IGeometryBakingContext;

/** CompositeModel compatibility facade for NeoForge 1.21.11. */
public final class CompositeModel {
    private CompositeModel() {}

    public static final class Baked {
        public static Builder builder(IGeometryBakingContext context, TextureAtlasSprite particle,
                ItemOverrides overrides, ItemTransforms transforms) {
            return new Builder(particle, overrides, transforms);
        }

        public static final class Builder {
            private TextureAtlasSprite particle;
            private final ItemOverrides overrides;
            private final ItemTransforms transforms;

            private Builder(TextureAtlasSprite particle, ItemOverrides overrides, ItemTransforms transforms) {
                this.particle = particle;
                this.overrides = overrides == null ? ItemOverrides.EMPTY : overrides;
                this.transforms = transforms == null ? ItemTransforms.NO_TRANSFORMS : transforms;
            }

            public Builder addQuads(Object renderTypes, List<BakedQuad> quads) { return this; }
            public Builder setParticle(TextureAtlasSprite particle) { this.particle = particle; return this; }
            public BakedModel build() { return new EmptyBakedModel(particle, overrides, transforms); }
        }
    }

    private record EmptyBakedModel(TextureAtlasSprite particle, ItemOverrides overrides, ItemTransforms transforms) implements BakedModel {
        public List<BakedQuad> getQuads(net.minecraft.world.level.block.state.BlockState state,
                net.minecraft.core.Direction side, net.minecraft.util.RandomSource rand) { return Collections.emptyList(); }
        public TextureAtlasSprite getParticleIcon() { return particle; }
        public ItemOverrides getOverrides() { return overrides; }
        public ItemTransforms getTransforms() { return transforms; }
    }
}
