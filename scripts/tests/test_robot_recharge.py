#!/usr/bin/env python3
"""Real Java AI decision regressions against materialized sources for all supported Minecraft versions."""
from __future__ import annotations

import tempfile
import sys
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import effective_source_files,_materialize_text_file
from robot_recharge_fixture import execute

TARGETS=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
         '26.1.2-neoforge','26.2-neoforge','26.3-neoforge')
PREFIX='src/main/java/buildcraft/robotics/ai/'
CLASSES=('AIRobotMain.java','AIRobotRecharge.java','AIRobotSleep.java','AIRobotSearchStation.java','AIRobotSearchAndGotoStation.java')

class RobotRechargeBehaviorTests(unittest.TestCase):
    def test_recharge_priority_in_all_targets(self):
        props=load_properties()
        with tempfile.TemporaryDirectory(prefix='bc-seven-robot-ai-') as temp:
            for target in TARGETS:
                with self.subTest(target=target):
                    layout=target_layout(target,props)
                    effective=effective_source_files(layout,props,'src/main/java/buildcraft/robotics')
                    downs=tuple(p for p in (layout.family_downport_root,layout.family_platform_downport_root) if p)
                    result=[]
                    for name in CLASSES:
                        relative=PREFIX+name
                        source=effective[relative]
                        dst=Path(temp)/target/name
                        _materialize_text_file(source,dst,logical_relative=relative,
                            minecraft=props['target.'+target+'.deps.minecraft'],family=layout.family,
                            platform=layout.platform,preprocess=True,
                            native_source=any(source.is_relative_to(down) for down in downs))
                        result.append(dst)
                    output=execute(*result)
                    self.assertEqual('30 assertions PASS',output)
                    print(target+': '+output,flush=True)

if __name__=='__main__':unittest.main()
