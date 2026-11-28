#!/usr/bin/env python3
"""Marker/Volume Box regression checks. API doubles do not replace in-game rendering."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source
from source_lookup import read_materialized_java

FABRIC_121 = '1.21.11-fabric'
FABRIC_261 = '26.1.2-fabric'
NEO_261 = '26.1.2-neoforge'
MARKER_MODEL = 'src/main/resources/assets/buildcraftcore/models/block/torch_center_lit.json'
LAYERS = 'fabric/FabricBlockTransparency.java'
LASER = 'lib/client/render/laser/LaserRenderer_BC8.java'
RENDER_BOXES = 'core/client/render/RenderVolumeBoxes.java'


def java(target, path):
    return read_materialized_java(target, 'src/main/java/buildcraft/' + path)


class FabricMarkerVolumeBoxTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.props = load_properties()

    def resource(self, target, rel):
        path = resolve_effective_source(target_layout(target, self.props), self.props, rel)
        self.assertIsNotNone(path, (target, rel))
        return path

    def test_volume_boxes_use_the_fabric_capture_in_every_fabric_target(self):
        for target in (FABRIC_121, FABRIC_261, '26.2-fabric', '26.3-fabric'):
            with self.subTest(target=target):
                renderer = java(target, RENDER_BOXES)
                self.assertIn('BCWorldGeometry.buffer(RenderCompat.solid())', renderer)
                self.assertIn('BCWorldGeometry.buffer(RenderCompat.translucent())', renderer)
                self.assertIn('renderVisibleLaserBoxDynamic(', renderer)
                self.assertIn('renderAddonFast(', renderer)
                self.assertNotIn('.renderBuffers()', renderer)
                self.assertNotIn('.endBatch(', renderer)
                bootstrap = java(target, 'core/client/FabricCoreClient.java')
                self.assertIn('RenderVolumeBoxes.INSTANCE', bootstrap)
                self.assertIn('MessageVolumeBoxesClientHandler::handle', bootstrap)
                geometry = java(target, 'core/client/CoreWorldGeometry.java')
                self.assertIn('BCWorldGeometry.capture(', geometry)
                self.assertIn('DetachedRenderer.INSTANCE.renderWorldLastEvent(', geometry)
                self.assertIn('BCWorldGeometry.submit(', geometry)

    def test_volume_box_creation_permissions_and_sync_preserved(self):
        # The placement failure was in the renderer, not a missing item callback.
        for path in ('core/item/ItemVolumeBox.java', 'core/marker/volume/VolumeBoxToolActions.java',
                     'core/marker/volume/MessageVolumeBoxes.java', 'core/marker/volume/MessageVolumeBoxesClientHandler.java'):
            self.assertEqual(java(FABRIC_121, path), java(FABRIC_261, path), path)
        item = java(FABRIC_121, 'core/item/ItemVolumeBox.java')
        self.assertIn('boxes.getVolumeBoxAt(position)', item)
        self.assertIn('VolumeBoxToolActions.mayEdit(level, ctx.getPlayer(), position)', item)
        self.assertIn('boxes.addVolumeBox(position)', item)
        self.assertIn('return InteractionResult.SUCCESS;', item)
        saved = java(FABRIC_121, 'core/marker/volume/WorldSavedDataVolumeBoxes.java')
        self.assertIn('volumeBoxes.add(new VolumeBox(world, pos));\n        setDirty();', saved)
        self.assertIn('MessageVolumeBoxes.sendSnapshotToDimension(volumeBoxes, world.dimension())', saved)
        self.assertIn('ClientVolumeBoxes.INSTANCE.volumeBoxes.add(added);', java(FABRIC_121, 'core/marker/volume/MessageVolumeBoxesClientHandler.java'))

    def test_marker_lamp_preserves_geometry_and_uv(self):
        previous = json.loads(self.resource(NEO_261, MARKER_MODEL).read_text())
        model = json.loads(self.resource(FABRIC_261, MARKER_MODEL).read_text())
        old, = previous['elements']
        body, lamp = model['elements']
        self.assertEqual(model['textures'], previous['textures'])
        self.assertEqual(body['from'], old['from'])
        self.assertEqual(lamp['to'], old['to'])
        self.assertEqual(body['to'], [9, 7, 9])
        self.assertEqual(lamp['from'], [7, 7, 7])
        self.assertFalse(body['shade'])
        self.assertFalse(lamp['shade'])
        self.assertNotIn('light_emission', body)
        self.assertEqual(lamp['light_emission'], 15)
        # Preserve the top and bottom; do not generate hidden interior faces.
        self.assertEqual(body['faces']['down'], old['faces']['down'])
        self.assertEqual(lamp['faces']['up'], old['faces']['up'])
        self.assertNotIn('up', body['faces'])
        self.assertNotIn('down', lamp['faces'])
        for face in ('east', 'west', 'north', 'south'):
            before = old['faces'][face]['uv']
            bottom = body['faces'][face]['uv']
            top = lamp['faces'][face]['uv']
            self.assertEqual(top, [before[0], before[1], before[2], 7])
            self.assertEqual(bottom, [before[0], 7, before[2], before[3]])
            self.assertEqual(body['faces'][face]['texture'], old['faces'][face]['texture'])
            self.assertEqual(lamp['faces'][face]['texture'], old['faces'][face]['texture'])
        for name in ('marker_volume', 'marker_path'):
            path = f'src/main/resources/assets/buildcraftcore/textures/blocks/{name}.png'
            self.assertEqual(self.resource(FABRIC_261, path).read_bytes(), self.resource(NEO_261, path).read_bytes())
        # Only the reported version gets this lighting workaround.
        for target in (FABRIC_121, '26.2-fabric', '26.3-fabric'):
            self.assertEqual(self.resource(target, MARKER_MODEL).read_bytes(), self.resource(NEO_261, MARKER_MODEL).read_bytes())
            self.assertNotIn('LitMarkerModel', java(target, LAYERS))

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_lamp_face_selection_in_six_directions(self):
        source = java(FABRIC_261, LAYERS)
        self.assertIn('quad.ambientOcclusion(TriState.FALSE);', source)
        self.assertIn('quad.emissive(true);', source)
        self.assertIn('quad.shadeMode(ShadeMode.VANILLA);', source)
        self.assertIn('quad.diffuseShade(false);', source)
        self.assertIn('state.getValue(BlockStateProperties.FACING)', source)
        self.assertIn('new MarkerGeometryKey(original, state.getValue(BlockStateProperties.FACING))', source)
        self.assertIn('quad.chunkLayer(ChunkSectionLayer.CUTOUT);', source)
        # Exercise the actual production method for all blockstate rotations.
        method = source.split('    static boolean isLampFace(', 1)[1].split('    private record MarkerGeometryKey', 1)[0]
        code = '''public class Probe {
    enum Direction {UP, DOWN, EAST, WEST, SOUTH, NORTH}
    record QuadView(float[][] points) {
        float x(int i) { return points[i][0]; }
        float y(int i) { return points[i][1]; }
        float z(int i) { return points[i][2]; }
    }
    static boolean isLampFace(''' + method + '''
    static QuadView rotate(Direction face, float low, float high) {
        float[][] local = {{.4375f, low, .4375f}, {.5625f, low, .4375f},
                           {.5625f, high, .4375f}, {.4375f, high, .4375f}};
        for (int i=0; i<4; i++) {
            float x=local[i][0], y=local[i][1], z=local[i][2];
            local[i] = switch(face) {
                case UP -> new float[]{x,y,z};
                case DOWN -> new float[]{x,1-y,1-z};
                case NORTH -> new float[]{x,z,1-y};
                case SOUTH -> new float[]{1-x,z,y};
                case EAST -> new float[]{y,z,x};
                case WEST -> new float[]{1-y,z,1-x};
            };
        }
        return new QuadView(local);
    }
    public static void main(String[] args) {
        for (Direction face : Direction.values()) {
            if (!isLampFace(rotate(face, 7f/16, 9f/16), face)) throw new AssertionError(face + " lamp");
            if (!isLampFace(rotate(face, 9f/16, 9f/16), face)) throw new AssertionError(face + " top");
            if (isLampFace(rotate(face, 0, 7f/16), face)) throw new AssertionError(face + " stem");
            if (isLampFace(rotate(face, 0, 0), face)) throw new AssertionError(face + " bottom");
        }
        System.out.println("24 facing/face checks passed");
    }
}
'''
        self.run_java_probe(code, '24 facing/face checks passed')

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_blue_signal_uses_neutral_light_and_other_lasers_keep_their_values(self):
        source = java(FABRIC_261, LASER)
        method = source.split('    private static void makeLaser(', 1)[1].split('    private static void removeCompiledLaser', 1)[0]
        code = '''public class Probe {
    static class BuildCraftLaserManager { static final Object MARKER_VOLUME_SIGNAL = new Object(); }
    static class LaserData_BC8 { Object laserType; boolean enableDiffuse = true, doubleFace = true; }
    interface ILaserRenderer { void vertex(float x,float y,float z,float u,float v,int light,float nx,float ny,float nz,float diffuse); }
    static class LaserContext {
        final ILaserRenderer renderer; final boolean diffuse;
        LaserContext(ILaserRenderer output, LaserData_BC8 data, boolean diffuse, boolean doubleFace, boolean isStatic) {
            this.renderer = output; this.diffuse = diffuse;
            if (!doubleFace || !isStatic) throw new AssertionError("flags changed");
        }
    }
    static class CompiledLaserType { void bakeFor(LaserContext ctx) {
        ctx.renderer.vertex(1,2,3,.25f,.75f,0x00500030,1,0,0,ctx.diffuse ? .5f : 1f);
    } }
    static CompiledLaserType compileType(Object type) { return new CompiledLaserType(); }
    private static void makeLaser(''' + method + '''
    public static void main(String[] args) {
        LaserData_BC8 data = new LaserData_BC8();
        data.laserType = BuildCraftLaserManager.MARKER_VOLUME_SIGNAL;
        makeLaser(data, (x,y,z,u,v,light,nx,ny,nz,diffuse) -> {
            if (light != 0x00F000F0 || diffuse != 1f || nx != 0 || ny != 1 || nz != 0) throw new AssertionError("blue lighting");
            if(x!=1||y!=2||z!=3||u!=.25f||v!=.75f) throw new AssertionError("blue geometry changed");
        }, true);
        data.laserType = new Object();
        makeLaser(data, (x,y,z,u,v,light,nx,ny,nz,diffuse) -> {
            if (light != 0x00500030 || diffuse != .5f || nx != 1 || ny != 0 || nz != 0) throw new AssertionError("other lighting changed");
        }, true);
        System.out.println("blue and ordinary laser light checks passed");
    }
}
'''
        self.run_java_probe(code, 'blue and ordinary laser light checks passed')
        for target in (NEO_261, FABRIC_121, '26.2-fabric', '26.3-fabric'):
            self.assertNotIn('boolean blueSignal =', java(target, LASER))

    def run_java_probe(self, code, expected):
        with tempfile.TemporaryDirectory(prefix='bc-marker-light-') as name:
            folder = Path(name)
            (folder / 'Probe.java').write_text(code, encoding='utf-8')
            build = subprocess.run(['javac', 'Probe.java'], cwd=folder, capture_output=True, text=True, timeout=30)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run(['java', 'Probe'], cwd=folder, capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn(expected, run.stdout)


if __name__ == '__main__':
    unittest.main()
