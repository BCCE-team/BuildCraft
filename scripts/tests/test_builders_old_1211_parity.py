#!/usr/bin/env python3
"""Legacy Forge Builders parity with the 1.21.1 behavioral baseline.

Source-boundary and Java-grammar checks; NOT substitute for Gradle or GameTests.
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

OLD = ('1.19.2-forge', '1.20.1-forge')
BASE = 'src/main/java/buildcraft/builders/'
JAVA = (
    'client/render/FillerGhostPreview.java',
    'client/render/FillerWorkRenderer.java',
    'client/render/RenderSnapshotBuilder.java',
    'client/render/RenderFiller.java',
    'client/render/RenderQuarry.java',
    'client/render/RenderArchitectTable.java',
    'client/render/BuilderIndicatorLights.java',
    'client/render/LegacyBlueprintPreviewScene.java',
    'client/render/LegacyBlueprintGuiRenderer.java',
    'client/tooltip/LegacyBlueprintTooltipOverlay.java',
    'snapshot/RequiredExtractorItemFromBlock.java',
    'snapshot/BlueprintBuilder.java',
    'snapshot/SchematicEntityDefault.java',
    'snapshot/MessageSnapshotResponse.java',
    'tile/TileFiller.java',
    'tile/TileReplacer.java',
    'gui/MenuReplacer.java',
    'gui/ReplacerGuiPreview.java',
    'gui/ScreenReplacer.java',
    'gui/GuiBuilder.java',
)


def effective(target: str, name: str) -> str:
    return read_materialized_java(target, BASE + name)


class LegacyForgeBuildersParityTests(unittest.TestCase):
    def test_all_legacy_sources_materialize_and_parse_for_both_versions(self):
        for target in OLD:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-old-builders-') as td:
                paths = []
                for logical in JAVA:
                    file = Path(td) / logical
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(effective(target, logical))
                    paths.append(file)
                result = parse_sources(paths)
                self.assertIn('0 errors', result, result)

    def test_old_source_layers_do_not_override_neoforge(self):
        for target in OLD:
            for logical in ('client/render/LegacyBlueprintGuiRenderer.java',
                            'client/render/LegacyBlueprintPreviewScene.java',
                            'client/render/FillerGhostPreview.java',
                            'gui/ReplacerGuiPreview.java'):
                with self.subTest(target=target, java=logical):
                    selected = resolve_target_source(target, BASE + logical)
                    expected_layer = 'source-platforms/forge/' if logical.endswith('LegacyBlueprintPreviewScene.java') else 'source-families/old/'
                    self.assertIn(expected_layer, selected.as_posix())
        for target in ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge'):
            for logical in ('client/render/LegacyBlueprintGuiRenderer.java',
                            'client/render/LegacyBlueprintPreviewScene.java'):
                with self.subTest(target=target, java=logical):
                    selected = resolve_target_source(target, BASE + logical)
                    self.assertFalse(selected.exists(), selected)

    def test_filler_ghost_robot_and_indicators(self):
        for target in OLD:
            with self.subTest(target=target):
                ghost = effective(target, 'client/render/FillerGhostPreview.java')
                robot = effective(target, 'client/render/FillerWorkRenderer.java')
                lamps = effective(target, 'client/render/BuilderIndicatorLights.java')
                filler = effective(target, 'client/render/RenderFiller.java')
                tile = effective(target, 'tile/TileFiller.java')
                self.assertIn('FILLER_PREVIEW_WHITE.getSprite()', ghost)
                self.assertIn('AddonQuadRenderer.box(', ghost)
                self.assertIn('getPreviewBuildingInfo()', ghost)
                self.assertIn('MAX_CUBES = 1024', ghost)
                self.assertIn('ROBOT.getInterpU(', robot)
                self.assertIn('renderLaserDynamic(', robot)
                self.assertIn('tile.getLevel().getGameTime()', filler)
                self.assertIn('RenderSnapshotBuilder.renderFiller(', filler)
                self.assertIn('indicatorLights.drawFiller(', filler)
                self.assertIn('drawQuarry(', lamps)
                self.assertIn('drawArchitect(', lamps)
                self.assertIn('clientPreviewBuildingInfo', tile)
                self.assertIn('private static final int RENDER_UPDATE_INTERVAL = 1', tile)

    def test_engine_costs_and_placed_engine_model(self):
        for target in OLD:
            with self.subTest(target=target):
                cost = effective(target, 'snapshot/RequiredExtractorItemFromBlock.java')
                preview = effective(target, 'client/render/LegacyBlueprintGuiRenderer.java')
                for token in ('instanceof BlockEngine_BC8 engine',
                              'getValue(engine.getEngineProperty())',
                              'BCCoreItems.ENGINE_ITEM_MAP.get(variant)'):
                    self.assertIn(token, cost)
                self.assertLess(cost.index('BCCoreItems.ENGINE_ITEM_MAP.get(variant)'),
                                cost.index('getCloneItemStack('))
                self.assertIn('be instanceof TileEngineBase_BC8 engine', preview)
                self.assertIn('engine.getModelData()', preview)
                self.assertIn('getBlockEntityRenderDispatcher().getRenderer(blockEntity)', preview)
                self.assertIn('renderer.render(blockEntity, 0.0f', preview)
                self.assertNotIn('getBlockEntityRenderDispatcher().render(be,', preview)

    def test_replacer_what_if_with_protected_server_click(self):
        for target in OLD:
            with self.subTest(target=target):
                screen = effective(target, 'gui/ScreenReplacer.java')
                client = effective(target, 'gui/ReplacerGuiPreview.java')
                tile = effective(target, 'tile/TileReplacer.java')
                menu = effective(target, 'gui/MenuReplacer.java')
                self.assertIn('LegacyBlueprintGuiRenderer.render(', screen)
                self.assertIn('menu.sendReplaceRequest(nameField.getValue())', screen)
                self.assertIn('summaryText()', screen)
                self.assertNotIn('getPreviewStacks(', screen)
                self.assertIn('Blueprint candidate = latest.copy()', client)
                self.assertIn('candidate.replace(find, replacement)', client)
                self.assertIn('new SchematicBlockAir()', client)
                self.assertNotIn('persist(', client)
                self.assertIn('void doReplace(String requestedName)', tile)
                self.assertNotIn('public void update()', tile)
                self.assertNotIn('invSchematicFrom.extractItem(', tile)
                self.assertNotIn('invSchematicTo.extractItem(', tile)
                self.assertIn('SnapshotCreationPersistence.persist(', tile)
                for token in ('sender.containerMenu == this',
                              '!sender.isSpectator()', 'stillValid(sender)',
                              'buffer.readUtf(MAX_BLUEPRINT_NAME_LENGTH)',
                              'side == BCNetworkSide.SERVER'):
                    self.assertIn(token, menu)

    def test_read_only_preview_states_tiles_pipes_and_entities(self):
        for target in OLD:
            with self.subTest(target=target):
                scene = effective(target, 'client/render/LegacyBlueprintPreviewScene.java')
                render = effective(target, 'client/render/LegacyBlueprintGuiRenderer.java')
                for token in ('new FakeWorld(client)',
                              'BlueprintSecurity.forBlockEntity(',
                              'BlueprintSecurity.forPipe(',
                              'BlockEntity.loadStatic(',
                              'world.setBlock(', 'world.setBlockEntity(',
                              'minecraft:item_frame', 'minecraft:glow_item_frame',
                              'minecraft:armor_stand', 'minecraft:painting',
                              'FakeWorld.BLUEPRINT_OFFSET',
                              'MAX_ENTITIES = 256'):
                    self.assertIn(token, scene)
                for unsafe in ('buildWithoutChecks(', 'world.superLevel.setBlock(',
                               'world.addFreshEntity(', 'world.uploadSnapshot('):
                    self.assertNotIn(unsafe, scene)
                self.assertIn('instanceof TilePipeHolder pipe', render)
                self.assertIn('pipe.getModelData()', render)
                self.assertIn('getEntityRenderDispatcher().render(', render)
                self.assertIn('getBlockStateForRender()', render)
                self.assertIn('MAX_CELLS = 8192', render)

    def test_entity_placement_cost_match_and_network_codec(self):
        for target in OLD:
            with self.subTest(target=target):
                builder = effective(target, 'snapshot/BlueprintBuilder.java')
                schematic = effective(target, 'snapshot/SchematicEntityDefault.java')
                codec = effective(target, 'snapshot/MessageSnapshotResponse.java')
                self.assertIn('ENTITY_ACTION_MICRO_MJ = 4L * MjAmount.MICRO_MJ_PER_MJ', builder)
                self.assertIn('chargeEntityAction()', builder)
                self.assertIn('hasEntityActionPower()', builder)
                self.assertNotIn('tile.getBattery().isFull()', builder)
                self.assertIn('matchesPlacedEntity(', schematic)
                self.assertIn('hanging.getPos().equals(basePos.offset(hangingPos))', schematic)
                self.assertIn('ItemStack.isSameItemSameTags(', schematic)
                self.assertIn('frame.getRotation()', schematic)
                self.assertIn('input.read() != -1', codec)
                self.assertIn('new GZIPInputStream(byteStream)', codec)
                self.assertIn('MAX_UNCOMPRESSED_SNAPSHOT_BYTES', codec)

    def test_architect_and_builder_have_no_gui_3d_preview(self):
        for target in OLD:
            with self.subTest(target=target):
                for logical in ('gui/GuiBuilder.java', 'gui/GuiArchitectTable.java'):
                    screen = effective(target, logical)
                    self.assertNotIn('BlueprintScreenPreview.draw(', screen)
                    self.assertNotIn('LegacyBlueprintGuiRenderer.render(', screen)

    def test_matrix_and_button_compat_per_version(self):
        old = effective(OLD[0], 'client/render/LegacyBlueprintGuiRenderer.java')
        new = effective(OLD[1], 'client/render/LegacyBlueprintGuiRenderer.java')
        screen_old = effective(OLD[0], 'gui/ScreenReplacer.java')
        screen_new = effective(OLD[1], 'gui/ScreenReplacer.java')
        self.assertIn('Vector3f.XP.rotationDegrees', old)
        self.assertNotIn('Axis.XP.rotationDegrees', old)
        self.assertIn('Axis.XP.rotationDegrees', new)
        self.assertNotIn('Vector3f.XP.rotationDegrees', new)
        self.assertIn('new Button(', screen_old)
        self.assertIn('Button.builder(', screen_new)
        self.assertIn('guiGraphics.enableScissor(', screen_new)
        self.assertIn('RenderSystem.enableScissor(', old)


if __name__ == '__main__':
    unittest.main()
