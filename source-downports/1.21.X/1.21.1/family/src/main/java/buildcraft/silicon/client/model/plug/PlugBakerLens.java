/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.model.plug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.client.model.key.KeyPlugLens;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;

/**
 * 1.21.1-only lens baker. Opaque lens/filter framing belongs in terrain cutout;
 * only the translucent glass layer retains the historical reduced vertex alpha.
 * Never change the shared source quads when baking a layer: models are cached and
 * reused across lens colours, filters, and block-model reloads.
 */
public enum PlugBakerLens implements IPluggableStaticBaker<KeyPlugLens> {
    INSTANCE;

    private static final Map<KeyPlugLens, List<BakedQuad>> cached = new HashMap<>();

    private static final IQuadGetter lensCutout = BCSiliconModels::getLensCutoutQuads;
    private static final IQuadGetter lensTranslucent = BCSiliconModels::getLensTranslucentQuads;
    private static final IQuadGetter filterCutout = BCSiliconModels::getFilterCutoutQuads;
    private static final IQuadGetter filterTranslucent = BCSiliconModels::getFilterTranslucentQuads;

    public static void onModelBake() {
        cached.clear();
    }

    private static IQuadGetter getGetter(KeyPlugLens key) {
        if (key.layer == RenderType.cutout())
            return key.isFilter ? filterCutout : lensCutout;
        if (key.layer == RenderType.translucent())
            return key.isFilter ? filterTranslucent : lensTranslucent;
        throw new IllegalArgumentException("Unknown layer " + key.layer);
    }

    @Override
    public List<BakedQuad> bake(KeyPlugLens key) {
        List<BakedQuad> existing = cached.get(key);
        if (existing != null) return existing;

        MutableQuad[] source = getGetter(key).get(key.side, key.colour);
        List<BakedQuad> result = new ArrayList<>(source.length);
        boolean glass = key.layer == RenderType.translucent();
        for (MutableQuad quad : source) {
            // The original shared implementation modified the SOURCE quad and
            // forced alpha=64 in both cutout and translucent passes. Under Iris
            // a partly transparent cutout frame may flicker or look ghost-like.
            MutableQuad baked = new MutableQuad(quad);
            short alpha = (short) (glass ? 64 : 255);
            for (var vertex : baked.vertexs) vertex.colour_a = alpha;
            baked.multShade();
            result.add(baked.toBakedBlock());
        }
        List<BakedQuad> immutable = List.copyOf(result);
        cached.put(key, immutable);
        return immutable;
    }

    interface IQuadGetter {
        MutableQuad[] get(Direction side, DyeColor colour);
    }
}
