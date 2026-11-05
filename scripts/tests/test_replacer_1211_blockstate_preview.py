"""Ensure placed block geometry, not item sprites, is used in 1.21.1 Replacer preview."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

RENDERER = 'src/main/java/buildcraft/builders/client/render/BlueprintGuiRenderer.java'


class Replacer1211BlockStatePreviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.file = resolve_target_source('1.21.1-neoforge', RENDERER)
        cls.code = read_materialized_java('1.21.1-neoforge', RENDERER)

    def test_1211_selects_neoforge_blockstate_renderer(self):
        self.assertIn('1.21.1/neoforge/', self.file.as_posix())
        self.assertIn('net.neoforged.neoforge.client.model.data.ModelData', self.code)
        self.assertNotIn('net.neoforged.neoforge.model.data.ModelData', self.code)

    def test_rendershape_resolves_to_1211_minecraft_block_enum(self):
        # This must be an explicit type import: an unqualified RenderShape.MODEL
        # compiles only if the enum is in scope, not merely mentioned in tests.
        self.assertIn('import net.minecraft.world.level.block.RenderShape;', self.code)
        self.assertIn('state.getRenderShape() == RenderShape.MODEL', self.code)

    def test_placed_blocks_render_with_actual_state_and_cell_origin(self):
        text = self.code
        self.assertIn('state = cell.getBlockStateForRender()', text)
        self.assertIn('if (!itemOnly && canRenderBlockModel(state))', text)
        self.assertIn('pose.translate(at.getX(), at.getY(), at.getZ());', text)
        self.assertIn('mc.getBlockRenderer().renderSingleBlock(state, pose, buffers,', text)
        self.assertIn('15728880, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null)', text)
        self.assertIn('state.getRenderShape() == RenderShape.MODEL', text)
        self.assertIn('&& !(state.getBlock() instanceof BlockEngineBase_BC8)', text)
        self.assertIn('&& !(state.getBlock() instanceof BlockPipeHolder)', text)

    def test_item_center_is_only_a_fallback_for_special_blocks(self):
        text = self.code
        assert_state = text.index('if (!itemOnly && canRenderBlockModel(state))')
        assert_item = text.index('ItemStack visual = models.computeIfAbsent(cell', assert_state)
        self.assertLess(assert_state, assert_item)
        self.assertIn('if (visual.isEmpty()) continue;', text[assert_item:])
        self.assertIn('engineRotation(state)', text)

    def test_templates_and_replacement_overlay_remain_unchanged(self):
        code = self.code
        self.assertIn('snapshot instanceof Template template', code)
        self.assertIn('template.data.get(i)', code)
        self.assertIn('snapshot instanceof Blueprint bp', code)
        self.assertIn('snapshot.indexToPos(i)', code)
        repl = (ROOT / 'source-families/1.21.X/src/main/java/buildcraft/builders/gui/ReplacerGuiPreview.java').read_text()
        self.assertIn('Blueprint candidate = latest.copy()', repl)
        self.assertIn('candidate.replace(find, replacement)', repl)

    def test_modern_renderer_remains_separate_and_old_untouched(self):
        modern = read_materialized_java('1.21.11-neoforge',
            'src/main/java/buildcraft/builders/client/render/pip/BlueprintPipRenderer.java')
        self.assertIn('PreviewBlockModelRenderer.renderBlock', modern)
        old_family = ROOT / 'source-families/old/src/main/java/buildcraft/builders/client/render/RenderSnapshotBuilder.java'
        self.assertTrue(old_family.exists())
        self.assertNotIn('BlueprintGuiRenderer', old_family.read_text())


if __name__ == '__main__':
    unittest.main()
