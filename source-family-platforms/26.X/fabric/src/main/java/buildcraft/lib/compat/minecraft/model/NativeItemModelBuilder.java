package buildcraft.lib.compat.minecraft.model;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.SpriteUtil;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import org.joml.Matrix4f;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
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

/** Converts BCCE geometry to native item layers. Vanilla quads cannot preserve NeoForge-only vertex colours or normals. */
public final class NativeItemModelBuilder {
    private NativeItemModelBuilder() {
    }

    public static ItemModel layer(List<MutableQuad> mutable, ItemTransforms transforms, RenderType renderType,
        boolean usesBlockLight) {
        return layer(mutable, transforms, renderType, usesBlockLight, List.of());
    }

    /** A tint index on a native BakedQuad is meaningful only when its item model provides the matching source. */
    public static ItemModel layer(List<MutableQuad> mutable, ItemTransforms transforms, RenderType renderType,
        boolean usesBlockLight, List<ItemTintSource> tints) {
        if (mutable == null || mutable.isEmpty()) {
            return EmptyItemModel.INSTANCE;
        }

        List<BakedQuad> nativeQuads = new ArrayList<>(mutable.size());
        for (MutableQuad quad : mutable) {
            BakedQuad baked = toNative(quad, renderType);
            if (baked != null) {
                nativeQuads.add(baked);
            }
        }
        if (nativeQuads.isEmpty()) {
            return EmptyItemModel.INSTANCE;
        }

        TextureAtlasSprite particle = nativeQuads.get(0).materialInfo().sprite();
        if (particle == null) {
            particle = SpriteUtil.missingSprite();
        }
        ModelRenderProperties properties = new ModelRenderProperties(
            usesBlockLight, new Material.Baked(particle, false), transforms);
        QuadCollection.Builder faces = new QuadCollection.Builder();
        for (BakedQuad quad : nativeQuads) {
            faces.addUnculledFace(quad);
        }
        return new CuboidItemModelWrapper(tints, faces.build(), properties, new Matrix4f());
    }

    private static BakedQuad toNative(MutableQuad quad, RenderType itemRenderType) {
        if (quad == null || quad.getSprite() == null) {
            return null;
        }

        MutableQuad itemQuad = new MutableQuad(quad);

        MutableVertex v0 = itemQuad.vertex_0;
        MutableVertex v1 = itemQuad.vertex_1;
        MutableVertex v2 = itemQuad.vertex_2;
        MutableVertex v3 = itemQuad.vertex_3;
        Direction face = actualFace(itemQuad);

        // Minecraft 26.3's vanilla record is 10-argument: NeoForge's extra
        // per-vertex colour/normal records are deliberately not linked here.
        // The common material and light emission remain equivalent; custom per-
        // vertex gradients require a Fabric Renderer API mesh at the caller.
        return new BakedQuad(
            position(v0), position(v1), position(v2), position(v3),
            UVPair.pack(v0.tex_u, v0.tex_v), UVPair.pack(v1.tex_u, v1.tex_v),
            UVPair.pack(v2.tex_u, v2.tex_v), UVPair.pack(v3.tex_u, v3.tex_v),
            face,
            new BakedQuad.MaterialInfo(
                itemQuad.getSprite(), ChunkSectionLayer.CUTOUT,
                itemRenderType, null, null,
                itemQuad.getTint(), itemQuad.isShade() ? face : null,
                lightEmission(itemQuad)
            )
        );
    }

    private static Vector3f position(MutableVertex vertex) {
        return new Vector3f(vertex.position_x, vertex.position_y, vertex.position_z);
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

        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            renderState.appendModelIdentityElement(this);
        }
    }
}
