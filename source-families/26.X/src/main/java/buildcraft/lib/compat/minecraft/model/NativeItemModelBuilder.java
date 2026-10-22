package buildcraft.lib.compat.minecraft.model;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.misc.SpriteUtil;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
//? if neoforge && (>=26.3) {
//? } else {
import buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad;
//?}
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
//? if neoforge && (>=26.3) {
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
//? } else {
import net.minecraft.client.renderer.item.BlockModelWrapper;
//?}
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
//? if neoforge && (>=26.3) {
import org.joml.Matrix4f;
//? } else {
//?}

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
//? if neoforge && (>=26.3) {
            BakedQuad baked = toNative(quad, renderType);
//? } else {
            BakedQuad baked = toNative(quad);
//?}
            if (baked != null) {
                nativeQuads.add(baked);
            }
        }
        if (nativeQuads.isEmpty()) {
            return EmptyItemModel.INSTANCE;
        }

//? if neoforge && (>=26.3) {
        TextureAtlasSprite particle = nativeQuads.get(0).materialInfo().sprite();
//? } else {
        TextureAtlasSprite particle = nativeQuads.get(0).sprite();
//?}
        if (particle == null) {
            particle = SpriteUtil.missingSprite();
        }
//? if neoforge && (>=26.3) {
        ChunkSectionLayer layer = renderType.hasBlending()
            ? ChunkSectionLayer.TRANSLUCENT
            : ChunkSectionLayer.CUTOUT;
        ModelRenderProperties properties = new ModelRenderProperties(
            usesBlockLight, new Material.Baked(particle, layer.translucent()), transforms
        );
        QuadCollection.Builder quads = new QuadCollection.Builder();
        nativeQuads.forEach(quads::addUnculledFace);
        return new CuboidItemModelWrapper(List.of(), quads.build(), properties, new Matrix4f());
//? } else {
        ModelRenderProperties properties = new ModelRenderProperties(usesBlockLight, particle, transforms);
        return new BlockModelWrapper(List.of(), nativeQuads, properties, ignored -> renderType);
//?}
    }

//? if neoforge && (>=26.3) {
    private static BakedQuad toNative(MutableQuad quad, RenderType renderType) {
//? } else {
    private static BakedQuad toNative(MutableQuad quad) {
//?}
        if (quad == null || quad.getSprite() == null) {
            return null;
        }

        MutableQuad itemQuad = new MutableQuad(quad);

        MutableVertex v0 = itemQuad.vertex_0;
        MutableVertex v1 = itemQuad.vertex_1;
        MutableVertex v2 = itemQuad.vertex_2;
        MutableVertex v3 = itemQuad.vertex_3;
        Direction face = actualFace(itemQuad);
//? if neoforge && (>=26.3) {
        ChunkSectionLayer layer = renderType.hasBlending()
            ? ChunkSectionLayer.TRANSLUCENT
            : ChunkSectionLayer.CUTOUT;
        Direction shadeOverride = itemQuad.getShadeDirectionOverride();
        BakedQuad.MaterialInfo defaults = BakedQuad.MaterialInfo.of(
            new Material.Baked(itemQuad.getSprite(), layer.translucent()), itemQuad.getSprite().transparency(),
            itemQuad.getTint(), shadeOverride, lightEmission(itemQuad), true);
        BakedQuad.MaterialInfo material = new BakedQuad.MaterialInfo(
            itemQuad.getSprite(), layer, renderType, defaults.itemGlintRenderType(), defaults.itemGlintSpecialRenderType(),
            itemQuad.getTint(), shadeOverride, lightEmission(itemQuad), true
        );
//? } else {
//?}

        return new BakedQuad(
            position(v0),
            position(v1),
            position(v2),
            position(v3),
            UVPair.pack(v0.tex_u, v0.tex_v),
            UVPair.pack(v1.tex_u, v1.tex_v),
            UVPair.pack(v2.tex_u, v2.tex_v),
            UVPair.pack(v3.tex_u, v3.tex_v),
//? if neoforge && (>=26.3) {
//? } else {
            itemQuad.getTint(),
//?}
            face,
//? if neoforge && (>=26.3) {
            material,
            new BakedNormals.PerVertex(
                packNormal(v0.normal_x, v0.normal_y, v0.normal_z),
                packNormal(v1.normal_x, v1.normal_y, v1.normal_z),
                packNormal(v2.normal_x, v2.normal_y, v2.normal_z),
                packNormal(v3.normal_x, v3.normal_y, v3.normal_z)
//? } else {
            itemQuad.getSprite(),
            itemQuad.isShade(),
            lightEmission(itemQuad),
            BakedNormals.of(
                BakedNormals.pack(v0.normal_x, v0.normal_y, v0.normal_z),
                BakedNormals.pack(v1.normal_x, v1.normal_y, v1.normal_z),
                BakedNormals.pack(v2.normal_x, v2.normal_y, v2.normal_z),
                BakedNormals.pack(v3.normal_x, v3.normal_y, v3.normal_z)
//?}
            ),
//? if neoforge && (>=26.3) {
            new BakedColors.PerVertex(argb(v0), argb(v1), argb(v2), argb(v3))
//? } else {
            BakedColors.of(argb(v0), argb(v1), argb(v2), argb(v3)),
            false
//?}
        );
//? if neoforge && (>=26.3) {
    }

    private static int packNormal(float x, float y, float z) {
        return (((byte) Math.round(x * 127.0F)) & 0xFF)
            | ((((byte) Math.round(y * 127.0F)) & 0xFF) << 8)
            | ((((byte) Math.round(z * 127.0F)) & 0xFF) << 16);
//? } else {
//?}
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

        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            renderState.appendModelIdentityElement(this);
        }
    }
}
