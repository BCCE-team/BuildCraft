"""Regression: real ProgrammingRecipeAccess computes a cost range from active variants.

Runs the actual production Java class against minimal API mocks. This does not require
Minecraft/Forge libraries, but is more useful than only checking source fragments.
"""
from __future__ import annotations
import pathlib
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = ROOT / "source-shared/src/main/java/buildcraft/lib/recipe/ProgrammingRecipeAccess.java"

class ProgrammingTooltipRangeTest(unittest.TestCase):
    def test_real_java_range_and_deduplication(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java SDK not installed")
        helpers = {
            "buildcraft/api/v2/BuildCraftApi.java": """
                package buildcraft.api.v2;
                import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
                import java.util.List;
                public final class BuildCraftApi {
                    public record Match<T>(T recipe) {}
                    public static final class Service {
                        public <T> List<Match<T>> recipes(Class<T> type) { return List.of(); }
                    }
                    public static Service service(Object key) { return new Service(); }
                }
            """,
            "buildcraft/api/v2/BuildCraftServices.java": """
                package buildcraft.api.v2;
                public final class BuildCraftServices {
                    public static final Object MACHINE_RECIPES = new Object();
                }
            """,
            "buildcraft/api/v2/recipe/ProgrammingRecipeDefinition.java": """
                package buildcraft.api.v2.recipe;
                public record ProgrammingRecipeDefinition(String boardKey, long requiredMicroJoules) {}
            """,
            "buildcraft/lib/recipe/ClientMachineRecipeCatalog.java": """
                package buildcraft.lib.recipe;
                import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
                import java.util.List;
                public final class ClientMachineRecipeCatalog {
                    public static List<ProgrammingRecipeDefinition> active = List.of();
                    @SuppressWarnings("unchecked")
                    public static <T> List<T> visibleRecipes(Class<T> type) {
                        return (List<T>)(List<?>)active;
                    }
                }
            """,
            "CostRangeTest.java": """
                import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
                import buildcraft.lib.recipe.ClientMachineRecipeCatalog;
                import buildcraft.lib.recipe.ProgrammingRecipeAccess;
                import java.util.List;
                public class CostRangeTest {
                    static ProgrammingRecipeDefinition recipe(String board, long cost) {
                        return new ProgrammingRecipeDefinition(board, cost);
                    }
                    static void check(boolean value, String label) {
                        if (!value) throw new AssertionError(label);
                    }
                    public static void main(String[] args) {
                        ClientMachineRecipeCatalog.active = List.of(
                            recipe("miner", 3_200_000_000L),
                            recipe("miner", 1_000_000L),
                            recipe("miner", 1_000_000L),
                            recipe("picker", 400_000_000L)
                        );
                        check(ProgrammingRecipeAccess.options(true).size() == 3, "content dedup");
                        var range = ProgrammingRecipeAccess.costRange("miner", true).orElseThrow();
                        check(range.minMicroMj() == 1_000_000L, "minimum MJ");
                        check(range.maxMicroMj() == 3_200_000_000L, "maximum MJ");
                        var single = ProgrammingRecipeAccess.costRange("picker", true).orElseThrow();
                        check(single.minMicroMj() == single.maxMicroMj(), "single cost");
                        check(ProgrammingRecipeAccess.costRange("missing", true).isEmpty(), "missing recipe");
                        // Simulate a blacklist-filtered client catalog: the expensive ID
                        // disappeared from the visible snapshot and MUST NOT raise max.
                        ClientMachineRecipeCatalog.active = List.of(recipe("miner", 1_000_000L));
                        var filtered = ProgrammingRecipeAccess.costRange("miner", true).orElseThrow();
                        check(filtered.minMicroMj() == filtered.maxMicroMj(), "blacklist-filtered max");
                    }
                }
            """,
        }
        with tempfile.TemporaryDirectory(prefix="bcce_programming_tooltip_") as temp:
            root = pathlib.Path(temp)
            actual = root / "buildcraft/lib/recipe/ProgrammingRecipeAccess.java"
            actual.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE, actual)
            files = [actual]
            for name, code in helpers.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(textwrap.dedent(code), encoding="utf-8")
                files.append(path)
            out = root / "classes"
            out.mkdir()
            result = subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(out), *map(str, files)],
                                    capture_output=True, text=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", str(out), "CostRangeTest"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)

if __name__ == "__main__":
    unittest.main()
