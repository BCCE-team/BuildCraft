#!/usr/bin/env python3
"""Shared helpers for external addon fixtures and the installable API smoke addon."""
from __future__ import annotations

from pathlib import Path
import os
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ADDON_FIXTURE_ROOT = ROOT / 'addon-fixture'
API_FIXTURE_SOURCE_ROOT = ADDON_FIXTURE_ROOT / 'src' / 'main' / 'java' / 'dev' / 'bcce' / 'apifixture'
ABI_CONSUMER_SOURCE = ADDON_FIXTURE_ROOT / 'src/abi/java/dev/bcce/abi/Api21BinaryConsumer.java'
API_TEST_ADDON_SOURCE_ROOT = ADDON_FIXTURE_ROOT / 'src' / 'testaddon' / 'java'
API_TEST_ADDON_LOADER_ROOT = ADDON_FIXTURE_ROOT / 'src' / 'testaddon-loaders'
PUBLIC_API_PREFIX = 'buildcraft/api/v2/'
INTERNAL_IMPORT = re.compile(r"\bbuildcraft\.(?!api\.v2\b)[A-Za-z][\w.]*")
INTERNAL_BYTECODE = re.compile(rb"buildcraft/(?!api/v2/)")

API_FIXTURE_ADDONS = (
    'AdditionalPipesConformanceAddon.java',
    'ApiV2FixtureAddon.java',
    'BlueprintLibraryFixtureAddon.java',
    'BuildCraftAdditionsParityAddon.java',
    'CustomEngineFixtureAddon.java',
    'CustomLaserTargetFixtureAddon.java',
    'CustomMachineFixtureAddon.java',
    'CustomPipeFixtureAddon.java',
    'EasyContentFixtureAddon.java',
    'FoundationReloadFixtureAddon.java',
    'LogisticsPipesParityAddon.java',
    'MiscParityFixtureAddon.java',
    'PresentationConformanceAddon.java',
    'ResourceConformanceAddon.java',
    'RobotBoardFixtureAddon.java',
    'RobotTaskConformanceAddon.java',
    'SchematicFixtureAddon.java',
    'TemplatePlacementFixtureAddon.java',
)


def fixture_source_files(root: Path = API_FIXTURE_SOURCE_ROOT) -> list[Path]:
    # Fail on lost baseline fixtures, but automatically pick up new cases.
    required = [root / name for name in API_FIXTURE_ADDONS]
    for path in required:
        if not path.is_file():
            raise ValueError(f'Missing independent addon fixture: {path}')
    return sorted(root.rglob('*.java'))


def ensure_public_sources(paths: list[Path]) -> None:
    if not paths:
        raise ValueError("No independent addon fixture sources")
    for path in paths:
        if not path.is_file():
            raise ValueError(f'Missing independent addon fixture: {path}')
        if INTERNAL_IMPORT.search(path.read_text(encoding='utf-8')):
            raise ValueError(f'Fixture references a non-public BuildCraft class: {path}')


def validate_class_dependency(relative: str, data: bytes, *, package_prefix: str | None = None) -> None:
    if package_prefix is not None and not relative.startswith(package_prefix):
        raise ValueError(f'Unexpected class outside package {package_prefix}: {relative}')
    if INTERNAL_BYTECODE.search(data):
        raise ValueError(f'Addon bytecode references internal BuildCraft: {relative}')


def ensure_public_bytecode(classes: Path, *, package_prefix: str | None = None) -> list[Path]:
    files = sorted(classes.rglob('*.class'))
    if not files:
        raise ValueError('No independently compiled addon classes')
    for path in files:
        relative = path.relative_to(classes).as_posix()
        validate_class_dependency(relative, path.read_bytes(), package_prefix=package_prefix)
    return files


def extract_public_api_classes(candidate: Path, destination: Path, *,
                               required_classes: tuple[str, ...] = ('BuildCraftApi.class',)) -> None:
    """Temporary compiler input only, never a published API artifact."""
    with zipfile.ZipFile(candidate) as jar:
        entries = [entry for entry in jar.infolist()
                   if entry.filename.startswith(PUBLIC_API_PREFIX) and entry.filename.endswith('.class')]
        names = {entry.filename for entry in entries}
        if len(names) != len(entries):
            raise ValueError('Duplicate public API class in candidate JAR')
        for required in required_classes:
            if PUBLIC_API_PREFIX + required not in names:
                raise ValueError(f'Candidate has no BCCE API entry: {PUBLIC_API_PREFIX}{required}')
        with zipfile.ZipFile(destination, 'w') as public:
            for entry in entries:
                public.writestr(entry.filename, jar.read(entry))


def javac_command(javac: str | Path, release: int, classes: Path, public_api: Path,
                  sources: list[Path], dependencies: str = '') -> list[str]:
    """All external consumers use the same isolated compiler settings."""
    ensure_public_sources(sources)
    cp = os.pathsep.join(filter(None, (str(public_api.resolve()), dependencies)))
    return [str(javac), '--release', str(release), '-proc:none', '-encoding', 'UTF-8',
            '-sourcepath', '', '-cp', cp, '-d', str(classes), *map(str, sources)]
