#!/usr/bin/env python3
"""26.3 facade culling and AO regression; keep the 26.1.2/26.2 renderer unchanged."""
from __future__ import annotations

import sys
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source


class GlassFacadeLighting263Test(unittest.TestCase):
    PIPE_NATIVE_PATH = 'src/main/java/buildcraft/transport/client/model/ModelPipeNative2612.java'

    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()

    def native_source(self, target: str) -> Path:
        source = resolve_effective_source(target_layout(target, self.props), self.props,
                                          self.PIPE_NATIVE_PATH)
        self.assertIsNotNone(source)
        return source

    def test_fix_is_only_in_263_native_renderer(self):
        newer = self.native_source('26.3-neoforge')
        self.assertIn('source-family-platforms/26.X/neoforge/', newer.as_posix())
        for older in ('26.1.2', '26.2'):
            with self.subTest(target=older):
                source = self.native_source(f'{older}-neoforge')
                self.assertIn(f'source-downports/26.X/{older}/neoforge/', source.as_posix())
                self.assertNotIn('boolean ambientOcclusion)', source.read_text(encoding='utf-8'))

    def test_translucent_facade_uses_outer_face_ao_without_changing_alpha(self):
        code = self.native_source('26.3-neoforge').read_text(encoding='utf-8')
        self.assertIn('material(sprite, translucentLayer, -1, true, 0, true)', code)
        self.assertIn('material(sprite, translucentLayer, quad.getTint(), quad.isShade(), lightEmission(quad),\n'
                      '                        !translucentLayer || isOuterGlassFace(quad, face))', code)
        self.assertIn('tintIndex, shade ? null : Direction.UP, lightEmission, ambientOcclusion)', code)
        self.assertIn('private static boolean isOuterGlassFace(MutableQuad quad, Direction face)', code)
        self.assertIn('float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE', code)
        self.assertIn('BakedColors.of(argb(quad.vertex_0), argb(quad.vertex_1),', code)
        # Native quads must stay in the translucent terrain pass, not a BER or the opaque pass.
        self.assertIn('translucentPart(translucentBody, translucentPlugs, translucent, particle)', code)
        self.assertIn('material(sprite, translucentLayer, quad.getTint(), quad.isShade(), lightEmission(quad),', code)

    def test_boundary_glass_is_separated_from_unculled_pipe_and_cutout(self):
        code = self.native_source('26.3-neoforge').read_text(encoding='utf-8')
        # The quads of the pipe body and all cutout pluggables still bypass vanilla neighbour culling.
        self.assertIn('unculled.addAll(pipeQuads);', code)
        self.assertIn('List.copyOf(cutout), List.copyOf(cutout), Map.of()', code)
        # Minecraft 26.3's native BakedQuad returns the read-only JOML interface,
        # not a mutable Vector3f. Keep the facade classifier type-compatible.
        self.assertIn('import org.joml.Vector3fc;', code)
        self.assertIn('Vector3fc pos = quad.position(i);', code)
        self.assertIn('if (isBoundaryFace(quad, face))', code)
        self.assertIn('culled.computeIfAbsent(face, ignored -> new ArrayList<>()).add(quad);', code)
        self.assertIn('return side == null ? quads : culledQuads.getOrDefault(side, List.of());', code)
        # Directional quads must still contribute flags for the glass translucent terrain pass.
        self.assertIn('return allQuads.stream().map(BakedQuad::materialInfo)', code)
        # Avoid introducing the model part boundary fix in older target downports.
        for older in ('26.1.2', '26.2'):
            previous = self.native_source(f'{older}-neoforge').read_text(encoding='utf-8')
            self.assertNotIn('culledQuads.getOrDefault', previous)
            self.assertNotIn('private static PipeModelPart translucentPart', previous)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_boundary_culling_predicate_all_six_faces(self):
        # Compile the exact Java classifier extracted from the production renderer. Exercise
        # exterior glass, interior slab faces, epsilon boundaries and non-boundary geometry.
        code = self.native_source('26.3-neoforge').read_text(encoding='utf-8')
        start = code.index('    private static boolean isBoundaryFace(')
        end = code.index('    private static List<BakedQuad> convertPipeBody(', start)
        predicate = code[start:end]
        fixture = r'''public class FacadeCullProbe {
    enum Direction {
        UP(Axis.Y, AxisDirection.POSITIVE), DOWN(Axis.Y, AxisDirection.NEGATIVE),
        EAST(Axis.X, AxisDirection.POSITIVE), WEST(Axis.X, AxisDirection.NEGATIVE),
        SOUTH(Axis.Z, AxisDirection.POSITIVE), NORTH(Axis.Z, AxisDirection.NEGATIVE);
        enum Axis { X, Y, Z }
        enum AxisDirection { POSITIVE, NEGATIVE }
        final Axis axis;
        final AxisDirection axisDirection;
        Direction(Axis axis, AxisDirection axisDirection) {
            this.axis = axis;
            this.axisDirection = axisDirection;
        }
        Axis getAxis() { return axis; }
        AxisDirection getAxisDirection() { return axisDirection; }
    }
    interface Vector3fc {
        float x();
        float y();
        float z();
    }
    record Vector3f(float x, float y, float z) implements Vector3fc {}
    static class BakedQuad {
        final Direction face;
        final Vector3f[] vertices;
        BakedQuad(Direction face, Vector3f... vertices) {
            this.face = face;
            this.vertices = vertices;
        }
        Vector3fc position(int i) { return vertices[i]; }
    }
    static BakedQuad plane(Direction direction, float depth) {
        Vector3f[] vertices = new Vector3f[4];
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) != 0 ? 1f : 0f;
            float y = (i & 2) != 0 ? 1f : 0f;
            float z = (i & 1) != 0 ? 0f : 1f;
            switch (direction.getAxis()) {
                case X -> x = depth;
                case Y -> y = depth;
                case Z -> z = depth;
            }
            vertices[i] = new Vector3f(x, y, z);
        }
        return new BakedQuad(direction, vertices);
    }
    static void expect(boolean actual, String name) {
        if (!actual) throw new AssertionError(name);
    }
''' + predicate + r'''
    public static void main(String[] args) {
        for (Direction face : Direction.values()) {
            float outer = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
            expect(isBoundaryFace(plane(face, outer), face), face + " outer boundary");
            expect(!isBoundaryFace(plane(face, 1f-outer), face), face + " opposite boundary");
            expect(!isBoundaryFace(plane(face, outer == 1f ? .875f : .125f), face), face + " inner face");
            expect(isBoundaryFace(plane(face, outer + .00001f), face), face + " epsilon");
            expect(!isBoundaryFace(plane(face, outer), null), face + " null face");
        }
        System.out.println("30 cull boundary assertions passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bc-glass-cull-probe-') as td:
            root = Path(td)
            (root / 'FacadeCullProbe.java').write_text(fixture, encoding='utf-8')
            compiled = subprocess.run(['javac', '-encoding', 'UTF-8', 'FacadeCullProbe.java'], cwd=root,
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(['java', 'FacadeCullProbe'], check=True, cwd=root,
                                    capture_output=True, text=True, timeout=30)
        self.assertIn('30 cull boundary assertions passed', result.stdout)

    def test_263_shade_conversion_does_not_use_ao_as_shade_flag(self):
        from source_layout import _materialize_text_file
        rel = 'src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java'
        layout = target_layout('26.3-neoforge', self.props)
        src = resolve_effective_source(layout, self.props, rel)
        with tempfile.TemporaryDirectory(prefix='bc-glass-facade-shade-') as td:
            out = Path(td) / 'PlugBakerFacade.java'
            _materialize_text_file(src, out, logical_relative=rel, minecraft='26.3',
                                   family=layout.family, platform=layout.platform, preprocess=True)
            text = out.read_text(encoding='utf-8')
        self.assertIn('material.shadeDirectionOverride() == null', text)
        self.assertNotIn('material.shadeDirectionOverride() != null || material.ambientOcclusion()', text)
        self.assertNotIn('material.shade()', text)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_ao_boundary_all_six_directions(self):
        # Execute the actual predicate in isolation with representative Minecraft facades:
        # exterior full quads, 2/16 inset back quads, and facade edges on all six sides.
        code = self.native_source('26.3-neoforge').read_text(encoding='utf-8')
        start = code.index('    private static boolean isOuterGlassFace(')
        end = code.index('    private static BakedQuad.MaterialInfo material(', start)
        predicate = code[start:end]
        java = '''
