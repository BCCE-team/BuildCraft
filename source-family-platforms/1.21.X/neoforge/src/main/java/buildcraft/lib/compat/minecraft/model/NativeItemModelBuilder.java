//? source if >=1.21.11
package buildcraft.lib.compat.minecraft.model;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.SpriteUtil;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

/** Converts BCCE geometry to native item layers without losing per-vertex colour or normals. */
public final class NativeItemModelBuilder {
    private NativeItemModelBuilder() {
    }

    public static ItemModel layer(List<MutableQuad> mutable, ItemTransforms transforms, RenderType renderType,
        boolean usesBlockLight) {
        if (mutable == null || mutable.isEmpty()) {
            return EmptyItemModel.INSTANCE;
        }

        List<BakedQuad> nativeQuads = new ArrayList<>(mutable.size());
        for (MutableQuad quad : mutable) {
            BakedQuad baked = toNative(quad);
            if (baked != null) {
                nativeQuads.add(baked);
            }
        }
        if (nativeQuads.isEmpty()) {
            return EmptyItemModel.INSTANCE;
        }

        TextureAtlasSprite particle = nativeQuads.get(0).sprite();
        if (particle == null) {
            particle = SpriteUtil.missingSprite();
        }
        ModelRenderProperties properties = new ModelRenderProperties(usesBlockLight, particle, transforms);
        return new BlockModelWrapper(List.of(), nativeQuads, properties, ignored -> renderType);
    }

    private static BakedQuad toNative(MutableQuad quad) {
        if (quad == null || quad.getSprite() == null) {
            return null;
        }

        MutableQuad itemQuad = new MutableQuad(quad);

        MutableVertex v0 = itemQuad.vertex_0;
        MutableVertex v1 = itemQuad.vertex_1;
        MutableVertex v2 = itemQuad.vertex_2;
        MutableVertex v3 = itemQuad.vertex_3;
        Direction face = actualFace(itemQuad);

        return new BakedQuad(
            position(v0),
            position(v1),
            position(v2),
            position(v3),
            UVPair.pack(v0.tex_u, v0.tex_v),
            UVPair.pack(v1.tex_u, v1.tex_v),
            UVPair.pack(v2.tex_u, v2.tex_v),
            UVPair.pack(v3.tex_u, v3.tex_v),
            itemQuad.getTint(),
            face,
            itemQuad.getSprite(),
            itemQuad.isShade(),
            lightEmission(itemQuad),
            BakedNormals.of(
                BakedNormals.pack(v0.normal_x, v0.normal_y, v0.normal_z),
                BakedNormals.pack(v1.normal_x, v1.normal_y, v1.normal_z),
                BakedNormals.pack(v2.normal_x, v2.normal_y, v2.normal_z),
                BakedNormals.pack(v3.normal_x, v3.normal_y, v3.normal_z)
            ),
            BakedColors.of(argb(v0), argb(v1), argb(v2), argb(v3)),
            false
        );
    }

    private static Vector3f position(MutableVertex vertex) {
        return new Vector3f(vertex.position_x, vertex.position_y, vertex.position_z);
    }

    private static int argb(MutableVertex vertex) {
        return ((vertex.colour_a & 0xFF) << 24)
            | ((vertex.colour_r & 0xFF) << 16)
            | ((vertex.colour_g & 0xFF) << 8)
            | (vertex.colour_b & 0xFF);
    }

    private static int lightEmission(MutableQuad quad) {
        int max = 0;
        for (MutableVertex vertex : quad.vertexs) {
            max = Math.max(max, vertex.light_block & 0xFFFF);
        }
        return Math.min(15, max);
    }

    private static Direction actualFace(MutableQuad quad) {
        if (quad.getFace() != null) {
            return quad.getFace();
        }
        MutableVertex vertex = quad.vertex_0;
        float ax = Math.abs(vertex.normal_x);
        float ay = Math.abs(vertex.normal_y);
        float az = Math.abs(vertex.normal_z);
        if (ax + ay + az < 1.0e-6F) {
            return Direction.UP;
        }
        if (ay >= ax && ay >= az) {
            return vertex.normal_y >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return vertex.normal_x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return vertex.normal_z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private enum EmptyItemModel implements ItemModel {
        INSTANCE;

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            renderState.appendModelIdentityElement(this);
        }
    }
}
