#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
errors: list[str] = []


def text(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="ignore")

# Public MJ v1 namespace must be physically gone.
for path in ROOT.rglob("*.java"):
    if any(part in {"build", ".gradle", ".git"} for part in path.parts):
        continue
    data = path.read_text(encoding="utf-8", errors="ignore")
    if re.search(r"\b(?:package|import)\s+buildcraft\.api\.mj(?:\.|;)", data):
        errors.append(f"legacy MJ API reference remains: {path.relative_to(ROOT)}")

energy = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/EnergyServiceImpl.java")
for required in ("MjPortProvider", "MjRuntimeLookup.port", "MjRuntimeLookup.descriptor", "canConnect(MjConnectionContext"):
    if required not in energy:
        errors.append(f"EnergyServiceImpl is missing runtime API2 hook: {required}")

runtime = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java")
for service in ("BuildCraftServices.MACHINES", "BuildCraftServices.LASER_TARGETS", "BuildCraftServices.MJ_FORMATTER", "BuildCraftServices.POWER_LOSS_EFFECTS"):
    if service not in runtime:
        errors.append(f"BuildCraftApiRuntime does not install {service}")

builtins = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuiltInApi2Content.java")
for engine in ("REDSTONE", "STONE", "IRON", "CREATIVE", "FE", "MJ_DYNAMO"):
    if f"BuildCraftContentIds.Engines.{engine}" not in builtins:
        errors.append(f"built-in EngineType missing: {engine}")

for platform in ("forge", "neoforge"):
    base = text(f"source-platforms/{platform}/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java")
    for required in ("EngineView, MjPortProvider", "BuildCraftServices.ENERGY", "getPortToPower", "OperationMode.SIMULATE", "OperationMode.EXECUTE"):
        if required not in base:
            errors.append(f"{platform} engine base missing API2 runtime hook: {required}")
    if "getReceiverToPower(" in base:
        errors.append(f"{platform} engine base still routes output through legacy IMjReceiver lookup")

    bridge = text(f"source-platforms/{platform}/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java")
    for required in ("MjRuntimeLookup.Backend", "automaticFeConversionEnabled", "MjConnectionContext"):
        if required not in bridge:
            errors.append(f"{platform} MJ platform bridge missing: {required}")

    laser = text(f"source-platforms/{platform}/src/main/java/buildcraft/silicon/tile/TileLaser.java")
    table = text(f"source-platforms/{platform}/src/main/java/buildcraft/silicon/tile/TileLaserTableBase.java")
    if "BuildCraftServices.LASER_TARGETS" not in laser or "target.laserPort()" not in laser:
        errors.append(f"{platform} laser runtime is not using API2 LaserTargetService/MjPort")
    if "implements LaserTarget" not in table or "public MjPort laserPort()" not in table:
        errors.append(f"{platform} laser table is not an API2 LaserTarget")
    if "ILaserTarget" in laser + table:
        errors.append(f"{platform} laser runtime still references legacy ILaserTarget")

machine_files = [
    "version-src/1.19.2-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
    "source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
    "source-platforms/forge/src/main/java/buildcraft/factory/tile/TileDistiller.java",
    "source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TileDistiller.java",
    "source-platforms/forge/src/main/java/buildcraft/factory/tile/TilePump.java",
    "source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TilePump.java",
    "version-src/1.19.2-forge/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
    "source-families/modern/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
]
for rel in machine_files:
    data = text(rel)
    if "MachineRuntimeView" not in data or "api2MachineTypeId()" not in data:
        errors.append(f"built-in machine is not exposed through MachineService/API2: {rel}")

for rel in (
    "source-shared/src/main/java/buildcraft/lib/internal/mj/ILaserTarget.java",
    "source-shared/src/main/java/buildcraft/lib/internal/mj/ILaserTargetBlock.java",
):
    if (ROOT / rel).exists():
        errors.append(f"obsolete laser target interface remains: {rel}")


fixture = text("addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java")
for required in ("BuildCraftServices.ENERGY).port", "BuildCraftServices.MACHINES).machine", "BuildCraftServices.LASER_TARGETS).target"):
    if required not in fixture:
        errors.append(f"addon fixture does not prove public runtime lookup: {required}")

if errors:
    print("Energy/Engine/Machine API2 migration FAILED:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Energy/Engine/Machine API2 migration OK: legacy MJ API retired, engines/machines/lasers use API2 runtime services")
