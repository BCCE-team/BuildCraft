"""Regression for an accidental @Override before a constructor.

Check the *effective* source emitted for every maintained target, then let a
real Java 17 compiler parse the exact overloaded constructor declaration.
The isolated fixture only replaces Minecraft types; it does not rewrite the
constructor under test.
"""
from __future__ import annotations

import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_lookup import read_materialized_java

TARGETS = (
    "1.19.2-forge",
    "1.20.1-forge",
    "1.21.1-neoforge",
    "1.21.11-neoforge",
    "26.1.2-neoforge",
    "26.2-neoforge",
    "26.3-neoforge",
    "26.3-fabric",
)
LOGICAL = "src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java"


class SearchBlockConstructorTests(unittest.TestCase):
    def test_no_override_on_constructors_in_effective_sources(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = read_materialized_java(target, LOGICAL)
                self.assertNotRegex(
                    source,
                    r"@Override\s+public\s+AIRobotSearchBlock\s*\(",
                    "Constructors do not override superclass methods",
                )
                self.assertRegex(
                    source,
                    r"public AIRobotSearchBlock\(EntityRobotBase robot, boolean random, IBlockFilter filter,\s*"
                    r"double maxDistanceToEnd, java\.util\.List<BlockPos> exclusiveTargets, boolean exclusiveOnly\)",
                )

    def test_real_java17_compiler_accepts_the_emitted_constructor(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix="bcce-constructor-") as temp:
                source = read_materialized_java(target, LOGICAL)
                start = source.find("public AIRobotSearchBlock(EntityRobotBase robot, boolean random, IBlockFilter filter,\n"
                                    "            double maxDistanceToEnd, java.util.List<BlockPos> exclusiveTargets, boolean exclusiveOnly) {")
                self.assertGreaterEqual(start, 0, target)
                # Include an annotation if it is immediately above the constructor;
                # this makes javac reproduce the original @Override failure.
                previous_line = source[:start].splitlines()[-1].strip()
                if previous_line == "@Override":
                    start = source.rfind("@Override", 0, start)
                opening = source.index("{", start)
                depth = 1
                end = opening + 1
                while depth:
                    depth += (source[end] == "{") - (source[end] == "}")
                    end += 1
                constructor = source[start:end]
                fixture = """
import java.util.List;
class EntityRobotBase {}
interface IBlockFilter {}
class BlockPos {}
public class AIRobotSearchBlock {
    private boolean exclusiveTargetsOnly;
    public AIRobotSearchBlock(EntityRobotBase robot, boolean random, IBlockFilter filter,
            double maxDistanceToEnd, List<BlockPos> preferred) {}
    /* CONSTRUCTOR */
}
""".replace("/* CONSTRUCTOR */", constructor)
                path = Path(temp) / "AIRobotSearchBlock.java"
                path.write_text(fixture, encoding="utf-8")
                result = subprocess.run(
                    ["javac", "--release", "17", "-d", temp, str(path)],
                    capture_output=True, text=True, timeout=25,
                )
                self.assertEqual(result.returncode, 0, f"{target}: {result.stderr}")

    def test_no_accidental_override_on_other_constructors(self):
        for directory in ("source-shared", "source-families", "source-platforms",
                          "source-family-platforms", "source-downports", "version-src"):
            for path in (ROOT / directory).rglob("*.java"):
                source = path.read_text(encoding="utf-8")
                match = re.search(
                    r"@Override\s+(?:public|protected|private)\s+"
                    + re.escape(path.stem) + r"\s*\(", source,
                )
                self.assertIsNone(match, str(path.relative_to(ROOT)))


if __name__ == "__main__":
    unittest.main()
