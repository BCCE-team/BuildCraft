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

    def test_legacy_gametest_compat_resolves_from_old_family(self):
        relative = "src/gametest/java/buildcraft/gametest/GameTestCompat.java"
        canonical = ROOT / "source-families/old" / relative
        self.assertTrue(canonical.is_file(), "legacy GameTestCompat must belong to old family")
        self.assertFalse((ROOT / "source-platforms/forge" / relative).exists())
        props = load_properties()
        for target in TARGETS[:2]:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                source = resolve_effective_source(layout, props, relative)
                self.assertEqual(source, canonical)
                text = source.read_text(encoding="utf-8")
                self.assertIn("helper.absolutePos(relativePos)", text)
                self.assertIn("return tag.getInt(key);", text)
                self.assertNotIn("net.minecraftforge", text)

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


    def test_pipe_event_handlers_do_not_reflect_client_only_descriptors(self):
        bus = (ROOT / "source-shared/src/main/java/buildcraft/transport/pipe/PipeEventBus.java").read_text(encoding="utf-8")
        scanner = bus.split("private static List<Handler> getHandlers", 1)[1].split("public void registerHandler", 1)[0]
        self.assertNotIn("cls.getDeclaredMethods()", scanner)
        self.assertIn("new ClassReader(input).accept(", scanner)
        self.assertIn("Type.getDescriptor(PipeEventHandler.class)", scanner)
        self.assertIn("MethodType.fromMethodDescriptorString(declaration.descriptor", scanner)
        self.assertIn("findVirtual(cls, declaration.name, signature)", scanner)
        self.assertIn("findStatic(cls, declaration.name, signature)", scanner)
        self.assertIn('"receiveCancelled".equals(key)', scanner)
        self.assertIn('"priority".equals(key)', scanner)
        self.assertIn("list.addAll(getHandlers(superCls))", scanner)

    def test_quarry_fixture_scans_after_world_blocks_are_placed(self):
        for loader in ("forge", "neoforge"):
            text = (ROOT / f"source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemQuarryMiningGameTests.java").read_text(encoding="utf-8")
            self.assertEqual(text.count("rig.activate(helper);"), 3)
            self.assertIn('void activate(GameTestHelper helper)', text)
            self.assertIn('invoke(quarry, "updatePoses");', text)
            self.assertIn('Quarry did not recognise its populated mining area', text)

    def test_farm_game_tests_use_independent_actor_profiles(self):
        for loader in ("forge", "neoforge"):
            text = (ROOT / f"source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemRobotFarmWarehouseGameTests.java").read_text(encoding="utf-8")
            self.assertIn('EntityRobot authorized = rig.robot(helper, AUTHORIZED_AFTER_DENIAL);', text)
            self.assertIn('rig.plant(helper, authorized);', text)
            self.assertIn('\"BCTestBlocked\"', text)
            self.assertIn('EntityRobot robot = rig.robot(helper, WORKER);', text)
            self.assertIn('UUID.fromString("7d298587-30f1-4350-8909-a9adf4f6645e")', text)
            self.assertEqual(text.count('helper.runAfterDelay(8, () -> {'), 2)
            self.assertIn('helper.setBlock(SOIL.above().north(), Blocks.GLOWSTONE.defaultBlockState());', text)
            self.assertIn('Blocks.WHEAT.defaultBlockState().canSurvive(helper.getLevel(), cropPos)', text)
            self.assertIn('helper.getLevel().getRawBrightness(cropPos, 0)', text)
            # Real harvest ItemEntities must not fall out of the Obsidian pipe's
            # pickup range before the first scheduled power offer.
            self.assertIn('for (int x = FARM_ROBOT.getX() - 1; x <= FARM_ROBOT.getX() + 1; x++)', text)
            self.assertIn('for (int z = FARM_ROBOT.getZ() - 1; z <= FARM_ROBOT.getZ() + 1; z++)', text)
            self.assertIn('new BlockPos(x, FARM_ROBOT.getY() - 1, z)', text)
            self.assertIn('"; warehouse wheat=" + wheatDelivered + ", seeds=" + seedsDelivered', text)
            self.assertIn('"; obsidian inFlight="', text)
            self.assertIn('"; remaining world wheat="', text)

    def test_robot_blueprint_parity_guard_requires_canonical_payment(self):
        # This guard must not revert to task.requirements(): those are restored
        # from untrusted robot-task NBT, not calculated from the current blueprint.
        guard = (ROOT / "scripts/validate-behavior-parity.py").read_text(encoding="utf-8")
        self.assertIn('"consumeRobotRequirements(robot, canonicalRequirements);",', guard)
        self.assertNotIn('"consumeRobotRequirements(robot, task.requirements());",', guard)
        for loader in ("forge", "neoforge"):
            path = ROOT / f"source-platforms/{loader}/src/main/java/buildcraft/builders/snapshot/BlueprintBuilder.java"
            source = path.read_text(encoding="utf-8")
            start = source.index("public RobotBuildResult buildRobotTaskResult(")
            end = source.index("private boolean hasRobotRequirements(", start)
            placement = source[start:end]
            with self.subTest(loader=loader):
                self.assertIn("getBuildingInfo().toPlaceRequiredItems[index]", placement)
                self.assertIn("List<ItemStack> canonicalRequirements = StackUtil.mergeSameItems(", placement)
                self.assertIn("!exactRobotRequirements(task.requirements(), canonicalRequirements)", placement)
                self.assertIn("!hasRobotRequirements(robot, canonicalRequirements)", placement)
                self.assertIn("consumeRobotRequirements(robot, canonicalRequirements);", placement)
                self.assertNotIn("consumeRobotRequirements(robot, task.requirements());", placement)
                self.assertLess(
                    placement.index("committed = isBlockCorrect(blockPos);"),
                    placement.index("consumeRobotRequirements(robot, canonicalRequirements);"),
                )

    def test_blueprint_sync_failure_is_verified_only_on_windows(self):
        for loader in ("forge", "neoforge"):
            path = ROOT / f"source-platforms/{loader}/src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java"
            text = path.read_text(encoding="utf-8")
            with self.subTest(loader=loader):
                self.assertIn("catch (java.io.SyncFailedException syncFailure)", text)
                self.assertIn('System.getProperty("os.name", "")', text)
                self.assertIn("throw syncFailure;", text)
                self.assertIn("output.getChannel().force(true)", text)
                self.assertIn("validatedCopy(Snapshot.readFromNBT(NbtSquisher.expand(input)))", text)
                self.assertIn("sameContentKey(restored.key, snapshot.key)", text)
                self.assertIn("StandardCopyOption.ATOMIC_MOVE", text)

    def test_long_running_fixtures_are_refueled_with_a_conservation_ledger(self):
        for loader in ("forge", "neoforge"):
            quarry = (ROOT / f"source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemQuarryMiningGameTests.java").read_text(encoding="utf-8")
            energy = (ROOT / f"source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemEnergyGridGameTests.java").read_text(encoding="utf-8")
            with self.subTest(loader=loader):
                self.assertIn("rig.keepPowered(helper, 320, 2420);", quarry)
                self.assertIn("totalInjected += missing;", quarry)
                self.assertIn("rig.maintainFuel(helper)", energy)
                self.assertIn("totalInputFe += inserted;", energy)

    def test_fe_energy_recovery_has_real_io_and_conservation_budget(self):
        for loader in ("forge", "neoforge"):
            text = (ROOT / f"source-platforms/{loader}/src/gametest/java/buildcraft/gametest/SystemEnergyGridGameTests.java").read_text(encoding="utf-8")
            self.assertIn("rig.drainFe(rig.first)", text)
            self.assertIn("rig.drainFe(rig.second)", text)
            self.assertIn("long inserted = engine.externalEnergyPort(Direction.NORTH)", text)
            self.assertIn("totalInputFe += inserted", text)
            self.assertIn("dynamoFe + exportedFe <= totalInputFe", text)


if __name__ == "__main__":
    unittest.main()
