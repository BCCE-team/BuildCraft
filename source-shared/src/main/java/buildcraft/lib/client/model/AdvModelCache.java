/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.expression.info.ContextInfo;
import buildcraft.lib.expression.info.VariableInfo;

public class AdvModelCache {
    private static final int MODEL_INDEX_INCORRECT = -1;
    private static final int MODEL_INDEX_NO_CACHE = -2;

    public final ModelHolderVariable model;
    public final ContextInfo modelCtxInfo;

    final List<VariableInfo<?>> variables = new ArrayList<>();
    private @Nullable CacheBase cache = null;

    public AdvModelCache(ModelHolderVariable model, ContextInfo modelCtxInfo) {
        this.model = model;
        this.modelCtxInfo = modelCtxInfo;
    }

    // Public API

    /** Clears the current cache. Note: this DOES NOT reset the variable info data! Call {@link #reset()} instead. */
    public void clear() {
        CacheBase base = cache;
        if (base != null) {
            base.clear();
        }
    }

    public void reset() {
        clear();
        variables.clear();
        cache = null;
    }

    /** @return The quads for the *current* variables as set in the {@link #modelCtxInfo}. */
    public MutableQuad[] getCutoutQuads() {
        return getCurrentValue().cutout;
    }

    /** @return The quads for the *current* variables as set in the {@link #modelCtxInfo}. */
    public MutableQuad[] getTranslucentQuads() {
        return getCurrentValue().translucent;
    }

    // Internal methods

    CacheValue computeFullModel() {
        return new CacheValue(model.getCutoutQuads(), model.getTranslucentQuads());
    }

    CacheValue getCurrentValue() {
        CacheBase c = cache;
        if (c == null) {
            c = cache = createNewCache();
        }
        return c.getCurrentValue();
    }

    CacheBase createNewCache() {
        variables.clear();
        variables.addAll(modelCtxInfo.variables.values());

        // A dense indexed cache is suitable only when every dimension is complete and the cartesian product remains
        // reasonably small. Incomplete or open-ended variable sets require a fallback cache keyed by actual values.
        int[] multipliers = new int[variables.size()];
        long possible = 1;
        boolean fullyIndexed = true;
        for (int i = 0; i < variables.size(); i++) {
            VariableInfo<?> info = variables.get(i);
            multipliers[i] = (int) possible;
            int values = Math.max(1, info.getPossibleValues().size());
            possible *= values;
            fullyIndexed &= info.setIsComplete && info.cacheType != VariableInfo.CacheType.NEVER;
            if (possible > 4096) fullyIndexed = false;
        }
        if (fullyIndexed && possible <= 4096) {
            return new CacheIndexed(multipliers, (int) possible);
        }

        // Split finite/complete dimensions from open-ended ones. Each finite combination gets a small bounded fallback
        // map keyed only by the remaining variable values. This keeps common booleans/enums cheap while still caching
        // models whose dynamic values cannot be represented by a dense indexed cache.
        return new CacheHybrid();
    }

    private static Object currentValueKey(VariableInfo<?> info) {
        if (info instanceof VariableInfo.VariableInfoBoolean value) return value.node.value;
        if (info instanceof VariableInfo.VariableInfoLong value) return value.node.value;
        if (info instanceof VariableInfo.VariableInfoDouble value) return value.node.value;
        if (info instanceof VariableInfo.VariableInfoObject<?> value) return value.node.value;
        return info.getCurrentOrdinal();
    }

    abstract class CacheBase {
        abstract CacheValue getCurrentValue();

        abstract void clear();
    }

    class CacheHybrid extends CacheBase {
        private static final int MAX_BUCKETS = 256;
        private static final int MAX_VALUES_PER_BUCKET = 128;

        final List<Integer> indexedVariables = new ArrayList<>();
        final List<Integer> dynamicVariables = new ArrayList<>();
        final int[] multipliers;
        final Map<List<Object>, CacheValue>[] buckets;

