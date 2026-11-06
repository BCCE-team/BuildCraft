#!/usr/bin/env python3
"""The detached entity ID contract for Minecraft 26.2/26.3 PiP scenes.

Tests the actual ID allocation helper extracted from the effective Java source,
with stubbed Entity, and checks the version-specific materialization. This is
not a full NeoForge compile or live PiP render.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java
from minecraft_compat_fixture import parse_sources

SCENE = 'src/main/java/buildcraft/builders/client/render/pip/BlueprintPreviewScene.java'


class PreviewEntityIdTests(unittest.TestCase):
    def test_2612_retains_original_entity_creation(self):
        source = read_materialized_java('26.1.2-neoforge', SCENE)
        self.assertNotIn('withPreviewId(', source)
        self.assertNotIn('NEXT_PREVIEW_ENTITY_ID', source)
        self.assertNotIn('AtomicInteger', source)
        self.assertIn('EntityType.create(TagValueInput.create(', source)

    def test_every_262_263_creation_route_assigns_id_before_use(self):
        for target in ('26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target):
                source = read_materialized_java(target, SCENE)
                self.assertIn('entity.setId(NEXT_PREVIEW_ENTITY_ID.getAndDecrement());', source)
                self.assertIn('withPreviewId(id.equals("minecraft:glow_item_frame")', source)
                self.assertIn('withPreviewId(new ArmorStand(', source)
                self.assertIn('entity = withPreviewId(EntityType.create(type,', source)
                self.assertIn('entity = withPreviewId(type.create(world, EntitySpawnReason.LOAD));', source)
                self.assertIn('EntityRenderState state = renderer.extractEntity(entity, 0.0F);', source)
                self.assertNotIn('world.addFreshEntity(', source)
                self.assertIn('new AtomicInteger(-1)', source)
                # A missing ID must be fixed on creation, not swallowed during submission.
                self.assertLess(source.index('entity = withPreviewId(type.create('),
                                source.index('output.add(entity);'))

    def test_java_syntax_of_all_effective_sources(self):
        for target in ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-ids-') as work:
                file = Path(work) / 'BlueprintPreviewScene.java'
                file.write_text(read_materialized_java(target, SCENE), encoding='utf-8')
                self.assertIn('0 errors', parse_sources([file]))

    def test_actual_allocator_executes_and_assigns_256_unique_ids(self):
        source = read_materialized_java('26.2-neoforge', SCENE)
        helper = re.search(
            r'    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger\(-1\);'
            r'\s+private static <T extends Entity> T withPreviewId\(T entity\) \{.*?\n    \}',
            source, flags=re.S)
        self.assertIsNotNone(helper, 'Allocator must be extracted from the actual Java source')
        program = '''import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
class PreviewIdProbe {
  static class Entity {
    private int id;
    public int getId() {
      if (id == 0) throw new IllegalStateException("Tried to access entity ID before ID assignment");
      return id;
    }
    public void setId(int id) { this.id = id; }
  }
''' + helper.group(0) + '''
  public static void main(String[] args) {
    HashSet<Integer> unique = new HashSet<>();
    for (int i = 0; i < 256; ++i) {
      Entity entity = withPreviewId(new Entity());
      int id = entity.getId(); // Simulates render-state extraction.
      if (id >= 0 || !unique.add(id)) throw new AssertionError("invalid detached ID " + id);
    }
    if (withPreviewId(null) != null) throw new AssertionError("null should stay null");
    System.out.println("preview entity IDs PASS " + unique.size());
  }
}
'''
        with tempfile.TemporaryDirectory(prefix='bcce-ids-run-') as work:
            main = Path(work) / 'PreviewIdProbe.java'
            main.write_text(program, encoding='utf-8')
            compiled = subprocess.run(['javac', '-d', work, str(main)], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compiled.returncode, compiled.stderr)
            result = subprocess.run(['java', '-cp', work, 'PreviewIdProbe'], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('preview entity IDs PASS 256', result.stdout)


if __name__ == '__main__':
    unittest.main()
