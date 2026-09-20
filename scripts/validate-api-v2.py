#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
API_ROOTS = [
    ROOT / "source-shared/src/main/java/buildcraft/api/v2",
    ROOT / "source-families/legacy/src/main/java/buildcraft/api/v2",
    ROOT / "source-families/modern/src/main/java/buildcraft/api/v2",
]
FIXTURE_ROOT = ROOT / "addon-fixture/src/main/java"
REGISTRY_KEYS_FILE = ROOT / "source-shared/src/main/java/buildcraft/api/v2/BuildCraftRegistries.java"
RUNTIME_FILE = ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java"
PUBLIC_API_FILE = ROOT / "source-shared/src/main/java/buildcraft/api/v2/BuildCraftApi.java"
PROVIDER_FILE = ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntimeProvider.java"
PROVIDER_DESCRIPTOR = ROOT / "source-shared/src/main/resources/META-INF/services/buildcraft.api.v2.ApiRuntime"
OLD_IMPL_ROOT = ROOT / "source-shared/src/main/java/buildcraft/lib/api/v2"

FORBIDDEN_API_IMPORT_PREFIXES = (
    "net.minecraftforge.",
    "net.neoforged.",
    "net.fabricmc.",
    "net.minecraft.client.",
)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)", re.MULTILINE)
PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)
PUBLIC_STATIC_FIELD_RE = re.compile(
    r"\bpublic\s+static\s+(?!final\b)(?:[\w<>?,.\[\] ]+\s+)?[A-Za-z_$][\w$]*\s*(?:=|;)",
    re.MULTILINE,
)
REGISTRY_KEY_RE = re.compile(
    r"^\s*public\s+static\s+final\s+RegistryKey<.*>\s+([A-Z0-9_]+)\s*=",
    re.MULTILINE,
)
RUNTIME_REGISTRY_RE = re.compile(r"registerRegistry\(BuildCraftRegistries\.([A-Z0-9_]+)\)\s*;")

# These are internal implementations and must not be exposed under buildcraft.api.v2.
INTERNALIZED_PUBLIC_FILENAMES = {
    "ImmutableApiFeatureSet.java",
    "MjBuffer.java",
    "SimpleApiRegistry.java",
    "SimpleRegistryBuilder.java",
    "RegistryBuilder.java",
    "RegistrySnapshot.java",
    "ReloadTransaction.java",
    "ReloadableDefinitionRegistry.java",
    "ReloadPhase.java",
    "DefinitionValidator.java",
    "PersistenceRegistryBuilder.java",
    "PersistenceRegistrySnapshot.java",
    "EnergyFluidRegistration.java",
    "EnergyFluidReloadResult.java",
    "MachineRecipeRegistration.java",
    "MachineRecipeReloadResult.java",
}

EXPECTED_PROVIDER = "buildcraft.lib.internal.api.v2.BuildCraftApiRuntimeProvider"

def java_files(root: Path):
    if root.is_dir():
        yield from sorted(root.rglob("*.java"))


