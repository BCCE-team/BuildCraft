/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.model;

import java.util.List;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.transport.internal.pluggable.IPluggableStaticBaker;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.internal.pluggable.PluggableModelKey;

import buildcraft.lib.client.model.IModelCache;
import buildcraft.lib.client.model.ModelCache;
import buildcraft.lib.client.model.ModelCacheMultipleSame;

import buildcraft.transport.client.PipeRegistryClient;

public class PipeModelCachePluggable {
    public static final IModelCache<PluggableKey> cacheCutoutAll, cacheTranslucentAll;
    public static final ModelCache<PluggableModelKey> cacheCutoutSingle, cacheTranslucentSingle;

    static {
        cacheCutoutSingle = new ModelCache<>(PipeModelCachePluggable::generate);
        cacheCutoutAll = new ModelCacheMultipleSame<>(PluggableKey::getKeys, cacheCutoutSingle);

        cacheTranslucentSingle = new ModelCache<>(PipeModelCachePluggable::generate);
        cacheTranslucentAll = new ModelCacheMultipleSame<>(PluggableKey::getKeys, cacheTranslucentSingle);
    }

    /** Clears every cached pluggable quad after a model/resource reload. */
    public static void clear() {
        cacheCutoutAll.clear();
        cacheTranslucentAll.clear();
    }

    private static <K extends PluggableModelKey> List<BakedQuad> generate(K key) {
        if (key == null) {
            return ImmutableList.of();
        }
        IPluggableStaticBaker<K> baker = PipeRegistryClient.getPlugBaker(key);
        if (baker == null) {
        	BCLog.d("PipeModelCachePluggable : empty Baker :"+key.getClass());
            return ImmutableList.of();
        }
        return baker.bake(key);
    }

    public static class PluggableKey {
        private final ImmutableSet<PluggableModelKey> pluggables;
        private final int hash;

        public PluggableKey(RenderType layer, IPipeHolder holder) {
            this(layer, holder, pluggable -> true);
        }

        /**
         * Builds a static-model key for a selected subset of pluggables. Native 1.21.11 terrain rendering uses this
         * to leave glass facades to the dynamic translucent renderer, which is the only path that preserves alpha.
         */
        public PluggableKey(RenderType layer, IPipeHolder holder, Predicate<PipePluggable> include) {
            ImmutableSet.Builder<PluggableModelKey> builder = ImmutableSet.builder();
            for (Direction side : Direction.values()) {
                PipePluggable pluggable = holder.getPluggable(side);
                if (pluggable == PipePluggable.EMPTY) continue;
                if (!include.test(pluggable)) continue;
                PluggableModelKey key = pluggable.getModelRenderKey(layer);
                if (key == null) continue;
                builder.add(key);
            }
            this.pluggables = builder.build();
            this.hash = pluggables.hashCode();
        }

        public ImmutableSet<PluggableModelKey> getKeys() {
            return pluggables;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            PluggableKey other = (PluggableKey) obj;
            if (!pluggables.equals(other.pluggables)) return false;
            return true;
        }
    }
}
