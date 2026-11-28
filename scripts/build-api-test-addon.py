#!/usr/bin/env python3
"""Build a separate installable API test mod from the exact candidate production JAR.

Only public API classes are exposed to javac. No BCCE sources or implementation
outputs are included in the addon. Loader entrypoints use no mapped Minecraft
method calls on legacy Forge; modern Minecraft targets use official names.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

from addon_fixture_common import (
    API_TEST_ADDON_LOADER_ROOT,
    API_TEST_ADDON_SOURCE_ROOT,
    ROOT,
    ensure_public_bytecode,
    ensure_public_sources,
    extract_public_api_classes,
    javac_command,
    validate_class_dependency,
)
from source_config import TARGETS_PROPERTIES, read_properties
from source_preprocessor import version_tuple

SOURCE = API_TEST_ADDON_SOURCE_ROOT
MOD_ID = 'bcceapitest'
ENTRY = 'dev.bcce.apitest.LoaderEntry'
PACKAGE = 'dev/bcce/apitest/'
BINDING = 'META-INF/bcce-api-test.json'

def digest(path: Path) -> str:
    with path.open('rb') as stream:
        hasher = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
        return hasher.hexdigest()


def target_properties(target: str) -> tuple[str, int, str, str]:
    props = read_properties(TARGETS_PROPERTIES)
    if target not in props['targets'].split(','):
        raise ValueError(f'Unknown target {target}')
    prefix = f'target.{target}.'
    loader = props[prefix + 'source.platform']
    minecraft = props[prefix + 'deps.minecraft']
    return loader, int(props[prefix + 'java.version']), minecraft, props[prefix + 'loader.version_range']


def identifier_source(target: str) -> str:
    loader, _, minecraft, _ = target_properties(target)
    # Official Minecraft names switched at 1.21.11, not at the 26.x version reset.
    name = 'Identifier' if version_tuple(minecraft) >= version_tuple('1.21.11') else 'ResourceLocation'
    expression = (f'new {name}(ApiSmokeProbe.MOD_ID, path)' if loader == 'forge'
                  else f'{name}.fromNamespaceAndPath(ApiSmokeProbe.MOD_ID, path)')
    return (f'package dev.bcce.apitest;\nimport net.minecraft.resources.{name};\n'
            f'final class Ids {{ static {name} id(String path) {{ return {expression}; }} }}\n')


def metadata(target: str) -> tuple[str, bytes]:
    loader, java, minecraft, loader_range = target_properties(target)
    if loader == 'fabric':
        return 'fabric.mod.json', json.dumps({
            'schemaVersion': 1, 'id': MOD_ID, 'version': '1.0.0', 'name': 'BCCE API Test Addon',
            'environment': 'server', 'entrypoints': {'main': [ENTRY]},
            'depends': {'buildcraftlib': '*', 'fabricloader': '*', 'fabric-api': '*',
                        'minecraft': minecraft, 'java': f'>={java}'}
        }, indent=2).encode()
    dependency_type = 'mandatory=true' if loader == 'forge' else 'type="required"'
    filename = 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml'
    content = f'''modLoader="javafml"
loaderVersion="{loader_range}"
license="MPL-2.0"
[[mods]]
modId="{MOD_ID}"
version="1.0.0"
displayName="BCCE API Test Addon"
'''
    # All BCCE modules are loaded by the candidate JAR; the core dependency also
    # establishes ordering. Forge/NeoForge common setup is after construction.
    for dependency, version, ordering in [('buildcraftlib', '[8.0.0,)', 'AFTER'), ('minecraft', f'[{minecraft}]', 'NONE')]:
        content += f'''[[dependencies.{MOD_ID}]]
modId="{dependency}"
{dependency_type}
versionRange="{version}"
ordering="{ordering}"
side="BOTH"
'''
    return filename, content.encode()


def binding(target: str, candidate: Path) -> dict:
    return {'target': target, 'candidate_sha256': digest(candidate), 'probe_protocol': 1}


def check_addon(target: str, candidate: Path, addon: Path) -> None:
    name, expected_metadata = metadata(target)
    with zipfile.ZipFile(addon) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError('Duplicate addon ZIP entries')
        if json.loads(archive.read(BINDING)) != binding(target, candidate):
            raise ValueError('Test addon was not compiled against this exact candidate JAR and target')
        actual_metadata = archive.read(name)
        if (json.loads(actual_metadata) != json.loads(expected_metadata) if target == '1.21.11-fabric' else actual_metadata != expected_metadata):
            raise ValueError('Incorrect loader metadata')
        classes = [n for n in names if n.endswith('.class')]
        for required in ('LoaderEntry.class', 'ApiSmokeProbe.class', 'Ids.class'):
            if PACKAGE + required not in classes:
                raise ValueError(f'Missing addon class {required}')
        for cls in classes:
            validate_class_dependency(cls, archive.read(cls), package_prefix=PACKAGE)
        allowed_resources = {name, BINDING}
        if target == '1.21.11-fabric':
            allowed_resources.add('META-INF/MANIFEST.MF')
        if any(n not in allowed_resources and n not in classes and not n.endswith('/') for n in names):
            raise ValueError('Unexpected addon resource')


def build(target: str, candidate: Path, addon: Path, dependencies: str, javac: str, distribution_candidate: Path | None = None) -> None:
    loader, java, _, _ = target_properties(target)
    sources = sorted(SOURCE.rglob('*.java'))
    sources.append(API_TEST_ADDON_LOADER_ROOT / loader / 'LoaderEntry.java')
    ensure_public_sources(sources)
    expected = binding(target, distribution_candidate or candidate)
    with tempfile.TemporaryDirectory(prefix='bcce-api-test-addon-') as temporary:
        temp = Path(temporary)
        api = temp / 'public-api.jar'
        classes = temp / 'classes'
        classes.mkdir()
        # Extract public bytecode, never compile against the implementation or sources.
        extract_public_api_classes(candidate, api)
        ids = temp / 'Ids.java'
        ids.write_text(identifier_source(target), encoding='utf-8')
        subprocess.run(javac_command(javac, java, classes, api, [*sources, ids], dependencies), check=True)
        verified = ensure_public_bytecode(classes, package_prefix=PACKAGE)
        result = temp / 'addon.jar'
        name, manifest = metadata(target)
        with zipfile.ZipFile(result, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
            jar.writestr(name, manifest)
            jar.writestr(BINDING, json.dumps(expected, sort_keys=True))
            for path in verified:
                jar.write(path, path.relative_to(classes).as_posix())
        check_addon(target, distribution_candidate or candidate, result)
        addon.parent.mkdir(parents=True, exist_ok=True)
        # Only expose a new artifact after all validation succeeds.
        staging = addon.with_suffix('.tmp')
        staging.write_bytes(result.read_bytes())
        staging.replace(addon)
    print(f'{target}: independent API test addon compiled and bound to candidate SHA-256 {expected["candidate_sha256"]}')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build', 'check'])
    parser.add_argument('--target', required=True)
    parser.add_argument('--bcce-jar', type=Path, required=True)
    parser.add_argument('--distribution-jar', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--dependencies', default='')
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    try:
        if args.command == 'build':
            build(args.target, args.bcce_jar, args.output, args.dependencies, args.javac, args.distribution_jar)
        else:
            check_addon(args.target, args.bcce_jar, args.output)
            print(f'{args.target}: test addon/candidate binding verified')
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        print(f'API test addon FAILED: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
