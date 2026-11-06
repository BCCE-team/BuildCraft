"""Regression guards for BCCE legacy (Forge 1.19.2/1.20.1) blueprint GUI preview.

Source checks complement but cannot substitute Gradle compile and an actual client.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

OLD = ('1.19.2-forge', '1.20.1-forge')
JAVA_BASE = 'src/main/java/buildcraft/builders/'


def java(target, name):
    return read_materialized_java(target, JAVA_BASE + name)


class OldForgeBlueprintGuiPreviewTests(unittest.TestCase):
    def test_old_tooltip_registered_on_client_game_bus(self):
        for target in OLD:
            with self.subTest(target=target):
                builders = java(target, 'BCBuilders.java')
                self.assertIn('Bus.FORGE, value = Dist.CLIENT', builders)
                self.assertIn('LegacyBlueprintTooltipOverlay.onTooltipPre(event)', builders)
                self.assertIn('RenderTooltipEvent.Pre', builders)
                self.assertIn('source-platforms/forge/', resolve_target_source(
                    target, JAVA_BASE + 'client/tooltip/LegacyBlueprintTooltipOverlay.java').as_posix())

    def test_used_snapshot_frame_visible_before_remote_data(self):
        for target in OLD:
            with self.subTest(target=target):
                overlay = java(target, 'client/tooltip/LegacyBlueprintTooltipOverlay.java')
                self.assertIn('ItemSnapshot.getHeader(stack)', overlay)
                self.assertIn('SIZE = 100', overlay)
                self.assertIn('snapshot = ClientSnapshots.INSTANCE.getSnapshot(header.key);', overlay)
                # Snapshot lookup may precede the panel, but missing remote data
                # must not suppress the frame: only the actual model is conditional.
                self.assertIn('if (snapshot != null) LegacyBlueprintGuiRenderer.render(', overlay)
                self.assertNotIn('if (snapshot == null) return;', overlay.split('List<ClientTooltipComponent> components')[0]
                                 .split('if (stack.getItem() instanceof ItemSnapshot)')[1]
                                 .split('} else if (stack.getItem() instanceof ItemSchematicSingle)')[0])
                self.assertNotIn('RenderTooltipEvent.PostText', overlay.replace('there is\n * no PostText event', ''))
                if target.startswith('1.19.'):
                    self.assertIn('event.getPoseStack()', overlay)
                    self.assertNotIn('event.getGraphics()', overlay)
                    self.assertNotIn('Vector2ic', overlay)
                else:
                    self.assertIn('event.getGraphics().pose()', overlay)
                    self.assertIn('getTooltipPositioner().positionTooltip(', overlay)
                    self.assertIn('event.getGraphics().flush()', overlay)
                    self.assertNotIn('GuiComponent.fill', overlay)

    def test_preview_uses_real_block_model_ber_and_pipe_layers(self):
        for target in OLD:
            with self.subTest(target=target):
                renderer = java(target, 'client/render/LegacyBlueprintGuiRenderer.java')
                self.assertIn('getBlockStateForRender()', renderer)
                self.assertIn('RenderShape.MODEL', renderer)
                self.assertIn('RenderType.cutout()', renderer)
                self.assertIn('RenderType.translucent()', renderer)
                self.assertIn('pipe.getModelData()', renderer)
                self.assertIn('engine.getModelData()', renderer)
                self.assertIn('getBlockEntityRenderDispatcher().getRenderer(blockEntity)', renderer)
                self.assertIn('renderer.render(blockEntity, 0.0f', renderer)
                self.assertNotIn('getBlockEntityRenderDispatcher().render(be', renderer)
                self.assertIn('GL_DEPTH_BUFFER_BIT', renderer)
                self.assertIn('RenderSystem.enableScissor(', renderer)
                self.assertIn('RenderSystem.disableScissor()', renderer)

    def test_detached_world_has_neighbours_and_sanitized_tiles(self):
        for target in OLD:
            with self.subTest(target=target):
                scene = java(target, 'client/render/LegacyBlueprintPreviewScene.java')
                self.assertIn('new FakeWorld(client)', scene)
                self.assertIn('staged.add(index);', scene)
                self.assertIn('if (!state.hasBlockEntity()) continue;', scene)
                self.assertIn('staged.add(blueprint.posToIndex(nearby))', scene)
                self.assertIn('BlueprintSecurity.forBlockEntity(', scene)
                self.assertIn('BlueprintSecurity.forPipe(', scene)
                self.assertIn('be.setLevel(world)', scene)
                self.assertIn('world.setBlockEntity(be)', scene)
                self.assertIn('minecraft:item_frame', scene)
                self.assertIn('minecraft:armor_stand', scene)
                self.assertNotIn('buildWithoutChecks(', scene)


if __name__ == '__main__':
    unittest.main()
