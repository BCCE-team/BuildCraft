#!/usr/bin/env python3
"""Guard the 26.2+ GUI preview repair, without touching working 26.1.2.

Source contract + resolved-Java parsing only. In-game rendering/FPS must be
verified with a 26.2/26.3 client and debug counters enabled.
"""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java
from minecraft_compat_fixture import parse_sources

BASE = 'src/main/java/buildcraft/builders/client/render/pip/'
SCENE = BASE + 'BlueprintPreviewScene.java'
PIP = BASE + 'BlueprintPipRenderer.java'
MODEL = BASE + 'PreviewBlockModelRenderer.java'


class AnchoredDisplayAndBatchCacheTests(unittest.TestCase):
    def test_2612_retains_original_entity_decode_and_preview_path(self):
        scene = read_materialized_java('26.1.2-neoforge', SCENE)
        pip = read_materialized_java('26.1.2-neoforge', PIP)
        model = read_materialized_java('26.1.2-neoforge', MODEL)
        self.assertIn('Entity entity = EntityType.create(TagValueInput.create(', scene)
        self.assertIn('if (entity.position().distanceToSqr(position) > 0.01D)', scene)
        self.assertNotIn('new ItemFrame(world, anchor, facing)', scene)
        self.assertNotIn('new ArmorStand(world, position.x', scene)
        self.assertNotIn('cachedBlockBatch', pip)
        self.assertNotIn('public static final class Batch', model)

    def test_262_and_263_reconstruct_display_entities_without_loading_first(self):
        for target in ('26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                scene = read_materialized_java(target, SCENE)
                frame = scene.index('new ItemFrame(world, anchor, facing)')
                stand = scene.index('new ArmorStand(world, position.x, position.y, position.z)')
                generic_loader = scene.index('entity = withPreviewId(EntityType.create(type,')
                self.assertLess(frame, generic_loader)
                self.assertLess(stand, generic_loader)
                self.assertIn('new GlowItemFrame(world, anchor, facing)', scene)
                self.assertIn('ItemStackUtil.parseOptional(world.registryAccess(),', scene)
                self.assertIn('frame.setItem(item);', scene)
                self.assertIn('frame.setRotation(', scene)
                self.assertIn('restorePreviewEquipment(entity, tag);', scene)
                self.assertIn('if (!hanging && entity.position().distanceToSqr(position)', scene)
                self.assertIn('reportEntityFailure("submit", type, invalidEntity)', scene)
                self.assertNotIn('world.addFreshEntity(', scene)

    def test_batch_is_prepared_once_per_snapshot_and_resets_on_model_reload(self):
        for target in ('26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                pip = read_materialized_java(target, PIP)
                model = read_materialized_java(target, MODEL)
                self.assertIn('cachedBlockBatch = new PreviewBlockModelRenderer.Batch();', pip)
                self.assertIn('cachedBlockBatchComplete = false;', pip)
                self.assertIn('boolean assembleBlockBatch = !cachedBlockBatchComplete;', pip)
                self.assertIn('blockBatch.contains(at)', pip)
                self.assertIn('cachedBlockBatchComplete = true;', pip)
                self.assertIn('private final java.util.Set<BlockPos> occupied', model)
                self.assertIn('if (occupied.add(position)) cells.add(', model)
                self.assertIn('List<Cell> draw = cells;', model)
                self.assertIn('profiledReuses++', pip)
                self.assertIn('cached blits=', pip)
                self.assertIn('GPU time not included', pip)
                # No batch rebuild from inside the texture render method.
                body = pip.split('protected void renderToTexture(', 1)[1]
                self.assertNotIn('new PreviewBlockModelRenderer.Batch()', body)

    def test_materialized_java_syntax(self):
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce26-display-') as tmp:
                paths = []
                for logical in (SCENE, PIP, MODEL):
                    path = Path(tmp) / logical
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(read_materialized_java(target, logical), encoding='utf8')
                    paths.append(path)
                self.assertIn('0 errors', parse_sources(paths))


if __name__ == '__main__':
    unittest.main()
