//? source if >=26.2
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.render.compat;

import java.util.List;
import java.util.Objects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.Layer;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.RecordingBuffers;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.Vertex;

/** Extraction-scoped geometry. No tile or world is retained by submitted draw callbacks. */
public final class BCWorldGeometry {
    private static final ThreadLocal<RecordingBuffers> ACTIVE = new ThreadLocal<>();

    private BCWorldGeometry() {}

    public static Scope bind(RecordingBuffers buffers) {
        return new Scope(Objects.requireNonNull(buffers));
    }

    public static List<Layer> capture(Runnable extraction) {
        RecordingBuffers buffers = new RecordingBuffers();
        try (Scope ignored = bind(buffers)) {
            extraction.run();
            return buffers.finish();
        }
    }

    public static VertexConsumer buffer(RenderType layer) {
        return active().getBuffer(layer);
    }

    public static void flush() {
        active().endVertices();
    }

    private static RecordingBuffers active() {
        RecordingBuffers result = ACTIVE.get();
        if (result == null) {
            throw new IllegalStateException("BuildCraft geometry must be recorded during render-state extraction");
        }
        return result;
    }

    public static void submit(List<Layer> layers, PoseStack pose, SubmitNodeCollector collector) {
        for (Layer layer : layers) {
            collector.submitCustomGeometry(pose, layer.type(), (transform, target) -> {
                for (Vertex vertex : layer.vertices()) vertex.emit(transform, target);
            });
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final RecordingBuffers previous = ACTIVE.get();
        private final RecordingBuffers current;
        private boolean closed;

        private Scope(RecordingBuffers current) {
            this.current = current;
            ACTIVE.set(current);
        }

        @Override
        public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner || ACTIVE.get() != current) {
                throw new IllegalStateException("Geometry scopes must close on their owning thread in nesting order");
            }
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            closed = true;
        }
    }
}
