#!/usr/bin/env python3
"""Resolve build-all JDKs using the same discovery as run-ci-local.py.

Print one JSON object to stdout; keep diagnostics on stderr so PowerShell can
consume the result without parsing platform-specific `java -version` strings.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys


class JavaSelectionError(RuntimeError):
    pass


def load_local_ci_module():
    path = Path(__file__).with_name("run-ci-local.py")
    spec = importlib.util.spec_from_file_location("buildcraft_local_ci_discovery", path)
    if spec is None or spec.loader is None:
        raise JavaSelectionError(f"Cannot load Java discovery from {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def select_jdks(versions: list[int], installations: dict[int, list[Path]], local_ci) -> dict[str, str]:
    result: dict[str, str] = {}
    missing: list[int] = []
    for version in dict.fromkeys(versions):
        # Prefer a real compiler JDK. Some launcher/runtime paths expose only
        # java.exe, and discovery may encounter one before a usable JDK.
        for home in installations.get(version, []):
            javac = home / "bin" / ("javac.exe" if sys.platform == "win32" else "javac")
            if javac.is_file():
                result[str(version)] = str(home)
                break
        else:
            missing.append(version)
    if missing:
        raise JavaSelectionError(
            "Missing JDK(s): " + ", ".join(str(v) for v in missing) + ".\n"
            + local_ci.format_java_diagnostics(installations) + "\n"
            "Install the missing JDK(s), or set JAVA_HOME_<version>_X64 "
            "to a matching installation."
        )
    return result


def main(argv: list[str]) -> int:
    if not argv or any(not token.isdecimal() for token in argv):
        print("Usage: python scripts/select-build-java.py 17 21 25", file=sys.stderr)
        return 2
    local_ci = load_local_ci_module()
    installations = local_ci.discover_java_installations()
    try:
        selection = select_jdks([int(v) for v in argv], installations, local_ci)
    except JavaSelectionError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(json.dumps(selection, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
