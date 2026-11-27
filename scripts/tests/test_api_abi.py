#!/usr/bin/env python3
"""Exercise the release ABI gate with real compiled JVM bytecode, without network."""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import api_v2_abi as abi

spec = importlib.util.spec_from_file_location('verify_api_artifacts', ROOT / 'scripts/verify-api-artifacts.py')
artifacts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(artifacts)


def compile_jar(root, sources, jar, *, include_mc=False):
    source = root / 'src'
    output = root / 'classes'
    output.mkdir(parents=True)
    files = []
    for name, contents in sources.items():
        path = source / (name.replace('.', '/') + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents, encoding='utf-8')
        files.append(str(path))
    subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(output), *files], check=True, capture_output=True, text=True, timeout=60)
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in output.rglob('*.class'):
            relative = path.relative_to(output).as_posix()
            if include_mc or relative.startswith(abi.PREFIX):
                archive.write(path, relative)
    return jar


class BytecodeAbiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which('javac') or not shutil.which('java'):
            raise unittest.SkipTest('Java toolchain required')
        cls.directory = tempfile.TemporaryDirectory(prefix='bcce-abi-tests-')
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        cls.base = {
            'buildcraft.api.v2.BuildCraftApi': '''package buildcraft.api.v2;
                public class BuildCraftApi { public static final long MAGIC = 99L;
                public String value(int number) { return "old"; }
                protected void extend() {} public static void shared() {}
                protected static class Nested { public void exposed() {} }
                private static class Hidden { public void secret() {} } }''',
            'buildcraft.api.v2.BuildCraftRegistries': '''package buildcraft.api.v2;
                public interface BuildCraftRegistries { int size(); default int version() { return 1; } }''',
            'buildcraft.api.v2.Value': '''package buildcraft.api.v2;
                public record Value(int value) implements Comparable<Value> {
                public int compareTo(Value other) { return Integer.compare(value, other.value); } }''',
            'buildcraft.api.v2.Parent': '''package buildcraft.api.v2;
                public sealed interface Parent permits Child, Second {}''',
            'buildcraft.api.v2.Child': 'package buildcraft.api.v2; public final class Child implements Parent {}',
            'buildcraft.api.v2.Second': 'package buildcraft.api.v2; public final class Second implements Parent {}',
            'buildcraft.api.v2.Mode': 'package buildcraft.api.v2; public enum Mode { FIRST, SECOND }',
        }
        cls.old = abi.read_jar(compile_jar(cls.root / 'old', cls.base, cls.root / 'old.jar'))

    def variant(self, name, changes):
        sources = dict(self.base)
        sources.update(changes)
        return abi.read_jar(compile_jar(self.root / name, sources, self.root / (name + '.jar')))

    def test_accept_additive_types_defaults_and_overloads(self):
        updated = self.variant('add', {
            'buildcraft.api.v2.BuildCraftApi': self.base['buildcraft.api.v2.BuildCraftApi'].replace('private static class Hidden', 'public void value(String x) {} private static class Hidden'),
            'buildcraft.api.v2.BuildCraftRegistries': self.base['buildcraft.api.v2.BuildCraftRegistries'].replace('int size();', 'int size(); default int extra() { return 0; }'),
            'buildcraft.api.v2.Mode': 'package buildcraft.api.v2; public enum Mode { FIRST, SECOND, THIRD }',
            'buildcraft.api.v2.Added': 'package buildcraft.api.v2; public interface Added { void required(); }',
        })
        self.assertEqual(abi.compare(self.old, updated), [])
        self.assertTrue(abi.exported(abi.PREFIX + 'BuildCraftApi$Nested', self.old))
        self.assertFalse(abi.exported(abi.PREFIX + 'BuildCraftApi$Hidden', self.old))

    def test_reject_descriptor_static_visibility_final_and_inlined_changes(self):
        updated = self.variant('broken', {
            'buildcraft.api.v2.BuildCraftApi': '''package buildcraft.api.v2;
                public final class BuildCraftApi { public static final long MAGIC = 100L;
                public int value(int number) { return number; }
                private void extend() {} public void shared() {}
                private static class Nested { public void exposed() {} } }''',
            'buildcraft.api.v2.BuildCraftRegistries': '''package buildcraft.api.v2;
                public interface BuildCraftRegistries { int size(); int version(); int extra(); }''',
            'buildcraft.api.v2.Mode': 'package buildcraft.api.v2; public enum Mode { FIRST }',
        })
        errors = '\n'.join(abi.compare(self.old, updated))
        for fragment in ('became final or abstract', 'inlined constant changed', 'value (I)Ljava/lang/String;',
                         'extend ()V: reduced visibility', 'shared ()V: static/instance status changed',
                         'BuildCraftApi$Nested: removed or no longer exported', 'version ()I: became final or abstract',
                         'extra ()I: new abstract obligation', 'SECOND Lbuildcraft/api/v2/Mode;: field removed'):
            self.assertIn(fragment, errors)

    def test_reject_bridge_removal_and_sealed_restriction(self):
        updated = self.variant('hierarchy', {
            'buildcraft.api.v2.Value': 'package buildcraft.api.v2; public record Value(int value) {}',
            'buildcraft.api.v2.Parent': 'package buildcraft.api.v2; public sealed interface Parent permits Child {}',
            'buildcraft.api.v2.Second': 'package buildcraft.api.v2; public final class Second {}',
        })
        errors = '\n'.join(abi.compare(self.old, updated))
        self.assertIn('compareTo (Ljava/lang/Object;)I', errors)  # javac synthetic bridge
        self.assertIn('sealed hierarchy restricted', errors)
        self.assertIn('direct interfaces changed', errors)

    def test_reject_invalid_or_missing_api_classes(self):
        with self.assertRaises(ValueError):
            abi.parse_class(b'not a class')
        path = self.root / 'missing.jar'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('not-api.txt', 'not an API')
        with self.assertRaisesRegex(ValueError, 'missing API'):
            abi.read_jar(path)

    def test_old_consumer_runs_against_new_jar_without_old_jar_or_game_stubs(self):
        # The pure value/MJ contracts are unchanged from 8.0.30; compile real
        # sources with a minimal compile-only Minecraft ResourceLocation stand-in.
        # This unit test is not the pinned production-JAR conformance test.
        api = ROOT / 'source-shared/src/main/java/buildcraft/api/v2'
        names = ('ApiVersion', 'BuildCraftApi', 'ApiRuntime', 'ApiFeature', 'ApiFeatureSet', 'ApiLifecycle', 'OperationMode',
                 'registry/ApiRegistry', 'registry/RegistryKey', 'registry/RegistryEntry', 'registry/RegistrationContext',
                 'service/ServiceKey', 'energy/MjAmount', 'energy/MjPort', 'energy/MjTransferResult', 'energy/MjTransferPolicy')
        sources = {'buildcraft.api.v2.' + name.replace('/', '.'): (api / (name + '.java')).read_text(encoding='utf-8') for name in names}
        sources['net.minecraft.resources.ResourceLocation'] = 'package net.minecraft.resources; public final class ResourceLocation {}'
        sources['buildcraft.api.v2.BuildCraftRegistries'] = 'package buildcraft.api.v2; public final class BuildCraftRegistries {}'
        old = dict(sources)
        key = 'buildcraft.api.v2.BuildCraftApi'
        old[key] = old[key].replace('new ApiVersion(2, 2, 0)', 'new ApiVersion(2, 1, 0)')
        old_jar = compile_jar(self.root / 'consumer-old', old, self.root / 'consumer-old.jar')
        new_jar = compile_jar(self.root / 'consumer-new', sources, self.root / 'consumer-new.jar')
        self.assertEqual(abi.compare(abi.read_jar(old_jar), abi.read_jar(new_jar)), [])
        abi.run_old_consumer(old_jar, new_jar, Path(shutil.which('javac')), 17, '2.1.0', '2.2.0')
        with self.assertRaises(subprocess.CalledProcessError):
            abi.run_old_consumer(old_jar, old_jar, Path(shutil.which('javac')), 17, '2.1.0', '2.2.0')


