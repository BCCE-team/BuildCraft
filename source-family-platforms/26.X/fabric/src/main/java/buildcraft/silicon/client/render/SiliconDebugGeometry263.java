/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.silicon.client.render;

import buildcraft.lib.client.render.compat.BCWorldGeometry;

/**
 * Fabric 26.3 uses CoreWorldGeometry's extraction/submit pipeline for detached
 * debug renderers. The NeoForge event-local capture flag maps to the active
 * Fabric geometry recording scope.
 */
public final class SiliconDebugGeometry263 {
    private SiliconDebugGeometry263() {}

    static boolean isCapturing() {
        return BCWorldGeometry.isCapturing();
    }
}
