#!/usr/bin/env python3
"""Guard BCU-like in-progress Filler construction effects on modern targets.

Checks resolved version sources and Java syntax. This does not compile Minecraft
nor replace runtime client visual smoke tests.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, preprocess_text
from minecraft_compat_fixture import parse_sources

MODERN = ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge',
          '26.2-neoforge', '26.3-neoforge')


def resolved_source(target: str, relative: str) -> str:
    props = load_properties()
    layout = target_layout(target, props)
    path = resolve_effective_source(layout, props, f'src/main/java/buildcraft/{relative}')
    if path is None:
        raise AssertionError(f'{target}: missing {relative}')
    return preprocess_text(path.read_text(encoding='utf-8'),
                           minecraft=props[f'target.{target}.deps.minecraft'],
                           family=layout.family, platform=layout.platform,
                           source=str(path))


class FillerConstructionVisualsTests(unittest.TestCase):
    def test_filler_uses_real_robot_and_progress_beams_not_task_cell_boxes(self):
        for target in MODERN:
            with self.subTest(target=target):
                filler = resolved_source(target, 'builders/client/render/RenderFiller.java')
                work = resolved_source(target, 'builders/client/render/FillerWorkRenderer.java')
                self.assertIn('FillerWorkRenderer.render(tile.getBuilder()', filler)
                self.assertNotIn('BuilderTaskHighlights.render(', filler)
                self.assertIn('BCBuildersSprites.ROBOT.getInterpU', work)
                self.assertIn('ModelUtil.createFace(', work)
                self.assertIn('MAX_BREAK_BEAMS = 64', work)
                self.assertIn('BuildCraftLaserManager.POWERS[index]', work)
                self.assertIn('clientBreakTasks.isEmpty()', work)
                self.assertNotIn('renderLaserBoxDynamic(', work)
                self.assertNotIn('clientPlaceTasks', work)

    def test_filler_flying_blocks_are_scaled_without_affecting_builder(self):
        for target in MODERN:
            with self.subTest(target=target):
                filler = resolved_source(target, 'builders/client/render/RenderFiller.java')
                renderer = resolved_source(target, 'builders/client/render/RenderSnapshotBuilder.java')
                builder = resolved_source(target, 'builders/client/render/RenderBuilder.java')
                if target == '1.21.1-neoforge':
                    self.assertIn('RenderSnapshotBuilder.renderFiller(', filler)
                    self.assertIn('renderSingleBlock(', renderer)
                    self.assertIn('matrix.scale(.30F, .30F, .30F)', renderer)
                    self.assertIn('RenderSnapshotBuilder.render(', builder)
                else:
                    self.assertIn('RenderSnapshotBuilder.submitFiller(', filler)
                    self.assertIn('matrix.scale(.30F, .30F, .30F)', renderer)
                    self.assertIn('ItemDisplayContext.FIXED', renderer)
                    self.assertIn('RenderSnapshotBuilder.submit(', builder)
                self.assertIn('findFirst().orElse(current)', renderer)
                self.assertNotIn('Collections.emptyList()', renderer)

    def test_white_projection_keeps_its_independent_render_path(self):
        for target in MODERN:
            with self.subTest(target=target):
                filler = resolved_source(target, 'builders/client/render/RenderFiller.java')
                self.assertIn('FillerGhostPreview.render(', filler)
                self.assertIn('tile.isPreviewEnabled()', filler) if target != '1.21.1-neoforge' else None

    def test_1211_block_model_uses_legacy_neoforge_modeldata_package(self):
        # NeoForge 1.21.1 uses client.model.data; the unprefixed model.data
        # package was introduced for later targets. Syntax-only tests miss this.
        renderer = resolved_source('1.21.1-neoforge', 'builders/client/render/RenderSnapshotBuilder.java')
        self.assertIn('import net.neoforged.neoforge.client.model.data.ModelData;', renderer)
        self.assertIn('ModelData.EMPTY, null);', renderer)
        self.assertNotIn('net.neoforged.neoforge.model.data', renderer)

    def test_java_syntax_after_target_preprocessor(self):
        paths = ('builders/client/render/RenderFiller.java',
                 'builders/client/render/FillerWorkRenderer.java',
                 'builders/client/render/RenderSnapshotBuilder.java')
        for target in MODERN:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='filler-visual-') as tmp:
                files = []
                for relative in paths:
                    path = Path(tmp) / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(resolved_source(target, relative), encoding='utf-8')
                    files.append(path)
                self.assertIn('0 errors', parse_sources(files))


if __name__ == '__main__':
    unittest.main()
