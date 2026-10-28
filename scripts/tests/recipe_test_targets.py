"""Authoritative Java sources for BCCE recipe source-contract tests.

Stonecutter keeps family/platform sources and historical downports beside the
seven deliverable targets. Recursive repository-wide globbing sees all of those
sources at once, including files excluded from a particular target build.
Keep the test inventory aligned with the selected source layers instead.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Each entry corresponds to one shipping target. 26.1.2 and 26.2 override
# the family JEI plugin; 26.3 uses the family-platform implementation.
JEI_PLUGINS = {
    "1.19.2-forge": "version-src/1.19.2-forge",
    "1.20.1-forge": "version-src/1.20.1-forge",
    "1.21.1-neoforge": "source-downports/1.21.X/1.21.1/neoforge",
    "1.21.11-neoforge": "source-family-platforms/1.21.X/neoforge",
    "26.1.2-neoforge": "source-downports/26.X/26.1.2/neoforge",
    "26.2-neoforge": "source-downports/26.X/26.2/neoforge",
    "26.3-neoforge": "source-family-platforms/26.X/neoforge",
}

# Networking is inherited by downport targets unless overridden. These are
# the five *owners* of MessageManager, not every matching directory in Git.
MESSAGE_MANAGERS = {
    "1.19.2-forge": "version-src/1.19.2-forge",
    "1.20.1-forge": "version-src/1.20.1-forge",
    "1.21.1-neoforge": "source-downports/1.21.X/1.21.1/neoforge",
    "1.21.11-neoforge": "source-family-platforms/1.21.X/neoforge",
    "26.X-neoforge": "source-family-platforms/26.X/neoforge",
}


def _sources(mapping: dict[str, str], relative_file: str) -> list[Path]:
    paths = [ROOT / folder / "src/main/java" / relative_file
             for folder in mapping.values()]
    missing = [str(path.relative_to(ROOT)) for path in paths if not path.is_file()]
    if missing:
        raise AssertionError("Missing effective target sources: " + ", ".join(missing))
    if len(paths) != len(set(paths)):
        raise AssertionError("Two target labels map to the same effective source")
    return paths


def jei_plugin_sources() -> list[Path]:
    return _sources(JEI_PLUGINS, "buildcraft/compat/jei/BuildCraftJeiPlugin.java")


def message_manager_sources() -> list[Path]:
    return _sources(MESSAGE_MANAGERS, "buildcraft/lib/net/MessageManager.java")
