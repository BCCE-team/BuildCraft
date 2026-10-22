/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.client.model.ModelItemSimple;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.misc.SpriteUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.item.ItemPluggableGate;
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

public enum ModelGateItem implements BakedModel {
    INSTANCE;

    private static final Map<GateVariant, List<BakedQuad>> cached = new HashMap<>();

    public static void onModelBake() {
        cached.clear();
    }

    private static List<BakedQuad> getQuads(GateVariant variant) {
        if (!cached.containsKey(variant)) {
            List<BakedQuad> list = new ArrayList<>();
            MutableQuad[] quads = BCSiliconModels.getGateStaticQuads(Direction.WEST, variant);
            for (MutableQuad q : quads) {
                list.add(q.toBakedItem());
            }
            for (MutableQuad q : BCSiliconModels.GATE_DYNAMIC.getCutoutQuads()) {
                list.add(q.toBakedItem());
            }

            cached.put(variant, list);
        }
        return cached.get(variant);
    }

    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
        return ImmutableList.of();
    }

    public boolean useAmbientOcclusion() {
        return false;
    }
    
    public boolean usesBlockLight() {
    	return true;
    }

    public boolean isGui3d() {
        return false;
    }

    public boolean isCustomRenderer() {
        return false;
    }

    public TextureAtlasSprite getParticleIcon() {
        return SpriteUtil.missingSprite();
    }

    public ItemTransforms getTransforms() {
        return ModelItemSimple.TRANSFORM_PLUG_AS_ITEM_BIGGER;
    }

    public ItemOverrides getOverrides() {
        return GateOverride.GATE_OVERRIDE;
    }

    public static final class GateOverride extends ItemOverrides {
        public static final GateOverride GATE_OVERRIDE = new GateOverride();

        private GateOverride() {
            super();
        }

        public BakedModel resolve(BakedModel originalModel, ItemStack stack, ClientLevel world, LivingEntity entity, int p) {
            GateVariant variant = ItemPluggableGate.getVariant(StackUtil.asNonNull(stack));
            return new ModelItemSimple(getQuads(variant), ModelItemSimple.TRANSFORM_PLUG_AS_ITEM_BIGGER, false);
        }
    }
}
