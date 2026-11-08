"""Built-in solid coolant coverage for every BuildCraft source family.

The iron engine resolves solid coolants through the API v2 registry. These
checks prevent Stonecutter family/downport overrides from silently removing
vanilla ice variants or changing their conversion rates.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
    "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)

RECIPE_SOURCES = (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/BCEnergyRecipes.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/BCEnergyRecipes.java",
    "source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/energy/BCEnergyRecipes.java",
    "source-families/1.21.X/src/main/java/buildcraft/energy/BCEnergyRecipes.java",
    "source-families/26.X/src/main/java/buildcraft/energy/BCEnergyRecipes.java",
)

ENGINE_SOURCES = (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "source-downports/26.X/26.1.2/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "source-downports/26.X/26.2/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
)

EXPECTED = {"ice": ("ICE", 1.5), "packed_ice": ("PACKED_ICE", 2.0), "blue_ice": ("BLUE_ICE", 3.0)}
REGISTRATION = re.compile(
    r'registerSolidCoolant\("solid_coolant/([a-z_]+)",\s*Blocks\.([A-Z_]+)\.asItem\(\),\s*([\d.]+)\);'
)


class VanillaIceCoolantTest(unittest.TestCase):
    def test_all_ice_types_registered_for_every_source_family(self):
        for relative in RECIPE_SOURCES:
            with self.subTest(source=relative):
                source = (ROOT / relative).read_text(encoding="utf-8")
                definitions = REGISTRATION.findall(source)
                self.assertEqual(len(definitions), len(EXPECTED), "Missing or duplicated solid coolant")
                self.assertEqual(
                    {name: (block, float(multiplier)) for name, block, multiplier in definitions},
                    EXPECTED,
                )
                # Each block is converted to water through the same public API v2 profile.
                self.assertIn("new SolidCoolantProfile(", source)
                self.assertIn("stack.getCount() * 1000.0 * multiplier", source)
                self.assertIn("FluidVolume.of(water, FluidAmount.of(amount))", source)

    def test_blue_ice_survives_all_seven_effective_target_overrides(self):
        properties = load_properties()
        logical = "src/main/java/buildcraft/energy/BCEnergyRecipes.java"
        for target in TARGETS:
            with self.subTest(target=target):
                resolved = resolve_effective_source(target_layout(target, properties), properties, logical)
                self.assertIsNotNone(resolved, "Energy recipe implementation missing")
                text = resolved.read_text(encoding="utf-8")
                self.assertIn(
                    'registerSolidCoolant("solid_coolant/blue_ice", Blocks.BLUE_ICE.asItem(), 3.0);',
                    text,
                )

    def test_iron_engines_accept_registered_solid_coolants(self):
        for relative in ENGINE_SOURCES:
            with self.subTest(source=relative):
                source = (ROOT / relative).read_text(encoding="utf-8")
                self.assertIn("energyFluids().findSolidCoolant(stack)", source)
                self.assertIn("match.profile().convert(stack)", source)
                self.assertIn("return new FluidGetResult(StackUtil.EMPTY, fluidCoolant);", source)
                self.assertIn("solidCoolantLoaded = true;", source)
                self.assertIn("super.transferStackToTank(player, stack)", source)


if __name__ == "__main__":
    unittest.main()
