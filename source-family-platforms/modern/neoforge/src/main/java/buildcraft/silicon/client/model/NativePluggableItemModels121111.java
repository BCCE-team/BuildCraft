//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.silicon.client.model;

import buildcraft.lib.platform.client.ClientModelBaking;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3f;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.client.model.MutableVertex;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.silicon.BCSilicon;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.client.model.plug.PlugBakerFacade;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.item.ItemPluggableLens.LensData;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.PluggablePulsar;
import buildcraft.transport.BCTransportModels;

import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.item.BlockModelWrapper;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.quad.BakedColors;
import net.neoforged.neoforge.client.model.quad.BakedNormals;

/**
 * Native 1.21.11 item-model bridge for BuildCraft Silicon pluggable inventory models.
 * Minecraft 1.21.11 uses native ItemModels; BuildCraft mutable-quad inventory geometry is converted into that
 * representation here.
 */
public final class NativePluggableItemModels121111 {
    private static final Identifier GATE = id("plug/gate");
    private static final Identifier LENS = id("plug/lens");
    private static final Identifier PULSAR = id("plug/pulsar");
    private static final Identifier FACADE = id("plug/facade");

    private NativePluggableItemModels121111() {
    }

    public static void install(ClientModelBaking.Models event) {
        Map<Identifier, ItemModel> models = event.itemStackModels();
        models.put(GATE, new GateItemModel());
        models.put(LENS, new LensItemModel());
        models.put(PULSAR, new PulsarItemModel());
        models.put(FACADE, new FacadeItemModel());
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(BCSilicon.MODID, path);
    }

    private static final class GateItemModel implements ItemModel {
        private final Map<GateVariant, ItemModel> cache = new HashMap<>();

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            GateVariant variant = ItemPluggableGate.getVariant(StackUtil.asNonNull(stack));
            cache.computeIfAbsent(variant, GateItemModel::bake)
                .update(renderState, stack, resolver, displayContext, level, owner, seed);
        }

