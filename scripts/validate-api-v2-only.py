#!/usr/bin/env python3
"""API2 extension-surface gate: BuildCraft's Java extension surface is buildcraft.api.v2 only."""
from __future__ import annotations

import re
from pathlib import Path

from source_config import load_properties, target_ids, target_layout

ROOT = Path(__file__).resolve().parents[1]


def java_roots() -> tuple[str, ...]:
    props = load_properties()
    roots: list[str] = []
    seen: set[str] = set()

    def add(relative: str) -> None:
        value = relative.strip('/')
        if value and value not in seen:
            seen.add(value)
            roots.append(value)

    for base in (
        'source-shared',
        'source-families',
        'source-platforms',
        'source-family-platforms',
        'source-downports',
    ):
        base_dir = ROOT / base
        if not base_dir.exists():
            continue
        for path in sorted(base_dir.rglob('src/main/java')):
            add(path.relative_to(ROOT).as_posix())
        for path in sorted(base_dir.rglob('src/test/java')):
            add(path.relative_to(ROOT).as_posix())
        for path in sorted(base_dir.rglob('src/gametest/java')):
            add(path.relative_to(ROOT).as_posix())

    for target in target_ids(props):
        overlay = target_layout(target, props).overlay_root
        if not overlay.exists():
            continue
        for relative in ('src/main/java', 'src/test/java', 'src/gametest/java'):
            candidate = overlay / relative
            if candidate.is_dir():
                add(candidate.relative_to(ROOT).as_posix())

    add('addon-fixture/src/main/java')
    return tuple(roots)


JAVA_ROOTS = java_roots()

PACKAGE_OR_IMPORT = re.compile(
    r"^\s*(?:package|import)\s+(?:static\s+)?(buildcraft\.api(?:\.[\w$*]+)*)\s*;",
    re.MULTILINE,
)
IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z0-9_.$*]+)\s*;", re.MULTILINE)
FORBIDDEN_API_IMPORT_PREFIXES = (
    "net.minecraftforge.",
    "net.neoforged.",
    "net.fabricmc.",
    "team.reborn.energy.",
)
V2_PREFIX = "buildcraft.api.v2"

OBSOLETE_MIGRATION_SCRIPTS = (
    "scripts/validate-api-v2-migration-surface.py",
    "scripts/validate-legacy-api-internalization.py",
)


GRADLE_FILES = (
    "build-logic/loaders/forge-target.gradle",
    "build-logic/loaders/neoforge-target.gradle",
)
CI_FILE = ".github/workflows/ci.yml"


def is_v2(name: str) -> bool:
    return name == V2_PREFIX or name.startswith(V2_PREFIX + ".")


def main() -> int:
    errors: list[str] = []
    public_api_files = 0
    scanned_java = 0

    for relative in JAVA_ROOTS:
        root = ROOT / relative
        if not root.is_dir():
            continue
        for path in root.rglob("*.java"):
            scanned_java += 1
            rel = path.relative_to(ROOT).as_posix()
            posix = path.as_posix()

            marker = "/buildcraft/api/"
            if marker in posix:
                suffix = posix.split(marker, 1)[1]
                if suffix.startswith("v2/"):
                    if relative.endswith("src/main/java"):
                        public_api_files += 1
                else:
                    errors.append(f"{rel}: non-v2 source remains below buildcraft/api")

            text = path.read_text(encoding="utf-8", errors="ignore")
            for name in PACKAGE_OR_IMPORT.findall(text):
                if not is_v2(name):
                    errors.append(f"{rel}: non-v2 BuildCraft API package/import remains: {name}")

            if marker in posix and posix.split(marker, 1)[1].startswith("v2/"):
                for imported in IMPORT_RE.findall(text):
                    if imported.startswith(FORBIDDEN_API_IMPORT_PREFIXES):
                        errors.append(f"{rel}: loader-specific import leaked into API v2 common: {imported}")
                    if imported.startswith("buildcraft.") and not is_v2(imported):
                        errors.append(f"{rel}: implementation import leaked into API v2 common: {imported}")

    api_root = ROOT / "source-shared/src/main/java/buildcraft/api"
    if not api_root.is_dir():
        errors.append("source-shared public API root is missing")
    else:
        # File deletions do not necessarily remove now-empty directories from the
        # working tree (notably after git apply on Windows). Only actual files
        # below non-v2 API roots are a violation; empty directories are harmless.
        unexpected = []
        for entry in api_root.iterdir():
            if entry.name == "v2":
                continue
            has_files = entry.is_file() or (entry.is_dir() and any(p.is_file() for p in entry.rglob("*")))
            if has_files:
                unexpected.append(entry.relative_to(api_root).as_posix())
        unexpected.sort()
        if unexpected:
            errors.append("public API root contains non-v2 entries: " + ", ".join(unexpected))

    if public_api_files == 0:
        errors.append("no buildcraft.api.v2 Java sources found")

    for relative in OBSOLETE_MIGRATION_SCRIPTS:
        if (ROOT / relative).exists():
            errors.append(f"obsolete migration artifact remains: {relative}")


    for relative in GRADLE_FILES:
        path = ROOT / relative
        if not path.is_file():
            errors.append(f"missing build file: {relative}")
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for token in (
            "validateApiV2Only",
            "scripts/validate-api-v2-only.py",
            "dependsOn validateApiV2Only",
            "java.include 'buildcraft/api/v2/**'",
        ):
            if token not in text:
                errors.append(f"{relative}: missing final API2-only build gate token {token}")
        if "validateApiV2MigrationSurface" in text or "validate-api-v2-migration-surface.py" in text:
            errors.append(f"{relative}: obsolete migration-surface build task remains")

    ci = ROOT / CI_FILE
    if not ci.is_file():
        errors.append(f"missing CI file: {CI_FILE}")
    else:
        text = ci.read_text(encoding="utf-8", errors="ignore")
        if "python scripts/validate-api-v2-only.py" not in text:
            errors.append(f"{CI_FILE}: API2-only validator is not executed")
        if "validate-api-v2-migration-surface.py" in text or "API v2 migration surface" in text:
            errors.append(f"{CI_FILE}: obsolete migration-surface CI gate remains")

    if errors:
        print("API v2-only finalization FAILED:")
        for error in errors:
            print(f" - {error}")
        return 1

    print(
        "API v2-only finalization OK: "
        f"{public_api_files} public API2 Java source(s); "
        f"{scanned_java} Java source(s) scanned; "
        "0 non-v2 or loader/implementation API imports"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
