#!/usr/bin/env python3
"""1.21.11-only rendering regressions: placed beds, double chests and BuildCraft engines.

This is a source/API-boundary test, not a screenshot or a substitute for compileJava.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java, resolve_target_source
from minecraft_compat_fixture import parse_sources

PREFIX = 'src/main/java/buildcraft/builders/client/render/pip/'
SCENE = PREFIX + 'BlueprintPreviewScene.java'
PIP = PREFIX + 'BlueprintPipRenderer.java'


class Builders12111BlockEntityPreviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene = read_materialized_java('1.21.11-neoforge', SCENE)
        cls.pip = read_materialized_java('1.21.11-neoforge', PIP)

    def test_only_12111_overlay_and_legacy_1211_untouched(self):
        for logical in (SCENE, PIP):
            self.assertIn('source-family-platforms/1.21.X/neoforge/', str(
                resolve_target_source('1.21.11-neoforge', logical)))
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            self.assertNotIn('source-family-platforms/1.21.X/neoforge/', str(
                resolve_target_source(target, PIP)))
        downport = read_materialized_java('1.21.1-neoforge',
            'src/main/java/buildcraft/builders/client/render/BlueprintGuiRenderer.java')
        self.assertIn('BlueprintBlockEntityScene.forSnapshot', downport)

    def test_beds_and_chests_not_silently_culled_by_real_camera(self):
        scene = self.scene
        self.assertIn('BlockEntityRenderer<?, ?> renderer = dispatcher.getRenderer(be);', scene)
        self.assertIn('renderer.createRenderState()', scene)
        self.assertIn('renderer.extractRenderState(be, state, 0.0F,', scene)
        self.assertIn('Vec3.atCenterOf(be.getBlockPos())', scene)
        self.assertIn('dispatcher.submit(state, pose, storage, camera);', scene)
        self.assertNotIn('dispatcher.tryExtractRenderState(be,', scene)
        self.assertIn('state.lightCoords = 15728880;', scene)
        self.assertIn('for (Direction side : Direction.values())', scene)
        self.assertIn('world.setBlockEntity(entity)', scene)

    def test_engine_ber_staged_with_nbt_instead_of_item_or_placeholder(self):
        scene = self.scene
        pip = self.pip
        self.assertIn('return state.getBlock() instanceof BlockPipeHolder;', scene)
        self.assertNotIn('return state.getBlock() instanceof BlockEngineBase_BC8', scene)
        self.assertIn('BlueprintSecurity.forBlockEntity(state, tag)', scene)
        self.assertIn('entity.loadWithComponents(TagValueInput.create(', scene)
        self.assertIn('renderedState.getBlock() instanceof BlockEngineBase_BC8', pip)
        self.assertIn('&& !blockEntityOnly', pip)
        self.assertIn('scene.submitBlockEntity(at, poseStack, storage, previewCamera)', pip)

    def test_animated_blocks_prefer_ber_and_have_visible_item_fallback(self):
        pip = self.pip
        self.assertIn('renderedState.getBlock() instanceof AbstractChestBlock', pip)
        self.assertIn('renderedState.getBlock() instanceof BedBlock', pip)
        self.assertLess(pip.index('scene.submitBlockEntity(at,'),
                        pip.index('PreviewBlockModelRenderer.renderBlock('))
        self.assertIn('if (submittedBlockEntity || drawnBlockModel) continue;', pip)
        self.assertLess(pip.index('if (submittedBlockEntity || drawnBlockModel) continue;'),
                        pip.index('TrackingItemStackRenderState item = models.get(block);'))
        self.assertIn('ItemDisplayContext.NONE', pip)
        self.assertIn('PreviewPipeModel.reconstruct(', pip)
        self.assertIn('PreviewFluidRenderer.render(', pip)
        self.assertIn('TemplateGhostGeometry.visibleFaces(', pip)

    def test_syntax_after_stonecutter(self):
        with tempfile.TemporaryDirectory(prefix='bcce-be-preview-') as tmp:
            files = []
            for logical in (SCENE, PIP):
                file = Path(tmp) / Path(logical).name
                file.write_text(read_materialized_java('1.21.11-neoforge', logical), encoding='utf8')
                files.append(file)
            self.assertIn('0 errors', parse_sources(files))


if __name__ == '__main__':
    unittest.main()
