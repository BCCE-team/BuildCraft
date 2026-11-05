"""Regression checks for the 1.21.1 Replacer block-entity preview.

These are source-level safety contracts, NOT an integrated Minecraft render test.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

BASE = 'src/main/java/buildcraft/builders/client/render/'


class BlockEntityPreview1211Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.renderer = read_materialized_java('1.21.1-neoforge', BASE + 'BlueprintGuiRenderer.java')
        cls.scene = read_materialized_java('1.21.1-neoforge', BASE + 'BlueprintBlockEntityScene.java')

    def test_only_1211_neoforge_picks_scene(self):
        src = resolve_target_source('1.21.1-neoforge', BASE + 'BlueprintBlockEntityScene.java')
        self.assertIn('1.21.1/neoforge/', src.as_posix())
        self.assertNotIn('BlueprintBlockEntityScene', read_materialized_java(
            '1.21.11-neoforge', BASE + 'pip/BlueprintPipRenderer.java'))

    def test_keeps_state_model_and_ber_rendering_separate(self):
        r = self.renderer
        self.assertIn('mc.getBlockRenderer().renderSingleBlock(state, pose, buffers,', r)
        self.assertIn('state.hasBlockEntity()', r)
        self.assertIn('scene.blockEntityAt(at)', r)
        self.assertIn('blockEntityRenderer.renderItem(be, pose, buffers,', r)
        self.assertIn('if (!noRenderer) {', r)
        self.assertIn('drewWorldGeometry = true;', r)
        self.assertIn('if (drewWorldGeometry) continue;', r)

    def test_never_hides_unrenderable_beds_chests_signs(self):
        r = self.renderer
        self.assertNotIn('state.getRenderShape() != RenderShape.MODEL) continue;', r)
        self.assertNotIn('&& state.getRenderShape() != RenderShape.MODEL) continue;', r)
        before = r.index('if (drewWorldGeometry) continue;')
        fallback = r.index('ItemStack visual = models.computeIfAbsent(cell', before)
        self.assertLess(before, fallback)
        self.assertIn('renderer.renderStatic(visual,', r[fallback:])

    def test_fake_world_stages_neighbours_before_entities(self):
        s = self.scene
        self.assertIn('new FakeWorld(client)', s)
        self.assertIn('for (Direction side : Direction.values())', s)
        self.assertIn('staged.add(blueprint.posToIndex(neighbour))', s)
        self.assertLess(s.index('// Stage block states before BE data'),
                        s.index('block.newBlockEntity(worldPos, state)'))

    def test_nbt_failure_cannot_remove_block_entity(self):
        s = self.scene
        register = s.index('blockEntities.put(blueprint.indexToPos(index).immutable(), be);')
        load = s.index('be.loadWithComponents(safe, level.registryAccess());')
        self.assertLess(register, load)
        self.assertIn('catch (RuntimeException invalidSavedData)', s)
        self.assertIn('BlockEntity fresh = block.newBlockEntity(worldPos, state);', s)
        self.assertIn('return blockEntities.get(position);', s)

    def test_sanitizes_nbt_and_never_mutates_real_level(self):
        s = self.scene
        r = self.renderer
        self.assertIn('BlueprintSecurity.forBlockEntity(state, nbt)', s)
        self.assertIn('BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())', s)
        self.assertNotIn('buildWithoutChecks(', s.split('private BlueprintBlockEntityScene(')[1])
        for forbidden in ('mc.level.setBlock(', 'mc.level.setBlockEntity('):
            self.assertNotIn(forbidden, r)
            self.assertNotIn(forbidden, s)

    def test_gui_depth_is_cleared_inside_scissor(self):
        r = self.renderer
        self.assertLess(r.index('graphics.enableScissor('), r.index('RenderSystem.clear('))
        self.assertLess(r.index('RenderSystem.clear('), r.index('pose.pushPose();'))
        self.assertIn('buffers.endBatch();', r)
        self.assertIn('graphics.disableScissor();', r)


if __name__ == '__main__':
    unittest.main()
