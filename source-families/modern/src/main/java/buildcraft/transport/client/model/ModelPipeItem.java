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

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.item.CompositeModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
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
    private final ItemModel baseModel;
    private final ItemModel[] colours = new ItemModel[17];

    public ModelPipeItem(PipeDefinition definition, ItemModel baseModel) {
        this.definition = definition;
        this.baseModel = baseModel;
        this.colours[0] = baseModel;
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

    private ItemModel bake(int colour) {
        if (colour <= 0) {
            return baseModel;
        }

        List<MutableQuad> overlay = new ArrayList<>();
        int argb = 0xFF000000 | ColourUtil.getLightHex(DyeColor.byId(colour - 1));
        EnumPipeColourType type = definition.getColourType();
        if (type == EnumPipeColourType.TRANSLUCENT) {
            addColoured(QUADS_COLOUR, overlay, BCTransportSprites.PIPE_COLOUR.getSprite(), argb);
        } else if (type == EnumPipeColourType.BORDER_OUTER) {
            addColoured(QUADS_SAME, overlay, BCTransportSprites.PIPE_COLOUR_BORDER_OUTER.getSprite(), argb);
        } else if (type == EnumPipeColourType.BORDER_INNER) {
            addColoured(QUADS_SAME, overlay, BCTransportSprites.PIPE_COLOUR_BORDER_INNER.getSprite(), argb);
        }

        if (overlay.isEmpty()) {
            return baseModel;
        }
        RenderType renderType = type == EnumPipeColourType.TRANSLUCENT
            ? Sheets.translucentBlockItemSheet()
            : Sheets.cutoutBlockSheet();
        return new CompositeModel(List.of(baseModel,
            NativeItemModelBuilder.layer(overlay, ModelItemSimple.TRANSFORM_BLOCK, renderType, true)));
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
