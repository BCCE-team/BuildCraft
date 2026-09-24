from __future__ import annotations

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class BuildLogicLoaderCleanupTests(unittest.TestCase):
    def test_common_target_stays_loader_neutral(self) -> None:
        text = (ROOT / 'build-logic/common-target.gradle').read_text(encoding='utf-8')
        self.assertNotIn('accesstransformer.cfg', text)
        self.assertNotIn('targetAccessTransformer', text)

    def test_forge_and_neoforge_own_access_transformers(self) -> None:
        forge = (ROOT / 'build-logic/loaders/forge-target.gradle').read_text(encoding='utf-8')
        neo = (ROOT / 'build-logic/loaders/neoforge-target.gradle').read_text(encoding='utf-8')
        self.assertIn("resolveSourceFile('src/main/resources/META-INF/accesstransformer.cfg')", forge)
        self.assertIn('accessTransformer = targetAccessTransformer', forge)
        self.assertIn("resolveSourceFile('src/main/resources/META-INF/accesstransformer.cfg')", neo)
        self.assertIn('accessTransformers.from(targetAccessTransformer)', neo)


if __name__ == '__main__':
    unittest.main()
