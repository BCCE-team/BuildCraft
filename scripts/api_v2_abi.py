#!/usr/bin/env python3
"""Fail-closed, per-target API v2 binary compatibility gate using released JARs.

Read JVM class files directly (including Java 25), without loading game classes.
Deliberately conservative: preserve directly declared visible symbols and inheritance,
including compiler bridges, and reject new abstract obligations. The second gate
compiles an independent consumer against the pinned release, then runs that exact
bytecode against the candidate production JAR. Neither gate replaces GameTests.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
from http.client import HTTPException
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
from time import time_ns
from urllib.parse import quote
from urllib.request import Request, urlopen
import zipfile

from addon_fixture_common import (ROOT, ABI_CONSUMER_SOURCE, PUBLIC_API_PREFIX,
                                  extract_public_api_classes, javac_command, ensure_public_bytecode)
BASELINE = ROOT / 'build-config/api-v2-abi-baseline.json'
PREFIX = PUBLIC_API_PREFIX
PUBLIC, PROTECTED, STATIC, FINAL = 0x1, 0x4, 0x8, 0x10
INTERFACE, ABSTRACT, ANNOTATION, ENUM = 0x200, 0x400, 0x2000, 0x4000
VISIBLE = PUBLIC | PROTECTED


class Reader:
    def __init__(self, data: bytes):
        self.stream = io.BytesIO(data)

    def take(self, count: int) -> bytes:
        result = self.stream.read(count)
        if len(result) != count:
            raise ValueError('Truncated class file')
        return result

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack('>H', self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack('>I', self.take(4))[0]

    def end(self) -> None:
        if self.stream.read(1):
            raise ValueError('Unexpected trailing class data')


@dataclass(frozen=True)
class Member:
    flags: int
    constant: object = None


@dataclass
class ClassApi:
    name: str
    flags: int
    parent: str | None
    interfaces: tuple[str, ...]
    outer: str | None
    fields: dict[tuple[str, str], Member]
    methods: dict[tuple[str, str], Member]
    permitted: frozenset[str] | None
    record: bool


def parse_class(data: bytes) -> ClassApi:
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError('Invalid class file magic')
    r.u2(); r.u2()  # minor, major; no dependency on the host JDK
    count = r.u2()
    pool: list[tuple[int, object] | None] = [None] * count
    index = 1
    while index < count:
        tag = r.u1()
        if tag == 1:
            value = r.take(r.u2())  # raw modified UTF-8, including inlined strings
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            value = r.take(4)
        elif tag in (5, 6):
            value = r.take(8)
        elif tag in (7, 8, 16, 19, 20):
            value = r.u2()
        elif tag == 15:
            value = r.take(3)
        else:
            raise ValueError(f'Unknown constant pool tag {tag}')
        pool[index] = (tag, value)
        index += 2 if tag in (5, 6) else 1

    def entry(i: int, tag: int | None = None):
        if not 0 < i < count or pool[i] is None:
            raise ValueError(f'Invalid constant pool index {i}')
        actual, value = pool[i]
        if tag is not None and actual != tag:
            raise ValueError(f'Wrong constant pool tag at {i}: {actual} != {tag}')
        return actual, value

    def utf(i: int) -> str:
        return entry(i, 1)[1].decode('utf-8', errors='surrogateescape')

    def clazz(i: int) -> str:
        return utf(entry(i, 7)[1])

    def attributes(reader: Reader) -> dict[str, bytes]:
        attrs = {}
        for _ in range(reader.u2()):
            name = utf(reader.u2())
            value = reader.take(reader.u4())
            # Unknown/non-ABI attributes may repeat; only the named ABI attributes
            # below are interpreted by this scanner.
            attrs[name] = value
        return attrs

    flags = r.u2()
    name = clazz(r.u2())
    parent_index = r.u2()
    parent = clazz(parent_index) if parent_index else None
    interfaces = tuple(clazz(r.u2()) for _ in range(r.u2()))

    def members() -> dict[tuple[str, str], Member]:
        result = {}
        for _ in range(r.u2()):
            access = r.u2()
            key = (utf(r.u2()), utf(r.u2()))
            attrs = attributes(r)
            constant = None
            if 'ConstantValue' in attrs:
                c = Reader(attrs['ConstantValue'])
                tag, value = entry(c.u2())
                c.end()
                constant = (tag, entry(value, 1)[1] if tag == 8 else value)
            if key in result:
                raise ValueError(f'Duplicate JVM member {name}.{key}')
            result[key] = Member(access, constant)
        return result

    fields, methods = members(), members()
    attrs = attributes(r)
    r.end()
    outer = None
    if 'InnerClasses' in attrs:
        inner = Reader(attrs['InnerClasses'])
        for _ in range(inner.u2()):
            inner_index, outer_index, simple, access = (inner.u2() for _ in range(4))
            if inner_index and clazz(inner_index) == name:
                # Protected/public nested visibility and static-ness are stored
                # in InnerClasses, not necessarily in the class access flags.
                flags = (flags & ~(VISIBLE | STATIC | FINAL)) | (access & (VISIBLE | STATIC | FINAL))
                outer = clazz(outer_index) if outer_index else None
                if not simple or outer is None:
                    flags &= ~VISIBLE  # anonymous/local classes aren't exported
        inner.end()
    permitted = None
    if 'PermittedSubclasses' in attrs:
        p = Reader(attrs['PermittedSubclasses'])
        permitted = frozenset(clazz(p.u2()) for _ in range(p.u2()))
        p.end()
    return ClassApi(name, flags, parent, interfaces, outer, fields, methods, permitted, 'Record' in attrs)


def read_jar(path: Path) -> dict[str, ClassApi]:
    classes = {}
    with zipfile.ZipFile(path) as jar:
        for item in jar.infolist():
            if not item.filename.startswith(PREFIX) or not item.filename.endswith('.class'):
                continue
            cls = parse_class(jar.read(item))
            if item.filename != cls.name + '.class' or cls.name in classes:
                raise ValueError(f'Wrong or duplicate API class: {item.filename}')
            classes[cls.name] = cls
    if PREFIX + 'BuildCraftApi' not in classes or PREFIX + 'BuildCraftRegistries' not in classes:
        raise ValueError(f'{path}: missing API facade/registry classes')
    return classes


def exported(name: str, classes: dict[str, ClassApi]) -> bool:
    seen = set()
    while name in classes:
        if name in seen:
            raise ValueError(f'Cyclic nested class metadata: {name}')
        seen.add(name)
        cls = classes[name]
        if not cls.flags & VISIBLE:
            return False
        if cls.outer is None:
            return True
        name = cls.outer
    return False


def visibility(flags: int) -> int:
    return 2 if flags & PUBLIC else 1 if flags & PROTECTED else 0


def compare(old: dict[str, ClassApi], new: dict[str, ClassApi]) -> list[str]:
    errors = []
    for name, before in sorted(old.items()):
        if not exported(name, old):
            continue
        after = new.get(name)
        if after is None or not exported(name, new):
            errors.append(f'{name}: removed or no longer exported')
            continue
        if visibility(after.flags) < visibility(before.flags):
            errors.append(f'{name}: reduced visibility')
        if (before.flags ^ after.flags) & (INTERFACE | ANNOTATION | ENUM | STATIC) or before.record != after.record:
            errors.append(f'{name}: class kind or nested static status changed')
        if after.flags & ~before.flags & (FINAL | ABSTRACT):
            errors.append(f'{name}: became final or abstract')
        if before.parent != after.parent:
            errors.append(f'{name}: superclass changed ({before.parent} -> {after.parent})')
        if set(before.interfaces) != set(after.interfaces):
            # Conservative: even additions can introduce abstract/default-method
            # obligations for existing third-party implementations.
            errors.append(f'{name}: direct interfaces changed')
        if after.permitted is not None and (before.permitted is None or not before.permitted <= after.permitted):
            errors.append(f'{name}: sealed hierarchy restricted')
        for label, previous, current in (('field', before.fields, after.fields), ('method', before.methods, after.methods)):
            for key, member in sorted(previous.items()):
                if not member.flags & VISIBLE:
                    continue
                symbol = f'{name}.{key[0]} {key[1]}'
                replacement = current.get(key)
                if replacement is None:
                    errors.append(f'{symbol}: {label} removed or JVM descriptor changed')
                    continue
                if visibility(replacement.flags) < visibility(member.flags):
                    errors.append(f'{symbol}: reduced visibility')
                if (replacement.flags ^ member.flags) & STATIC:
                    errors.append(f'{symbol}: static/instance status changed')
                if replacement.flags & ~member.flags & (FINAL | ABSTRACT):
                    errors.append(f'{symbol}: became final or abstract')
                if label == 'field' and member.constant is not None and member.constant != replacement.constant:
                    errors.append(f'{symbol}: inlined constant changed')
        for key, member in sorted(after.methods.items()):
            if key not in before.methods and member.flags & ABSTRACT and member.flags & VISIBLE:
                errors.append(f'{name}.{key[0]} {key[1]}: new abstract obligation')
            elif key in before.methods and not before.methods[key].flags & VISIBLE and member.flags & ABSTRACT and member.flags & VISIBLE:
                errors.append(f'{name}.{key[0]} {key[1]}: newly exported abstract obligation')
    return errors


def sha256(path: Path) -> str:
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def load_baseline() -> dict:
    baseline = json.loads(BASELINE.read_text(encoding='utf-8'))
    target_line = next(line for line in (ROOT / 'build-config/targets.properties').read_text(encoding='utf-8').splitlines() if line.startswith('targets='))
    targets = target_line.split('=', 1)[1].split(',')
    references = baseline.get('first_release_reference', {})
    direct = set(baseline['sha256'])
    if (set(targets) != direct | set(references) or direct & set(references)
            or len(targets) != len(set(targets))
            or any(source not in direct or (target.split('-')[-1] != source.split('-')[-1]
                   and (target, source) != ('1.21.11-fabric', '1.21.11-neoforge'))
                   for target, source in references.items())):
        raise ValueError('ABI baseline must cover exactly the configured target registry')
    if not all(re.fullmatch(r'[0-9a-f]{64}', digest) for digest in baseline['sha256'].values()):
        raise ValueError('Invalid baseline SHA-256')
    asset_ids = baseline['asset_ids']
    if (set(asset_ids) != direct
            or any(type(value) is not int or value <= 0 for value in asset_ids.values())
            or len(set(asset_ids.values())) != len(direct)):
        raise ValueError('ABI baseline must pin one distinct release asset ID per target')
    for target, source in references.items():
        baseline['sha256'][target] = baseline['sha256'][source]
        baseline['asset_ids'][target] = baseline['asset_ids'][source]
    return baseline


def baseline_jar(baseline: dict, target: str, cache: Path, offline: bool) -> Path:
    target = baseline.get('first_release_reference', {}).get(target, target)
    release = baseline['release']
    digest = baseline['sha256'][target]
    filename = f'BuildCraft-Community-Edition-{release}+{target.replace("-", "+")}.jar'
    path = cache / release / filename
    if path.is_file():
        if sha256(path) != digest:
            raise ValueError(f'Baseline SHA-256 mismatch: {path}; remove corrupt cache and retry')
        return path
    if offline:
        raise ValueError(f'Offline build requires the pinned baseline JAR: {path}')
    path.parent.mkdir(parents=True, exist_ok=True)
    # GitHub release redirects (and intervening caches) may return stale,
    # truncated or non-JAR responses. Retry the public download with a fresh
    # URL, then use the exact pinned release asset's binary API endpoint.
    # The committed SHA-256 remains the sole acceptance criterion on every route.
    repository = baseline['repository']
    base_url = f'https://github.com/{repository}/releases/download/{quote(release, safe="")}/'
    retry = f'?download=1&bcce_retry={time_ns()}'
    routes = (
        ('release URL', base_url + quote(filename, safe='')),
        ('uncached release URL', base_url + quote(filename, safe='+') + retry),
        ('release asset API', f'https://api.github.com/repos/{repository}/releases/assets/{baseline["asset_ids"][target]}' + retry),
    )
    errors = []
    for label, url in routes:
        temporary = None
        try:
            request = Request(url, headers={
                'User-Agent': 'BCCE-API-ABI/1.0',
                'Accept': 'application/octet-stream',
                'Accept-Encoding': 'identity',
                'Cache-Control': 'no-cache',
                'Pragma': 'no-cache',
            })
            with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
                temporary = Path(output.name)
                with urlopen(request, timeout=120) as response:
                    content_type = response.headers.get('Content-Type', 'unknown')
                    while chunk := response.read(1024 * 1024):
                        output.write(chunk)
            actual = sha256(temporary)
            if actual != digest:
                raise ValueError(f'Downloaded baseline SHA-256 mismatch: expected {digest}, '
                                 f'got {actual} ({temporary.stat().st_size} bytes, {content_type})')
            os.replace(temporary, path)
            return path
        except (OSError, HTTPException, ValueError) as error:
            errors.append(f'{label}: {error}')
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    raise ValueError(f'Could not download pinned baseline {filename}; expected SHA-256 {digest}. '
                     + '; '.join(errors) + f'. Supply the exact release JAR at {path} and retry.')


def candidate_api_version() -> str:
    source = (ROOT / 'source-shared/src/main/java/buildcraft/api/v2/BuildCraftApi.java').read_text(encoding='utf-8')
    match = re.search(r'VERSION\s*=\s*new ApiVersion\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)', source)
    if not match:
        raise ValueError('Cannot resolve current API version')
    return '.'.join(match.groups())


def run_old_consumer(old_jar: Path, new_jar: Path, javac: Path, release: int, expected_api: str, candidate_api: str) -> None:
    source = ABI_CONSUMER_SOURCE
    java = javac.with_name('java.exe' if javac.suffix.lower() == '.exe' else 'java')
    with tempfile.TemporaryDirectory(prefix='bcce-api21-consumer-') as temp:
        classes = Path(temp) / 'classes'
        classes.mkdir()
        api = Path(temp) / 'public-api.jar'
        extract_public_api_classes(old_jar, api)
        subprocess.run(javac_command(javac, release, classes, api, [source]), check=True, timeout=120)
        ensure_public_bytecode(classes, package_prefix='dev/bcce/abi/')
        # Run the SAME already-compiled consumer twice. The previous JAR is never
        # on the candidate classpath. No exploded BuildCraft classes or stubs.
        for jar, api in ((old_jar, expected_api), (new_jar, candidate_api)):
            cp = os.pathsep.join((str(classes), str(jar.resolve())))
            subprocess.run([str(java), '-Xverify:all', '-cp', cp, 'dev.bcce.abi.Api21BinaryConsumer', api, str(jar.resolve())], check=True, timeout=120)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True)
    parser.add_argument('--bcce-jar', type=Path, required=True)
    parser.add_argument('--distribution-jar', type=Path, help='Remapped distribution from the named candidate')
    parser.add_argument('--javac', type=Path, required=True)
    parser.add_argument('--release', type=int, choices=(17, 21, 25), required=True)
    parser.add_argument('--cache-dir', type=Path, default=ROOT / 'build/api-abi/baselines')
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    report = {'target': args.target, 'status': 'failed'}
    try:
        baseline = load_baseline()
        candidate_api = candidate_api_version()
        before_version, after_version = (tuple(map(int, v.split('.'))) for v in (baseline['api'], candidate_api))
        if before_version[0] != after_version[0] or after_version < before_version:
            raise ValueError(f'API version regressed or changed major: {baseline["api"]} -> {candidate_api}')
        report.update(baseline=baseline['release'], baseline_api=baseline['api'], candidate_api=candidate_api, baseline_sha256=baseline['sha256'][args.target])
        old_jar = baseline_jar(baseline, args.target, args.cache_dir, args.offline)
        old, new = read_jar(old_jar), read_jar(args.bcce_jar)
        report.update(candidate_sha256=sha256(args.distribution_jar or args.bcce_jar), baseline_exported_classes=sum(exported(n, old) for n in old), candidate_exported_classes=sum(exported(n, new) for n in new))
        if args.distribution_jar:
            report['named_candidate_sha256'] = sha256(args.bcce_jar)
        report['violations'] = compare(old, new)
        if report['violations']:
            raise ValueError('\n'.join(report['violations']))
        run_old_consumer(old_jar, args.bcce_jar, args.javac, args.release, baseline['api'], candidate_api)
        report['old_binary_consumer'] = 'passed'
        report['status'] = 'passed'
        print(f'API ABI and old binary consumer passed: {args.target} ({baseline["release"]} -> {args.bcce_jar.name})')
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report['error'] = str(error)
        print(f'API compatibility gate FAILED: {error}', file=sys.stderr)
        return 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    raise SystemExit(main())
