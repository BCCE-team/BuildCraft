#!/usr/bin/env python3
"""Versioned 26.x Builder preview API contract, not a Minecraft Gradle compile.

26.2 changed the camera getter and entity-load overload; 26.1.2 kept the
older shapes. Check the actual materialized versions, never just raw source.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from minecraft_compat_fixture import parse_sources
from source_lookup import read_materialized_java, resolve_target_source

CAMERA = 'src/main/java/buildcraft/builders/client/render/FillerGhostPreview.java'
SCENE = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPreviewScene.java'
ENTITY = 'src/main/java/buildcraft/builders/snapshot/SchematicEntityDefault.java'
TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')


class Builders26xCameraSpawnApiTests(unittest.TestCase):
    def test_2612_retains_legacy_camera_and_spawn_reason_overload(self):
        camera = read_materialized_java(TARGETS[0], CAMERA)
        scene = read_materialized_java(TARGETS[0], SCENE)
        self.assertIn('gameRenderer.getMainCamera()', camera)
        self.assertNotIn('gameRenderer.mainCamera()', camera)
        self.assertIn('EntitySpawnReason.LOAD).orElse(null)', scene)
        self.assertNotIn('EntitySpawnRequest', scene)

    def test_262_and_263_use_new_camera_and_spawn_request(self):
        for target in TARGETS[1:]:
            with self.subTest(target=target):
                camera = read_materialized_java(target, CAMERA)
                scene = read_materialized_java(target, SCENE)
                entity = read_materialized_java(target, ENTITY)
                self.assertIn('BCCamera.position(Minecraft.getInstance().gameRenderer.mainCamera())', camera)
                self.assertNotIn('gameRenderer.getMainCamera()', camera)
                self.assertIn('import net.minecraft.world.entity.EntitySpawnRequest;', scene)
                # The detached scene uses a whitelisted load that skips normal
                # spawn checks; live Builder placement must KEEP those checks.
                self.assertIn('new EntitySpawnRequest(EntitySpawnReason.LOAD, true)', scene)
                self.assertNotIn('EntitySpawnReason.LOAD).orElse(null)', scene)
                self.assertIn('new EntitySpawnRequest(EntitySpawnReason.LOAD, false)', entity)

    def test_all_targets_keep_white_sprite_not_full_atlas_uv(self):
        for target in ('1.21.11-neoforge', *TARGETS):
            with self.subTest(target=target):
                camera = read_materialized_java(target, CAMERA)
                self.assertIn('BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite()', camera)
                self.assertIn('AddonQuadRenderer.box(', camera)
                self.assertNotIn('FillPreviewRenderer.emit(', camera)

    def test_26x_preview_scene_remains_scoped_to_neoforge(self):
        for target in TARGETS:
            with self.subTest(target=target):
                path = resolve_target_source(target, SCENE)
                self.assertIn('source-family-platforms/26.X/neoforge/', path.as_posix())

    def test_java_syntax_for_materialized_sources(self):
        for target in ('1.21.11-neoforge', *TARGETS):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce26-api-') as tmp:
                files = []
                for logical in ((CAMERA, SCENE) if target in TARGETS else (CAMERA,)):
                    path = Path(tmp) / logical.removeprefix('src/main/java/')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(read_materialized_java(target, logical), encoding='utf-8')
                    files.append(path)
                self.assertIn('0 errors', parse_sources(files))


if __name__ == '__main__':
    unittest.main()
