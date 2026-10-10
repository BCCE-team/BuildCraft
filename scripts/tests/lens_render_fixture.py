"""Execute the real per-target PlugBakerLens against a small Minecraft-shaped Java model stub.

Catches invisible native cutout frames, near-invisible translucent panes and regressions
on the pre-1.21.11 rendering path. Does not require a Minecraft client/JDK 25.
"""
from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

STUBS = {
    'net/minecraft/client/renderer/RenderType.java': '''
package net.minecraft.client.renderer;
public final class RenderType {
    private final String id;
    private RenderType(String id) { this.id = id; }
    private static final RenderType CUTOUT = new RenderType("cutout");
    private static final RenderType TRANSLUCENT = new RenderType("translucent");
    public static RenderType cutout() { return CUTOUT; }
    public static RenderType translucent() { return TRANSLUCENT; }
    @Override public String toString() { return id; }
}
''',
    'net/minecraft/client/renderer/block/model/BakedQuad.java': '''
package net.minecraft.client.renderer.block.model;
public class BakedQuad {
    public final int[] alphas;
    public BakedQuad(int... values) { this.alphas = values; }
}
''',
    'net/minecraft/core/Direction.java': '''
package net.minecraft.core;
public enum Direction { WEST }
''',
    'net/minecraft/world/item/DyeColor.java': '''
package net.minecraft.world.item;
public enum DyeColor { RED }
''',
    'buildcraft/transport/internal/pluggable/IPluggableStaticBaker.java': '''
package buildcraft.transport.internal.pluggable;
import java.util.List;
import net.minecraft.client.renderer.block.model.BakedQuad;
public interface IPluggableStaticBaker<T> { List<BakedQuad> bake(T key); }
''',
    'buildcraft/silicon/client/model/key/KeyPlugLens.java': '''
package buildcraft.silicon.client.model.key;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
public final class KeyPlugLens {
    public final RenderType layer;
    public final boolean isFilter;
    public final Direction side;
    public final DyeColor colour;
    public KeyPlugLens(RenderType layer, boolean isFilter) {
        this.layer = layer; this.isFilter = isFilter;
        this.side = Direction.WEST; this.colour = DyeColor.RED;
    }
}
''',
    'buildcraft/lib/client/model/MutableQuad.java': '''
package buildcraft.lib.client.model;
import net.minecraft.client.renderer.block.model.BakedQuad;
public class MutableQuad {
    public static class Vertex { public short colour_a = 255; }
    public final Vertex vertex_0 = new Vertex();
    public final Vertex vertex_1 = new Vertex();
    public final Vertex vertex_2 = new Vertex();
    public final Vertex vertex_3 = new Vertex();
    public MutableQuad() {}
    public MutableQuad(int alpha) {
        vertex_0.colour_a = vertex_1.colour_a = vertex_2.colour_a = vertex_3.colour_a = (short) alpha;
    }
    public void copyFrom(MutableQuad other) {
        vertex_0.colour_a = other.vertex_0.colour_a;
        vertex_1.colour_a = other.vertex_1.colour_a;
        vertex_2.colour_a = other.vertex_2.colour_a;
        vertex_3.colour_a = other.vertex_3.colour_a;
    }
    // Real MutableVertex.multShade() multiplies RGBA; simulate a shaded side.
    public void multShade() {
        vertex_0.colour_a /= 2; vertex_1.colour_a /= 2;
        vertex_2.colour_a /= 2; vertex_3.colour_a /= 2;
    }
    public BakedQuad toBakedBlock() {
        return new BakedQuad(vertex_0.colour_a, vertex_1.colour_a, vertex_2.colour_a, vertex_3.colour_a);
    }
}
''',
    'buildcraft/silicon/BCSiliconModels.java': '''
package buildcraft.silicon;
import buildcraft.lib.client.model.MutableQuad;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
public final class BCSiliconModels {
    public static final MutableQuad lensFrame = new MutableQuad(255);
    public static final MutableQuad lensGlass = new MutableQuad(180);
    public static final MutableQuad filterFrame = new MutableQuad(255);
    public static final MutableQuad filterGlass = new MutableQuad(180);
    public static MutableQuad[] getLensCutoutQuads(Direction d, DyeColor c) { return new MutableQuad[]{lensFrame}; }
    public static MutableQuad[] getLensTranslucentQuads(Direction d, DyeColor c) { return new MutableQuad[]{lensGlass}; }
    public static MutableQuad[] getFilterCutoutQuads(Direction d, DyeColor c) { return new MutableQuad[]{filterFrame}; }
    public static MutableQuad[] getFilterTranslucentQuads(Direction d, DyeColor c) { return new MutableQuad[]{filterGlass}; }
}
''',
    'buildcraft/lib/compat/RenderCompat.java': '''
package buildcraft.lib.compat;
import net.minecraft.client.renderer.RenderType;
public final class RenderCompat {
    public static RenderType cutout() { return RenderType.cutout(); }
    public static RenderType translucent() { return RenderType.translucent(); }
}
''',
    'ValidateLensRender.java': '''
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.client.model.plug.PlugBakerLens;
import buildcraft.silicon.client.model.key.KeyPlugLens;
import buildcraft.lib.compat.RenderCompat;
import net.minecraft.client.renderer.block.model.BakedQuad;
public final class ValidateLensRender {
    static int assertions = 0;
    static void check(boolean b, String message) { assertions++; if (!b) throw new AssertionError(message); }
    public static void main(String[] args) {
        boolean modern = Boolean.parseBoolean(args[0]);
        for (boolean filter : new boolean[]{false, true}) {
            for (boolean cutout : new boolean[]{true, false}) {
                MutableQuad input = filter
                    ? (cutout ? BCSiliconModels.filterFrame : BCSiliconModels.filterGlass)
                    : (cutout ? BCSiliconModels.lensFrame : BCSiliconModels.lensGlass);
                int expectedInput = cutout ? 255 : 180;
                var key = new KeyPlugLens(cutout ? RenderCompat.cutout() : RenderCompat.translucent(), filter);
                PlugBakerLens.onModelBake();
                var quads = PlugBakerLens.INSTANCE.bake(key);
                check(quads.size() == 1, "must bake one quad: " + key.layer);
                BakedQuad baked = quads.get(0);
                int expectedBaked = modern ? expectedInput : 32;
                int expectedSource = modern ? expectedInput : 64;
                for (int i = 0; i < 4; i++) {
                    check(baked.alphas[i] == expectedBaked,
                        "frame/glass alpha " + baked.alphas[i] + " vs " + expectedBaked + ", filter=" + filter + ", layer=" + key.layer);
                }
                check(input.vertex_0.colour_a == expectedSource, "shared model quad changed unexpectedly");
                check(PlugBakerLens.INSTANCE.bake(key) == quads, "cache should remain stable");
            }
        }
        System.out.println("lens layer alpha: " + assertions + " Java assertions PASS");
    }
}
''',
}


