#!/usr/bin/env python3
"""Compile the production charging-station search against both Entity.level APIs."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_charging_dock_dispatcher import STUBS

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchStation.java'
QUEUE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/ChargingDockQueue.java'


class ChargingStationVersionCompatibilityTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK unavailable')
    def test_station_search_compiles_and_arbitrates_with_legacy_and_modern_level(self):
        versions = (
            ('1.19.2', 'old', 'forge', True),
            ('1.20.1', 'old', 'forge', False),
            ('1.21.11', '1.21.X', 'neoforge', False),
            ('26.3', '26.X', 'neoforge', False),
            ('26.3', '26.X', 'fabric', False),
        )
        original = SOURCE.read_text(encoding='utf-8')
        for minecraft, family, platform, legacy in versions:
            with self.subTest(minecraft=minecraft, platform=platform):
                processed = preprocess_text(original, minecraft=minecraft,
                                            family=family, platform=platform,
                                            source=str(SOURCE))
                self.assertIn('return robot.level;' if legacy else 'return robot.level();', processed)
                self.assertNotIn('return robot.level();' if legacy else 'return robot.level;', processed)
                with tempfile.TemporaryDirectory(prefix='bc-charge-compat-') as tmp:
                    directory = Path(tmp)
                    sources = []
                    for logical, source in STUBS.items():
                        if legacy and logical.endswith('/EntityRobotBase'):
                            self.assertIn('public final Level world;', source)
                            source = (source.replace('public final Level world;', 'public final Level level;')
                                            .replace('this.world=world;', 'this.level=world;')
                                            .replace('public Level level(){return world;}', ''))
                        file = directory / (logical + '.java')
                        file.parent.mkdir(parents=True, exist_ok=True)
                        file.write_text(source, encoding='utf-8')
                        sources.append(str(file))
                    ai = directory / 'buildcraft/robotics/ai/AIRobotSearchStation.java'
                    ai.parent.mkdir(parents=True, exist_ok=True)
                    ai.write_text(processed, encoding='utf-8')
                    sources.extend((str(ai), str(QUEUE)))
                    result = subprocess.run(['javac', '--release', '17', '-d', str(directory / 'classes'),
                                             *sources], capture_output=True, text=True, timeout=40)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    result = subprocess.run(['java', '-cp', str(directory / 'classes'),
                                             'fixture.ChargingDockDispatcherFixture'],
                                            capture_output=True, text=True, timeout=15)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn('PASS:', result.stdout)


if __name__ == '__main__':
    unittest.main()