public class FacadeAOProbe {
    enum Direction {
        UP(Axis.Y, AxisDirection.POSITIVE), DOWN(Axis.Y, AxisDirection.NEGATIVE),
        EAST(Axis.X, AxisDirection.POSITIVE), WEST(Axis.X, AxisDirection.NEGATIVE),
        SOUTH(Axis.Z, AxisDirection.POSITIVE), NORTH(Axis.Z, AxisDirection.NEGATIVE);
        enum Axis { X, Y, Z }
        enum AxisDirection { POSITIVE, NEGATIVE }
        private final Axis axis;
        private final AxisDirection axisDirection;
        Direction(Axis axis, AxisDirection axisDirection) {
            this.axis = axis;
            this.axisDirection = axisDirection;
        }
        Axis getAxis() { return axis; }
        AxisDirection getAxisDirection() { return axisDirection; }
    }
    static class MutableVertex {
        float position_x, position_y, position_z;
        MutableVertex(float x, float y, float z) {
            position_x = x; position_y = y; position_z = z;
        }
    }
    static class MutableQuad {
        MutableVertex[] vertexs;
        MutableQuad(MutableVertex... vertices) { vertexs = vertices; }
    }
    static MutableQuad plane(Direction face, float coordinate) {
        MutableVertex[] vertices = new MutableVertex[4];
        for (int i = 0; i < 4; i++) {
            float x = (i & 1) == 0 ? 0f : 1f;
            float y = (i & 2) == 0 ? 0f : 1f;
            float z = (i & 1) == 0 ? 1f : 0f;
            switch (face.getAxis()) {
                case X -> x = coordinate;
                case Y -> y = coordinate;
                case Z -> z = coordinate;
            }
            vertices[i] = new MutableVertex(x, y, z);
        }
        return new MutableQuad(vertices);
    }
    static void expect(boolean actual, String message) {
        if (!actual) throw new AssertionError(message);
    }
''' + predicate + '''
    public static void main(String[] args) {
        for (Direction face : Direction.values()) {
            float boundary = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1f : 0f;
            expect(isOuterGlassFace(plane(face, boundary), face), face + " outer AO");
            expect(!isOuterGlassFace(plane(face, 1f - boundary), face), face + " wrong side");
            float interior = boundary == 1f ? 0.875f : 0.125f;
            expect(!isOuterGlassFace(plane(face, interior), face), face + " inside AO");
            expect(isOuterGlassFace(plane(face, boundary + 0.00001f), face), face + " tolerance");
        }
        System.out.println("24 directional face/AO assertions passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bc-glass-ao-probe-') as td:
            root = Path(td)
            (root / 'FacadeAOProbe.java').write_text(java, encoding='utf-8')
            compiled = subprocess.run(['javac', '-encoding', 'UTF-8', 'FacadeAOProbe.java'], cwd=root,
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(['java', 'FacadeAOProbe'], check=True, cwd=root,
                                    capture_output=True, text=True, timeout=30)
        self.assertIn('24 directional face/AO assertions passed', result.stdout)


    def test_only_glass_facades_use_translucent_pluggable_layer(self):
        path = ROOT / 'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/silicon/plug/PluggableFacade.java'
        code = path.read_text(encoding='utf-8')
        self.assertIn('if (isGlass(blockState))', code)
        self.assertIn('if (layer != RenderCompat.translucent())', code)
        self.assertIn('} else if (layer == RenderCompat.translucent())', code)


if __name__ == '__main__':
    unittest.main()
