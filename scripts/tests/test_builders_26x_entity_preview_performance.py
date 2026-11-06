#!/usr/bin/env python3
"""26.x preview visual entity restoration and 26.2+ draw-call budget.

These verify Stonecutter selection and Java syntax. They do not substitute for
an in-game render benchmark or full NeoForge compile.
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

PIP = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPipRenderer.java'
SCENE = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPreviewScene.java'
MODEL = 'src/main/java/buildcraft/builders/client/render/pip/PreviewBlockModelRenderer.java'
TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')


class BlueprintEntityPerformance26XTests(unittest.TestCase):
    def test_native_scene_supports_hanging_frames_and_armor_stands(self):
        for target in TARGETS:
            with self.subTest(target=target):
                scene = read_materialized_java(target, SCENE)
                self.assertIn('"minecraft:item_frame"', scene)
                self.assertIn('"minecraft:glow_item_frame"', scene)
                self.assertIn('"minecraft:painting"', scene)
                self.assertIn('"minecraft:armor_stand"', scene)
                self.assertIn('attachment == null || !within(blueprint, attachment)', scene)
                self.assertIn('if (hanging) {', scene)
                self.assertIn('restorePreviewEquipment(entity, tag);', scene)
                self.assertIn('stand.setItemSlot(slots[i], item);', scene)
                self.assertIn('reportEntityFailure("prepare", id, invalidEntity)', scene)
                self.assertNotIn('world.addFreshEntity(', scene)
                self.assertNotIn('buildWithoutChecks(', scene)

    def test_262_263_use_explicit_type_without_changing_server_spawn_checks(self):
        for target in TARGETS[1:]:
            with self.subTest(target=target):
                code = read_materialized_java(target, SCENE)
                self.assertIn('EntityType.create(type,', code)
                self.assertIn('new ItemFrame(world, anchor, facing)', code)
                self.assertIn('new GlowItemFrame(world, anchor, facing)', code)
                self.assertNotIn('EntitySpawnRequest', code)
                # The production placement still checks normal spawn conditions.
                entity = read_materialized_java(target,
                    'src/main/java/buildcraft/builders/snapshot/SchematicEntityDefault.java')
                self.assertIn('new EntitySpawnRequest(EntitySpawnReason.LOAD, false)', entity)
        old = read_materialized_java(TARGETS[0], SCENE)
        self.assertIn('EntitySpawnReason.LOAD).orElse(null)', old)
        self.assertNotIn('EntitySpawnRequest', old)

    def test_renderer_reuses_model_and_mesh_cache_only_until_resource_change(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = read_materialized_java(target, PIP)
                self.assertIn('private void preparePreviewCache(Snapshot snapshot, Minecraft minecraft)', code)
                self.assertIn('snapshot == cachedSnapshot', code)
                self.assertIn('minecraft.level == cachedClientLevel', code)
                self.assertIn('models == cachedModelSet', code)
                self.assertIn('minecraft.getModelManager().getBlockStateModelSet()', code)
                self.assertIn('cachedItems.clear();', code)
                self.assertIn('cachedPipes.clear();', code)
                self.assertIn('cachedTopology = snapshot instanceof Blueprint bp', code)
                self.assertIn('cachedBlockModels = new PreviewBlockModelRenderer.CellCache()', code)
                self.assertIn('Map<ISchematicBlock, TrackingItemStackRenderState> models = cachedItems;', code)
                self.assertIn('MAX_CELLS = 8192', code)
                # No accidental per-frame recreation in the PiP render loop.
                loop = code.split('protected void renderToTexture(', 1)[1].split('private record PipePreviewKey(', 1)[0]
                for allocation in ('new PreviewPipeTopology(', 'new PreviewBlockModelRenderer.CellCache()',
                                   'new HashMap<', 'new IdentityHashMap<'):
                    self.assertNotIn(allocation, loop)

    def test_262_263_batches_block_geometry_without_losing_special_entities(self):
        for target in TARGETS[1:]:
            with self.subTest(target=target):
                pip = read_materialized_java(target, PIP)
                renderer = read_materialized_java(target, MODEL)
                self.assertIn('PreviewBlockModelRenderer.Batch blockBatch', pip)
                self.assertIn('blockBatch.add(renderedState, at, modelCache,', pip)
                self.assertIn('blockBatch.submit(poseStack, collector);', pip)
                self.assertIn('scene.submitEntities(poseStack, collector, previewCamera);', pip)
                self.assertIn('scene.submitBlockEntity(at, poseStack, collector, previewCamera);', pip)
                self.assertLess(pip.index('blockBatch.submit('), pip.index('scene.submitEntities('))
                self.assertIn('public static final class Batch', renderer)
                self.assertIn('collector.submitCustomGeometry(parent, RenderCompat.cutout(),', renderer)
                self.assertIn('local.translate(cell.position().getX()', renderer)
                self.assertIn('emit(consumer, local.last(), cell.quads()', renderer)
                self.assertIn('new QuadInstance();', renderer)
        # 26.1.2 uses the immediate buffer path and has no new draw-collector batch.
        self.assertNotIn('PreviewBlockModelRenderer.Batch', read_materialized_java(TARGETS[0], PIP))
        self.assertNotIn('public static final class Batch', read_materialized_java(TARGETS[0], MODEL))
        self.assertNotIn('public static final class Batch', read_materialized_java('1.21.11-neoforge', MODEL))

    def test_scope_is_neoforge_26x_only(self):
        for target in TARGETS:
            with self.subTest(target=target):
                for file in (PIP, SCENE):
                    self.assertIn('source-family-platforms/26.X/neoforge/',
                        resolve_target_source(target, file).as_posix())

    def test_materialized_java_syntax_all_targets(self):
        for target in ('1.21.11-neoforge', *TARGETS):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='preview26-performance-') as temp:
                files = []
                for rel in ((MODEL,) if target == '1.21.11-neoforge' else (PIP, SCENE, MODEL)):
                    source = read_materialized_java(target, rel)
                    path = Path(temp) / rel.removeprefix('src/main/java/')
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(source, encoding='utf-8')
                    files.append(path)
                self.assertIn('0 errors', parse_sources(files))


if __name__ == '__main__':
    unittest.main()
