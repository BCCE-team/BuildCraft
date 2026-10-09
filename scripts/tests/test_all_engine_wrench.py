#!/usr/bin/env python3
"""All seven targets: wrench rotation matches MJ Dynamo for every BC8 engine type."""
from __future__ import annotations
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import resolve_effective_source,_materialize_text_file
from minecraft_compat_fixture import parse_sources
from all_engine_wrench_fixture import execute as execute_wrench
from dynamo_rotation_fixture import execute as execute_tile

TARGETS=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
         '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
BLOCK='src/main/java/buildcraft/lib/engine/BlockEngineBase_BC8.java'
TILE='src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java'
DYNAMO='src/main/java/buildcraft/energy/block/BlockDynamoMJ.java'


class AllEngineWrenchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bc-all-engine-wrench-')
        cls.effective={}
        props=load_properties()
        for target in TARGETS:
            layout=target_layout(target,props)
            down=tuple(p for p in (layout.family_downport_root,layout.family_platform_downport_root) if p)
            per={}
            for kind,logical in [('block',BLOCK),('tile',TILE),('dynamo',DYNAMO)]:
                source=resolve_effective_source(layout,props,logical)
                if source is None:raise AssertionError(f'Missing {target}: {logical}')
                outfile=Path(cls.temp.name)/target/logical
                _materialize_text_file(source,outfile,logical_relative=logical,
                    minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(d) for d in down))
                per[kind]=outfile
            cls.effective[target]=per

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def text(self,target,kind):
        return self.effective[target][kind].read_text(encoding='utf-8')

    def test_all_seven_targets_wrench_uses_manual_rotation(self):
        for target in TARGETS:
            with self.subTest(target=target):
                block=self.text(target,'block')
                self.assertIn('return engine.attemptManualRotation();',block)
                self.assertNotIn('return engine.attemptRotation();',block)
                self.assertIn('if (world.isClientSide',block)
                self.assertIn('return InteractionResult.SUCCESS;',block)
                self.assertIn('engine.rotateIfInvalid();',block)
                self.assertIn('ICustomRotationHandler',block)

    def test_direct_java_wrench_paths_five_engine_types(self):
        by_source={p['block'].read_bytes():p['block'] for p in self.effective.values()}
        for source in by_source.values():
            with self.subTest(source=source):
                result=execute_wrench(source)
                self.assertIn('145 assertions PASS',result)
        print(f'Native block rotation variants checked: {len(by_source)}',flush=True)

    def test_engine_rotation_uses_existing_dynamo_manual_semantics(self):
        by_source={p['tile'].read_bytes():p['tile'] for p in self.effective.values()}
        for source in by_source.values():
            with self.subTest(source=source):
                self.assertEqual('21 assertions PASS',execute_tile(source))
        print(f'Native engine tile variants checked: {len(by_source)}',flush=True)

    def test_manual_direction_persists_and_survives_neighbor_updates(self):
        for target in TARGETS:
            with self.subTest(target=target):
                tile=self.text(target,'tile')
                self.assertIn('public InteractionResult attemptManualRotation()',tile)
                self.assertIn('return attemptRotation(true);',tile)
                self.assertIn('if (manual || isFacingReceiver(current))',tile)
                self.assertIn('if (manual) manuallySelectedDirection = true;',tile)
                self.assertIn('if (manuallySelectedDirection || (currentDirection != null && isFacingReceiver(currentDirection)))',tile)
                self.assertIn('manuallySelectedDirection = false;',tile)
                self.assertIn('manualDirection',tile)
                self.assertIn('sendNetworkUpdate(NET_RENDER_DATA);',tile)
                self.assertIn('markChunkDirty();',tile)
                self.assertIn('capturePersistedState();',tile)

    def test_dynamo_remains_on_same_behavior(self):
        for target in TARGETS:
            with self.subTest(target=target):
                self.assertIn('dynamo.attemptManualRotation()',self.text(target,'dynamo'))

    def test_2612_exactly_one_audited_source_change(self):
        audit=json.loads((ROOT/'build-config/known-cross-target-fixes/engine-wrench-unified-2026-10.json').read_text())
        manifest=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())
        self.assertEqual(['src/main/java/buildcraft/lib/engine/BlockEngineBase_BC8.java'],audit['changed_files'])
        self.assertEqual(4351,audit['total_files_before'])
        self.assertEqual(4351,audit['total_files_after'])
        self.assertEqual([],audit['added'])
        self.assertEqual([],audit['removed'])
        self.assertEqual(manifest['files'][BLOCK],audit['modified'][BLOCK]['after'])
        self.assertNotEqual(audit['modified'][BLOCK]['before'],audit['modified'][BLOCK]['after'])
        self.assertEqual(hashlib.sha256(self.effective['26.1.2-neoforge']['block'].read_bytes()).hexdigest(),manifest['files'][BLOCK])

    def test_all_seven_target_java_syntax(self):
        for target in TARGETS:
            with self.subTest(target=target):
                files=self.effective[target]
                result=parse_sources([p.parent for p in files.values()])
                self.assertIn('0 errors',result)

if __name__ == '__main__':unittest.main()
