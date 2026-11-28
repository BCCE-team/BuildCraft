#!/usr/bin/env python3
"""Compile BCCE's real oil profile/config against tiny Java API doubles.

The real oil generator selects the config preset in every supported target;
this fixture verifies the enum values and config lifecycle without Gradle.
"""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

CONFIGS = (
    "source-families/old/src/main/java/buildcraft/energy/BCEnergyConfig.java",
    "source-families/1.21.X/src/main/java/buildcraft/energy/BCEnergyConfig.java",
    "source-family-platforms/26.X/fabric/src/main/java/buildcraft/energy/BCEnergyConfig.java",
)

STUBS = {
    "buildcraft/energy/BCEnergy.java": """package buildcraft.energy;
public final class BCEnergy {
  public static final String MODID = "buildcraftenergy";
  public static void refreshFlammability() {}
}
""",
    "buildcraft/energy/generation/features/OilGenerator.java": """package buildcraft.energy.generation.features;
public final class OilGenerator {
  public static int clears;
  public static void clearCache() { clears++; }
}
""",
    "buildcraft/lib/internal/debug/BCLog.java": """package buildcraft.lib.internal.debug;
public final class BCLog {
  public static final Logger logger = new Logger();
  public static final class Logger { public void warn(String message, Object... params) {} }
}
""",
    "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
public final class ResourceLocation {
  public static ResourceLocation tryParse(String value) { return new ResourceLocation(); }
}
""",
    "net/minecraft/resources/Identifier.java": """package net.minecraft.resources;
public final class Identifier {
  public static Identifier tryParse(String value) { return new Identifier(); }
}
""",
    "fixture/VerifyOilProfiles.java": r"""package fixture;
import buildcraft.energy.BCEnergy;
import buildcraft.energy.BCEnergyConfig;
import buildcraft.energy.OilIndustry;
import buildcraft.energy.generation.features.OilGenerator;
import buildcraft.energy.generation.features.OilGenerationProfile;
import buildcraft.lib.platform.config.BCConfigSpec;
import java.util.function.Supplier;

public final class VerifyOilProfiles {
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
  static void equalsDouble(double expected, double actual, String message) {
    if (Math.abs(expected - actual) > 0.00000001D) throw new AssertionError(message + ": " + actual);
  }
  static final class Backend implements BCConfigSpec.Backend {
    OilGenerationProfile chosen = OilGenerationProfile.STANDARD;
    OilIndustry.Mode industryMode = OilIndustry.Mode.MODERN;
    double customRate = 0.3D;
    boolean blockDamage = true;
    boolean restartPending;
    int choices;
    int modeChoices;
    public void push(String path) {}
    public void pop() {}
    public void comment(String... lines) {}
    public void worldRestart() { restartPending = true; }
    public Supplier<Boolean> bool(String name, boolean value) {
      if ("engineExplosionBlockDamage".equals(name)) {
        check(value && restartPending, "Engine explosion default changed");
        restartPending = false; return () -> blockDamage;
      }
      restartPending = false;
      return () -> value;
    }
    public Supplier<Integer> integer(String name, int value, int min, int max) {
      restartPending = false;
      return () -> value;
    }
    public Supplier<Double> decimal(String name, double value, double min, double max) {
      if ("customGenerationRate".equals(name)) {
        check(restartPending && value == 0.3D && min == 0D && max == 1D, "Invalid custom range/default");
        restartPending = false; return () -> customRate;
      }
      restartPending = false;
      return () -> value;
    }
    public Supplier<String> string(String name, String value) {
      restartPending = false;
      return () -> value;
    }
    @SuppressWarnings("unchecked")
    public <E extends Enum<E>> Supplier<E> enumeration(String name, E value, E[] allowed) {
      if ("mode".equals(name) && value instanceof OilIndustry.Mode) {
        check(restartPending, "Oil industry mode needs a world restart");
        check(value == OilIndustry.Mode.MODERN, "Wrong default industry mode");
        check(allowed.length == 0 || java.util.Set.of(allowed).equals(java.util.Set.of(OilIndustry.Mode.values())),
            "Both industry modes must remain selectable");
        modeChoices++;
        restartPending = false;
        return () -> (E) industryMode;
      }
      if ("generationProfile".equals(name)) {
        check(restartPending, "Oil profile needs a restart for deterministic generation");
        check(value == OilGenerationProfile.STANDARD, "Wrong default preset");
        check(allowed.length == 4, "Expected exactly four user choices");
        for (OilGenerationProfile profile : OilGenerationProfile.values()) {
          boolean present = false;
          for (E allowedValue : allowed) if (allowedValue == profile) present = true;
          check(present, "Missing allowed profile " + profile);
        }
        choices++;
        restartPending = false;
        return () -> (E) chosen;
      }
      restartPending = false;
      return () -> value;
    }
  }
  public static void main(String[] args) {
    OilGenerationProfile[] presets = OilGenerationProfile.values();
    double[] rates = {0.3D, 0.6D, 1.0D, 0.3D};
    double[] factors = {1.0D, 2.0D, (1.0D / 0.3D), 1.0D};
    check(presets.length == 4, "Wrong number of presets");
    check(presets[0].name().equals("STANDARD") && presets[1].name().equals("HIGH")
        && presets[2].name().equals("VERY_HIGH") && presets[3].name().equals("CUSTOM"), "Unstable preset IDs");
    BCEnergyConfig.preInit();
    Backend backend = new Backend();
    BCEnergyConfig.config.bind(backend);
    check(backend.choices == 1, "Oil config option was not registered exactly once");
    check(backend.modeChoices == 1, "Oil industry mode was not registered exactly once");
    for (int i = 0; i < presets.length; i++) {
      backend.chosen = presets[i];
      BCEnergyConfig.onLoadConfig(BCEnergy.MODID);
      check(BCEnergyConfig.oilGenerationProfile == presets[i], "Config reload ignored preset " + i);
      check(OilIndustry.serverMode() == OilIndustry.Mode.MODERN && !OilIndustry.classic(),
          "Modern industry mode was not loaded");
      equalsDouble(rates[i], presets[i].rate(), "Wrong frequency");
      equalsDouble(factors[i], presets[i].multiplier(), "Wrong profile multiplier");
      equalsDouble(factors[i], BCEnergyConfig.oilGenerationMultiplier, "Wrong runtime multiplier");
      equalsDouble(rates[i], 0.3D * presets[i].multiplier(), "Wrong stock datapack rate");
      equalsDouble(2.0D * rates[i], 0.6D * presets[i].multiplier(), "Custom datapack scaling lost");
    }
    backend.customRate = 0.73D; backend.blockDamage = false;
    backend.industryMode = OilIndustry.Mode.CLASSIC;
    BCEnergyConfig.onLoadConfig(BCEnergy.MODID);
    check(OilIndustry.serverMode() == OilIndustry.Mode.CLASSIC && OilIndustry.classic(),
        "Classic industry mode was not loaded");
    equalsDouble(0.73D, 0.3D * BCEnergyConfig.oilGenerationMultiplier, "Custom rate ignored");
    check(!BCEnergyConfig.engineExplosionBlockDamage, "Explosion block damage toggle ignored");
    backend.customRate = 0D;
    BCEnergyConfig.onLoadConfig(BCEnergy.MODID);
    equalsDouble(0D, BCEnergyConfig.oilGenerationMultiplier, "Custom zero ignored");
    backend.chosen = OilGenerationProfile.HIGH;
    backend.industryMode = OilIndustry.Mode.MODERN;
    BCEnergyConfig.onReloadConfig("unrelated_mod");
    check(OilIndustry.serverMode() == OilIndustry.Mode.CLASSIC && OilGenerator.clears == 6,
        "Unrelated config reload changed the industry mode or generation cache");
    BCEnergyConfig.onReloadConfig(BCEnergy.MODID);
    check(OilIndustry.serverMode() == OilIndustry.Mode.MODERN && !OilIndustry.classic(),
        "Modern industry mode was not restored on config reload");
    equalsDouble(2D, BCEnergyConfig.oilGenerationMultiplier, "Preset affected by custom value");
    check(OilGenerator.clears == 7, "Config reload did not clear cached oil structures");
    System.out.println("PASS: four profiles, default 0.3, custom rate, independent preset rates, block damage, cache invalidation, both oil industry modes");
  }
}
""",
}


def verify() -> str:
    """Compile the real config for each loader family, then execute its lifecycle."""
    output = []
    for config in CONFIGS:
        with tempfile.TemporaryDirectory(prefix="bc-oil-profile-") as directory:
            root = Path(directory)
            sources = []
            for relative, text in STUBS.items():
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                sources.append(path)
            for source, target in (
                (ROOT / config, root / "buildcraft/energy/BCEnergyConfig.java"),
                (ROOT / "source-shared/src/main/java/buildcraft/energy/OilIndustry.java",
                 root / "buildcraft/energy/OilIndustry.java"),
                (ROOT / "source-shared/src/main/java/buildcraft/energy/generation/features/OilGenerationProfile.java",
                 root / "buildcraft/energy/generation/features/OilGenerationProfile.java"),
                (ROOT / "source-shared/src/main/java/buildcraft/lib/platform/config/BCConfigSpec.java",
                 root / "buildcraft/lib/platform/config/BCConfigSpec.java"),
            ):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")
                sources.append(target)
            classes = root / "classes"
            classes.mkdir()
            compiled = subprocess.run(
                ["javac", "--release", "17", "-d", str(classes), *map(str, sources)],
                capture_output=True, text=True, timeout=30,
            )
            if compiled.returncode:
                raise AssertionError(f"{config}: javac failed:\n{compiled.stdout}{compiled.stderr}")
            executed = subprocess.run(
                ["java", "-cp", str(classes), "fixture.VerifyOilProfiles"],
                capture_output=True, text=True, timeout=15,
            )
            if executed.returncode:
                raise AssertionError(f"{config}: runtime failed:\n{executed.stdout}{executed.stderr}")
            output.append(f"{config}: {executed.stdout.strip()}")
    return "\n".join(output)


if __name__ == "__main__":
    sys.stdout.write(verify() + "\n")
