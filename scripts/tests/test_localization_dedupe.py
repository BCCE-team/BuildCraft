#!/usr/bin/env python3
"""Guard against regressions when sharing identical BuildCraft translation keys.

This suite validates source contracts and localization data only. It does not
replace the per-target Gradle compilation and Minecraft client checks.

Run: python3 -m unittest discover -s scripts/tests -p test_localization_dedupe.py -v
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java
LANG_FILE = ROOT / 'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json'
MANIFEST_FILE = ROOT / 'scripts/tests/localization_dedupe_manifest.json'

# Check maintained source layers only. Gradle/Stonecutter materializations under
# build/ and builds/ may be stale, duplicated, and are not repository sources.
MAINTAINED_SOURCE_ROOTS = (
    'source-shared', 'source-families', 'source-platforms',
    'source-family-platforms', 'source-downports', 'version-src',
    'resource-src', 'addon-fixture',
)


def maintained_source_files(pattern: str, *, root: Path = ROOT) -> list[Path]:
    return sorted(
        path
        for folder in MAINTAINED_SOURCE_ROOTS
        for path in (root / folder).rglob(pattern)
        if path.is_file()
    )


class LocalizationDeduplicationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.language = json.loads(LANG_FILE.read_text(encoding='utf-8'))
        cls.manifest = json.loads(MANIFEST_FILE.read_text(encoding='utf-8'))
        cls.aliases = cls.manifest['aliases']

    def test_canonical_keys_exist_and_removed_aliases_do_not(self):
        self.assertEqual(len(self.language), self.manifest['final_keys'])
        self.assertEqual(len(self.aliases), self.manifest['removed_keys'])
        for obsolete, canonical in self.aliases.items():
            with self.subTest(obsolete=obsolete):
                self.assertNotIn(obsolete, self.language)
                self.assertIn(canonical, self.language)
                self.assertNotIn(canonical, self.aliases)

    def test_all_explicit_item_names_resolve_to_existing_keys(self):
        item_name_files = maintained_source_files('ItemNameKeys*.java')
        self.assertTrue(item_name_files, 'Missing maintained ItemNameKeys source files')
        for src in item_name_files:
            pairs = re.findall(r'case "([^"]+)" -> "([^"]+)";', src.read_text(encoding='utf-8'))
            self.assertGreater(len(pairs), 100, src)
            for _, translation_key in pairs:
                with self.subTest(source=str(src.relative_to(ROOT)), key=translation_key):
                    self.assertIn(translation_key, self.language)

    def test_shared_pipe_tip_lookup_covers_all_retired_runtime_tip_keys(self):
        source = (ROOT / 'source-shared/src/main/java/buildcraft/transport/item/PipeTooltipKeys.java').read_text(encoding='utf-8')
        lookups = dict(re.findall(r'case "([^"]+)" -> "([^"]+)";', source))
        runtime_aliases = {k: v for k, v in self.aliases.items() if k.startswith('tip.pipe.buildcrafttransport.')}
        self.assertEqual(runtime_aliases, lookups)
        for tip in lookups.values():
            self.assertIn(tip, self.language)
        pipe_files = maintained_source_files('ItemPipeHolder.java')
        self.assertEqual(len(pipe_files), 4)
        for p in pipe_files:
            self.assertIn('PipeTooltipKeys.canonical("tip." + unlocalizedName)', p.read_text(encoding='utf-8'))

    def test_fluid_block_uses_registered_fluid_type_translation(self):
        # Since 1.21.11, BlockBehaviour.getDescriptionId() is final. Bind the
        # existing fluid_type keys through block properties at registration,
        # rather than overriding the method or resurrecting block.* aliases.
        block_path = 'src/main/java/buildcraft/energy/fluid/BCLiquidBlock.java'
        registry_path = 'src/main/java/buildcraft/energy/BCEnergyFluids.java'
        targets = (
            '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
            '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
        )
        for target in targets:
            with self.subTest(target=target):
                block = read_materialized_java(target, block_path)
                registration = read_materialized_java(target, registry_path)
                if target in ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge'):
                    self.assertIn('public String getDescriptionId()', block)
                    self.assertRegex(block, r'return (?:sourceFluid|getFluid\(\))\.getFluidType\(\)\.getDescriptionId\(\);')
                    self.assertNotIn('.overrideDescription("fluid_type.buildcraftenergy.', registration)
                else:
                    self.assertNotIn('public String getDescriptionId()', block)
                    self.assertIn('.overrideDescription("fluid_type.buildcraftenergy." + fullName)', registration)
                    # The oil spout shares the regular oil name, not a missing spout key.
                    self.assertIn('.overrideDescription("fluid_type.buildcraftenergy.oil")', registration)
        for fluid in ['oil', 'oil_residue', 'oil_heavy', 'oil_dense', 'oil_distilled',
                      'fuel_dense', 'fuel_mixed_heavy', 'fuel_light', 'fuel_mixed_light', 'fuel_gaseous']:
            for suffix in ['', '_heat_1', '_heat_2']:
                self.assertIn('fluid_type.buildcraftenergy.' + fluid + suffix, self.language)

    def test_robot_board_title_and_description_still_use_their_own_contracts(self):
        board_item_files = maintained_source_files('ItemRobot.java') + maintained_source_files('ItemRedstoneBoard.java')
        self.assertTrue(board_item_files, 'Missing maintained robot item source files')
        for p in board_item_files:
            source = p.read_text(encoding='utf-8')
            self.assertIn('board.displayName().copy().withStyle(', source, p)
            self.assertNotIn('board.displayName().withStyle(', source, p)
            self.assertIn('legacyKey', source, p)
            self.assertNotIn('Component.translatable("buildcraft." + legacyKey).withStyle(', source, p)

    def test_no_retired_keys_remain_in_code_and_resource_templates(self):
        # Match whole language keys, but not prefixes inside longer keys or filenames.
        rex = re.compile(r'(?<![A-Za-z0-9_.])(?:' + '|'.join(
            re.escape(key) for key in sorted(self.aliases, key=len, reverse=True)
        ) + r')(?![A-Za-z0-9_.])')
        helper = ROOT / 'source-shared/src/main/java/buildcraft/transport/item/PipeTooltipKeys.java'
        for folder in MAINTAINED_SOURCE_ROOTS:
            for p in (ROOT / folder).rglob('*'):
                if p == LANG_FILE or p == helper or not p.is_file() or p.suffix not in {'.java', '.json', '.xml', '.txt'}:
                    continue
                matches = set(rex.findall(p.read_text(encoding='utf-8', errors='replace')))
                self.assertFalse(matches, (p.relative_to(ROOT), sorted(matches)))

    def test_generated_materializations_are_excluded_from_source_discovery(self):
        # Simulate a stale pre-deduplication source alongside a maintained one.
        with tempfile.TemporaryDirectory() as temp:
            fixture_root = Path(temp)
            current = fixture_root / 'source-shared/src/main/java/buildcraft/robotics/item/ItemRobot.java'
            generated = (
                fixture_root / 'build/effective-sources/1.19.2-forge/src/main/java/buildcraft/robotics/item/ItemRobot.java'
            )
            stonecutter = (
                fixture_root / 'builds/old/versions/1.19.2-forge/build/effective-source/src/main/java/buildcraft/robotics/item/ItemRobot.java'
            )
            for path in (current, generated, stonecutter):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('// fixture', encoding='utf-8')
            self.assertEqual(maintained_source_files('ItemRobot.java', root=fixture_root), [current])

    def test_book_templates_and_filler_layout_are_valid_json(self):
        for file in [ROOT / 'source-shared/src/main/resources/assets/buildcraft/guide/page_layouts.json',
                     ROOT / 'source-shared/src/main/resources/assets/buildcraftbuilders/gui/filler_planner.json']:
            json.loads(file.read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
