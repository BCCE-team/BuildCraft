#!/usr/bin/env python3
"""Regression tests for 1.19.2/1.20.1 Forge compiler compatibility.

Read the effective materialized classes, not just their maintained source owner,
so source-family overlays and target-specific preprocessing remain covered.
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
    "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)
SOURCES = (
    "builders/internal/schematic/api2/Api2SchematicEntity.java",
    "lib/recipe/MachineRecipeApiBridge.java",
    "robotics/internal/api2/RobotServiceImpl.java",
    "robotics/SimpleRobotRegistryProvider.java",
    "transport/pipe/flow/PipeFlowFluids.java",
    "transport/wire/MessageWireSystems.java",
    "lib/recipe/AssemblyRecipeBasic.java",
)


class OldForgeCompilerRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="bcce-old-compiler-")
        cls.effective = {}
        props = load_properties()
        for target in TARGETS:
            layout = target_layout(target, props)
            downports = tuple(p for p in (
                layout.family_downport_root, layout.family_platform_downport_root
            ) if p is not None)
            for name in SOURCES:
                relative = "src/main/java/buildcraft/" + name
                source = resolve_effective_source(layout, props, relative)
                if source is None:
                    continue
                dest = Path(cls.temp.name) / target / relative
                _materialize_text_file(
                    source, dest, logical_relative=relative,
                    minecraft=props[f"target.{target}.deps.minecraft"],
                    family=layout.family, platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports),
                )
                cls.effective[(target, name)] = dest.read_text(encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def source(self, target, name):
        self.assertIn((target, name), self.effective)
        return self.effective[(target, name)]

    def test_1192_compatible_schematic_positions(self):
        source = self.source("1.19.2-forge", SOURCES[0])
        self.assertNotIn("BlockPos.containing(getPos())", source)
        self.assertIn("new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z))", source)
        self.assertEqual(source.count("relativeBlockPos()"), 4)  # declaration + 3 usages

    def test_forge_registry_works_before_built_in_registries(self):
        for target in TARGETS[:2]:
            source = self.source(target, SOURCES[1])
            with self.subTest(target=target):
                self.assertIn("ForgeRegistries.FLUIDS.getValues()", source)
                self.assertIn("ForgeRegistries.FLUIDS.getKey(fluid)", source)
                self.assertNotIn("BuiltInRegistries.FLUID", source)

    def test_robot_world_accessor_is_preprocessed_for_1192(self):
        for target in TARGETS[:2]:
            source = self.source(target, SOURCES[2])
            with self.subTest(target=target):
                self.assertIn("level(robot) instanceof ServerLevel serverLevel", source)
                self.assertNotIn("robot.level() instanceof ServerLevel", source)
        self.assertIn("return robot.level;", self.source("1.19.2-forge", SOURCES[2]))
        self.assertIn("return robot.level();", self.source("1.20.1-forge", SOURCES[2]))

    def test_private_robot_lease_cleanup_has_no_override(self):
        for target in TARGETS:
            source = self.source(target, SOURCES[3])
            with self.subTest(target=target):
                self.assertNotIn("@Override\n        private boolean expireOrphanedRestoredLease", source)
                self.assertIn("private boolean expireOrphanedRestoredLease", source)

    def test_forge_fluid_clones_keep_stack_tags(self):
        for target in TARGETS[:2]:
            source = self.source(target, SOURCES[4])
            with self.subTest(target=target):
                self.assertNotIn("toAdd.copyWithAmount(", source)
                self.assertIn("FluidStack copied = original.copy();", source)
                self.assertIn("copied.setAmount(Math.max(0, millibuckets));", source)
                self.assertIn("restoreUnexpectedFluid(fluidHandler, copyWithAmount(toAdd,", source)

    def test_forge_assembly_equality_handles_facade_jei_wrappers(self):
        # Execute the actual materialized equals implementation with the same
        # subclass hierarchy used by facades and JEI. List.equals exercises
        # the recipe reconciliation path that crashed clients on 8.0.24.
        for target in TARGETS[:2]:
            source = self.source(target, SOURCES[6])
            start = source.index("public boolean equals(Object o)")
            opening = source.index("{", start)
            depth = 1
            end = opening + 1
            while depth:
                depth += (source[end] == "{") - (source[end] == "}")
                end += 1
            equals_method = source[start:end]
            fixture = """import java.util.List;
public class AssemblyEqualsProbe {
    static abstract class AssemblyRecipeBasic {
        protected final String name;
        AssemblyRecipeBasic(String name) { this.name = name; }
        /* EQUALS_METHOD */
    }
    static final class AssemblyRecipe extends AssemblyRecipeBasic {
        AssemblyRecipe(String name) { super(name); }
    }
    static class FacadeAssemblyRecipes extends AssemblyRecipeBasic {
        FacadeAssemblyRecipes(String name) { super(name); }
    }
    static final class FacadeAssemblyJeiRecipe extends FacadeAssemblyRecipes {
        FacadeAssemblyJeiRecipe(String name) { super(name); }
    }
    public static void main(String[] args) {
        List<AssemblyRecipeBasic> oldRecipes = List.of(new FacadeAssemblyJeiRecipe("facades"));
        List<AssemblyRecipeBasic> refreshedRecipes = List.of(new FacadeAssemblyJeiRecipe("facades"));
        if (!oldRecipes.equals(refreshedRecipes)) throw new AssertionError("same facade recipe not equal");
        if (oldRecipes.equals(List.of(new FacadeAssemblyJeiRecipe("other"))))
            throw new AssertionError("different facade ids compared equal");
        if (oldRecipes.equals(List.of(new AssemblyRecipe("facades"))))
            throw new AssertionError("different recipe subclasses compared equal");
    }
}
""".replace("/* EQUALS_METHOD */", equals_method)
            with tempfile.TemporaryDirectory(prefix="bcce-facade-equals-") as temp_dir:
                src = Path(temp_dir) / "AssemblyEqualsProbe.java"
                src.write_text(fixture, encoding="utf-8")
                subprocess.run(["javac", str(src)], check=True, capture_output=True, text=True)
                subprocess.run(["java", "-cp", temp_dir, "AssemblyEqualsProbe"],
                               check=True, capture_output=True, text=True)

    def test_wire_snapshots_only_send_to_server_players(self):
        for target in TARGETS:
            source = self.source(target, SOURCES[5])
            with self.subTest(target=target):
                self.assertIn("player instanceof ServerPlayer serverPlayer", source)
                self.assertIn("new MessageWireSystems(page, continuing, first, false), serverPlayer)", source)
                self.assertIn("new MessageWireSystems(page, continuing, first, true), serverPlayer)", source)
                self.assertNotIn("new MessageWireSystems(page, continuing, first, true), player)", source)


if __name__ == "__main__":
    unittest.main()
