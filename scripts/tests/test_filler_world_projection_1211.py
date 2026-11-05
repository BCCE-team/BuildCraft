#!/usr/bin/env python3
"""Guard Filler's world projection on modern NeoForge, especially 1.21.1.

Checks resolved target sources and network payload symmetry; does not substitute
for a full Gradle compile or an in-game rendering test.
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, preprocess_text
from minecraft_compat_fixture import parse_sources

MODERN = ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge',
          '26.2-neoforge', '26.3-neoforge')


def source(target: str, relative: str) -> str:
    props = load_properties()
    layout = target_layout(target, props)
    path = resolve_effective_source(layout, props, 'src/main/java/buildcraft/' + relative)
    if path is None:
        raise AssertionError(f'{target}: missing {relative}')
    return preprocess_text(path.read_text(encoding='utf-8'),
                           minecraft=props[f'target.{target}.deps.minecraft'],
                           family=layout.family, platform=layout.platform,
                           source=str(path))


class FillerWorldProjection1211Tests(unittest.TestCase):
    def test_world_preview_network_payloads_are_symmetric(self):
        for target in MODERN:
            with self.subTest(target=target):
                tile = source(target, 'builders/tile/TileFiller.java')
                self.assertIn('writePayload(NET_PREVIEW, buffer, side);', tile)
                self.assertIn('readPayload(NET_PREVIEW, buffer, side, ctx);', tile)
                self.assertIn('if (previewEnabled) {\n                    writePayload(NET_INVERT, buffer, side);', tile)
                self.assertIn('if (previewEnabled) {\n                    readPayload(NET_INVERT, buffer, side, ctx);', tile)
                self.assertIn('sendNetworkUpdate(NET_RENDER_DATA);', tile)
                self.assertIn('sendNetworkGuiUpdate(NET_PREVIEW);', tile)
                self.assertIn('bcData.writeBoolean("showPreview", previewEnabled);', tile)
                self.assertIn('previewEnabled = bcData.has("showPreview") && bcData.readBoolean("showPreview");', tile)

    def test_preview_template_is_separate_from_active_builder(self):
        for target in MODERN:
            with self.subTest(target=target):
                tile = source(target, 'builders/tile/TileFiller.java')
                renderer = source(target, 'builders/client/render/FillerGhostPreview.java')
                self.assertIn('public Template.BuildingInfo getPreviewBuildingInfo()', tile)
                self.assertIn('clientPreviewPattern.equals(currentPattern)', tile)
                self.assertIn('volume <= 2_000_000L', tile)
                self.assertIn('tile.getPreviewBuildingInfo()', renderer)
                self.assertNotIn('tile.getTemplateBuildingInfo()', renderer)

    def test_1211_ghost_uses_planner_material_and_atlas(self):
        renderer = source('1.21.1-neoforge', 'builders/client/render/FillerGhostPreview.java')
        planner = source('1.21.1-neoforge', 'builders/addon/AddonRendererFillerPlanner.java')
        self.assertIn('buffer.getBuffer(RenderType.translucent())', renderer)
        self.assertIn('AddonQuadRenderer.box(vertices, matrix, new AABB(cell).inflate(-0.1), sprite, 127);', renderer)
        self.assertIn('BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite()', renderer)
        self.assertIn('AddonQuadRenderer.box(vb, pose, bb, s, 127)', planner)
        self.assertNotIn('RenderType.cutout()', renderer)

    def test_preview_button_sends_server_packet(self):
        for target in MODERN:
            with self.subTest(target=target):
                gui = source(target, 'builders/gui/GuiFiller.java')
                self.assertIn('container.tile.sendPreviewEnabled(b.isButtonActive());', gui)

    def test_resolved_java_syntax(self):
        for target in ('1.21.1-neoforge', '1.21.11-neoforge', '26.2-neoforge'):
            with self.subTest(target=target):
                # The syntax probe is language-level only, not dependency resolution.
                paths = ('builders/tile/TileFiller.java',
                         'builders/client/render/FillerGhostPreview.java')
                import tempfile
                with tempfile.TemporaryDirectory(prefix='filler-world-') as tmp:
                    files = []
                    for path in paths:
                        file = Path(tmp) / path
                        file.parent.mkdir(parents=True, exist_ok=True)
                        file.write_text(source(target, path), encoding='utf-8')
                        files.append(file)
                    self.assertIn('0 errors', parse_sources(files))


if __name__ == '__main__':
    unittest.main()
