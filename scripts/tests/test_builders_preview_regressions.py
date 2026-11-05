#!/usr/bin/env python3
"""Builder/Architect graphical parity regressions in effective Stonecutter sources.

Static contracts only; they do not stand in for a NeoForge runtime renderer test.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java, resolve_target_source

ARCHITECT_GUI = 'src/main/java/buildcraft/builders/gui/GuiArchitectTable.java'
ARCHITECT_TILE = 'src/main/java/buildcraft/builders/tile/TileArchitectTable.java'
SCHEMATIC = 'src/main/java/buildcraft/builders/snapshot/SchematicBlockDefault.java'
PIP = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPipRenderer.java'
BUILDER_GUI = 'src/main/java/buildcraft/builders/gui/GuiBuilder.java'
FILLER = 'src/main/java/buildcraft/builders/client/render/RenderFiller.java'
GHOST = 'src/main/java/buildcraft/builders/client/render/FillerGhostPreview.java'


def effective(target: str, logical: str) -> str:
    return read_materialized_java(target, logical)


class BuildersPreviewRegressionTests(unittest.TestCase):
    def test_architect_table_never_renders_offscreen_3d_preview(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge',
                       '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                gui = effective(target, ARCHITECT_GUI)
                for forbidden in ('BlueprintScreenPreview.draw(', 'ClientArchitectPreview.get(',
                                  'ClientArchitectPreview.tick(', 'ClientArchitectPreview.clear(',
                                  'BlueprintRenderer.renderSnapshot('):
                    self.assertNotIn(forbidden, gui)
                self.assertIn('drawProgress(guiGraphics, RECT_PROGRESS, ICON_PROGRESS,', gui)
                self.assertIn('sendSettingsToServer()', gui)

    def test_architect_scanning_and_output_workflow_remains_untouched(self):
        for target in ('1.21.11-neoforge', '26.2-neoforge'):
            with self.subTest(target=target):
                tile = effective(target, ARCHITECT_TILE)
                for required in ('scanMultipleBlocks();', 'finishScanning();',
                                 'SnapshotCreationPersistence.persist(',
                                 'invSnapshotOut.setStackInSlot(0, ItemSnapshot.getUsed(',
                                 'scanEntities();'):
                    self.assertIn(required, tile)

    def test_modern_schematic_exposes_captured_block_state_for_render(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge',
                       '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                schematic = effective(target, SCHEMATIC)
                self.assertIn('public BlockState getBlockStateForRender()', schematic)
                method_body = schematic.split('public BlockState getBlockStateForRender()', 1)[1].split('}', 1)[0]
                self.assertIn('return blockState;', method_body)
                self.assertNotIn('defaultBlockState()', method_body)
                self.assertIn('blockState = context.blockState;', schematic)
                self.assertIn('blockState.rotate(rotation)', schematic)
                self.assertIn('"blockState"', schematic)

    def test_snapshot_render_path_reaches_block_geometry_and_block_entities(self):
        code = effective('1.21.11-neoforge', PIP)
        self.assertIn('block.getBlockStateForRender()', code)
        self.assertIn('renderedState.getRenderShape() == RenderShape.MODEL', code)
        self.assertIn('PreviewBlockModelRenderer.renderBlock(', code)
        self.assertIn('renderedState.hasBlockEntity()', code)
        self.assertIn('scene.submitBlockEntity(at, poseStack, storage, previewCamera)', code)
        self.assertLess(code.index('PreviewBlockModelRenderer.renderBlock('),
                        code.index('TrackingItemStackRenderState item = models.get(block);'))
        self.assertLess(code.index('scene.submitBlockEntity(at, poseStack, storage, previewCamera)'),
                        code.index('TrackingItemStackRenderState item = models.get(block);'))
        # Only the Architect GUI loses the extra viewport, not Builder's blueprint view.
        builder = effective('1.21.11-neoforge', BUILDER_GUI)
        self.assertIn('BlueprintScreenPreview.draw(', builder)

    def test_12111_captured_state_completes_missing_platform_contract(self):
        before = effective('1.21.1-neoforge', SCHEMATIC)
        after = effective('1.21.11-neoforge', SCHEMATIC)
        self.assertIn('return blockState;', before.split('public BlockState getBlockStateForRender()', 1)[1].split('}', 1)[0])
        self.assertIn('return blockState;', after.split('public BlockState getBlockStateForRender()', 1)[1].split('}', 1)[0])
        path = resolve_target_source('1.21.11-neoforge', SCHEMATIC).as_posix()
        self.assertIn('source-family-platforms/1.21.X/neoforge/', path)

    def test_filler_world_preview_uses_white_sprite_and_block_atlas(self):
        ghost = effective('1.21.11-neoforge', GHOST)
        filler = effective('1.21.11-neoforge', FILLER)
        self.assertIn('BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite()', ghost)
        self.assertIn('AddonQuadRenderer.box(', ghost)
        self.assertIn('new AABB(cell).inflate(-0.1)', ghost)
        self.assertIn('RenderCompat.entityTranslucent(TextureAtlas.LOCATION_BLOCKS)', filler)
        self.assertNotIn('RenderCompat.translucent()', filler)


if __name__ == '__main__':
    unittest.main()
