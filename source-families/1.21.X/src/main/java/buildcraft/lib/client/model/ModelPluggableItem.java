//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import java.util.List;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.compat.mc121111.client.renderer.block.model.BakedQuad;
import buildcraft.lib.compat.mc121111.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc121111.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

public class ModelPluggableItem implements BakedModel {

    private volatile List<BakedQuad> quads;
    private final Supplier<MutableQuad[]>[] quadSuppliers;

    public ModelPluggableItem(MutableQuad[]... quads) {
        this.quads = bake(quads);
        this.quadSuppliers = null;
    }

    @SafeVarargs
    public ModelPluggableItem(Supplier<MutableQuad[]>... quadSuppliers) {
        this.quadSuppliers = quadSuppliers;
    }

    private List<BakedQuad> getBakedQuads() {
        List<BakedQuad> current = quads;
        if (current == null) {
            synchronized (this) {
                current = quads;
                if (current == null) {
                    MutableQuad[][] supplied = new MutableQuad[quadSuppliers.length][];
                    for (int i = 0; i < quadSuppliers.length; i++) {
                        supplied[i] = quadSuppliers[i].get();
                    }
                    current = bake(supplied);
                    quads = current;
                }
            }
        }
        return current;
    }

    private static List<BakedQuad> bake(MutableQuad[][] quads) {
        ImmutableList.Builder<BakedQuad> list = ImmutableList.builder();
        for (MutableQuad[] qa : quads) {
            for (MutableQuad q : qa) {
                MutableQuad itemQuad = new MutableQuad(q);
                itemQuad.setShade(false);
                itemQuad.colouri(0xFFFFFFFF);
                itemQuad.lighti(15, 15);
                list.add(itemQuad.toBakedItem());
            }
        }
        return list.build();
    }
    
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
        return side == null ? getBakedQuads() : ImmutableList.of();
    }

    public boolean useAmbientOcclusion() {
        return false;
    }

    public boolean isGui3d() {
        return false;
    }

    public boolean isCustomRenderer() {
        return false;
    }

    public TextureAtlasSprite getParticleIcon() {
        List<BakedQuad> baked = getBakedQuads();
        return baked.isEmpty() ? SpriteUtil.missingSprite() : baked.get(0).sprite();
    }

    public ItemTransforms getTransforms() {
        return ModelItemSimple.TRANSFORM_PLUG_AS_ITEM;
    }

    public ItemOverrides getOverrides() {
        return ItemOverrides.EMPTY;
    }

	public boolean usesBlockLight() {
		return false;
	}


}
