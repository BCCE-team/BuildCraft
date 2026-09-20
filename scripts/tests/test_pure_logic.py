#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import unittest

from pure_logic_fixture import run

ROOT = Path(__file__).resolve().parents[2]
LOGIC = ROOT / "source-shared/src/main/java/buildcraft/lib/logic"


class PureLogic(unittest.TestCase):
    def test_actual_logic_compiles_and_runs_on_plain_java17(self):
        print(run())

    def test_logic_package_has_no_minecraft_loader_or_public_api_dependency(self):
        files = sorted(LOGIC.rglob("*.java"))
        self.assertGreaterEqual(len(files), 7)
        forbidden = re.compile(r"\b(?:net\.minecraft|net\.minecraftforge|net\.neoforged|net\.fabricmc|com\.mojang|buildcraft\.api)\b")
        for path in files:
            text = path.read_text(encoding="utf-8")
            self.assertIsNone(forbidden.search(text), str(path))

    def test_production_code_uses_the_pure_algorithms(self):
        expected = {
            "source-shared/src/main/java/buildcraft/transport/internal/pipe/PipeEventItem.java": "PriorityGroups.group",
            "source-shared/src/main/java/buildcraft/robotics/zone/ZonePlan.java": "ChunkGridMath.",
            "source-shared/src/main/java/buildcraft/lib/misc/RotationUtil.java": "BlueprintRotation.rotateUnit",
            "source-platforms/forge/src/main/java/buildcraft/transport/pipe/Pipe.java": "WeightedOrder.order",
            "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java": "WeightedOrder.order",
            "source-platforms/forge/src/main/java/buildcraft/robotics/tile/TileRequester.java": "RequestMath.",
            "source-platforms/neoforge/src/main/java/buildcraft/robotics/tile/TileRequester.java": "RequestMath.",
        }
        for rel, token in expected.items():
            self.assertIn(token, (ROOT / rel).read_text(encoding="utf-8"), rel)

        for rel in (
            "source-platforms/forge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
            "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
        ):
            text = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("WeightedAllocation", text, rel)
            self.assertIn("EnergyMath", text, rel)

        for rel in (
            "source-platforms/forge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java",
            "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java",
        ):
            text = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("WeightedAllocation", text, rel)
            self.assertIn("EnergyMath", text, rel)

        for rel in (
            "source-platforms/forge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java",
            "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java",
            "source-family-platforms/modern/neoforge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java",
        ):
            self.assertIn("EqualFlowMath.share", (ROOT / rel).read_text(encoding="utf-8"), rel)


if __name__ == "__main__":
    unittest.main(verbosity=2)