        @SuppressWarnings("unchecked")
        CacheHybrid() {
            long possible = 1;
            List<Integer> multipliersList = new ArrayList<>();
            for (int i = 0; i < variables.size(); i++) {
                VariableInfo<?> info = variables.get(i);
                int count = Math.max(1, info.getPossibleValues().size());
                if (info.setIsComplete && info.cacheType != VariableInfo.CacheType.NEVER
                    && possible * count <= MAX_BUCKETS) {
                    indexedVariables.add(i);
                    multipliersList.add((int) possible);
                    possible *= count;
                } else {
                    dynamicVariables.add(i);
                }
            }
            multipliers = multipliersList.stream().mapToInt(Integer::intValue).toArray();
            buckets = new Map[(int) Math.max(1, possible)];
        }

        @Override
        CacheValue getCurrentValue() {
            int bucketIndex = 0;
            for (int i = 0; i < indexedVariables.size(); i++) {
                VariableInfo<?> info = variables.get(indexedVariables.get(i));
                if (!info.shouldCacheCurrentValue()) return computeFullModel();
                int ordinal = info.getCurrentOrdinal();
                if (ordinal < 0) return computeFullModel();
                bucketIndex += ordinal * multipliers[i];
            }

            List<Object> key = new ArrayList<>(dynamicVariables.size());
            for (int variableIndex : dynamicVariables) {
                VariableInfo<?> info = variables.get(variableIndex);
                if (!info.shouldCacheCurrentValue()) return computeFullModel();
                key.add(currentValueKey(info));
            }

            Map<List<Object>, CacheValue> bucket = buckets[bucketIndex];
            if (bucket == null) {
                bucket = new LinkedHashMap<List<Object>, CacheValue>(16, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<List<Object>, CacheValue> eldest) {
                        return size() > MAX_VALUES_PER_BUCKET;
                    }
                };
                buckets[bucketIndex] = bucket;
            }
            List<Object> stableKey = List.copyOf(key);
            CacheValue value = bucket.get(stableKey);
            if (value == null) {
                value = computeFullModel();
                bucket.put(stableKey, value);
            }
            return value;
        }

        @Override
        void clear() {
            Arrays.fill(buckets, null);
        }
    }

    class CacheIndexed extends CacheBase {
        final int[] multipliers;
        final CacheValue[] values;

        private CacheIndexed(int[] multipliers, int possible) {
            this.multipliers = multipliers;
            values = new CacheValue[possible];
        }

        @Override
        CacheValue getCurrentValue() {
            int index = computeIndex();
            if (index < 0 || index >= values.length) {
                if (index == MODEL_INDEX_INCORRECT) {
                    // Uh-oh! incorrect creation of this cache!
                    BCLog.logger.warn(
                        "[lib.model.adv_cache] Cache miss for indexed cache - this should be impossible! (index = "
                            + index + ", length = " + values.length + ")");
                    for (VariableInfo<?> var : variables) {
                        BCLog.logger.warn("            - " + var);
                    }
                }
                return computeFullModel();
            }
            CacheValue val = values[index];
            if (val == null) {
                val = computeFullModel();
                values[index] = val;
            }
            return val;
        }

        private int computeIndex() {
            int index = 0;
            for (int i = 0; i < variables.size(); i++) {
                VariableInfo<?> info = variables.get(i);
                if (!info.shouldCacheCurrentValue()) {
                    return MODEL_INDEX_NO_CACHE;
                }
                int ord = info.getCurrentOrdinal();
                if (ord < 0) {
                    return MODEL_INDEX_INCORRECT;
                }
                index += ord * multipliers[i];
            }
            return index;
        }

        @Override
        void clear() {
            Arrays.fill(values, null);
        }
    }

    static class CacheValue {
        final MutableQuad[] cutout, translucent;

        CacheValue(MutableQuad[] cutout, MutableQuad[] translucent) {
            this.cutout = cutout;
            this.translucent = translucent;
        }
    }
}
