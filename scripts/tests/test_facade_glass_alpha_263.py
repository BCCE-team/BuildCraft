#!/usr/bin/env python3
"""26.3-only glass-facade opacity regression (materialized Java + runnable branch)."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

LOGICAL = 'src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java'
CORRECTED_ALPHA = 'quad.multColourd(1.0, 1.0, 1.0, 0.65);'
WORLD_FACADE_CHECK = 'if (applyGlassAlpha && PluggableFacade.isGlass(key.state))'


def effective_baker(target: str) -> tuple[Path, str]:
    props = load_properties()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, LOGICAL)
    if source is None:
        raise AssertionError(f'{target}: no PlugBakerFacade source')
    downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root)
    with tempfile.TemporaryDirectory(prefix='bcce-facade-src-') as tmp:
        output = Path(tmp) / LOGICAL
        _materialize_text_file(
            source, output, logical_relative=LOGICAL,
            minecraft=props[f'target.{target}.deps.minecraft'],
            family=layout.family, platform=layout.platform, preprocess=True,
            native_source=any(source.is_relative_to(root) for root in downports),
        )
        return source, output.read_text(encoding='utf-8')


class GlassFacade263(unittest.TestCase):
    def test_fix_is_strictly_263(self):
        for target in (
            '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
            '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
        ):
            with self.subTest(target=target):
                source, code = effective_baker(target)
                self.assertNotIn('//? if >=26.3', code)
                if target == '26.3-neoforge':
                    self.assertIn(WORLD_FACADE_CHECK, code)
                    self.assertIn(CORRECTED_ALPHA, code)
                    self.assertNotIn('quad.multColourd(1.0, 1.0, 1.0, 0.2);', code)
                    self.assertIn('return bakeForKey(key, true);', code)
                    self.assertIn('bakeForKey(KeyPlugFacade key, boolean applyGlassAlpha)', code)
                else:
                    self.assertNotIn(WORLD_FACADE_CHECK, code)
                    if target in ('26.1.2-neoforge', '26.2-neoforge', '1.21.11-neoforge'):
                        self.assertNotIn(CORRECTED_ALPHA, code)

    def test_263_real_branch_scales_only_world_glass_alpha(self):
        _, baker = effective_baker('26.3-neoforge')
        start = baker.index('        ' + WORLD_FACADE_CHECK)
        end = baker.index('        return quads;', start)
        branch = baker[start:end]
        self.assertIn(CORRECTED_ALPHA, branch)
        self.assertNotIn('quad.multColourd(1.0, 1.0, 1.0, 0.2);', branch)
        self.assertNotIn('quad.colouri(', branch)
        program = '''import java.util.*;
public final class FacadeGlassAlphaProbe {
    record BlockState(boolean glass) {}
    record KeyPlugFacade(BlockState state) {}
    static final class PluggableFacade {
        static boolean isGlass(BlockState state) { return state.glass(); }
    }
    static final class MutableQuad {
        final int[][] rgba = new int[4][4];
        MutableQuad(int alpha) {
            for (int[] color : rgba) {
                color[0] = 73; color[1] = 129; color[2] = 203; color[3] = alpha;
            }
        }
        void multColourd(double r, double g, double b, double a) {
            int ri = (int)(r * 255), gi = (int)(g * 255);
            int bi = (int)(b * 255), ai = (int)(a * 255);
            for (int[] color : rgba) {
                color[0] = color[0] * ri / 255;
                color[1] = color[1] * gi / 255;
                color[2] = color[2] * bi / 255;
                color[3] = color[3] * ai / 255;
            }
        }
    }
    static void apply(KeyPlugFacade key, boolean applyGlassAlpha, List<MutableQuad> quads) {
__EXACT_BRANCH__
    }
    static int checks = 0;
    static void verify(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("Check " + checks);
    }
    static void check(int startAlpha, boolean glass, boolean world, int expectedAlpha) {
        List<MutableQuad> quads = List.of(new MutableQuad(startAlpha), new MutableQuad(startAlpha));
        apply(new KeyPlugFacade(new BlockState(glass)), world, quads);
        for (MutableQuad quad : quads) {
            for (int[] color : quad.rgba) {
                verify(color[0] == 73 && color[1] == 129 && color[2] == 203);
                verify(color[3] == expectedAlpha);
            }
        }
    }
    public static void main(String[] args) {
        check(255, true, true, 165); // 26.3 installed glass keeps visible detail
        check(128, true, true, 82);  // preserve original per-vertex alpha proportion
        check(255, true, false, 255); // facade item must keep original alpha
        check(255, false, true, 255); // stone/solid facade unchanged
        check(70, false, true, 70);   // non-glass translucent material unchanged
        System.out.println("PASS " + checks + " java opacity checks");
    }
}
'''.replace('__EXACT_BRANCH__', branch)
        with tempfile.TemporaryDirectory(prefix='bcce-facade-alpha-java-') as tmp:
            path = Path(tmp) / 'FacadeGlassAlphaProbe.java'
            path.write_text(program, encoding='utf-8')
            compile_result = subprocess.run(['javac', path.name], cwd=tmp, capture_output=True, text=True, timeout=25)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(['java', '-cp', tmp, 'FacadeGlassAlphaProbe'], cwd=tmp, capture_output=True, text=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('PASS 80 java opacity checks', result.stdout)
            print(result.stdout.strip())


if __name__ == '__main__':
    unittest.main()
