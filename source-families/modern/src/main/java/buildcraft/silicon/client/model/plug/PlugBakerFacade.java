//? source if >=1.21.1
/* Copyright (c) 2017 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.silicon.client.model.plug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;

import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.misc.VecUtil;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.plug.PluggableFacade;
import buildcraft.transport.BCTransportModels;
import buildcraft.transport.client.model.key.KeyPlugBlocker;
import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** 1.21.11 facade baker backed by the native BlockStateModel API.
 *
 * <p>On 1.21.11 facades sample native BlockModelPart quads from the selected block state, convert them to BuildCraft
 * MutableQuads and remap that geometry onto the thin facade shell.</p>
 */
public enum PlugBakerFacade implements IPluggableStaticBaker<KeyPlugFacade> {
    INSTANCE;

    /**
     * 1.21.11-only extra alpha multiplier for glass facade vertex colours.
     * The glass texture still supplies its own alpha, so this value is multiplied with texture alpha at render time.
     * 1.0 = vanilla glass texture opacity; 0.0 = fully invisible.
     */
    private static final double GLASS_FACADE_ALPHA = 0.2D;

    private final RandomSource random = RandomSource.create();

    private int getVertexIndex(List<Vec3> positions, Direction.Axis axis, boolean minOrMax1, boolean minOrMax2) {
        Direction.Axis axis1;
        Direction.Axis axis2;
        switch (axis) {
            case X -> {
                axis1 = Direction.Axis.Y;
                axis2 = Direction.Axis.Z;
            }
            case Y -> {
                axis1 = Direction.Axis.X;
                axis2 = Direction.Axis.Z;
            }
            case Z -> {
                axis1 = Direction.Axis.X;
                axis2 = Direction.Axis.Y;
            }
            default -> throw new IllegalArgumentException();
        }
        double min1 = positions.stream().mapToDouble(pos -> VecUtil.getValue(pos, axis1)).min().orElse(0);
        double min2 = positions.stream().mapToDouble(pos -> VecUtil.getValue(pos, axis2)).min().orElse(0);
        double max1 = positions.stream().mapToDouble(pos -> VecUtil.getValue(pos, axis1)).max().orElse(0);
        double max2 = positions.stream().mapToDouble(pos -> VecUtil.getValue(pos, axis2)).max().orElse(0);
        double center1 = (min1 + max1) / 2;
        double center2 = (min2 + max2) / 2;
        return positions.indexOf(
            positions.stream()
                .filter(pos ->
                    (minOrMax1 ? VecUtil.getValue(pos, axis1) < center1 : VecUtil.getValue(pos, axis1) > center1)
                        && (minOrMax2 ? VecUtil.getValue(pos, axis2) < center2 : VecUtil.getValue(pos, axis2) > center2)
                )
                .findFirst()
                .orElse(positions.get(0))
        );
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private List<MutableQuad> getTransformedQuads(
        BlockState state,
        BlockStateModel model,
        Direction side,
        Vec3 pos0,
        Vec3 pos1,
        Vec3 pos2,
        Vec3 pos3
    ) {
        List<BlockModelPart> parts = model.collectParts(random);
        List<MutableQuad> source = new ArrayList<>();
        for (BlockModelPart part : parts) {
            List<net.minecraft.client.renderer.block.model.BakedQuad> faceQuads = part.getQuads(side);
            if (!faceQuads.isEmpty()) {
                for (net.minecraft.client.renderer.block.model.BakedQuad quad : faceQuads) {
                    source.add(fromNative(state, quad));
                }
            } else {
                // Some 1.21.11 models keep their geometry in the unculled bucket. A facade still needs the
                // geometrical face matching the requested side; otherwise e.g. translucent/glass states bake to air.
                for (net.minecraft.client.renderer.block.model.BakedQuad quad : part.getQuads(null)) {
                    if (quad.direction() == side) {
                        source.add(fromNative(state, quad));
                    }
                }
            }
        }

        return source.stream().map(mutableQuad -> {
            boolean positive = side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
            Function<Vec3, Vec3> transformPosition = pos -> switch (side.getAxis()) {
                case X -> new Vec3(positive ? 1 - pos.z : pos.z, pos.y, pos.x);
                case Y -> new Vec3(pos.x, positive ? 1 - pos.z : pos.z, pos.y);
                case Z -> new Vec3(pos.y, pos.x, positive ? 1 - pos.z : pos.z);
            };
            List<Vec3> poses = Arrays.asList(
                transformPosition.apply(pos0),
                transformPosition.apply(pos1),
                transformPosition.apply(pos2),
                transformPosition.apply(pos3)
            );
            List<MutableVertex> vertices = Arrays.asList(
                mutableQuad.vertex_0,
                mutableQuad.vertex_1,
                mutableQuad.vertex_2,
                mutableQuad.vertex_3
            );
            List<Vec3> vertexPositions = vertices.stream()
                .map(vertex -> new Vec3(vertex.position_x, vertex.position_y, vertex.position_z))
                .collect(Collectors.toList());
            double minU = vertices.stream().mapToDouble(vertex -> vertex.tex_u).min().orElse(0);
            double minV = vertices.stream().mapToDouble(vertex -> vertex.tex_v).min().orElse(0);
            double maxU = vertices.stream().mapToDouble(vertex -> vertex.tex_u).max().orElse(0);
            double maxV = vertices.stream().mapToDouble(vertex -> vertex.tex_v).max().orElse(0);
            Stream.of(Pair.of(false, false), Pair.of(false, true), Pair.of(true, true), Pair.of(true, false))
                .forEach(minOrMaxPair -> {
                    Vec3 newPos = poses.get(
                        getVertexIndex(poses, side.getAxis(), minOrMaxPair.getLeft(), minOrMaxPair.getRight())
                    );
                    MutableVertex vertex = vertices.get(
                        getVertexIndex(vertexPositions, side.getAxis(), minOrMaxPair.getLeft(), minOrMaxPair.getRight())
                    );
                    vertex.positiond(newPos.x, newPos.y, newPos.z);
                    switch (side.getAxis()) {
                        case X -> vertex.texf(
                            (float) (minU + (maxU - minU) * (positive ? (1 - newPos.z) : newPos.z)),
                            (float) (minV + (maxV - minV) * (1 - newPos.y))
                        );
                        case Y -> vertex.texf(
                            (float) (minU + (maxU - minU) * newPos.x),
                            (float) (minV + (maxV - minV) * (positive ? newPos.z : (1 - newPos.z)))
                        );
                        case Z -> vertex.texf(
                            (float) (minU + (maxU - minU) * (positive ? newPos.x : (1 - newPos.x))),
                            (float) (minV + (maxV - minV) * (1 - newPos.y))
                        );
                    }
                });
            return mutableQuad;
        }).collect(Collectors.toList());
    }

    private static MutableQuad fromNative(BlockState state, net.minecraft.client.renderer.block.model.BakedQuad quad) {
        // Keep the source block tint index unresolved here. World facades must resolve biome-dependent colours
        // through PipeBlockColours/PluggableFacade at the real pipe position; baking it with a null world makes
        // leaves/grass gray and also makes the cached geometry biome-independent in the wrong way.
        MutableQuad mutable = new MutableQuad(quad.tintIndex(), quad.direction(), quad.shade());
        mutable.setSprite(quad.sprite());

        for (int i = 0; i < 4; i++) {
            MutableVertex vertex = mutable.vertexs[i];
            var pos = quad.position(i);
            vertex.positionf(pos.x(), pos.y(), pos.z());
            long packedUv = quad.packedUV(i);
            vertex.texf(
                Float.intBitsToFloat((int) (packedUv >>> 32)),
                Float.intBitsToFloat((int) packedUv)
            );
            // NeoForge 1.21.11 carries static vertex colour/alpha separately from BlockColor tinting.
            vertex.colouri(quad.bakedColors().color(i));
            Direction face = quad.direction();
            if (face != null) {
                var normal = face.getUnitVec3i();
                vertex.normalf(normal.getX(), normal.getY(), normal.getZ());
            }
            int light = quad.lightEmission();
            vertex.lighti(light, light);
        }
        return mutable;
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private Vec3 rotate(Vec3 vec, Rotation rotation) {
        return switch (rotation) {
            case NONE -> new Vec3(vec.x, vec.y, vec.z);
            case CLOCKWISE_90 -> new Vec3(1 - vec.y, 1 - vec.x, vec.z);
            case CLOCKWISE_180 -> new Vec3(1 - vec.x, 1 - vec.y, vec.z);
            case COUNTERCLOCKWISE_90 -> new Vec3(vec.y, vec.x, vec.z);
        };
    }

    private void addRotatedQuads(
        List<MutableQuad> quads,
        BlockState state,
        BlockStateModel model,
        Direction side,
        Rotation rotation,
        Vec3 pos0,
        Vec3 pos1,
        Vec3 pos2,
        Vec3 pos3
    ) {
        quads.addAll(getTransformedQuads(
            state, model, side,
            rotate(pos0, rotation), rotate(pos1, rotation), rotate(pos2, rotation), rotate(pos3, rotation)
        ));
    }

    public List<MutableQuad> bakeForKey(KeyPlugFacade key) {
        return bakeForKey(key, true);
    }

    public List<MutableQuad> bakeForKey(KeyPlugFacade key, boolean applyGlassAlpha) {
        BlockStateModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(key.state);
        List<MutableQuad> quads = new ArrayList<>();
        int pS = PluggableFacade.SIZE;
        int nS = 16 - pS;
        if (!key.isHollow) {
            quads.addAll(getTransformedQuads(
                key.state, model, key.side,
                new Vec3(0, 1, 0), new Vec3(1, 1, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 0)
            ));
            quads.addAll(getTransformedQuads(
                key.state, model, key.side.getOpposite(),
                new Vec3(pS / 16D, nS / 16D, nS / 16D),
                new Vec3(nS / 16D, nS / 16D, nS / 16D),
                new Vec3(nS / 16D, pS / 16D, nS / 16D),
                new Vec3(pS / 16D, pS / 16D, nS / 16D)
            ));
        }
        for (Rotation rotation : Rotation.values()) {
            if (key.isHollow) {
                addRotatedQuads(
                    quads, key.state, model, key.side, rotation,
                    new Vec3(0, rotation.ordinal() % 2 == 0 ? 4 / 16D : 0, 0),
                    new Vec3(4 / 16D, rotation.ordinal() % 2 == 0 ? 4 / 16D : 0, 0),
                    new Vec3(4 / 16D, rotation.ordinal() % 2 == 0 ? 1 : 12 / 16D, 0),
                    new Vec3(0, rotation.ordinal() % 2 == 0 ? 1 : 12 / 16D, 0)
                );
            }
            addRotatedQuads(
                quads, key.state, model, key.side.getOpposite(), rotation,
                new Vec3(0, 1, 1),
                new Vec3(pS / 16D, nS / 16D, nS / 16D),
                new Vec3(pS / 16D, pS / 16D, nS / 16D),
                new Vec3(0, 0, 1)
            );
            if (key.isHollow) {
                addRotatedQuads(
                    quads, key.state, model, key.side.getOpposite(), rotation,
                    new Vec3(pS / 16D, rotation.ordinal() % 2 == 0 ? nS / 16D : 12 / 16D, nS / 16D),
                    new Vec3(4 / 16D, rotation.ordinal() % 2 == 0 ? nS / 16D : 12 / 16D, nS / 16D),
                    new Vec3(4 / 16D, rotation.ordinal() % 2 == 0 ? 4 / 16D : pS / 16D, nS / 16D),
                    new Vec3(pS / 16D, rotation.ordinal() % 2 == 0 ? 4 / 16D : pS / 16D, nS / 16D)
                );
            }
        }
        if (key.isHollow) {
            for (Direction facing : Direction.values()) {
                if (facing.getAxis() != key.side.getAxis()) {
                    boolean positive = key.side.getAxisDirection() == Direction.AxisDirection.POSITIVE;
                    if (key.side.getAxis() == Direction.Axis.Z && facing.getAxis() == Direction.Axis.X
                        || key.side.getAxis() == Direction.Axis.X && facing.getAxis() == Direction.Axis.Y
                        || key.side.getAxis() == Direction.Axis.Y && facing.getAxis() == Direction.Axis.Z) {
                        quads.addAll(getTransformedQuads(
                            key.state, model, facing,
                            new Vec3(positive ? 1 : pS / 16D, 4 / 16D, 12.003 / 16D),
                            new Vec3(positive ? 1 : pS / 16D, 12 / 16D, 12.003 / 16D),
                            new Vec3(positive ? nS / 16D : 0, 12 / 16D, 12.003 / 16D),
                            new Vec3(positive ? nS / 16D : 0, 4 / 16D, 12.003 / 16D)
                        ));
                    } else {
                        quads.addAll(getTransformedQuads(
                            key.state, model, facing,
                            new Vec3(4 / 16D, positive ? 1 : pS / 16D, 12.003 / 16D),
                            new Vec3(4 / 16D, positive ? nS / 16D : 0, 12.003 / 16D),
                            new Vec3(12 / 16D, positive ? nS / 16D : 0, 12.003 / 16D),
                            new Vec3(12 / 16D, positive ? 1 : pS / 16D, 12.003 / 16D)
                        ));
                    }
                }
            }
        }
        for (MutableQuad quad : quads) {
            int tint = quad.getTint();
            if (tint != -1) {
                // PipeBlockColours uses the low part of this index to identify the facade side and the high part
                // as the original block tint index. This preserves biome-aware foliage/grass colouring in-world.
                quad.setTint(tint * Direction.values().length + key.side.ordinal());
            }
        }
        if (applyGlassAlpha && isGlass(key.state)) {
            for (MutableQuad quad : quads) {
                quad.multColourd(1.0, 1.0, 1.0, GLASS_FACADE_ALPHA);
            }
        }
        return quads;
    }

    private static boolean isGlass(BlockState state) {
        var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (key == null) {
            return false;
        }
        String path = key.getPath();
        return path.equals("glass") || path.equals("glass_pane")
            || path.endsWith("_stained_glass") || path.endsWith("_stained_glass_pane");
    }

    @Override
    public List<BakedQuad> bake(KeyPlugFacade key) {
        List<BakedQuad> baked = new ArrayList<>();
        for (MutableQuad quad : bakeForKey(key)) {
            baked.add(quad.toBakedBlock());
        }
        if (BCModules.TRANSPORT.isLoaded()
            && key.state.isSolidRender()
            && !key.isHollow) {
            baked.addAll(TransportCompat.bakeBlocker(key.side));
        }
        return baked;
    }

    static final class TransportCompat {
        static List<BakedQuad> bakeBlocker(Direction side) {
            // The blocker starts exactly at the facade inner plane. Its outward face is therefore coplanar with
            // the facade's inner face and produces camera-dependent stripe/z-fighting artifacts in 1.21.11.
            // The facade already covers that face, so discard only the coplanar outward blocker quad.
            List<BakedQuad> result = new ArrayList<>();
            for (BakedQuad quad : BCTransportModels.BAKER_PLUG_BLOCKER.bake(new KeyPlugBlocker(side))) {
                if (quad.getDirection() != side) {
                    result.add(quad);
                }
            }
            return result;
        }
    }
}
