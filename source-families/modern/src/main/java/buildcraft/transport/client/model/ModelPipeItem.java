//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.transport.client.model;

import java.util.ArrayList;
import java.util.List;

import org.joml.Vector3f;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.ModelUtil;
import buildcraft.lib.client.model.ModelUtil.UvFaceData;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.compat.minecraft.model.NativeItemModelBuilder;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.internal.pipe.EnumPipeColourType;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.transport.internal.pipe.PipeFaceTex;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Native item model; one instance per pipe definition and resource reload. */
public final class ModelPipeItem implements ItemModel {
    private final PipeDefinition definition;
    private final ItemModel[] colours = new ItemModel[17];

    public ModelPipeItem(PipeDefinition definition) {
        this.definition = definition;
    }

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
        ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
        int colour = ItemStackUtil.getCustomData(stack).getIntOr("color", 0);
        if (colour < 0 || colour >= colours.length) {
            colour = 0;
        }
        ItemModel model = colours[colour];
        if (model == null) {
            model = bake(colour);
            colours[colour] = model;
        }
        model.update(state, stack, resolver, context, level, owner, seed);
    }

    private static final MutableQuad[] QUADS_SAME;
    private static final MutableQuad[] QUADS_TOP, QUADS_CENTER, QUADS_BOTTOM;
    private static final MutableQuad[] QUADS_COLOUR;

    static {
        // Same sprite for all 3 sections
        {
            QUADS_SAME = new MutableQuad[6];
            Vector3f center = new Vector3f(0.5f, 0.5f, 0.5f);
            Vector3f radius = new Vector3f(0.25f, 0.5f, 0.25f);
            UvFaceData uvsY = UvFaceData.from16(4, 4, 12, 12);
            UvFaceData uvsXZ = UvFaceData.from16(4, 0, 12, 16);
            for (Direction face : Direction.values()) {
                UvFaceData uvs = face.getAxis() == Axis.Y ? uvsY : uvsXZ;
                QUADS_SAME[face.ordinal()] = ModelUtil.createFace(face, center, radius, uvs);
            }
        }

        // Separate sprites for the top cap, middle band and bottom cap. Internal horizontal faces are omitted.
        {
            QUADS_TOP = createSectionQuads(0.75f, 1.0f, true, false);
            QUADS_CENTER = createSectionQuads(0.25f, 0.75f, false, false);
            QUADS_BOTTOM = createSectionQuads(0.0f, 0.25f, false, true);
        }

        // Translucent Coloured pipes
        {
            QUADS_COLOUR = new MutableQuad[6];
            Vector3f center = new Vector3f(0.5f, 0.5f, 0.5f);
            Vector3f radius = new Vector3f(0.24f, 0.49f, 0.24f);
            UvFaceData uvsY = UvFaceData.from16(4, 4, 12, 12);
            UvFaceData uvsXZ = UvFaceData.from16(4, 0, 12, 16);
            for (Direction face : Direction.values()) {
                UvFaceData uvs = face.getAxis() == Axis.Y ? uvsY : uvsXZ;
                QUADS_COLOUR[face.ordinal()] = ModelUtil.createFace(face, center, radius, uvs);
            }
        }
    }

    private static MutableQuad[] createSectionQuads(float minY, float maxY, boolean includeTop, boolean includeBottom) {
        MutableQuad[] quads = new MutableQuad[6];
        Vector3f center = new Vector3f(0.5f, (minY + maxY) * 0.5f, 0.5f);
        Vector3f radius = new Vector3f(0.25f, (maxY - minY) * 0.5f, 0.25f);
        UvFaceData sideUvs = UvFaceData.from16(4, minY * 16.0f, 12, maxY * 16.0f);
        UvFaceData capUvs = UvFaceData.from16(4, 4, 12, 12);
        for (Direction face : Direction.values()) {
            if (face == Direction.UP && !includeTop) continue;
            if (face == Direction.DOWN && !includeBottom) continue;
            quads[face.ordinal()] = ModelUtil.createFace(face, center, radius, face.getAxis() == Axis.Y ? capUvs : sideUvs);
        }
        return quads;
    }


    private ItemModel bake(int colour) {
        TextureAtlasSprite[] sprites = PipeModelCacheBase.generator.getItemSprites(definition);
        List<MutableQuad> cutout = new ArrayList<>();
        PipeFaceTex center = definition.itemModelCenter;
        PipeFaceTex top = definition.itemModelTop;
        PipeFaceTex bottom = definition.itemModelBottom;
        if (center.equals(top) && center.equals(bottom)) {
            addQuads(QUADS_SAME, sprites, cutout, center);
        } else {
            addQuads(QUADS_TOP, sprites, cutout, top);
            addQuads(QUADS_CENTER, sprites, cutout, center);
            addQuads(QUADS_BOTTOM, sprites, cutout, bottom);
        }

        List<MutableQuad> translucent = new ArrayList<>();
        if (colour > 0) {
            // MutableQuad and native BakedColors both use ARGB. Do not swap red/blue.
            int argb = 0xFF000000 | ColourUtil.getLightHex(DyeColor.byId(colour - 1));
            EnumPipeColourType type = definition.getColourType();
            if (type == EnumPipeColourType.TRANSLUCENT) {
                addColoured(QUADS_COLOUR, translucent, BCTransportSprites.PIPE_COLOUR.getSprite(), argb);
            } else if (type == EnumPipeColourType.BORDER_OUTER) {
                addColoured(QUADS_SAME, cutout, BCTransportSprites.PIPE_COLOUR_BORDER_OUTER.getSprite(), argb);
            } else if (type == EnumPipeColourType.BORDER_INNER) {
                addColoured(QUADS_SAME, cutout, BCTransportSprites.PIPE_COLOUR_BORDER_INNER.getSprite(), argb);
            }
        }

        ItemModel body = NativeItemModelBuilder.layer(cutout, ModelItemSimple.TRANSFORM_BLOCK,
            Sheets.cutoutBlockSheet(), true);
        if (translucent.isEmpty()) {
            return body;
        }
        return new CompositeModel(List.of(body,
            NativeItemModelBuilder.layer(translucent, ModelItemSimple.TRANSFORM_BLOCK,
                Sheets.translucentBlockItemSheet(), true)));
    }

    private static void addQuads(MutableQuad[] templates, TextureAtlasSprite[] sprites,
        List<MutableQuad> target, PipeFaceTex face) {
        for (int layer = 0; layer < face.getCount(); layer++) {
            int index = face.getTexture(layer);
            TextureAtlasSprite sprite = sprites != null && index >= 0 && index < sprites.length
                && sprites[index] != null ? sprites[index] : SpriteUtil.missingSprite();
            addColoured(templates, target, sprite, 0xFF000000 | face.getColour(layer));
        }
    }

    private static void addColoured(MutableQuad[] templates, List<MutableQuad> target,
        TextureAtlasSprite sprite, int colour) {
        if (sprite == null) {
            sprite = SpriteUtil.missingSprite();
        }
        for (MutableQuad template : templates) {
            if (template != null) {
                // Never mutate the templates: instances are reused across definitions and dyes.
                MutableQuad quad = new MutableQuad(template);
                quad.texFromSprite(sprite);
                quad.colouri(colour);
                quad.setTint(-1);
                target.add(quad);
            }
        }
    }
}
