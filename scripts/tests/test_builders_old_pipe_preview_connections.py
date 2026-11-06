#!/usr/bin/env python3
"""Read-only preview pipe connections for Forge 1.19.2 and 1.20.1.

Source/bit-packing regression tests, not a substitute for Forge compile/GameTests.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java, resolve_target_source
from minecraft_compat_fixture import parse_sources

TARGETS = ('1.19.2-forge', '1.20.1-forge')
LOGICAL = 'src/main/java/buildcraft/builders/client/render/LegacyBlueprintPreviewScene.java'


class LegacyBlueprintPipePreviewConnectionsTests(unittest.TestCase):
    def test_source_is_shared_by_both_legacy_forge_targets_and_parses(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                selected = resolve_target_source(target, LOGICAL)
                self.assertIn('source-platforms/forge/', selected.as_posix())
                source_file = Path(temporary) / 'LegacyBlueprintPreviewScene.java'
                source_file.write_text(read_materialized_java(target, LOGICAL), encoding='utf-8')
                result = parse_sources([source_file])
                self.assertIn('0 errors', result, result)

    def test_mask_is_rebuilt_from_sampled_neighbours_not_original_pipe_con(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, LOGICAL)
                self.assertIn('BlueprintSecurity.forPipe(saved.getCompound("tileNbt"))', source)
                self.assertIn('restorePreviewPipeConnections(blueprint, step, pipeNbtForPreview)', source)
                self.assertLess(source.index('BlueprintSecurity.forPipe('),
                                source.index('restorePreviewPipeConnections(blueprint, step, pipeNbtForPreview)'))
                self.assertIn('Pipe.canPipesConnect(face, pipe, other.getPipe())', source)
                self.assertIn('blocksConnection(holder.getPluggable(face))', source)
                self.assertIn('blocksConnection(other.getPluggable(face.getOpposite()))', source)
                self.assertIn('blueprint.posToIndex(next) % step != 0', source)
                self.assertIn('previewTags.containsKey(next)', source)
                self.assertIn('pipe.getFlow().canConnect(face, adjacent)', source)
                self.assertIn('Map<BlockPos, Integer> masks = new HashMap<>()', source)
                self.assertIn('CompoundTag renderOnly = saved.copy()', source)
                self.assertIn('renderOnly.getCompound("pipe").putInt("con", entry.getValue())', source)
                self.assertNotIn('saved.getCompound("pipe").putInt("con"', source)
                self.assertNotIn('world.uploadSnapshot(', source)
                self.assertNotIn('schematic.buildWithoutChecks(', source)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java runtime/JDK unavailable')
    def test_actual_packing_method_emits_six_symmetric_two_bit_faces(self):
        source = read_materialized_java(TARGETS[0], LOGICAL)
        expression = re.search(
            r'private static int previewConnectionBits\(Direction face, boolean pipeNeighbour\)\s*\{' r'[^}]*\}',
            source)
        self.assertIsNotNone(expression, 'Expected packed mask helper in effective source')
        test_source = '''public class PipePreviewMaskTest {
            enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
            %s
            public static void main(String[] args) {
                for (Direction direction : Direction.values()) {
                    int shift = direction.ordinal() * 2;
                    int maskPipe = previewConnectionBits(direction, true);
                    int maskTile = previewConnectionBits(direction, false);
                    if (maskPipe != (1 << shift) || maskTile != (2 << shift))
                        throw new AssertionError("Incorrect side mask: " + direction);
                    if ((maskPipe & maskTile) != 0)
                        throw new AssertionError("Pipe/tile encodings overlap");
                }
                int threeWay = previewConnectionBits(Direction.NORTH, true)
                    | previewConnectionBits(Direction.SOUTH, true)
                    | previewConnectionBits(Direction.UP, false);
                if (threeWay != ((1 << 4) | (1 << 6) | (2 << 2)))
                    throw new AssertionError("Incorrect mixed three-way mask");
            }
        }''' % expression.group(0)
        with tempfile.TemporaryDirectory(prefix='bcce-preview-mask-') as temporary:
            src = Path(temporary) / 'PipePreviewMaskTest.java'
            src.write_text(test_source, encoding='utf-8')
            subprocess.run(['javac', str(src)], cwd=temporary, check=True,
                           capture_output=True, text=True, timeout=25)
            subprocess.run(['java', '-cp', temporary, 'PipePreviewMaskTest'],
                           cwd=temporary, check=True, capture_output=True, text=True, timeout=25)


if __name__ == '__main__':
    unittest.main()
