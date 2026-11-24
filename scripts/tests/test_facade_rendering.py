#!/usr/bin/env python3
"""Glass facade rendering, alpha blending and native light/shade contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import subprocess
import tempfile
import unittest
import shutil
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

FACADE_ALPHA_LOGICAL = 'src/main/java/buildcraft/silicon/client/model/plug/PlugBakerFacade.java'
FACADE_ALPHA_CORRECTED_ALPHA = 'quad.multColourd(1.0, 1.0, 1.0, 1.0);'
FACADE_ALPHA_WORLD_FACADE_CHECK = 'if (applyGlassAlpha && PluggableFacade.isGlass(key.state))'


def facade_alpha_effective_baker(target: str) -> tuple[Path, str]:
    props = load_properties()
    layout = target_layout(target, props)
    source = resolve_effective_source(layout, props, FACADE_ALPHA_LOGICAL)
    if source is None:
        raise AssertionError(f'{target}: no PlugBakerFacade source')
    downports = tuple(root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root)
    with tempfile.TemporaryDirectory(prefix='bcce-facade-src-') as tmp:
        output = Path(tmp) / FACADE_ALPHA_LOGICAL
        _materialize_text_file(
            source, output, logical_relative=FACADE_ALPHA_LOGICAL,
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
                source, code = facade_alpha_effective_baker(target)
                self.assertNotIn('//? if >=26.3', code)
                if target == '26.3-neoforge':
                    self.assertIn(FACADE_ALPHA_WORLD_FACADE_CHECK, code)
                    self.assertIn(FACADE_ALPHA_CORRECTED_ALPHA, code)
                    self.assertNotIn('quad.multColourd(1.0, 1.0, 1.0, 0.2);', code)
                    self.assertIn('return bakeForKey(key, true);', code)
                    self.assertIn('bakeForKey(KeyPlugFacade key, boolean applyGlassAlpha)', code)
                else:
                    self.assertNotIn(FACADE_ALPHA_WORLD_FACADE_CHECK, code)
                    if target in ('26.1.2-neoforge', '26.2-neoforge', '1.21.11-neoforge'):
                        self.assertNotIn(FACADE_ALPHA_CORRECTED_ALPHA, code)

    def test_263_real_branch_scales_only_world_glass_alpha(self):
        _, baker = facade_alpha_effective_baker('26.3-neoforge')
        start = baker.index('        ' + FACADE_ALPHA_WORLD_FACADE_CHECK)
        end = baker.index('        return quads;', start)
        branch = baker[start:end]
        self.assertIn(FACADE_ALPHA_CORRECTED_ALPHA, branch)
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
        check(255, true, true, 255); // 26.3 installed glass keeps visible detail
        check(128, true, true, 128);  // preserve original per-vertex alpha proportion
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


class NativeGlassNormals263(unittest.TestCase):
    def test_only_263_reorients_glass_normals(self):
        _, source = facade_alpha_effective_baker('26.3-neoforge')
        self.assertIn('if (PluggableFacade.isGlass(state)) {', source)
        self.assertIn('alignGlassNormalToGeometry(mutableQuad, side);', source)
        self.assertIn('quad.normalvf(normal);', source)
        self.assertIn('quad.setFace(Direction.getApproximateNearest(', source)
        for version in ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge'):
            with self.subTest(version=version):
                _, old = facade_alpha_effective_baker(version)
                self.assertNotIn('alignGlassNormalToGeometry', old)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_actual_263_normal_helper_handles_slopes_winding_and_flat_faces(self):
        _, baker = facade_alpha_effective_baker('26.3-neoforge')
        start = baker.index('    private static void alignGlassNormalToGeometry(')
        end = baker.index('    private static MutableQuad fromNative(', start)
        method = baker[start:end]
        code = r'''public final class FacadeNormalProbe {
    enum Direction {
        EAST(1,0,0), WEST(-1,0,0), UP(0,1,0), DOWN(0,-1,0), SOUTH(0,0,1), NORTH(0,0,-1);
        final int x,y,z;
        Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
        int getStepX(){return x;} int getStepY(){return y;} int getStepZ(){return z;}
        static Direction getApproximateNearest(float x,float y,float z){
            Direction nearest=EAST;float score=Float.NEGATIVE_INFINITY;
            for(Direction d:values()){float s=x*d.x+y*d.y+z*d.z;if(s>score){score=s;nearest=d;}}
            return nearest;
        }
    }
    static class MutableQuad {
        org.joml.Vector3f expected, actual;
        Direction face;
        MutableQuad(org.joml.Vector3f expected){this.expected=expected;}
        org.joml.Vector3f getCalculatedNormal(){return new org.joml.Vector3f(expected);}
        void normalvf(org.joml.Vector3f n){actual=new org.joml.Vector3f(n);}
        void setFace(Direction d){face=d;}
    }
''' + method + r'''
    static int assertions=0;
    static void check(boolean correct) {assertions++;if(!correct) throw new AssertionError("assertion "+assertions);}
    public static void main(String[] args) {
        for(Direction source:Direction.values()) {
            org.joml.Vector3f flat=new org.joml.Vector3f(source.x,source.y,source.z);
            MutableQuad q=new MutableQuad(flat);
            alignGlassNormalToGeometry(q,source);
            check(q.face==source);
            check(q.actual!=null && Math.abs(q.actual.lengthSquared()-1)<.00001f);
            org.joml.Vector3f sloped=new org.joml.Vector3f(source.x+.35f*(source.x==0?1:0),
                source.y+.35f*(source.x!=0?1:0),source.z);
            if(source.x==0 && source.y==0) sloped.x=0.35f;
            q=new MutableQuad(sloped);
            alignGlassNormalToGeometry(q,source);
            check(q.actual!=null && q.actual.x()*source.x+q.actual.y()*source.y+q.actual.z()*source.z>0);
            check(Math.abs(q.actual.lengthSquared()-1)<.00001f);
            q=new MutableQuad(new org.joml.Vector3f(-sloped.x(),-sloped.y(),-sloped.z()));
            alignGlassNormalToGeometry(q,source);
            check(q.actual!=null && q.actual.x()*source.x+q.actual.y()*source.y+q.actual.z()*source.z>0);
        }
        MutableQuad degenerate=new MutableQuad(new org.joml.Vector3f(0,0,0));
        alignGlassNormalToGeometry(degenerate,Direction.UP);
        check(degenerate.actual==null);
        System.out.println(assertions+" normal tests PASS");
    }
}
'''
        vector = r'''package org.joml;
public class Vector3f {
    public float x,y,z;
    public Vector3f(float x,float y,float z){this.x=x;this.y=y;this.z=z;}
    public Vector3f(Vector3f o){this(o.x,o.y,o.z);}
    public float x(){return x;} public float y(){return y;} public float z(){return z;}
    public float lengthSquared(){return x*x+y*y+z*z;}
    public Vector3f negate(){x=-x;y=-y;z=-z;return this;}
    public Vector3f normalize(){float len=(float)Math.sqrt(lengthSquared());x/=len;y/=len;z/=len;return this;}
}
'''
        with tempfile.TemporaryDirectory(prefix='bc-glass-normals-') as tmp:
            root = Path(tmp)
            vpath = root / 'org/joml/Vector3f.java'
            vpath.parent.mkdir(parents=True, exist_ok=True)
            vpath.write_text(vector, encoding='utf-8')
            (root / 'FacadeNormalProbe.java').write_text(code, encoding='utf-8')
            build = subprocess.run(['javac', '-encoding', 'UTF-8', 'FacadeNormalProbe.java',
                                    'org/joml/Vector3f.java'], cwd=root, capture_output=True, text=True)
            self.assertEqual(0, build.returncode, build.stderr)
            result = subprocess.run(['java', 'FacadeNormalProbe'], cwd=root, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('31 normal tests PASS', result.stdout)


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


class FacadeValidation(unittest.TestCase):
    """The registry must reject unsafe geometry before publishing recipes or facade items."""

    @staticmethod
    def effective(target, name):
        props = load_properties()
        layout = target_layout(target, props)
        rel = f'src/main/java/buildcraft/silicon/plug/{name}.java'
        src = resolve_effective_source(layout, props, rel)
        with tempfile.TemporaryDirectory(prefix='bcce-facade-validation-') as td:
            out = Path(td) / rel
            _materialize_text_file(src, out, logical_relative=rel,
                                   minecraft=props[f'target.{target}.deps.minecraft'],
                                   family=layout.family, platform=layout.platform, preprocess=True)
            return out.read_text(encoding='utf-8')

    def test_all_targets_validate_before_registering(self):
        from source_config import target_ids
        for target in target_ids(load_properties()):
            with self.subTest(target=target):
                manager = self.effective(target, 'FacadeStateManager')
                validator = self.effective(target, 'FacadeStateValidator')
                self.assertIn('FacadeStateValidator.blockProblem(block)', manager)
                self.assertIn('FacadeStateValidator.geometryProblem(state,', manager)
                self.assertNotIn('wholeBlockAllowed', manager)
                for guard in ('!state.getFluidState().isEmpty()', 'state.hasBlockEntity()',
                              'state.getRenderShape() != RenderShape.MODEL'):
                    self.assertIn(guard, manager)
                scan = manager[manager.index('private static void scanBlock('):]
                self.assertLess(scan.index('isValidFacadeBlock(block)'), scan.index('doesPropertyConform(property)'))
                self.assertLess(scan.index('isValidFacadeState(state)'), scan.index('getRequiredStack(state)'))
                self.assertLess(scan.index('isValidFacadeState(state)'), scan.index('validFacadeStates.put(state, info)'))
                self.assertIn('stateResult.getObject()', scan)
                self.assertNotIn('net.minecraft.client.', validator)
                if target.startswith(('1.19.2-', '1.20.1-')):
                    self.assertIn('import net.minecraft.world.level.block.GlassBlock;', validator)
                    self.assertIn('block instanceof GlassBlock', validator)
                else:
                    self.assertNotIn('import net.minecraft.world.level.block.GlassBlock;', validator)
                    self.assertNotIn('block instanceof GlassBlock', validator)
                if target.startswith(('1.19.2-', '1.20.1-', '1.21.1-')):
                    self.assertIn('state.isSolidRender(access, pos)', validator)
                    self.assertIn('instanceof BushBlock', validator)
                else:
                    self.assertIn('state.isSolidRender()', validator)
                    self.assertIn('instanceof VegetationBlock', validator)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java SDK required')
    def test_geometry_policy_with_legacy_and_modern_signatures(self):
        # Run the actual materialized Java policy with small Minecraft shape/property stand-ins.
        # These are logic regressions, not substitutes for a game integration test of a real mod's model.
        from source_config import target_ids
        for target in target_ids(load_properties()):
            with self.subTest(target=target):
                code = self.effective(target, 'FacadeStateValidator')
                policy = code[code.index('final class FacadeStateValidator {'):]
                policy = policy.replace('final class FacadeStateValidator {', 'static final class FacadeStateValidator {', 1)
                legacy = target.startswith(('1.19.2-', '1.20.1-'))
                old_shape_api = legacy or target.startswith('1.21.1-')
                plant = 'BushBlock' if old_shape_api else 'VegetationBlock'
                solid = ('boolean isSolidRender(SingleBlockAccess a, BlockPos p) { return solid; }'
                         if old_shape_api else 'boolean isSolidRender() { return solid; }')
                program = r'''import java.util.*;
public class FacadeValidationProbe {
    enum Direction { DOWN, UP, NORTH, SOUTH, EAST, WEST }
    enum Half { UPPER, LOWER }
    record Property<T extends Comparable<T>>(String name, Class<T> type, Map<String,T> values) {
        String getName() { return name; }
        Class<T> getValueClass() { return type; }
        Optional<T> getValue(String name) { return Optional.ofNullable(values.get(name)); }
    }
    record StateDefinition(List<Property<?>> properties) {
        List<Property<?>> getProperties() { return properties; }
    }
    static class Block {
        final StateDefinition definition;
        Block(Property<?>... p) { definition = new StateDefinition(List.of(p)); }
        StateDefinition getStateDefinition() { return definition; }
        static boolean isShapeFullBlock(boolean full) { return full; }
    }
    static class __PLANT__ extends Block {}
    __LEGACY_GLASS_CLASS__
    static class StainedGlassBlock extends Block {}
    static class LeavesBlock extends Block {}
    static class Blocks {
        static final Block GLASS = new Block(), TINTED_GLASS = new Block();
    }
    static class BlockTags { static final Object LEAVES = new Object(); }
    static class BlockPos { static final BlockPos ZERO = new BlockPos(); }
    record SingleBlockAccess(BlockState state) {}
    record BlockState(Block block, boolean outline, boolean collision, boolean solid, Direction weak, boolean leaves) {
        boolean getShape(SingleBlockAccess a, BlockPos p) { return outline; }
        boolean getCollisionShape(SingleBlockAccess a, BlockPos p) { return collision; }
        __SOLID__
        boolean isFaceSturdy(SingleBlockAccess a, BlockPos p, Direction d) { return d != weak; }
        boolean is(Object tag) { return leaves && tag == BlockTags.LEAVES; }
        Block getBlock() { return block; }
    }
    __POLICY__
    static int checks;
    static void expect(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    static Property<Boolean> flag(String n) { return new Property<>(n, Boolean.class, Map.of()); }
    static Property<Integer> connection(String n) { return new Property<>(n, Integer.class, Map.of()); }
    static boolean valid(Block b, String path, boolean outline, boolean collision, boolean solid, Direction weak) {
        return FacadeStateValidator.blockProblem(b) == null && FacadeStateValidator.geometryProblem(
            new BlockState(b, outline, collision, solid, weak, false), path) == null;
    }
    public static void main(String[] args) {
        Block stone = new Block();
        expect(valid(stone, "stone", true, true, true, null), "stone");
        expect(!valid(stone, "slab", false, true, true, null), "partial outline");
        expect(!valid(stone, "tea_crop", true, false, false, null), "fake full selection shape");
        expect(!valid(stone, "christmas_tree", true, true, false, null), "non-occluding decoration");
        expect(!valid(new __PLANT__(), "day_lily", true, true, true, null), "plant even with fake solid shapes");
        Block doublePlant = new Block(new Property<>("half", Half.class, Map.of("upper", Half.UPPER, "lower", Half.LOWER)));
        expect(!valid(doublePlant, "dead_shrub", true, true, true, null), "custom double plant");
        Block pipe = new Block(connection("north"), connection("south"), connection("up"), flag("straight"));
        expect(!valid(pipe, "framed_energy_pipe", true, true, true, null), "connection-dependent pipe");
        Block boolPipe = new Block(flag("north"), flag("east"), flag("straight"));
        expect(!valid(boolPipe, "framed_pipe", true, true, true, null), "boolean pipe connections");
        Block mushroom = new Block(flag("north"), flag("east"), flag("south"), flag("west"), flag("up"), flag("down"));
        expect(valid(mushroom, "red_mushroom_block", true, true, true, null), "cube with face-texture switches");
        expect(valid(new Block(flag("powered")), "redstone_lamp", true, true, true, null), "stateful cube");
        for (Direction face : Direction.values()) {
            expect(!valid(stone, "broken_cube", true, true, true, face), "weak " + face);
        }
        expect(valid(Blocks.GLASS, "glass", true, true, false, null), "glass");
        expect(valid(Blocks.TINTED_GLASS, "tinted_glass", true, true, false, null), "tinted glass");
        expect(valid(new Block(), "custom_stained_glass", true, true, false, null), "named modded glass");
        __LEGACY_GLASS_CHECK__
        expect(valid(new StainedGlassBlock(), "blue_stained_glass", true, true, false, null), "stained glass");
        expect(valid(new LeavesBlock(), "oak_leaves", true, true, false, null), "full leaves");
        expect(!valid(Blocks.GLASS, "glass", false, true, false, null), "glass cannot bypass outline");
        expect(!valid(Blocks.GLASS, "glass", true, false, false, null), "glass cannot bypass collision");
        expect(!valid(new LeavesBlock(), "oak_leaves", true, false, false, null), "foliage cannot bypass collision");
        expect(!valid(stone, "glass_pane", false, false, false, null), "glass pane");
        System.out.println(checks + " facade validation checks passed");
    }
}
'''.replace('__POLICY__', policy).replace('__SOLID__', solid).replace('__PLANT__', plant)
                program = program.replace('__LEGACY_GLASS_CLASS__',
                                          'static class GlassBlock extends Block {}' if legacy else '')
                program = program.replace('__LEGACY_GLASS_CHECK__',
                                          'expect(valid(new GlassBlock(), "custom_glass", true, true, false, null), "legacy glass subclass");'
                                          if legacy else '')
                with tempfile.TemporaryDirectory(prefix='bcce-facade-policy-java-') as td:
                    root = Path(td)
                    (root / 'FacadeValidationProbe.java').write_text(program, encoding='utf-8')
                    compiled = subprocess.run(['javac', 'FacadeValidationProbe.java'], cwd=root,
                                              capture_output=True, text=True, timeout=30)
                    self.assertEqual(0, compiled.returncode, compiled.stderr)
                    result = subprocess.run(['java', 'FacadeValidationProbe'], cwd=root,
                                            capture_output=True, text=True, timeout=30)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn(f'{26 if legacy else 25} facade validation checks passed', result.stdout)


if __name__ == "__main__":
    unittest.main()
