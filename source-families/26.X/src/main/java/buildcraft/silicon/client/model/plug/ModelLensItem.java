/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.model.plug;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.item.ItemPluggableLens.LensData;
import net.minecraft.client.multiplayer.ClientLevel;
import buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad;
import buildcraft.lib.compat.mc2612.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc2612.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public enum ModelLensItem implements BakedModel {
    INSTANCE;

    private static final List<List<BakedQuad>> cached = new ArrayList<>(34);

    public static void onModelBake() {
        cached.clear();
    }

    private static List<BakedQuad> getQuads(int damage) {
        if (damage < 0 || damage >= 34) damage = 0;
        if (cached.isEmpty()) {
            for (int i = 0; i < 34; i++) {
                List<BakedQuad> list = new ArrayList<>();
                LensData data = new LensData(i);
                MutableQuad[] cutout, translucent;
                Direction side = Direction.WEST;
                if (data.isFilter) {
                    cutout = BCSiliconModels.getFilterCutoutQuads(side, data.colour);
                    translucent = BCSiliconModels.getFilterTranslucentQuads(side, data.colour);
                } else {
                    cutout = BCSiliconModels.getLensCutoutQuads(side, data.colour);
                    translucent = BCSiliconModels.getLensTranslucentQuads(side, data.colour);
                }
                for (MutableQuad q : cutout) {
                    list.add(q.toBakedItem());
                }
                for (MutableQuad q : translucent) {
                    list.add(q.toBakedItem());
                }
                cached.add(list);
            }
        }
        return cached.get(damage);
    }

    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
        return ImmutableList.of();
    }

    public boolean useAmbientOcclusion() {
        return false;
    }

    public boolean isGui3d() {
        return false;
    }
    
    public boolean usesBlockLight() {
    	return true;
    }

    public boolean isCustomRenderer() {
        return false;
    }

    public TextureAtlasSprite getParticleIcon() {
        return SpriteUtil.missingSprite();
    }

    public ItemTransforms getTransforms() {
        return ModelItemSimple.TRANSFORM_PLUG_AS_ITEM;
    }

    public ItemOverrides getOverrides() {
        return LensOverride.LENS_OVERRIDE;
    }

    public static class LensOverride extends ItemOverrides {
        public static final LensOverride LENS_OVERRIDE = new LensOverride();

        private LensOverride() {
            super();
        }

        public BakedModel resolve(BakedModel originalModel, ItemStack stack, ClientLevel world, LivingEntity entity, int p) {
            return new ModelItemSimple(getQuads(ItemPluggableLens.getData(stack).getItemDamage()), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM, false);
        }
    }
}
