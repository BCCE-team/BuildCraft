#!/usr/bin/env python3
"""Regression guard for the 26.3 VertexConsumer#setUv3 API addition.

Verifies target materialization and compiles the exact DISCARD consumer against
small signatures of the 26.2 and 26.3 VertexConsumer interfaces. This is not a
Minecraft/NeoForge Gradle build.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from source_lookup import read_materialized_java
from minecraft_compat_fixture import parse_sources

PIPE = 'src/main/java/buildcraft/builders/client/render/pip/PreviewPipeModel.java'
TARGETS = ('1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')


def extract_discard(java_source: str) -> str:
    begin = java_source.index('private static final VertexConsumer DISCARD = new VertexConsumer() {')
    end = java_source.index('\n    };', begin) + len('\n    };')
    return java_source[begin:end]


class PreviewPipeVertexConsumerTests(unittest.TestCase):
    def test_effective_263_consumer_only_has_new_uv_method_for_263(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, PIPE)
                consumer = extract_discard(source)
                self.assertEqual('setUv3(float u, float v)' in consumer, target == '26.3-neoforge')
                self.assertIn('setUv2(int u, int v)', consumer)
                self.assertIn('setNormal(float x, float y, float z)', consumer)

    def test_all_selected_sources_parse_as_java(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'PreviewPipeModel.java'
                path.write_text(read_materialized_java(target, PIPE), encoding='utf-8')
                self.assertIn('0 errors', parse_sources([path]))

    def test_discard_compiles_against_262_and_263_interface_contracts(self):
        signatures = '''
            VertexConsumer addVertex(float x, float y, float z);
            VertexConsumer setColor(int r, int g, int b, int a);
            VertexConsumer setColor(int color);
            VertexConsumer setUv(float u, float v);
            VertexConsumer setUv1(int u, int v);
            VertexConsumer setUv2(int u, int v);
            VertexConsumer setNormal(float x, float y, float z);
            VertexConsumer setLineWidth(float width);
'''
        for target in ('26.2-neoforge', '26.3-neoforge'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                is_263 = target == '26.3-neoforge'
                extra = 'VertexConsumer setUv3(float u, float v);' if is_263 else ''
                extracted = extract_discard(read_materialized_java(target, PIPE))
                test = f'''public final class PipeDiscardProbe {{
                    interface VertexConsumer {{ {signatures} {extra} }}
                    {extracted}
                    public static void main(String[] args) {{
                        if (DISCARD.setUv2(1, 2) != DISCARD) throw new AssertionError();
                        {'if (DISCARD.setUv3(0.5f, 0.75f) != DISCARD) throw new AssertionError();' if is_263 else ''}
                    }}
                }}'''
                path = Path(tmp) / 'PipeDiscardProbe.java'
                path.write_text(test, encoding='utf-8')
                built = subprocess.run(['javac', '-d', tmp, str(path)], capture_output=True, text=True, timeout=30)
                self.assertEqual(0, built.returncode, built.stderr)
                ran = subprocess.run(['java', '-cp', tmp, 'PipeDiscardProbe'], capture_output=True, text=True, timeout=30)
                self.assertEqual(0, ran.returncode, ran.stderr)


if __name__ == '__main__':
    unittest.main()
