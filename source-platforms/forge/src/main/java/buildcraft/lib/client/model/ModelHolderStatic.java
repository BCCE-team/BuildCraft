/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import buildcraft.lib.platform.client.ClientModelBaking;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.client.model.json.JsonModel;
import buildcraft.lib.client.model.json.JsonModelPart;
import buildcraft.lib.client.model.json.JsonQuad;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonParseException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.client.model.data.ModelData;

/** Holds a model that will never change except if the JSON file it is defined from is changed. */
public class ModelHolderStatic extends ModelHolder {
	private final static RandomSource random = RandomSource.create();
	
    private final ImmutableMap<String, String> textureLookup;
    private final boolean allowTextureFallthrough;
    private MutableQuad[][] quads;
    private JsonModel rawModel;
    private boolean unseen = true;

    public ModelHolderStatic(String location) {
        this(location, ImmutableMap.of(), false);
    }

    public ModelHolderStatic(String location, String[][] textures, boolean allowTextureFallthrough) {
        this(location, genTextureMap(textures), allowTextureFallthrough);
    }

    public ModelHolderStatic(String modelLocation, ImmutableMap<String, String> textureLookup, boolean allowTextureFallthrough) {
        super(modelLocation);
        this.textureLookup = textureLookup;
        this.allowTextureFallthrough = allowTextureFallthrough;
    }

    @Override
    public boolean hasBakedQuads() {
        return quads != null;
    }

    private static ImmutableMap<String, String> genTextureMap(String[][] textures) {
        if (textures == null || textures.length == 0) {
            return ImmutableMap.of();
        }
        ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        for (String[] ar : textures) {
            if (ar.length != 2) {
                throw new IllegalArgumentException("Must have 2 elements (key,value) but got " + Arrays.toString(ar));
            }
            if (!ar[0].startsWith("~")) {
                throw new IllegalArgumentException("Key must start with '~' otherwise it will never be used!");
            }
            builder.put(ar[0], ar[1]);
        }
        return builder.build();
    }

    
    @Override
    protected void onTextureStitch(Set<ResourceLocation> toRegisterSprites) {
    }

    @Override
    protected void onModelBake(ClientModelBaking.Completed event) {
        if (rawModel == null) {
        	BakedModel model = event.getModels().get(modelLocation);
        	if(model == null)
        		quads = null;
        	else {
        		MutableQuad[] cut = model.getQuads(null, null, random, ModelData.EMPTY, RenderType.cutout()).stream().map(MutableQuad::new).toArray(MutableQuad[]::new);
        		MutableQuad[] trans = model.getQuads(null, null, random, ModelData.EMPTY, RenderType.translucent()).stream().map(MutableQuad::new).toArray(MutableQuad[]::new);
        		quads = new MutableQuad[][] { cut, trans };
        	}
        } else {
            MutableQuad[] cut = bakePart(rawModel.cutoutElements);
            MutableQuad[] trans = bakePart(rawModel.translucentElements);
            quads = new MutableQuad[][] { cut, trans };
            rawModel = null;
        }
    }

    private MutableQuad[] bakePart(JsonModelPart[] a) {
        TextureAtlasSprite missingSprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(MissingTextureAtlasSprite.getLocation());
        List<MutableQuad> list = new ArrayList<>();
        for (JsonModelPart part : a) {
            for (JsonQuad quad : part.quads) {
                String lookup = quad.texture;
                int attempts = 0;
                while (lookup.startsWith("#") && rawModel.textures.containsKey(lookup) && attempts < 10) {
                    lookup = rawModel.textures.get(lookup);
                    attempts++;
                }
                if (lookup.startsWith("~") && textureLookup.containsKey(lookup)) {
                    lookup = textureLookup.get(lookup);
                }
                TextureAtlasSprite sprite;
                if (lookup.startsWith("#") || lookup.startsWith("~")) {
                    if (allowTextureFallthrough) {
                        // Let the caller manually replace the sprite (as we don't know what to replace it with)
                        // But only if the model user is aware of this (so its not an error)
                        sprite = null;
                    } else {
                        sprite = missingSprite;
                    }
                } else {
                    sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(new ResourceLocation(lookup));
                }
                list.add(quad.toQuad(sprite));
            }
        }
        return list.toArray(new MutableQuad[list.size()]);
    }

    private MutableQuad[][] getQuadsChecking() {
        if (quads == null) {
            if (unseen) {
                unseen = false;
                String warnText = "[lib.model.holder] Tried to use the model " + modelLocation + " before it was baked!";
                if (ModelHolderRegistry.DEBUG) {
                    BCLog.logger.warn(warnText, new Throwable());
                } else {
                    BCLog.logger.warn(warnText);
                }
            }
            return new MutableQuad[][] { MutableQuad.EMPTY_ARRAY, MutableQuad.EMPTY_ARRAY };
        }
        return quads;
    }

    public MutableQuad[] getCutoutQuads() {
        return getQuadsChecking()[0];
    }

    public MutableQuad[] getTranslucentQuads() {
        return getQuadsChecking()[1];
    }

}