def main() -> int:
    errors: list[str] = []
    api_files = [path for root in API_ROOTS for path in java_files(root)]
    if not api_files:
        errors.append("No API v2 Java sources found")

    for path in api_files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        if path.name in INTERNALIZED_PUBLIC_FILENAMES:
            errors.append(f"{rel}: implementation type must live in BuildCraft Lib, not public API2")
        package_match = PACKAGE_RE.search(text)
        if not package_match or not package_match.group(1).startswith("buildcraft.api.v2"):
            errors.append(f"{rel}: API v2 source has an invalid package declaration")
        for imported in IMPORT_RE.findall(text):
            if imported.startswith(FORBIDDEN_API_IMPORT_PREFIXES):
                errors.append(f"{rel}: forbidden loader/client import {imported}")
            if imported.startswith("buildcraft.") and not imported.startswith("buildcraft.api.v2."):
                errors.append(f"{rel}: API v2 imports BuildCraft implementation/non-v2 API: {imported}")
        for match in PUBLIC_STATIC_FIELD_RE.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            errors.append(f"{rel}:{line}: public writable static field is forbidden")
        if path.name.endswith("Impl.java"):
            errors.append(f"{rel}: implementation class name is forbidden in public API2")
        if re.search(r"\b(?:public\s+)?(?:default\s+)?[^;{]+\s+reloadData\s*\(", text):
            errors.append(f"{rel}: bulk reload publication belongs in BuildCraft Lib")
        if path.name == "ApiRegistry.java" and re.search(r"\bvoid\s+freeze\s*\(", text):
            errors.append(f"{rel}: registry freeze control belongs in BuildCraft Lib")

    fixture_files = list(java_files(FIXTURE_ROOT))
    if not fixture_files:
        errors.append("addon-fixture has no Java sources")
    for path in fixture_files:
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(ROOT)
        for imported in IMPORT_RE.findall(text):
            if imported.startswith("buildcraft.") and not imported.startswith("buildcraft.api.v2."):
                errors.append(f"{rel}: fixture imports non-v2 BuildCraft class {imported}")
            if imported.startswith(("net.minecraftforge.", "net.neoforged.", "net.fabricmc.")):
                errors.append(f"{rel}: fixture common code imports loader API {imported}")

    if OLD_IMPL_ROOT.is_dir() and any(OLD_IMPL_ROOT.rglob("*.java")):
        errors.append("Legacy implementation namespace buildcraft.lib.api.v2 still contains Java sources; use buildcraft.lib.internal.api.v2")

    # Repository-wide retirement of all non-v2 Java API namespaces is enforced by validate-api-v2-only.py.

    if REGISTRY_KEYS_FILE.is_file() and RUNTIME_FILE.is_file():
        registry_keys = set(REGISTRY_KEY_RE.findall(REGISTRY_KEYS_FILE.read_text(encoding="utf-8")))
        runtime_keys = set(RUNTIME_REGISTRY_RE.findall(RUNTIME_FILE.read_text(encoding="utf-8")))
        for name in sorted(registry_keys - runtime_keys):
            errors.append(f"BuildCraftRegistries.{name} is not created by BuildCraftApiRuntime")
        for name in sorted(runtime_keys - registry_keys):
            errors.append(f"BuildCraftApiRuntime registers unknown BuildCraftRegistries.{name}")
    else:
        errors.append("BuildCraftRegistries.java or internal BuildCraftApiRuntime.java is missing")

    if PUBLIC_API_FILE.is_file():
        public_api = PUBLIC_API_FILE.read_text(encoding="utf-8")
        if re.search(r"\b(?:public\s+)?static\s+void\s+install\s*\(", public_api):
            errors.append("BuildCraftApi exposes runtime installation; bootstrap must stay in Lib/internal SPI")
    else:
        errors.append("BuildCraftApi.java is missing")

    if (ROOT / "source-shared/src/main/java/buildcraft/api/v2/spi").exists():
        errors.append("Implementation-only buildcraft.api.v2.spi namespace must not exist")
    if not PROVIDER_FILE.is_file():
        errors.append("Internal BuildCraftApiRuntimeProvider.java is missing")
    if not PROVIDER_DESCRIPTOR.is_file():
        errors.append("ApiRuntime ServiceLoader descriptor is missing")
    else:
        providers = [line.strip() for line in PROVIDER_DESCRIPTOR.read_text(encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("#")]
        if providers != [EXPECTED_PROVIDER]:
            errors.append(f"ApiRuntime descriptor must contain exactly {EXPECTED_PROVIDER}, found {providers}")

    if errors:
        print("API v2 / Lib boundary validation FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    internal_count = len(list(java_files(ROOT / "source-shared/src/main/java/buildcraft/lib/internal/api/v2")))
    print(
        f"API v2 / Lib boundary OK: {len(api_files)} public API file(s), "
        f"{internal_count} internal API backend file(s), {len(fixture_files)} fixture file(s)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
