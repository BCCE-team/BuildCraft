#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
SOURCE_ROOTS = [
    ROOT / "source-shared/src/main/java",
    ROOT / "source-families/legacy/src/main/java",
    ROOT / "source-families/modern/src/main/java",
    ROOT / "source-platforms/forge/src/main/java",
    ROOT / "source-platforms/neoforge/src/main/java",
    ROOT / "source-family-platforms/legacy/forge/src/main/java",
    ROOT / "source-family-platforms/legacy/fabric/src/main/java",
    ROOT / "source-family-platforms/modern/neoforge/src/main/java",
    ROOT / "source-family-platforms/modern/fabric/src/main/java",
    ROOT / "version-src/1.19.2-forge/src/main/java",
    ROOT / "version-src/1.20.1-forge/src/main/java",
    ROOT / "version-src/1.21.1-neoforge/src/main/java",
]


def read(relative: str) -> str:
    path = resolve_source_path(relative)
    if not path.is_file():
        raise AssertionError(f"missing {relative}")
    return path.read_text(encoding="utf-8")


def main() -> int:
    errors: list[str] = []
    retired = "buildcraft.api.schematics"
    for source_root in SOURCE_ROOTS:
        if not source_root.exists():
            continue
        for path in source_root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="ignore")
            if retired in text:
                errors.append(f"{path.relative_to(ROOT)} still references retired {retired}")

    service = read("source-shared/src/main/java/buildcraft/api/v2/schematic/SchematicService.java")
    if "captureEntity" not in service:
        errors.append("SchematicService does not expose entity capture")

    regs = read("source-shared/src/main/java/buildcraft/api/v2/BuildCraftRegistries.java")
    for name in ["SCHEMATIC_ADAPTERS", "SCHEMATIC_ENTITY_ADAPTERS", "SNAPSHOT_ELEMENT_TYPES", "INVENTORY_COPY_POLICIES"]:
        if name not in regs:
            errors.append(f"BuildCraftRegistries.{name} missing")

    for family in ["legacy", "modern"]:
        schematics = read(f"source-families/{family}/src/main/java/buildcraft/builders/BCBuildersSchematics.java")
        if "BuildersSchematicApi2.bootstrap();" not in schematics:
            errors.append(f"{family}: Builder schematic API2 service is not bootstrapped")

    manager = read("source-shared/src/main/java/buildcraft/builders/snapshot/SchematicBlockManager.java")
    if "BuildCraftServices.SCHEMATICS" not in manager or "getLegacySchematicBlock" not in manager:
        errors.append("SchematicBlockManager is not routed through SchematicService with a legacy fallback")
    entity_manager = read("source-shared/src/main/java/buildcraft/builders/snapshot/SchematicEntityManager.java")
    if "BuildCraftServices.SCHEMATICS" not in entity_manager or "getLegacySchematicEntity" not in entity_manager:
        errors.append("SchematicEntityManager is not routed through SchematicService with a legacy fallback")

    for platform in ["forge", "neoforge"]:
        policy = read(f"source-platforms/{platform}/src/main/java/buildcraft/builders/snapshot/InventoryContentPolicy.java")
        if "BuildCraftRegistries.INVENTORY_COPY_POLICIES" not in policy:
            errors.append(f"{platform}: Builder inventory-copy policy does not read API2 registry")
        if "BuilderInventoryCopyAPI" in policy:
            errors.append(f"{platform}: old BuilderInventoryCopyAPI still used")
        impl = read(f"source-platforms/{platform}/src/main/java/buildcraft/builders/internal/schematic/api2/SchematicServiceImpl.java")
        if "SCHEMATIC_ADAPTERS" not in impl or "SCHEMATIC_ENTITY_ADAPTERS" not in impl:
            errors.append(f"{platform}: SchematicServiceImpl does not dispatch both adapter registries")

    fixture = read("addon-fixture/src/main/java/dev/bcce/apifixture/SchematicFixtureAddon.java")
    for token in ["blockSchematic", "inventoryCopyPolicy", "PersistentType"]:
        if token not in fixture:
            errors.append(f"schematic fixture missing {token}")

    old_api_files = []
    for root in SOURCE_ROOTS:
        candidate = root / "buildcraft/api/schematics"
        if candidate.exists():
            old_api_files.extend(candidate.rglob("*.java"))
    if old_api_files:
        errors.append(f"retired buildcraft.api.schematics contains {len(old_api_files)} Java file(s)")

    internal = []
    for root in SOURCE_ROOTS:
        internal.extend(root.rglob("buildcraft/builders/internal/schematic/legacy/*.java"))
    if not internal:
        errors.append("expected internal legacy schematic compatibility runtime is missing")

    if errors:
        print("Schematics / Builders API2 migration FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1
    print(
        "Schematics / Builders API2 migration OK: retired public schematic namespace absent; "
        f"{len(internal)} legacy compatibility source(s) are internal and capture/persistence/inventory policy route through API2"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