class BaselineAndReleaseGateTest(unittest.TestCase):
    def test_baseline_is_pinned_for_every_target(self):
        baseline = abi.load_baseline()
        self.assertEqual(baseline['release'], '8.0.30')
        self.assertEqual(baseline['api'], '2.1.0')
        self.assertEqual(len(baseline['sha256']), 8)
        self.assertEqual(len(set(baseline['sha256'].values())), 8)

    def test_offline_cache_fails_closed_on_missing_or_modified_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            data = b'original pinned baseline'
            file = cache / 'test' / 'BuildCraft-Community-Edition-test+1.20.1+forge.jar'
            file.parent.mkdir()
            import hashlib
            baseline = {'release': 'test', 'sha256': {'1.20.1-forge': hashlib.sha256(data).hexdigest()}}
            with self.assertRaisesRegex(ValueError, 'Offline build requires'):
                abi.baseline_jar(baseline, '1.20.1-forge', cache, True)
            file.write_bytes(data)
            self.assertEqual(abi.baseline_jar(baseline, '1.20.1-forge', cache, True), file)
            file.write_bytes(b'modified')
            with self.assertRaisesRegex(ValueError, 'SHA-256 mismatch'):
                abi.baseline_jar(baseline, '1.20.1-forge', cache, True)

    def test_all_targets_required_and_artifact_tampering_rejected(self):
        baseline = abi.load_baseline()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target, digest in baseline['sha256'].items():
                jar = root / artifacts.jar_name(target)
                jar.write_bytes(target.encode())  # isolated artifact bookkeeping test
                report = {'target': target, 'status': 'passed', 'old_binary_consumer': 'passed',
                          'baseline': baseline['release'], 'baseline_api': baseline['api'], 'candidate_api': abi.candidate_api_version(),
                          'baseline_sha256': digest, 'candidate_sha256': abi.sha256(jar), 'violations': [],
                          'baseline_exported_classes': 1, 'candidate_exported_classes': 2}
                (root / f'api-abi-{target}.json').write_text(json.dumps(report), encoding='utf-8')
            artifacts.verify_all(root, baseline)
            target = '26.3-fabric'
            jar = root / artifacts.jar_name(target)
            jar.write_bytes(b'not the verified JAR')
            with self.assertRaisesRegex(ValueError, 'candidate_sha256'):
                artifacts.verify_all(root, baseline)
            jar.unlink()
            with self.assertRaisesRegex(ValueError, 'exactly 8 unique production JARs'):
                artifacts.verify_all(root, baseline)

    def test_independent_release_preserves_abi_and_configuration_gates(self):
        ci = (ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        local = (ROOT / 'scripts/run-ci-local.py').read_text(encoding='utf-8')
        release = (ROOT / '.github/workflows/publish.yml').read_text(encoding='utf-8')
        self.assertIn("-p 'test_configuration_*.py'", ci)
        self.assertIn('_unittest_command("test_configuration_*.py")', local)
        self.assertIn('workflow_call:', ci)
        self.assertNotIn('uses: ./.github/workflows/ci.yml', release)
        self.assertNotIn('bcce-verified-', release)
        self.assertIn("-p 'test_configuration_*.py'", release)
        self.assertIn('needs: [metadata, build]', release)
        self.assertIn('run: sh ./gradlew --no-daemon buildAndCollect', release)
        self.assertIn('scripts/verify-api-artifacts.py stage', release)
        self.assertIn('--report "build/api-abi/reports/${target}.json"', release)
        self.assertIn('name: bcce-release-${{ matrix.generation }}', release)
        self.assertLess(release.index('buildAndCollect'), release.index('scripts/verify-api-artifacts.py stage'))
        self.assertLess(release.index('scripts/verify-api-artifacts.py stage'), release.index('name: bcce-release-${{ matrix.generation }}'))
        self.assertLess(release.index('verify-api-artifacts.py verify-all --directory release'), release.index('uses: Kira-NT/mc-publish@v3'))
        self.assertLess(ci.index('SERVER_RUNTIME_PROFILE=base bash scripts/ci-server-smoke.sh'), ci.index('name: bcce-verified-${{ matrix.target }}'))
        self.assertIn('verify-api-artifacts.py verify-all --directory build/verified-production-targets', ci)


if __name__ == '__main__':
    unittest.main()
