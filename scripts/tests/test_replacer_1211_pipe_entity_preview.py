"""Source-contract regressions for saved decorations, pipes and facade previews.

These do not replace an actual NeoForge compile + world render smoke test.
"""
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java, resolve_target_source

TARGET = '1.21.1-neoforge'
PREFIX = 'src/main/java/buildcraft/builders/client/render/'


class ReplacerEntitiesPipesPreview1211(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.renderer = read_materialized_java(TARGET, PREFIX + 'BlueprintGuiRenderer.java')
        cls.scene = read_materialized_java(TARGET, PREFIX + 'BlueprintBlockEntityScene.java')
        cls.entities = read_materialized_java(TARGET, PREFIX + 'BlueprintEntityPreview.java')
        cls.pipe = read_materialized_java(TARGET, PREFIX + 'pip/PreviewPipeModel.java')
        cls.topology = read_materialized_java(TARGET, PREFIX + 'pip/PreviewPipeTopology.java')

    def test_only_1211_uses_the_new_pipeline(self):
        for name in ['BlueprintEntityPreview.java', 'pip/PreviewPipeModel.java', 'pip/PreviewPipeTopology.java']:
            source = resolve_target_source(TARGET, PREFIX + name)
            self.assertIn('source-downports/1.21.X/1.21.1/neoforge/', source.as_posix())
        other = read_materialized_java('1.21.11-neoforge', PREFIX + 'pip/PreviewPipeModel.java')
        self.assertIn('NbtCompat.getCompound', other)
        self.assertNotIn('LegacyMaterialAlpha', other)

    def test_entity_capture_is_rendered_independently_of_palette(self):
        self.assertIn('blueprint.entities', self.entities)
        self.assertIn('minecraft:item_frame', self.entities)
        self.assertIn('minecraft:glow_item_frame', self.entities)
        self.assertIn('minecraft:armor_stand', self.entities)
        self.assertIn('EntityType.create(tag, world)', self.entities)
        self.assertIn('dispatcher.render(entity,', self.entities)
        self.assertIn('BlueprintEntityPreview.render(scene.previewEntities(), pose, buffers)', self.renderer)
        self.assertLess(self.renderer.index('BlueprintEntityPreview.render('), self.renderer.index('buffers.endBatch()'))

    def test_entities_use_isolated_fake_world(self):
        self.assertIn('new FakeWorld(client)', self.scene)
        self.assertIn('BlueprintEntityPreview.create(blueprint, level)', self.scene)
        self.assertIn('FakeWorld.BLUEPRINT_OFFSET', self.entities)
        self.assertIn('BlueprintSecurity.stripUnsafeEntityState(tag)', self.entities)
        for forbidden in ('addFreshEntity(', 'buildWithoutChecks(', 'mc.level.addFreshEntity(', 'world.addEntity('):
            self.assertNotIn(forbidden, self.entities.replace('// Never call buildWithoutChecks / addFreshEntity / world.addEntity.', ''))

    def test_pipe_is_not_mistaken_for_empty_item_or_block(self):
        self.assertLess(self.renderer.index('cell instanceof SchematicBlockPipe'), self.renderer.index('BlockState state;'))
        self.assertIn('scene.pipeMeshAt(at, cell)', self.renderer)
        self.assertIn('PreviewPipeModel.submit(mesh, pose, buffers, 15728880)', self.renderer)
        self.assertIn('tile = getCompound(pipe.serializeNBT(), "tileNbt")', self.topology)
        self.assertIn('mask |= 1 << (side.ordinal() * 2)', self.topology)
        self.assertIn('PreviewPipeModel.canConnect(own, links(neighbourCell), side)', self.topology)

    def test_facades_glass_colors_and_pluggables(self):
        self.assertIn('PipeModelCacheBase.generator.generateCutout', self.pipe)
        self.assertIn('PipeModelCachePluggable.cacheCutoutAll.bake', self.pipe)
        self.assertIn('PipeModelCachePluggable.cacheTranslucentAll.bake', self.pipe)
        self.assertIn('LegacyMaterialAlpha.rewriteAll', self.pipe)
        self.assertIn('new PluggableKey(RenderType.cutout(), holder)', self.pipe)
        self.assertIn('new PluggableKey(RenderType.translucent(), holder)', self.pipe)
        self.assertIn('renderDynamic(mesh, pose, buffers, light)', self.pipe)

    def test_1211_has_supported_render_layers_without_excluded_compat_bridge(self):
        # RenderCompat is available only from 1.21.11 onward.
        self.assertIn('import net.minecraft.client.renderer.RenderType;', self.pipe)
        self.assertNotIn('RenderCompat', self.pipe)
        self.assertIn('buffers.getBuffer(RenderType.cutout())', self.pipe)
        self.assertIn('buffers.getBuffer(RenderType.translucent())', self.pipe)
        modern = read_materialized_java('1.21.11-neoforge', PREFIX + 'pip/PreviewPipeModel.java')
        self.assertIn('RenderCompat.cutout()', modern)


    def test_scene_caches_mesh_and_entity_instances_across_frames(self):
        self.assertIn('pipeMeshes.computeIfAbsent', self.scene)
        self.assertIn('if (!variants.containsKey(mask))', self.scene)
        self.assertIn('cachedBlueprint.get() != blueprint', self.scene)
        self.assertIn('cachedClient.get() != client', self.scene)
        self.assertIn('private final List<Entity> previewEntities;', self.scene)

    def test_legacy_branches_untouched(self):
        self.assertNotIn('BlueprintEntityPreview',
            read_materialized_java('1.20.1-forge', 'src/main/java/buildcraft/builders/gui/GuiFiller.java'))


if __name__ == '__main__':
    unittest.main()
