#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
SKIP_PARTS = {"build", ".gradle", ".git"}


def text(path: str) -> str:
    p = resolve_source_path(path)
    if not p.is_file():
        raise FileNotFoundError(path)
    return p.read_text(encoding="utf-8")


def main() -> int:
    errors: list[str] = []

    # The BC7/BC8 extension namespaces are no longer supported public API.
    retired = ("statements", "filler", "gates")
    for path in ROOT.rglob("*.java"):
        if any(part in SKIP_PARTS for part in path.parts):
            continue
        src = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        for package in retired:
            if re.search(rf"^\s*package\s+buildcraft\.api\.{package}(?:\.|;)", src, re.MULTILINE):
                errors.append(f"{rel}: retired buildcraft.api.{package} source remains")
            if re.search(rf"^\s*import\s+(?:static\s+)?buildcraft\.api\.{package}\.", src, re.MULTILINE):
                errors.append(f"{rel}: imports retired buildcraft.api.{package}")

    internal_statement_roots = [
        ROOT / "source-shared/src/main/java/buildcraft/lib/internal/statement",
        ROOT / "source-families/legacy/src/main/java/buildcraft/lib/internal/statement",
        ROOT / "source-families/modern/src/main/java/buildcraft/lib/internal/statement",
        ROOT / "source-platforms/forge/src/main/java/buildcraft/lib/internal/statement",
        ROOT / "source-platforms/neoforge/src/main/java/buildcraft/lib/internal/statement",
    ]
    internal_count = sum(1 for root in internal_statement_roots if root.is_dir() for _ in root.rglob("*.java"))
    if internal_count < 20:
        errors.append("BC8 statement runtime was not internalized under buildcraft.lib.internal.statement")

    runtime = text("source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java")
    for token in [
        "BuildCraftServices.STATEMENTS",
        "StatementServiceImpl.INSTANCE",
        "BuildCraftServices.FILLER_PATTERNS",
        "FillerPatternServiceImpl.INSTANCE",
    ]:
        if token not in runtime:
            errors.append(f"BuildCraftApiRuntime: missing {token}")

    bc_statement = text("source-shared/src/main/java/buildcraft/core/statements/BCStatement.java")
    if "StatementApi2Bridge.mirrorLegacyStatement(this);" in bc_statement:
        errors.append("BCStatement mirrors API2 during superclass construction; subclass parameter schema may be uninitialized")

    bridge = text("source-shared/src/main/java/buildcraft/lib/internal/statement/api2/StatementApi2Bridge.java")
    for token in [
        "mirrorLegacyStatement(",
        "mirrorLegacyStatements(",
        "nativeInternalTriggers(",
        "nativeInternalActions(",
        "toApiParameters(",
        "toLegacyParameters(",
        "ensureNativeAdapter(",
        "LEGACY_PARAMETER_TYPE_ID",
        "STATEMENT_CONTRIBUTORS",
    ]:
        if token not in bridge:
            errors.append(f"StatementApi2Bridge: missing runtime hook {token}")

    for path in [
        "source-families/legacy/src/main/java/buildcraft/lib/internal/statement/StatementManager.java",
        "source-families/modern/src/main/java/buildcraft/lib/internal/statement/StatementManager.java",
    ]:
        src = text(path)
        for token in [
            "StatementApi2Bridge.registerParameterBridge();",
            "StatementApi2Bridge.mirrorLegacyStatement(statement);",
            "StatementApi2Bridge.nativeInternalTriggers(container)",
            "StatementApi2Bridge.nativeInternalActions(container)",
        ]:
            if token not in src:
                errors.append(f"{path}: missing API2 statement bridge {token}")

    construction_safe_mirroring = {
        "source-shared/src/main/java/buildcraft/builders/BCBuildersStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(PATTERNS);",
        "source-shared/src/main/java/buildcraft/silicon/BCSiliconStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(TRIGGER_LIGHT);",
        "source-shared/src/main/java/buildcraft/transport/BCTransportStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(TRIGGER_PIPE_SIGNAL);",
        "source-shared/src/main/java/buildcraft/robotics/BCRoboticsStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(",
        "source-families/legacy/src/main/java/buildcraft/core/BCCoreStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(TRIGGER_INVENTORY_ALL);",
        "source-families/modern/src/main/java/buildcraft/core/BCCoreStatements.java": "StatementApi2Bridge.mirrorLegacyStatements(TRIGGER_INVENTORY_ALL);",
    }
    for path, token in construction_safe_mirroring.items():
        if token not in text(path):
            errors.append(f"{path}: missing post-construction API2 statement mirroring")

    for path in [
        "source-platforms/forge/src/main/java/buildcraft/silicon/gate/GateLogic.java",
        "source-platforms/neoforge/src/main/java/buildcraft/silicon/gate/GateLogic.java",
    ]:
        src = text(path)
        for token in [
            "GateView, GateControl",
            "public GateProgram program()",
            "public boolean setProgram(GateProgram program, OperationMode mode)",
            "StatementApi2Bridge.toApiParameters",
            "StatementApi2Bridge.toLegacyTrigger",
            "StatementApi2Bridge.toLegacyAction",
        ]:
            if token not in src:
                errors.append(f"{path}: missing API2 gate runtime hook {token}")

    silicon = text("source-shared/src/main/java/buildcraft/silicon/BCSiliconStatements.java")
    if "SiliconApi2.install();" not in silicon:
        errors.append("BCSiliconStatements does not install GateService")
    for path in [
        "source-platforms/forge/src/main/java/buildcraft/silicon/api2/GateServiceImpl.java",
        "source-platforms/neoforge/src/main/java/buildcraft/silicon/api2/GateServiceImpl.java",
    ]:
        src = text(path)
        if "instanceof PluggableGate gate" not in src or "Optional.of(gate.logic)" not in src:
            errors.append(f"{path}: GateService is not backed by the real gate runtime")

    filler = text("source-shared/src/main/java/buildcraft/builders/api2/FillerApi2Bridge.java")
    for token in ["mirrorLegacyPattern(", "nativePattern(", "nativePatterns(", "type.pattern().createMask"]:
        if token not in filler:
            errors.append(f"FillerApi2Bridge: missing {token}")
    registry = text("source-shared/src/main/java/buildcraft/builders/registry/FillerRegistry.java")
    for token in ["FillerApi2Bridge.mirrorLegacyPattern", "FillerApi2Bridge.nativePattern", "FillerApi2Bridge.nativePatterns"]:
        if token not in registry:
            errors.append(f"FillerRegistry: missing API2 bridge {token}")

    fixture = text("addon-fixture/src/main/java/dev/bcce/apifixture/ApiV2FixtureAddon.java")
    if "STATEMENT_CONTRIBUTORS" not in fixture or "collector.addTrigger" not in fixture or "collector.addAction" not in fixture:
        errors.append("ApiV2FixtureAddon does not prove API2 gate statement contribution")
    for token in ["ParameterType<Boolean>", "BuildCraftRegistries.PARAMETER_TYPES", "new ParameterSpec(", ".get(BOOL_PARAMETER_SLOT, BOOL_PARAMETER)"]:
        if token not in fixture:
            errors.append(f"ApiV2FixtureAddon does not prove typed API2 statement parameters: missing {token}")

    if errors:
        print("Statements/Gates/Filler API2 migration validation FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print(f"Statements/Gates/Filler API2 migration OK: {internal_count} internal statement source(s), retired public namespaces absent")
    return 0


if __name__ == "__main__":
    sys.exit(main())
