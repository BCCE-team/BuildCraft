"""Regression guards for BCU-parity Architect Table green scan cubes."""

from __future__ import annotations

from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_lookup import read_materialized_java, resolve_target_source

TILE = "src/main/java/buildcraft/builders/tile/TileArchitectTable.java"
EVENTS = "src/main/java/buildcraft/builders/BCBuildersEventDist.java"
GEOMETRY = "src/main/java/buildcraft/builders/client/render/ArchitectScanGeometry.java"
RENDERER = "src/main/java/buildcraft/builders/client/render/ArchitectScanWorldRenderer.java"
TARGETS = (
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
    "26.2-neoforge",
    "26.3-neoforge",
)


class ArchitectScanDigitizingTests(unittest.TestCase):
    def test_scan_batches_every_position_and_bounds_receive(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, TILE)
                self.assertIn("scanned.add(scanSingleBlock())", source)
                self.assertIn("buffer.writeVarInt(scanned.size())", source)
                self.assertIn("MessageUtil.writeBlockPos(buffer, position)", source)
                self.assertIn("int count = buffer.readVarInt()", source)
                self.assertIn("count < 0 || count > bound", source)
                self.assertNotIn("BlockPos lastScanned", source)

    def test_world_renderer_is_registered_and_world_state_clears(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, EVENTS)
                self.assertIn("ArchitectScanWorldRenderer.INSTANCE", source)
                self.assertIn("RenderMatrixType.FROM_WORLD_ORIGIN", source)
                self.assertIn("ClientArchitectTables.SCANNED_BLOCKS.clear()", source)

    def test_world_cubes_use_green_scan_texture_and_fade(self):
        for target in TARGETS:
            with self.subTest(target=target):
                renderer = read_materialized_java(target, RENDERER)
                geometry = read_materialized_java(target, GEOMETRY)
                self.assertIn("buildcraftbuilders:textures/blocks/scan.png", renderer)
                self.assertIn("ArchitectScanGeometry.draw(", renderer)
                self.assertIn("Math.min(50, entry.getValue())", geometry)
                self.assertIn(".lighti(15, 15)", geometry)
                self.assertIn("MAX_DRAWN_CELLS", geometry)

    def test_renderer_api_matches_target(self):
        for target in TARGETS:
            with self.subTest(target=target):
                path = resolve_target_source(target, RENDERER).as_posix()
                source = read_materialized_java(target, RENDERER)
                if target == "1.21.1-neoforge":
                    self.assertIn("source-downports/1.21.X/1.21.1/", path)
                    self.assertIn("RenderType.entityTranslucent", source)
                    self.assertNotIn("import buildcraft.lib.compat.RenderCompat", source)
                elif target == "26.1.2-neoforge":
                    self.assertIn("source-downports/26.X/26.1.2/", path)
                    self.assertIn("source.endBatch(layer)", source)
                    self.assertNotIn("BCWorldGeometry.buffer", source)
                elif target.startswith("26."):
                    self.assertIn("source-families/26.X/", path)
                    self.assertIn("BCWorldGeometry.buffer", source)
                    self.assertNotIn("source.endBatch", source)
                else:
                    self.assertIn("source-families/1.21.X/", path)
                    self.assertIn("RenderCompat.entityTranslucent", source)


if __name__ == "__main__":
    unittest.main()
