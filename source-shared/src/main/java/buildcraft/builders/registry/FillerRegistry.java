/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.registry;

import buildcraft.builders.api2.FillerApi2Bridge;

import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

import javax.annotation.Nullable;

import buildcraft.builders.internal.filler.legacy.IFilledTemplate;
import buildcraft.builders.internal.filler.legacy.IFillerPattern;
import buildcraft.builders.internal.filler.legacy.IFillerRegistry;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Template;
import net.minecraft.core.BlockPos;

public enum FillerRegistry implements IFillerRegistry {
    INSTANCE;

    private final Map<String, IFillerPattern> patterns = new LinkedHashMap<>();

    @Override
    public synchronized void addPattern(IFillerPattern pattern) {
        Objects.requireNonNull(pattern, "pattern");
        String id = Objects.requireNonNull(pattern.getUniqueTag(), "pattern unique tag");
        // Duplicate statement tags use last-write-wins semantics for compatibility
        // with IFillerRegistry consumers. Registered patterns are also mirrored
        // into the typed API v2 statements domain.
        patterns.put(id, pattern);
        FillerApi2Bridge.mirrorLegacyPattern(pattern);
    }

    @Override
    @Nullable
    public IFillerPattern getPattern(String name) {
        IFillerPattern pattern = patterns.get(name);
        return pattern != null ? pattern : FillerApi2Bridge.nativePattern(name);
    }

    @Override
    public synchronized Collection<IFillerPattern> getPatterns() {
        List<IFillerPattern> all = new java.util.ArrayList<>(patterns.values());
        all.addAll(FillerApi2Bridge.nativePatterns());
        return List.copyOf(all);
    }

    @Override
    public IFilledTemplate createFilledTemplate(BlockPos pos, BlockPos size) {
        Template template = new Template();
        template.size = size;
        template.offset = pos;
        template.data = new BitSet(Snapshot.getDataSize(size));
        return template.getFilledTemplate();
    }
}