def execute_target(target: str, source: Path, *, modern: bool) -> str:
    if shutil.which('javac') is None or shutil.which('java') is None:
        raise RuntimeError('Java JDK required for this regression fixture')
    with tempfile.TemporaryDirectory(prefix='bcce-lens-render-') as tmp:
        root = Path(tmp)
        own = root / 'buildcraft/silicon/client/model/plug/PlugBakerLens.java'
        own.parent.mkdir(parents=True, exist_ok=True)
        own.write_bytes(source.read_bytes())
        to_compile = [own]
        stubs = dict(STUBS)
        if modern:
            version = 'mc121111' if target == '1.21.11-neoforge' else 'mc2612'
            native_quad = f'buildcraft.lib.compat.{version}.client.renderer.block.model.BakedQuad'
            native_path = native_quad.replace('.', '/') + '.java'
            stubs[native_path] = f'''
package buildcraft.lib.compat.{version}.client.renderer.block.model;
public class BakedQuad extends net.minecraft.client.renderer.block.model.BakedQuad {{
    public BakedQuad(int... values) {{ super(values); }}
}}
'''
            stubs['net/minecraft/client/renderer/rendertype/RenderType.java'] = stubs.pop(
                'net/minecraft/client/renderer/RenderType.java'
            ).replace('package net.minecraft.client.renderer;', 'package net.minecraft.client.renderer.rendertype;')
            stubs['net/minecraft/client/renderer/rendertype/RenderTypes.java'] = '''
package net.minecraft.client.renderer.rendertype;
public final class RenderTypes {
    public static RenderType cutoutMovingBlock() { return RenderType.cutout(); }
    public static RenderType translucentMovingBlock() { return RenderType.translucent(); }
}
'''
            stubs['buildcraft/lib/compat/RenderCompat.java'] = '''
package buildcraft.lib.compat;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
public final class RenderCompat {
    public static RenderType cutout() { return RenderTypes.cutoutMovingBlock(); }
    public static RenderType translucent() { return RenderTypes.translucentMovingBlock(); }
}
'''
            for key in ('buildcraft/silicon/client/model/key/KeyPlugLens.java',):
                stubs[key] = stubs[key].replace(
                    'import net.minecraft.client.renderer.RenderType;',
                    'import net.minecraft.client.renderer.rendertype.RenderType;'
                )
            for key in ('buildcraft/lib/client/model/MutableQuad.java',
                        'buildcraft/transport/internal/pluggable/IPluggableStaticBaker.java'):
                stubs[key] = stubs[key].replace(
                    'import net.minecraft.client.renderer.block.model.BakedQuad;',
                    f'import {native_quad};'
                )
        for path, content in stubs.items():
            dest = root / path
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(content, encoding='utf-8')
            to_compile.append(dest)
        classes = root / 'classes'
        compile_cmd = ['javac', '-encoding', 'UTF-8', '-d', str(classes)] + [str(p) for p in to_compile]
        compiled = subprocess.run(compile_cmd, text=True, capture_output=True, timeout=40)
        if compiled.returncode:
            raise AssertionError(f'{target}: javac failed:\n{compiled.stderr}')
        run = subprocess.run(['java', '-cp', str(classes), 'ValidateLensRender', str(modern).lower()],
                             text=True, capture_output=True, timeout=25)
        if run.returncode or 'assertions PASS' not in run.stdout:
            raise AssertionError(f'{target}: Java probe failed:\n{run.stdout}\n{run.stderr}')
        return f'{target}: {run.stdout.strip()}'
