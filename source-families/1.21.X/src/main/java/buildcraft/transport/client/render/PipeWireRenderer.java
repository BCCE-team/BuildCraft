/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.render;

import java.util.EnumMap;
import java.util.Map;
//? if >=1.21.10 {
import buildcraft.lib.compat.NbtCompat;
import net.minecraft.nbt.CompoundTag;
//?}

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.transport.internal.EnumWirePart;
import buildcraft.lib.client.model.ModelUtil;
import buildcraft.lib.client.model.ModelUtil.UvFaceData;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.client.sprite.SpriteHolderRegistry;
import buildcraft.lib.misc.VecUtil;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.transport.wire.EnumWireBetween;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
public class PipeWireRenderer {

    private static final Map<EnumWirePart, MutableQuad[]> partQuads = new EnumMap<>(EnumWirePart.class);
    private static final Map<EnumWireBetween, MutableQuad[]> betweenQuads = new EnumMap<>(EnumWireBetween.class);

    private static final Map<DyeColor, SpriteHolderRegistry.SpriteHolder> wireSprites =
        new EnumMap<>(DyeColor.class);
    static {
        for (DyeColor color : DyeColor.values()) {
            wireSprites.put(color, SpriteHolderRegistry.getHolder("buildcrafttransport:wires/" + color.getName()));
        }

        for (EnumWirePart part : EnumWirePart.VALUES) {
            partQuads.put(part, getQuads(part));
        }
        for (EnumWireBetween part : EnumWireBetween.VALUES) {
            betweenQuads.put(part, getQuads(part));
        }
    }

/*    public static void clearWireCache() {
        Arrays.fill(wireRenderingCache, -1);
    }*/
    
    public static SpriteHolderRegistry.SpriteHolder getWireSprite(DyeColor colour) {
        return wireSprites.get(colour);
    }

    private static MutableQuad[] getQuads(EnumWirePart part) {
        MutableQuad[] quads = new MutableQuad[6];

        Vector3f center = new Vector3f(//
            0.5f + (part.x.getStep() * 4.51f / 16f), //
            0.5f + (part.y.getStep() * 4.51f / 16f), //
            0.5f + (part.z.getStep() * 4.51f / 16f) //
        );
        Vector3f radius = new Vector3f(1 / 32f, 1 / 32f, 1 / 32f);
        UvFaceData uvs = new UvFaceData();
        int off = func(part.x) * 4 + func(part.y) * 2 + func(part.z);
        uvs.minU = off / 16f;
        uvs.maxU = (off + 1) / 16f;
        uvs.minV = 0;
        uvs.maxV = 1 / 16f;
        for (Direction face : Direction.values()) {
            quads[face.ordinal()] = ModelUtil.createFace(face, center, radius, uvs);
        }
        return quads;
    }

    private static int func(Direction.AxisDirection dir) {
        return dir == AxisDirection.POSITIVE ? 1 : 0;
    }

