#!/usr/bin/env python3
"""API2 extension-surface gate: BuildCraft's Java extension surface is buildcraft.api.v2 only."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

JAVA_ROOTS = (
    "source-shared/src/main/java",
    "source-families/legacy/src/main/java",
    "source-families/modern/src/main/java",
    "source-platforms/forge/src/main/java",
    "source-platforms/neoforge/src/main/java",
    "source-family-platforms/legacy/forge/src/main/java",
    "source-family-platforms/legacy/fabric/src/main/java",
    "source-family-platforms/modern/neoforge/src/main/java",
    "source-family-platforms/modern/fabric/src/main/java",
    "version-src/1.19.2-forge/src/main/java",
    "version-src/1.20.1-forge/src/main/java",
    "version-src/1.21.1-neoforge/src/main/java",
    "source-shared/src/test/java",
    "source-families/legacy/src/test/java",
    "source-families/modern/src/test/java",
    "source-platforms/forge/src/test/java",
    "source-platforms/neoforge/src/test/java",
    "source-family-platforms/legacy/forge/src/test/java",
    "source-family-platforms/legacy/fabric/src/test/java",
    "source-family-platforms/modern/neoforge/src/test/java",
    "source-family-platforms/modern/fabric/src/test/java",
    "source-shared/src/gametest/java",
    "source-families/legacy/src/gametest/java",
    "source-families/modern/src/gametest/java",
    "source-platforms/forge/src/gametest/java",
    "source-platforms/neoforge/src/gametest/java",
    "source-family-platforms/legacy/forge/src/gametest/java",
    "source-family-platforms/legacy/fabric/src/gametest/java",
    "source-family-platforms/modern/neoforge/src/gametest/java",
    "source-family-platforms/modern/fabric/src/gametest/java",
    "addon-fixture/src/main/java",
)

PACKAGE_OR_IMPORT = re.compile(
    r"^\s*(?:package|import)\s+(?:static\s+)?(buildcraft\.api(?:\.[\w$*]+)*)\s*;",
    re.MULTILINE,
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
        "0 non-v2 buildcraft.api packages/imports"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
