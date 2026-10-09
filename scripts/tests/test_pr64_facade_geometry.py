"""Independent six-direction rotation contract and materialization coverage for PR #64."""
from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VECTORS = {
    'DOWN': (0, -1, 0), 'UP': (0, 1, 0),
    'NORTH': (0, 0, -1), 'SOUTH': (0, 0, 1),
    'WEST': (-1, 0, 0), 'EAST': (1, 0, 0),
}


def rotate(vec, source, target):
    """The axis/quarter-turn mapping of MutableQuad.rotate and MutableVertex.rotate*_90."""
    if source == target:
        return vec
    x, y, z = vec
    sx, sy, sz = VECTORS[source]
    tx, ty, tz = VECTORS[target]
    axis_s = next(i for i, component in enumerate((sx, sy, sz)) if component)
    axis_t = next(i for i, component in enumerate((tx, ty, tz)) if component)
    if axis_s == axis_t:
        if axis_s == 0 or axis_s == 2:
            return (-x, y, -z)
        return (-x, -y, z)
    if axis_s == 0 and axis_t == 1:
        k = sx * ty
        return (-k * y, k * x, z)
    if axis_s == 0 and axis_t == 2:
        k = sx * tz
        return (-k * z, y, k * x)
    if axis_s == 1 and axis_t == 0:
        k = -sy * tx
        return (-k * y, k * x, z)
    if axis_s == 1 and axis_t == 2:
        k = sy * tz
        return (x, -k * z, k * y)
    if axis_s == 2 and axis_t == 0:
        k = -sz * tx
        return (-k * z, y, k * x)
    if axis_s == 2 and axis_t == 1:
        k = -sz * ty
        return (x, -k * z, k * y)
    raise AssertionError((source, target))


class FacadeRotationPort(unittest.TestCase):
    def test_all_36_direction_pairs(self):
        for src, vector in VECTORS.items():
            for dst, target in VECTORS.items():
                with self.subTest(src=src, dst=dst):
                    self.assertEqual(target, rotate(vector, src, dst))

    def test_216_face_rotations_remain_cardinal(self):
        for face, vector in VECTORS.items():
            for src in VECTORS:
                for dst in VECTORS:
                    with self.subTest(face=face, src=src, dst=dst):
                        self.assertIn(rotate(vector, src, dst), VECTORS.values())

    def test_materialized_sources_cover_all_targets(self):
        paths = [
            'source-families/old/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-families/1.21.X/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-families/26.X/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
            'source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/lib/client/model/MutableQuad.java',
        ]
        for rel in paths:
            with self.subTest(rel=rel):
                code = (ROOT / rel).read_text(encoding='utf-8')
                self.assertIn('face = rotateFace(face, from, to)', code)
                self.assertIn('probe.rotate(from, to, 0, 0, 0)', code)
                self.assertIn('if (face != null)', code)
                self.assertIn('DirectionCompat.nearest(', code)
        self.assertIn('Direction.getNearest(', (
            ROOT / 'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/lib/misc/DirectionCompat.java'
        ).read_text())

    def test_blocker_uv_fix(self):
        code = (ROOT / 'source-shared/src/main/resources/assets/buildcrafttransport/models/plugs/blocker.json').read_text()
        self.assertIn('"north": { "uv": [ 4, 4, 2, 12 ]', code)


if __name__ == '__main__':
    unittest.main()
