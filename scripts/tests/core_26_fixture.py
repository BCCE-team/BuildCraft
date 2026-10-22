"""Execute the real 26.2/26.3 core render bridge against typed minimal event doubles."""
from __future__ import annotations

from pathlib import Path
import subprocess
import tempfile


STUBS = {
    'com/mojang/blaze3d/vertex/PoseStack': 'package com.mojang.blaze3d.vertex; public class PoseStack {}',
    'org/joml/Matrix4f': 'package org.joml; public class Matrix4f {}',
    'net/minecraft/client/multiplayer/ClientLevel': 'package net.minecraft.client.multiplayer; public class ClientLevel {}',
    'net/minecraft/world/entity/player/Player': 'package net.minecraft.world.entity.player; public class Player {}',
    'net/minecraft/client/Minecraft': '''package net.minecraft.client;
        public final class Minecraft {
            private static final Minecraft INSTANCE = new Minecraft();
            public net.minecraft.client.multiplayer.ClientLevel level;
            public net.minecraft.world.entity.player.Player player;
            public static Minecraft getInstance() { return INSTANCE; }
        }''',
    'net/minecraft/resources/Identifier': '''package net.minecraft.resources;
        public record Identifier(String namespace, String path) {
            public static Identifier fromNamespaceAndPath(String n, String p) { return new Identifier(n, p); }
        }''',
    'net/minecraft/util/context/ContextKey': '''package net.minecraft.util.context;
        public record ContextKey<T>(net.minecraft.resources.Identifier id) {}''',
    'net/minecraft/client/renderer/SubmitNodeCollector': '''package net.minecraft.client.renderer;
        public interface SubmitNodeCollector {}''',
    'net/minecraft/client/renderer/state/level/LevelRenderState': '''package net.minecraft.client.renderer.state.level;
        import java.util.*;
        import net.minecraft.util.context.ContextKey;
        public final class LevelRenderState {
            private final Map<ContextKey<?>,Object> data = new HashMap<>();
            public <T> void setRenderData(ContextKey<T> key, T value) { data.put(key, value); }
            @SuppressWarnings("unchecked")
            public <T> T getRenderData(ContextKey<T> key) { return (T)data.get(key); }
        }''',
    'net/neoforged/neoforge/client/event/ExtractLevelRenderStateEvent': '''package net.neoforged.neoforge.client.event;
        public record ExtractLevelRenderStateEvent(net.minecraft.client.renderer.state.level.LevelRenderState state) {
            public net.minecraft.client.renderer.state.level.LevelRenderState getRenderState() { return state; }
            public DeltaTracker getDeltaTracker() { return new DeltaTracker(); }
            public record DeltaTracker() { public float getGameTimeDeltaPartialTick(boolean ignored) { return 0.5f; } }
        }''',
    'net/neoforged/neoforge/client/event/SubmitCustomGeometryEvent': '''package net.neoforged.neoforge.client.event;
        public record SubmitCustomGeometryEvent(
            net.minecraft.client.renderer.state.level.LevelRenderState state,
            net.minecraft.client.renderer.SubmitNodeCollector collector) {
            public net.minecraft.client.renderer.state.level.LevelRenderState getLevelRenderState() { return state; }
            public net.minecraft.client.renderer.SubmitNodeCollector getSubmitNodeCollector() { return collector; }
        }''',
    'buildcraft/lib/client/render/compat/CapturedBlockEntityRenderer': '''package buildcraft.lib.client.render.compat;
        public interface CapturedBlockEntityRenderer { record Layer(int marker) {} }''',
    'buildcraft/lib/client/render/compat/BCWorldGeometry': '''package buildcraft.lib.client.render.compat;
        import java.util.*;
        import com.mojang.blaze3d.vertex.PoseStack;
        import net.minecraft.client.renderer.SubmitNodeCollector;
        public final class BCWorldGeometry {
            public static int captures, submits;
            private static boolean capturing;
            public static List<CapturedBlockEntityRenderer.Layer> last;
            public static void assertCapturing() { if (!capturing) throw new AssertionError("not in extraction"); }
            public static List<CapturedBlockEntityRenderer.Layer> capture(Runnable runnable) {
                if (capturing) throw new AssertionError("nested capture");
                capturing = true;
                try { runnable.run(); ++captures; return List.of(new CapturedBlockEntityRenderer.Layer(1)); }
                finally { capturing = false; }
            }
            public static void submit(List<CapturedBlockEntityRenderer.Layer> layers, PoseStack pose, SubmitNodeCollector collector) {
                if (capturing || layers.size() != 1 || layers.get(0).marker() != 1 || pose == null || collector == null)
                    throw new AssertionError("broken deferred submit");
                last = layers; ++submits;
            }
        }''',
    'buildcraft/lib/client/render/DetachedRenderer': '''package buildcraft.lib.client.render;
        public final class DetachedRenderer {
            public static final DetachedRenderer INSTANCE = new DetachedRenderer();
            public static int renders;
            public void renderWorldLastEvent(com.mojang.blaze3d.vertex.PoseStack pose, org.joml.Matrix4f matrix,
                    net.minecraft.world.entity.player.Player player, float ticks) {
                buildcraft.lib.client.render.compat.BCWorldGeometry.assertCapturing();
                if (ticks != 0.5f) throw new AssertionError("partial tick lost");
                renders++;
            }
        }''',
    'buildcraft/lib/client/render/laser/LaserRenderer_BC8': '''package buildcraft.lib.client.render.laser;
        public final class LaserRenderer_BC8 {
            public static int prepares, flushes;
            public static void setupLaserRenderState() {
                buildcraft.lib.client.render.compat.BCWorldGeometry.assertCapturing(); prepares++;
            }
            public static void flushStaticLasers() {
                buildcraft.lib.client.render.compat.BCWorldGeometry.assertCapturing(); flushes++;
            }
        }''',
    'buildcraft/core/client/RenderTickListener': '''package buildcraft.core.client;
        public final class RenderTickListener {
            public static int captures;
            public static void captureWorld(com.mojang.blaze3d.vertex.PoseStack pose, org.joml.Matrix4f matrix,
                    float partialTicks) {
                buildcraft.lib.client.render.compat.BCWorldGeometry.assertCapturing(); captures++;
            }
        }''',
    'buildcraft/core/client/MarkerSubmitRenderer121111': '''package buildcraft.core.client;
        public final class MarkerSubmitRenderer121111 {
            public static int captures;
            public static void capture(net.minecraft.client.multiplayer.ClientLevel level,
                    net.minecraft.world.entity.player.Player player,
                    com.mojang.blaze3d.vertex.PoseStack pose, org.joml.Matrix4f matrix) {
                buildcraft.lib.client.render.compat.BCWorldGeometry.assertCapturing(); captures++;
            }
        }''',
    'Harness': '''import net.minecraft.client.Minecraft;
        import net.minecraft.client.multiplayer.ClientLevel;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.client.renderer.state.level.LevelRenderState;
        import net.neoforged.neoforge.client.event.*;
        import buildcraft.core.client.*;
        import buildcraft.lib.client.render.compat.BCWorldGeometry;
        import buildcraft.lib.client.render.DetachedRenderer;
        import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
        public class Harness {
            public static void main(String[] args) {
                var mc = Minecraft.getInstance();
                mc.level = new ClientLevel(); mc.player = new Player();
                var frame = new LevelRenderState();
                CoreWorldGeometry.extract(new ExtractLevelRenderStateEvent(frame));
                if (BCWorldGeometry.captures != 1 || RenderTickListener.captures != 1 ||
                    MarkerSubmitRenderer121111.captures != 1 || DetachedRenderer.renders != 1 ||
                    LaserRenderer_BC8.prepares != 1 || LaserRenderer_BC8.flushes != 1)
                    throw new AssertionError("extraction must run exactly once");
                CoreWorldGeometry.submit(new SubmitCustomGeometryEvent(frame, new net.minecraft.client.renderer.SubmitNodeCollector() {}));
                if (BCWorldGeometry.submits != 1 || BCWorldGeometry.last == null)
                    throw new AssertionError("deferred submit missing");
                mc.level = null;
                var noLevel = new LevelRenderState();
                CoreWorldGeometry.extract(new ExtractLevelRenderStateEvent(noLevel));
                CoreWorldGeometry.submit(new SubmitCustomGeometryEvent(noLevel, new net.minecraft.client.renderer.SubmitNodeCollector() {}));
                if (BCWorldGeometry.captures != 1 || BCWorldGeometry.submits != 1)
                    throw new AssertionError("unloaded world submitted geometry");
                System.out.println("Core geometry lifecycle: 8 assertions PASS");
            }
        }''',
}


def execute(actual_core_class: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-core-geometry-') as tmp:
        root = Path(tmp)
        sources = []
        for name, text in STUBS.items():
            dest = root / 'src' / (name + '.java')
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(text, encoding='utf-8')
            sources.append(dest)
        sources.append(actual_core_class)
        out = root / 'classes'
        built = subprocess.run(['javac', '--release', '21', '-d', str(out), *map(str, sources)],
                               capture_output=True, text=True, timeout=40)
        if built.returncode:
            raise AssertionError('Core geometry compile failed: ' + built.stdout + built.stderr)
        ran = subprocess.run(['java', '-cp', str(out), 'Harness'],
                             capture_output=True, text=True, timeout=20)
        if ran.returncode:
            raise AssertionError('Core geometry runtime probe failed: ' + ran.stdout + ran.stderr)
        return ran.stdout.strip()