    private static MutableQuad[] getQuads(EnumWireBetween between) {
        // 4 rather than 6 -- don't render the end caps
        MutableQuad[] quads = new MutableQuad[4];

        int i = 0;

        Vec3 center;
        Vec3 radius;

        boolean ax = between.mainAxis == Axis.X;
        boolean ay = between.mainAxis == Axis.Y;
        boolean az = between.mainAxis == Axis.Z;

        if (between.to == null) {
            double cL = 0.5f - 4.51f / 16f;
            double cU = 0.5f + 4.51f / 16f;
            center = new Vec3(//
                ax ? 0.5f : (between.xy ? cU : cL), //
                ay ? 0.5f : ((ax ? between.xy : between.yz) ? cU : cL), //
                az ? 0.5f : (between.yz ? cU : cL) //
            );
            double rC = 4.01f / 16f;
            double rN = 1f / 16f / 2;
            radius = new Vec3(//
                ax ? rC : rN, //
                ay ? rC : rN, //
                az ? rC : rN //
            );
        } else {// we are a connection
            double cL = (8 - 4.51) / 16;
            double cU = (8 + 4.51) / 16;
            radius = new Vec3(//
                ax ? 2.99 / 32 : 1 / 32.0, //
                ay ? 2.99 / 32 : 1 / 32.0, //
                az ? 2.99 / 32 : 1 / 32.0 //
            );
            center = new Vec3(//
                ax ? (0.5 + 6.505 / 16 * between.to.getStepX()) : (between.xy ? cU : cL), //
                ay ? (0.5 + 6.505 / 16 * between.to.getStepY()) : ((ax ? between.xy : between.yz) ? cU : cL), //
                az ? (0.5 + 6.505 / 16 * between.to.getStepZ()) : (between.yz ? cU : cL) //
            );
        }

        UvFaceData uvBase = new UvFaceData();
        uvBase.minU = (float) VecUtil.getValue(center.subtract(radius), between.mainAxis);
        uvBase.maxU = (float) VecUtil.getValue(center.add(radius), between.mainAxis);
        uvBase.minV = 0;
        uvBase.maxV = 1 / 16f;

        Vector3f centerFloat = new Vector3f((float) center.x, (float) center.y, (float) center.z);
        Vector3f radiusFloat = new Vector3f((float) radius.x, (float) radius.y, (float) radius.z);

        for (Direction face : Direction.values()) {
            if (face.getAxis() == between.mainAxis) {
                continue;
            }
            UvFaceData uvs = new UvFaceData(uvBase);

            Axis aAxis = between.mainAxis;
            Axis fAxis = face.getAxis();
            boolean fPositive = face.getAxisDirection() == AxisDirection.POSITIVE;

            int rotations = 0;
            boolean swapU = false;
            boolean swapV = false;

            if (aAxis == Axis.X) {
                swapV = fPositive;
            } else if (aAxis == Axis.Y) {
                rotations = 1;
                swapU = (fAxis == Axis.X) != fPositive;
                swapV = fAxis == Axis.Z;
            } else {// aAxis == Axis.Z
                if (fAxis == Axis.Y) {
                    rotations = 1;
                }
                swapU = face == Direction.DOWN;
                swapV = face != Direction.EAST;
            }

            if (swapU) {
                float t = uvs.minU;
                uvs.minU = uvs.maxU;
                uvs.maxU = t;
            }
            if (swapV) {
                float t = uvs.minV;
                uvs.minV = uvs.maxV;
                uvs.maxV = t;
            }

            MutableQuad quad = ModelUtil.createFace(face, centerFloat, radiusFloat, uvs);
            if (rotations > 0) quad.rotateTextureUp(rotations);
            quads[i++] = quad;
        }
        return quads;
    }

    private static void renderQuads(Matrix4f pose, Matrix3f normal, VertexConsumer bb, MutableQuad[] quads,
    		ISprite sprite, int level, int blockLight, int skyLight, int combinedOverlay) {
        float vOffset = (level & 0xF) / 16f;
        MutableQuad newQuad = new MutableQuad();
        for (MutableQuad q : quads) {
            if (q.getFace() != Direction.UP && level != 15) {
                q = newQuad.copyFrom(q);
                float shade = 1 - q.getCalculatedDiffuse();
                shade = shade * (15 - level) / 15;
                shade = 1 - shade;
                q.multColourd(shade);
            }
            q.lighti(blockLight>>4, skyLight>>4);
            renderVertex(pose, normal, bb, q.vertex_0, sprite, vOffset, combinedOverlay);
            renderVertex(pose, normal, bb, q.vertex_1, sprite, vOffset, combinedOverlay);
            renderVertex(pose, normal, bb, q.vertex_2, sprite, vOffset, combinedOverlay);
            renderVertex(pose, normal, bb, q.vertex_3, sprite, vOffset, combinedOverlay);
        }
    }

    private static void renderVertex(Matrix4f pose, Matrix3f normal, VertexConsumer bb, MutableVertex vertex, ISprite sprite, float vOffset, int combinedOverlay) {
        vertex.renderPosition(pose, bb);
        vertex.renderColour(bb);
        float u = sprite.getInterpU(vertex.tex_u);
        float v = sprite.getInterpV(vertex.tex_v + vOffset);
        bb.setUv(u, v)
        .setOverlay(combinedOverlay);
        vertex.renderLightMap(bb);
 //       bb.uv2(15728640);
        vertex.renderNormal(normal, bb);
    }

