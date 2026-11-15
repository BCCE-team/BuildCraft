#!/usr/bin/env python3
"""BCCE Builder regression guards for all supported Forge/NeoForge versions."""
from __future__ import annotations

import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORGE = ('1.19.2-forge', '1.20.1-forge')
NEOFORGE = ('1.21.1-neoforge', '1.21.11-neoforge',
            '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
SNAP = 'src/main/java/buildcraft/builders/snapshot/SnapshotBuilder.java'


class BuilderStallRegressionTests(unittest.TestCase):
    def test_real_targets_resolve_fixed_snapshots(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source

        properties = load_properties()
        self.assertEqual(tuple(t for t in properties['targets'].split(',')
                               if not t.endswith('-fabric')), FORGE + NEOFORGE)
        for target in FORGE + NEOFORGE:
            with self.subTest(target=target):
                path = resolve_effective_source(target_layout(target, properties), properties, SNAP)
                self.assertIsNotNone(path)
                source = path.read_text(encoding='utf-8')
                self.assertIn('Only executable excavation tasks should delay placements', source)
                self.assertIn('if (!tile.canExcavate() || breakTasks.isEmpty()) {', source)
                self.assertIn('!BlockUtil.isUnbreakableBlock(', source)
                self.assertIn('BlockUtil.getFluidWithFlowing(', source)
                self.assertIn('return unknownCheckResults == 0 && correctCheckResults == checkResults.length;',
                              source, 'Unresolved obstructions must NOT finish the blueprint')
                if target in FORGE:
                    self.assertIn('A missing support is not an excavation target.', source)
                    self.assertIn('if (!tile.canExcavate() && !breakTasks.isEmpty()) {', source)
                else:
                    self.assertIn('A missing support or an occupied second half', source)

    def test_forge_support_check_classifies_empty_position_as_place(self):
        for target in FORGE:
            path = ROOT / f'version-src/{target}' / SNAP
            text = path.read_text(encoding='utf-8')
            support_case = re.search(
                r'else if \(!tile\.getWorldBC\(\)\.isEmptyBlock\(blockPos\)\) '\
                r'\{[^{}]*CHECK_RESULT_TO_BREAK;[^{}]*\} else '\
                r'\{[^{}]*A missing support[^{}]*CHECK_RESULT_TO_PLACE;[^{}]*\}',
                text, re.S)
            self.assertIsNotNone(support_case, str(path))

    def test_materialized_java_syntax_all_seven_targets(self):
        # Stonecutter selects different owners, then strips conditional code.
        # Compile-agnostic Java parser verifies the effective source, not only
        # the canonical overlay text. Full Gradle/GameTests remain required.
        sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
        from source_config import load_properties, target_layout
        from source_layout import resolve_effective_source, _materialize_text_file
        from minecraft_compat_fixture import parse_sources

        properties = load_properties()
        with tempfile.TemporaryDirectory(prefix='bcce-builder-stall-') as tmp:
            for target in FORGE + NEOFORGE:
                with self.subTest(target=target):
                    layout = target_layout(target, properties)
                    source = resolve_effective_source(layout, properties, SNAP)
                    self.assertIsNotNone(source)
                    dst = Path(tmp) / target / SNAP
                    downports = tuple(root for root in (
                        layout.family_downport_root,
                        layout.family_platform_downport_root,
                    ) if root)
                    _materialize_text_file(
                        source, dst, logical_relative=SNAP,
                        minecraft=properties[f'target.{target}.deps.minecraft'],
                        family=layout.family, platform=layout.platform, preprocess=True,
                        native_source=any(source.is_relative_to(root) for root in downports),
                    )
                    self.assertIn('0 errors', parse_sources([dst]))

    def test_no_ui_switches_or_fabric_gameplay_changed(self):
        for path in (ROOT / 'source-families/26.X/src/main/java/buildcraft/builders/gui/GuiBuilder.java',
                     ROOT / 'source-families/26.X/src/main/java/buildcraft/builders/gui/GuiFiller.java'):
            self.assertNotIn('runningButton', path.read_text(encoding='utf-8'))
        for path in (ROOT / 'version-src/1.19.2-forge/src/main/java/buildcraft/builders/tile/TileBuilder.java',
                     ROOT / 'version-src/1.20.1-forge/src/main/java/buildcraft/builders/tile/TileBuilder.java',
                     ROOT / 'source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileBuilder.java'):
            self.assertNotIn('NET_RUNNING', path.read_text(encoding='utf-8'))

    def test_in_world_gametests_registered_on_both_platforms(self):
        for platform in ('forge', 'neoforge'):
            path = ROOT / f'source-platforms/{platform}/src/gametest/java/buildcraft/gametest/SystemBuilderLifecycleGameTests.java'
            content = path.read_text(encoding='utf-8')
            self.assertIn('void unbreakableTemplateCellDoesNotStarveIndependentPlacement(', content)
            self.assertIn('void liquidObstructionDoesNotStarveIndependentPlacement(', content)
            self.assertIn('Builder falsely completed a blueprint with unresolved excavation', content)


if __name__ == '__main__':
    unittest.main()
