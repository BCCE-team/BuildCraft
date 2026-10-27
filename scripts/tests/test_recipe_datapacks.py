"""Validate BCCE built-in data-driven machine recipes and deny-wins wiring."""
from __future__ import annotations

import json
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class DataDrivenRecipesTest(unittest.TestCase):
    def test_built_in_distillation_and_heat_exchange(self):
        for family, directory in (("old", "recipes"), ("1.21.X", "recipe"), ("26.X", "recipe")):
            with self.subTest(family=family):
                base = ROOT / "source-families" / family / "src/main/resources/data/buildcraftfactory" / directory
                distiller = sorted((base / "distiller").rglob("*.json"))
                heat = sorted((base / "heat_exchanger").rglob("*.json"))
                self.assertEqual(len(distiller), 10)
                self.assertEqual(len(heat), 42)
                manifest = json.loads((base.parent / "bundled_machine_recipes.json").read_text())
                self.assertEqual(len(manifest), 52)
                self.assertEqual(set(manifest), {str(f.relative_to(base.parent)).replace("\\", "/") for f in [*distiller, *heat]})
                fluid_inputs = set()
                for path in distiller:
                    data = json.loads(path.read_text(encoding="utf-8"))
                    self.assertEqual(data["type"], "buildcraftfactory:distillation")
                    self.assertGreater(data["input"]["amount_mb"], 0)
                    self.assertGreater(data["energy"]["mj"], 0)
                    self.assertTrue(data["outputs"])
                    self.assertGreater(sum(v["amount_mb"] for v in data["outputs"].values()), 0)
                    for obj in (data["input"], *data["outputs"].values()):
                        self.assertRegex(obj["fluid"], r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")
                    self.assertNotIn(data["input"]["fluid"], fluid_inputs)
                    fluid_inputs.add(data["input"]["fluid"])
                for path in heat:
                    data = json.loads(path.read_text(encoding="utf-8"))
                    self.assertEqual(data["type"], "buildcraftfactory:heat_exchange")
                    self.assertGreater(data["input"]["amount_mb"], 0)
                    if data["mode"] == "heating":
                        self.assertGreater(data["heat_to"], data["heat_from"])
                    elif data["mode"] == "cooling":
                        self.assertLess(data["heat_to"], data["heat_from"])
                    else:
                        self.fail("Invalid heat mode: " + str(path))
                    if "output" in data:
                        self.assertGreater(data["output"]["amount_mb"], 0)

    def test_multitarget_loader_and_native_serializers(self):
        for target in (
            "version-src/1.19.2-forge", "version-src/1.20.1-forge",
            "source-downports/1.21.X/1.21.1/neoforge",
            "source-family-platforms/1.21.X/neoforge",
            "source-downports/26.X/26.1.2/neoforge", "source-families/26.X",
        ):
            file = ROOT / target / "src/main/java/buildcraft/lib/BCLibEventDist.java"
            with self.subTest(file=file):
                self.assertIn("reloadMachineRecipes", file.read_text())
                self.assertIn("MachineRecipeDatapackReloadListener", file.read_text())
        for folder in ("version-src/1.19.2-forge", "version-src/1.20.1-forge",
                       "source-families/1.21.X", "source-families/26.X"):
            file = ROOT / folder / "src/main/java/buildcraft/factory/BCFactoryRecipeSerializers.java"
            self.assertIn("DISTILLATION", file.read_text())
            self.assertIn("HEAT_EXCHANGE", file.read_text())

    def test_deny_wins_implementation(self):
        loader = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/MachineRecipeDatapackReloadListener.java").read_text()
        policy = (ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/RecipeBlacklist.java").read_text()
        self.assertIn("listResourceStacks", loader)
        self.assertIn("for (Object resource : entry.getValue())", loader)
        self.assertIn("mergeFailClosed", loader)
        self.assertIn("mergeFailClosed", policy)
        self.assertIn("new Snapshot(current.generation() + 1", policy)
        self.assertIn("Duplicate recipe ID", loader)
        self.assertIn("Duplicate recipe content", loader)
        self.assertIn("Overlapping machine recipe input", loader)

    def test_policy_real_java(self):
        """Compile and run the actual immutable blacklist holder with tiny MC stubs."""
        policy = ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/RecipeBlacklist.java"
        with tempfile.TemporaryDirectory(prefix="bcce-recipe-policy-") as td:
            base = Path(td)
            stubs = {
                "net/minecraft/resources/ResourceLocation.java": '''
                    package net.minecraft.resources;
                    public record ResourceLocation(String id) {
                        public static ResourceLocation tryParse(String s) { return new ResourceLocation(s); }
                    }
                ''',
                "buildcraft/api/v2/recipe/RecipeDefinition.java": '''
                    package buildcraft.api.v2.recipe;
                    public interface RecipeDefinition {
                        enum Kind {INTEGRATION, DISTILLATION, HEATING, COOLING, ASSEMBLY, PROGRAMMING}
                    }
                ''',
                "buildcraft/lib/recipe/BlacklistExercise.java": '''
                    package buildcraft.lib.recipe;
                    import java.util.*;
                    import net.minecraft.resources.ResourceLocation;
                    public class BlacklistExercise {
                      public static void main(String[] args) {
                        var oil = ResourceLocation.tryParse("bc:distiller/oil");
                        var steam = ResourceLocation.tryParse("other:steam");
                        RecipeBlacklist.publish(Map.of("distiller", Set.of(oil)));
                        if (!RecipeBlacklist.isDenied("distiller", oil)) throw new AssertionError("ban missing");
                        if (RecipeBlacklist.isDenied("assembly_table", oil)) throw new AssertionError("wrong machine");
                        RecipeBlacklist.mergeFailClosed(Map.of("distiller", Set.of(steam)));
                        if (!RecipeBlacklist.isDenied("distiller", oil) || !RecipeBlacklist.isDenied("distiller", steam))
                            throw new AssertionError("merging must not lift old ban");
                        RecipeBlacklist.publish(Map.of());
                        if (RecipeBlacklist.isDenied("distiller", oil)) throw new AssertionError("successful reload must lift removed bans");
                        System.out.println("deny wins PASS");
                      }
                    }
                ''',
            }
            inputs = [policy]
            for name, body in stubs.items():
                dest = base / name
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(textwrap.dedent(body), encoding="utf-8")
                inputs.append(dest)
            classes = base / "classes"
            compiled = subprocess.run(["javac", "--release", "17", "-d", str(classes),
                                       *map(str, inputs)], capture_output=True, text=True, timeout=20)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(["java", "-cp", str(classes), "buildcraft.lib.recipe.BlacklistExercise"],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("deny wins PASS", run.stdout)


if __name__ == "__main__":
    unittest.main()
