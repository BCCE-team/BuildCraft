#!/usr/bin/env python3
"""Regression: 26.3 native baked quads must keep signed legacy normals."""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

MUTABLE_VERTEX = 'src/main/java/buildcraft/lib/client/model/MutableVertex.java'
PIPE_NATIVE = 'src/main/java/buildcraft/transport/client/model/ModelPipeNative2612.java'


class SignedNormal263Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.properties = load_properties()

    def source(self, version: str, path: str) -> Path:
        result = resolve_effective_source(
            target_layout(f'{version}-neoforge', self.properties), self.properties, path)
        self.assertIsNotNone(result)
        return result

    def test_signed_decode_is_263_only(self):
        current = self.source('26.3', MUTABLE_VERTEX)
        self.assertIn('source-family-platforms/26.X/neoforge/', current.as_posix())
        text = current.read_text(encoding='utf-8')
        self.assertIn('normal_x = (byte) combined / 127.0F;', text)
        self.assertIn('normal_y = (byte) (combined >>> 8) / 127.0F;', text)
        self.assertIn('normal_z = (byte) (combined >>> 16) / 127.0F;', text)
        for older in ('26.1.2', '26.2'):
            with self.subTest(version=older):
                downport = self.source(older, MUTABLE_VERTEX)
                self.assertIn(f'source-downports/26.X/{older}/neoforge/', downport.as_posix())
                self.assertNotIn('normal_x = (byte) combined / 127.0F;', downport.read_text(encoding='utf-8'))

    @unittest.skipUnless(shutil.which('java') and shutil.which('javac'), 'JDK required')
    def test_real_java_roundtrip_preserves_all_six_face_directions(self):
        # Extract the PRODUCTION packing, unpacking, and face-direction methods,
        # not copies of their formulas, so a future regression fails immediately.
        vertex = self.source('26.3', MUTABLE_VERTEX).read_text(encoding='utf-8')
        native = self.source('26.3', PIPE_NATIVE).read_text(encoding='utf-8')
        decode = vertex[vertex.index('    public MutableVertex normali(int combined) {'):
                        vertex.index('    public MutableVertex invertNormal()', vertex.index('    public MutableVertex normali(int combined) {'))]
        pack = vertex[vertex.index('    public int normalToPackedInt() {'):
                      vertex.index('    public MutableVertex colourv(', vertex.index('    public int normalToPackedInt() {'))]
        face = native[native.index('    private static Direction actualFace(MutableQuad quad) {'):
                      native.index('    /**\n     * Legacy pipe quads', native.index('    private static Direction actualFace(MutableQuad quad) {'))]
        code = '''public class SignedNormalProbe {
    enum Direction { UP, DOWN, NORTH, SOUTH, EAST, WEST }
    static class MutableVertex {
        float normal_x, normal_y, normal_z;
        MutableVertex normalf(float x, float y, float z) {
            normal_x = x; normal_y = y; normal_z = z;
            return this;
        }
''' + decode + pack + '''
    }
    static class MutableQuad {
        MutableVertex vertex_0;
        MutableQuad(MutableVertex vertex) { this.vertex_0 = vertex; }
    }
''' + face + '''
    private static void expect(float actual, float expected, String message) {
        if (Math.abs(actual - expected) > .01f) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
    private static void check(Direction expected, float x, float y, float z) {
        MutableVertex input = new MutableVertex().normalf(x, y, z);
        MutableVertex output = new MutableVertex().normali(input.normalToPackedInt());
        expect(output.normal_x, x, expected + " X");
        expect(output.normal_y, y, expected + " Y");
        expect(output.normal_z, z, expected + " Z");
        Direction actualFace = actualFace(new MutableQuad(output));
        if (actualFace != expected) throw new AssertionError(expected + " decoded as " + actualFace);
    }
    public static void main(String[] args) {
        check(Direction.NORTH, 0, 0, -1);
        check(Direction.SOUTH, 0, 0, 1);
        check(Direction.WEST, -1, 0, 0);
        check(Direction.EAST, 1, 0, 0);
        check(Direction.DOWN, 0, -1, 0);
        check(Direction.UP, 0, 1, 0);
        System.out.println("18 component and 6 face-direction assertions passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bcce-signed-normal-') as path:
            folder = Path(path)
            (folder / 'SignedNormalProbe.java').write_text(code, encoding='utf-8')
            javac = subprocess.run(['javac', '-encoding', 'UTF-8', 'SignedNormalProbe.java'],
                                   cwd=folder, text=True, capture_output=True, timeout=30)
            self.assertEqual(0, javac.returncode, javac.stderr)
            run = subprocess.run(['java', 'SignedNormalProbe'], cwd=folder, text=True,
                                 capture_output=True, timeout=30)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn('18 component and 6 face-direction assertions passed', run.stdout)


if __name__ == '__main__':
    unittest.main()
