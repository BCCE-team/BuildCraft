/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL-2.0. */
package buildcraft.builders.client.render.pip;

import java.util.Objects;

/**
 * Controls how often the offscreen preview texture is redrawn. An interval of
 * zero means uncapped; it never reuses a previous frame. Geometry/model caches
 * remain independent of this budget, and the vanilla PiP renderer owns the GPU
 * texture. A changed snapshot, viewport, world or baked-model set forces a draw.
 */
final class PreviewFrameBudget {
    private final long intervalNanos;
    private Object lastState;
    private Object lastWorld;
    private Object lastModels;
    private long lastFrameNanos;
    private boolean valid;

    PreviewFrameBudget(long intervalNanos) {
        if (intervalNanos < 0) throw new IllegalArgumentException("intervalNanos");
        this.intervalNanos = intervalNanos;
    }

    static PreviewFrameBudget forMaxFps(int fps) {
        if (fps < 0) throw new IllegalArgumentException("fps");
        return new PreviewFrameBudget(fps == 0 ? 0L : Math.max(1L, 1_000_000_000L / fps));
    }

    boolean canReuse(Object state, Object world, Object models, long now) {
        return intervalNanos > 0 && valid && Objects.equals(lastState, state)
            && lastWorld == world && lastModels == models
            && now >= lastFrameNanos
            && now - lastFrameNanos < intervalNanos;
    }

    void markRendered(Object state, Object world, Object models, long now) {
        lastState = Objects.requireNonNull(state);
        lastWorld = world;
        lastModels = models;
        lastFrameNanos = now;
        valid = true;
    }
}
