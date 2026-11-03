#!/usr/bin/env python3
"""Regression: Jade presents the board-specific robot item name, without a stale translation alias.

These are materialized source/asset checks; a real Minecraft client with Jade remains the
runtime acceptance test for the entity title and its placement in the tooltip.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
    '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)
JADE_SOURCE = 'src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java'


class JadeRobotNames(unittest.TestCase):
    def test_every_target_uses_the_robots_server_board_key_in_the_jade_title(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, JADE_SOURCE)
                self.assertIn('registerEntityDataProvider(', source)
                self.assertIn('registerEntityComponent(', source)
                self.assertIn('BuildCraftJadePlugin::preserveBuildCraftTitleColours', source)
                title = source.split('private static Component getBuildCraftTitle(Accessor<?> accessor)', 1)[1]
                title = title.split('private static boolean isBuildCraftBlock(', 1)[0]
                robot = title.split('entityAccessor.getEntity() instanceof EntityRobot robot', 1)[1]
                robot = robot.split('entityAccessor.getEntity() instanceof ItemEntity itemEntity', 1)[0]
                self.assertIn('entityAccessor.getServerData()', robot)
                self.assertIn('"Robot"', robot)
                self.assertIn('"BoardKey"', robot)
                self.assertIn('robot.getBoardEntry().key()', robot)
                self.assertIn('"item.buildcraftrobotics.robot." + boardKey', robot)
                self.assertIn('"item.buildcraftrobotics.robot.empty"', robot)
                self.assertIn('return Component.translatable(nameKey)', robot)
                self.assertNotIn('return Component.translatable("entity.buildcraftrobotics.robot")', robot)

    def test_no_target_uses_the_retired_board_label_as_a_translation_key(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, JADE_SOURCE)
                # The robot's board is already represented by the subtype in the entity title;
                # showing a second board row is redundant, and the old label was deduplicated.
                self.assertNotIn('line("robot.board"', source)
                self.assertNotIn('"buildcraft.jade.robot.board"', source)
                self.assertIn('line("robot.owner"', source)
                self.assertIn('line("robot.state"', source)

    def test_all_builtin_robot_names_exist_and_match_item_naming_contract(self):
        lang = json.loads((ROOT / 'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
        boards = (ROOT / 'source-families/1.21.X/src/main/java/buildcraft/robotics/BCRoboticsBoards.java').read_text(encoding='utf-8')
        keys = re.findall(r'board\("[^"]+", "([a-z_]+)",', boards)
        self.assertEqual(18, len(keys), keys)  # 17 specialized robot boards + the empty board
        for board in keys:
            with self.subTest(board=board):
                name = lang.get('item.buildcraftrobotics.robot.' + board)
                self.assertTrue(name, board)
                self.assertTrue(name.endswith('Robot'), (board, name))
        self.assertEqual('Picker Robot', lang['item.buildcraftrobotics.robot.picker'])
        self.assertNotIn('buildcraft.jade.robot.board', lang)
        self.assertIn('buildcraft.jade.robot.board', json.loads((ROOT / 'scripts/tests/localization_dedupe_manifest.json').read_text(encoding='utf-8'))['aliases'])


if __name__ == '__main__':
    unittest.main()
