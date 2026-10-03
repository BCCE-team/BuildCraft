/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.model.plug;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.module.BCModules;
import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.world.SingleBlockAccess;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.item.ItemPluggableFacade;
import buildcraft.silicon.plug.FacadeInstance;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.transport.BCTransportModels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import buildcraft.lib.compat.mc2612.client.renderer.block.model.BakedQuad;
import buildcraft.lib.compat.mc2612.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import buildcraft.lib.compat.mc2612.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.compat.RenderCompat;

public enum ModelFacadeItem implements BakedModel {
    INSTANCE;

    private static final LoadingCache<KeyPlugFacade, BakedModel> cache = CacheBuilder.newBuilder()//
        .expireAfterAccess(1, TimeUnit.MINUTES)//
        .build(CacheLoader.from(key -> new ModelItemSimple(bakeForKey(key), ModelItemSimple.TRANSFORM_PLUG_AS_BLOCK, false)));

    public static void onModelBake() {
        cache.invalidateAll();
    }

    private static List<BakedQuad> bakeForKey(KeyPlugFacade key) {
        List<BakedQuad> quads = new ArrayList<>();
        for (MutableQuad quad : PlugBakerFacade.INSTANCE.bakeForKey(key, false)) {
            quads.add(quad.toBakedItem());
        }

        if (BCModules.TRANSPORT.isLoaded() && key.state.isSolidRender() && !key.isHollow) {
            for (MutableQuad quad : BCTransportModels.BLOCKER.getCutoutQuads()) {
                quads.add(quad.toBakedItem());
            }
        }
        return quads;
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
        return ModelItemSimple.TRANSFORM_PLUG_AS_BLOCK;
    }

    public ItemOverrides getOverrides() {
        return FacadeOverride.FACADE_OVERRIDE;
    }

    public static class FacadeOverride extends ItemOverrides {
        public static final FacadeOverride FACADE_OVERRIDE = new FacadeOverride();

        private FacadeOverride() {
            super();
        }

        public BakedModel resolve(BakedModel originalModel, ItemStack stack, ClientLevel world,
            LivingEntity entity, int p_173469_) {
            FacadeInstance inst = ItemPluggableFacade.getStates(stack);
            FacadePhasedState state = inst.getCurrentStateForStack();
            return cache.getUnchecked(
                new KeyPlugFacade(RenderCompat.translucent(), Direction.WEST, state.stateInfo.state, inst.isHollow));
        }
    }
}