    public static void renderWires(TilePipeHolder pipe, float conSize, PoseStack matrix, MultiBufferSource buffer,
			int combinedLight, int combinedOverlay) {
        int skyLight = combinedLight >> 16 & 0xFFFF;
        int blockLight = combinedLight & 0xFFFF;
//        RenderSystem.disa();
//        matrix.pushPose();
        VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        for (Map.Entry<EnumWirePart, DyeColor> partColor : pipe.getWireManager().parts.entrySet()) {
            EnumWirePart part = partColor.getKey();
            DyeColor color = partColor.getValue();
            boolean isOn = pipe.wireManager.isPowered(part);
            renderQuads(pose, normal, bb, partQuads.get(part), wireSprites.get(color), isOn ? 15 : 0, isOn ? 240 : blockLight, skyLight, combinedOverlay);
        }
        for (Map.Entry<EnumWireBetween, DyeColor> betweenColor : pipe.getWireManager().betweens.entrySet()) {
            EnumWireBetween between = betweenColor.getKey();
            DyeColor color = betweenColor.getValue();
            boolean isOn = pipe.wireManager.isPowered(between.parts[0]);
            renderQuads(pose, normal, bb, betweenQuads.get(between), wireSprites.get(color), isOn ? 15 : 0, isOn ? 240 : blockLight, skyLight, combinedOverlay);
        }
    }

    //? if >=1.21.10 {
    /**
     * Draw the exact existing BuildCraft wire cubes and segments from a blueprint.
     * No TilePipeHolder, level, wire-system rebuild or signal execution is needed.
     * Local parts are stored in the pipe tile NBT; cross-pipe segments require a
     * corresponding same-colour part in the adjacent blueprint cell.
     */
    public static void renderPreviewWires(CompoundTag tile, Map<Direction, CompoundTag> neighbours,
        PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay) {
        EnumMap<EnumWirePart, DyeColor> parts = readPreviewParts(tile);
        if (parts.isEmpty()) return;
        int sky = light >>> 16 & 0xFFFF;
        int block = light & 0xFFFF;
        int[] litParts = NbtCompat.getIntArray(NbtCompat.getCompound(tile, "wireManager"), "previewPowered");
        for (Map.Entry<EnumWirePart, DyeColor> entry : parts.entrySet()) {
            DyeColor color = entry.getValue();
            ISprite sprite = wireSprites.get(color);
            if (sprite == null) continue;
            boolean powered = isPreviewPowered(litParts, entry.getKey());
            renderQuads(pose.pose(), pose.normal(), consumer, partQuads.get(entry.getKey()),
                sprite, powered ? 15 : 0, powered ? 240 : block, sky, overlay);
        }
        EnumMap<Direction, EnumMap<EnumWirePart, DyeColor>> adjacent = new EnumMap<>(Direction.class);
        for (EnumWireBetween between : EnumWireBetween.VALUES) {
            DyeColor color = parts.get(between.parts[0]);
            if (color == null) continue;
            DyeColor neighbourColor;
            if (between.to == null) {
                neighbourColor = parts.get(between.parts[1]);
            } else {
                CompoundTag other = neighbours.get(between.to);
                if (other == null) continue;
                EnumMap<EnumWirePart, DyeColor> neighbourParts = adjacent.computeIfAbsent(
                    between.to, ignored -> readPreviewParts(other));
                neighbourColor = neighbourParts.get(between.parts[1]);
            }
            if (color != neighbourColor) continue;
            ISprite sprite = wireSprites.get(color);
            if (sprite == null) continue;
            boolean powered = isPreviewPowered(litParts, between.parts[0]);
            renderQuads(pose.pose(), pose.normal(), consumer, betweenQuads.get(between),
                sprite, powered ? 15 : 0, powered ? 240 : block, sky, overlay);
        }
    }

    private static boolean isPreviewPowered(int[] parts, EnumWirePart wire) {
        for (int part : parts) if (part == wire.ordinal()) return true;
        return false;
    }

    /** Reject malformed palette NBT instead of indexing EnumWirePart.VALUES out of bounds. */
    private static EnumMap<EnumWirePart, DyeColor> readPreviewParts(CompoundTag tile) {
        EnumMap<EnumWirePart, DyeColor> result = new EnumMap<>(EnumWirePart.class);
        if (tile == null) return result;
        int[] values = NbtCompat.getIntArray(NbtCompat.getCompound(tile, "wireManager"), "parts");
        // There are eight possible local corners; duplicates and extra values are ignored.
        for (int i = 0; i + 1 < values.length && i < 16; i += 2) {
            int part = values[i];
            int dye = values[i + 1];
            if (part < 0 || part >= EnumWirePart.VALUES.length
                || dye < 0 || dye >= DyeColor.values().length) continue;
            result.putIfAbsent(EnumWirePart.VALUES[part], DyeColor.byId(dye));
        }
        return result;
    }

    //?}

    public static void init() {
        // make sure static runs
    }

}