        private static ItemModel bake(GateVariant variant) {
            List<MutableQuad> cutout = new ArrayList<>();
            addAll(cutout, BCSiliconModels.getGateStaticQuads(Direction.WEST, variant));
            addAll(cutout, BCSiliconModels.GATE_DYNAMIC.getCutoutQuads());
            return layer(cutout, ModelItemSimple.TRANSFORM_PLUG_AS_ITEM_BIGGER, Sheets.cutoutBlockSheet(), false);
        }
    }

    private static final class LensItemModel implements ItemModel {
        private final ItemModel[] cache = new ItemModel[34];

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            int damage = ItemPluggableLens.getData(stack).getItemDamage();
            if (damage < 0 || damage >= cache.length) {
                damage = 0;
            }
            ItemModel model = cache[damage];
            if (model == null) {
                model = bake(damage);
                cache[damage] = model;
            }
            model.update(renderState, stack, resolver, displayContext, level, owner, seed);
        }

        private static ItemModel bake(int damage) {
            LensData data = new LensData(damage);
            MutableQuad[] cutout;
            MutableQuad[] translucent;
            if (data.isFilter) {
                cutout = BCSiliconModels.getFilterCutoutQuads(Direction.WEST, data.colour);
                translucent = BCSiliconModels.getFilterTranslucentQuads(Direction.WEST, data.colour);
            } else {
                cutout = BCSiliconModels.getLensCutoutQuads(Direction.WEST, data.colour);
                translucent = BCSiliconModels.getLensTranslucentQuads(Direction.WEST, data.colour);
            }
            return composite(
                layer(List.of(cutout), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM, Sheets.cutoutBlockSheet(), false),
                layer(List.of(translucent), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM, Sheets.translucentBlockItemSheet(), false)
            );
        }
    }

    private static final class PulsarItemModel implements ItemModel {
        private volatile ItemModel cached;

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            ItemModel model = cached;
            if (model == null) {
                synchronized (this) {
                    model = cached;
                    if (model == null) {
                        model = bake();
                        cached = model;
                    }
                }
            }
            model.update(renderState, stack, resolver, displayContext, level, owner, seed);
        }

        private static ItemModel bake() {
            PluggablePulsar.setModelVariablesForItem();
            return composite(
                layer(List.of(BCSiliconModels.PULSAR_STATIC.getCutoutQuads()), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM,
                    Sheets.cutoutBlockSheet(), false),
                layer(List.of(BCSiliconModels.PULSAR_DYNAMIC.getCutoutQuads()), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM,
                    Sheets.cutoutBlockSheet(), false)
            );
        }
    }

    private static final class FacadeItemModel implements ItemModel {
        private final Map<KeyPlugFacade, ItemModel> cache = new HashMap<>();

        @Override
        public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver,
            ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
            FacadeInstance instance = ItemPluggableFacade.getStates(stack);
            FacadePhasedState phased = instance.getCurrentStateForStack();
            KeyPlugFacade key = new KeyPlugFacade(
                RenderCompat.translucent(), Direction.WEST, phased.stateInfo.state, instance.isHollow
            );
            cache.computeIfAbsent(key, FacadeItemModel::bake)
                .update(renderState, stack, resolver, displayContext, level, owner, seed);
        }

        private static ItemModel bake(KeyPlugFacade key) {
            boolean glass = isGlass(key.state);
            List<MutableQuad> facade = prepareFacadeItemQuads(
                key, PlugBakerFacade.INSTANCE.bakeForKey(key, false)
            );
            ItemModel facadeLayer = layer(
                facade,
                ModelItemSimple.TRANSFORM_PLUG_AS_BLOCK,
                glass ? Sheets.translucentBlockItemSheet() : Sheets.cutoutBlockSheet(),
                false
            );

            if (!BCModules.TRANSPORT.isLoaded() || key.isHollow || !key.state.isSolidRender()) {
                return facadeLayer;
            }

            List<MutableQuad> blocker = new ArrayList<>();
            addAll(blocker, BCTransportModels.BLOCKER.getCutoutQuads());
            return composite(
                facadeLayer,
                layer(blocker, ModelItemSimple.TRANSFORM_PLUG_AS_BLOCK, Sheets.cutoutBlockSheet(), false)
            );
        }
    }

    private static ItemModel composite(ItemModel first, ItemModel second) {
        if (first == EmptyItemModel.INSTANCE) {
            return second;
        }
        if (second == EmptyItemModel.INSTANCE) {
            return first;
        }
        return new CompositeModel(List.of(first, second));
    }

    private static ItemModel layer(List<MutableQuad> mutable, ItemTransforms transforms, RenderType renderType,
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
        itemQuad.lighti(15, 15);

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

    private static List<MutableQuad> prepareFacadeItemQuads(KeyPlugFacade key, List<MutableQuad> source) {
        List<MutableQuad> result = new ArrayList<>(source.size());
        for (MutableQuad original : source) {
            MutableQuad quad = new MutableQuad(original);
            int encodedTint = quad.getTint();
            if (encodedTint != -1) {
                int blockTintIndex = encodedTint / Direction.values().length;
                int colour = resolveInventoryBlockTint(key.state, blockTintIndex);
                if (colour != -1) {
                    quad.multColouri(
                        (colour >> 16) & 0xFF,
                        (colour >> 8) & 0xFF,
                        colour & 0xFF,
                        0xFF
                    );
                }
                // Native ItemModel tint sources are separate from ItemColor handlers. The facade state is
                // already part of the cache key, so bake its inventory fallback tint directly into vertex colour.
                quad.setTint(-1);
            }
            result.add(quad);
        }
        return result;
    }

    private static int resolveInventoryBlockTint(BlockState state, int tintIndex) {
        int colour = -1;
        try {
            colour = Minecraft.getInstance().getBlockColors().getColor(state, null, null, tintIndex);
        } catch (RuntimeException ignored) {
            // Some third-party BlockColor implementations require a non-null world/position.
        }
        if (colour != -1 && colour != 0) {
            return colour;
        }

        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String path = id == null ? "" : id.getPath();
        if (path.endsWith("_leaves") || path.equals("leaves") || path.contains("mangrove") || path.contains("vine")) {
            return FoliageColor.get(0.5D, 1.0D);
        }
        if (path.contains("grass") || path.contains("fern")) {
            return GrassColor.get(0.5D, 1.0D);
        }
        return colour;
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

    private static void addAll(List<MutableQuad> target, MutableQuad[] source) {
        if (source == null || source.length == 0) {
            return;
        }
        for (MutableQuad quad : source) {
            if (quad != null) {
                target.add(quad);
            }
        }
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
