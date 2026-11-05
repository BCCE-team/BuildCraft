#!/usr/bin/env python3
"""Check that modern material counting, two-cell placement and task classification remain wired.

These are source/Java syntax checks. The matching NeoForge GameTests use a real world.
They are NOT a substitute for Gradle type checking or running the Minecraft tests.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file
from minecraft_compat_fixture import parse_sources

TARGETS = ('1.21.1-neoforge', '1.21.11-neoforge', '26.1.2-neoforge',
           '26.2-neoforge', '26.3-neoforge')
FILES = ('SchematicBlockDefault.java', 'BlueprintBuilder.java', 'SnapshotBuilder.java')


class BuilderMultiBlockTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bcce-builder-multiblock-')
        cls.targets = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            resolved = {}
            for name in FILES:
                rel = 'src/main/java/buildcraft/builders/snapshot/' + name
                owner = resolve_effective_source(layout, props, rel)
                if owner is None:
                    raise AssertionError(f'{target}: missing {rel}')
                dst = Path(cls.temp.name) / target / rel
                _materialize_text_file(owner, dst, logical_relative=rel,
                    minecraft=props[f'target.{target}.deps.minecraft'],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(owner.is_relative_to(x) for x in (
                        layout.family_downport_root, layout.family_platform_downport_root) if x))
                resolved[name] = dst
            cls.targets[target] = resolved

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def test_default_schematic_supports_all_two_cell_classes(self):
        for target, classes in self.targets.items():
            with self.subTest(target=target):
                source = classes['SchematicBlockDefault.java'].read_text()
                self.assertIn('BlockStateProperties.BED_PART', source)
                self.assertIn('DoorBlock.HALF', source)
                self.assertIn('DoublePlantBlock.HALF', source)
                self.assertIn('if (isSecondaryHalf(blockState)) return java.util.Collections.emptyList();', source)
                self.assertIn('private boolean placeBothHalves(', source)
                self.assertIn('Block.UPDATE_KNOWN_SHAPE', source)
                self.assertIn('level.setBlock(secondPos, priorSecond, silentFlags)', source)
                self.assertIn('result.add(BlockPos.ZERO.relative(facing.getOpposite()))', source)

    def test_dependencies_are_virtual_not_serialized_into_rule_validated_nbt(self):
        for target, classes in self.targets.items():
            with self.subTest(target=target):
                source = classes['SchematicBlockDefault.java'].read_text()
                self.assertIn('new HashSet<>(requiredBlockOffsets)', source)
                self.assertIn('BuildersNbtUtil.writeBlockPosList(requiredBlockOffsets.stream())', source)
                self.assertIn('requiredBlockOffsets, updateBlockOffsets, canBeReplacedWithBlocks', source)
                self.assertIn('map(blockPos -> blockPos.rotate(rotation))', source)

    def test_snapshot_does_not_schedule_air_for_breaking(self):
        for target, classes in self.targets.items():
            with self.subTest(target=target):
                source = classes['SnapshotBuilder.java'].read_text()
                self.assertIn('Classify empty destinations as pending placement', source)
                self.assertIn('checkResults[i] = CHECK_RESULT_TO_PLACE;\n            }\n', source)

    def test_pair_and_permissions_are_checked_before_consuming_materials(self):
        for target, classes in self.targets.items():
            with self.subTest(target=target):
                source = classes['BlueprintBuilder.java'].read_text()
                self.assertIn('getBuildingInfo().box.contains(otherPos)', source)
                self.assertIn('canPlacePairedHalf(blockPos, OperationMode.SIMULATE', source)
                self.assertIn('canPlacePairedHalf(placeTask.pos, OperationMode.EXECUTE', source)
                self.assertIn('owner, AutomationPermissionUtil.SOURCE_ROBOT', source)
                self.assertIn('sourceId, WorldOperationKind.BLOCK_PLACE, operation', source)

    def test_java_syntax_of_effective_modern_code(self):
        for target, classes in self.targets.items():
            with self.subTest(target=target):
                result = parse_sources(list(classes.values()))
                self.assertIn('0 errors', result)


if __name__ == '__main__':
    unittest.main()
