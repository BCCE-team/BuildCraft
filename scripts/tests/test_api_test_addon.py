"""Offline checks for the installed-server addon gate (not a Minecraft launch).

Compile the real addon and loader adapters against materialized public contracts,
with minimal Minecraft/loader and unused API leaf stand-ins. The target matrix
compiles against the real candidate and real dependencies and executes in-game.
"""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import target_ids
from source_lookup import read_materialized_java

SPEC = importlib.util.spec_from_file_location('build_api_test_addon', ROOT / 'scripts/build-api-test-addon.py')
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)
API = 'buildcraft/api/v2/'
SHARED = ROOT / 'source-shared/src/main/java' / API


def write_sources(root, files):
    paths = []
    for name, body in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body, encoding='utf-8')
        paths.append(str(path))
    return paths


def compile_jar(root, files, jar, dependencies=()):
    classes = root / 'classes'
    classes.mkdir(parents=True)
    args = ['javac', '--release', '17', '-proc:none', '-encoding', 'UTF-8', '-sourcepath', '', '-d', str(classes)]
    if dependencies:
        args += ['-cp', os.pathsep.join(map(str, dependencies))]
    result = subprocess.run(args + write_sources(root / 'src', files), capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise AssertionError(result.stdout + result.stderr)
    with zipfile.ZipFile(jar, 'w') as archive:
        for path in classes.rglob('*.class'):
            archive.write(path, path.relative_to(classes).as_posix())


def selected_api(target):
    names = ['ApiRuntime', 'ApiVersion', 'ApiLifecycle', 'ApiFeature', 'ApiFeatureSet', 'BuildCraftApi',
             'BuildCraftFeatures', 'BuildCraftServices', 'BuildCraftRegistries', 'OperationMode',
             'service/ServiceKey', 'module/ModuleInfo', 'module/ModuleService', 'pipe/PipeType', 'pipe/PipeMedium',
             'pipe/ItemTransportProfile', 'pipe/FluidTransportProfile', 'pipe/PowerTransportProfile',
             'pipe/ExternalEnergyTransportProfile', 'fluid/FluidAmount', 'signal/SignalChannelType', 'signal/SignalCombiner',
             'persistence/ApiCodec', 'persistence/CodecResult', 'persistence/OpaqueData', 'platform/PlatformServices',
             'world/WorldAccessService', 'world/WorldAccessContext', 'robot/RobotService', 'request/RequestService']
    for directory in ('config', 'registry', 'permission'):
        names += [f'{directory}/{p.stem}' for p in sorted((SHARED / directory).glob('*.java'))
                  if p.stem not in ('PermissionServices', 'OwnedView')]
    names += ['energy/' + n for n in ('EnergyService', 'EnergyConversion', 'EnergyConversionStatus', 'EnergyRateUnit',
              'MjAmount', 'MjStorage', 'MjStoragePolicy', 'MjStorageState', 'MjPort', 'MjTransferPolicy',
              'MjTransferResult', 'MjConnectionContext', 'MjPortDescriptor', 'MjPortRole', 'MjDemandPort')]
    files = {API + n + '.java': read_materialized_java(target, 'src/main/java/' + API + n + '.java') for n in names}
    # Preserve the real complete key declarations. Stub unrelated domain types so
    # the independently compiled probe must still use the actual generic key types.
    for name, key in (('BuildCraftServices', 'ServiceKey'), ('BuildCraftRegistries', 'RegistryKey')):
        text = files[API + name + '.java']
        imports = {f.rsplit('.', 1)[-1]: f for f in re.findall(r'import (buildcraft\.api\.v2\.[\w.]+);', text)}
        for typ in re.findall(rf'{key}<([\w.]+(?:<\?>)?)>\s+\w+\s*=', text):
            raw = typ.split('<')[0]
            qualified = raw if '.' in raw else imports[raw]
            path = qualified.replace('.', '/') + '.java'
            if path not in files:
                pkg, cls = qualified.rsplit('.', 1)
                files[path] = f'package {pkg}; public interface {cls}{"<T>" if "<" in typ else ""} {{}}'
    for name in ('platform/ItemTransfer', 'platform/FluidTransfer', 'platform/EnergyTransfer', 'item/ItemPort',
                 'fluid/FluidPort', 'platform/ExternalEnergyPort', 'robot/RobotHandle', 'robot/RobotDock',
                 'robot/RobotResourceRequest', 'robot/RobotResourceLease', 'robot/RobotEventContext', 'robot/RobotEventDecision',
                 'request/RequestProvider', 'request/ManagedRequestProvider'):
        pkg, cls = ('buildcraft.api.v2.' + name.replace('/', '.')).rsplit('.', 1)
        files.setdefault(API + name + '.java', f'package {pkg}; public interface {cls} {{}}')
    files[API+'request/RequestLedger.java'] = '''package buildcraft.api.v2.request;
public class RequestLedger { public enum DeliveryStatus {UNAVAILABLE} public interface PromiseView {}
public interface DeliveryResult { static DeliveryResult failure(DeliveryStatus status, String reason){return null;} }}'''
    # A private class must be stripped even if the candidate contains it.
    files['buildcraft/lib/PrivateBackend.java'] = 'package buildcraft.lib; public class PrivateBackend {}'
    return files


# Keep an independent expected matrix: deriving the doubles from the builder's
# version predicate previously let both production code and its tests be wrong.
IDENTIFIER_TYPES = {
    '1.19.2-forge': 'ResourceLocation',
    '1.20.1-forge': 'ResourceLocation',
    '1.21.1-neoforge': 'ResourceLocation',
    '1.21.11-neoforge': 'Identifier',
    '26.1.2-neoforge': 'Identifier',
    '26.2-neoforge': 'Identifier',
    '26.3-neoforge': 'Identifier',
    '26.3-fabric': 'Identifier',
}


def dependencies(target):
    loader, _, _, _ = BUILDER.target_properties(target)
    loc = IDENTIFIER_TYPES[target]
    files = {
        f'net/minecraft/resources/{loc}.java': f'''package net.minecraft.resources;
public record {loc}(String namespace, String path) {{ public static {loc} tryParse(String s){{String[] p=s.split(":",2);return new {loc}(p[0],p[1]);}}
public static {loc} fromNamespaceAndPath(String n,String p){{return new {loc}(n,p);}} }}''',
        'net/minecraft/resources/ResourceKey.java': f'''package net.minecraft.resources; public class ResourceKey<T> {{
public {loc} location(){{return null;}} public {loc} identifier(){{return null;}} }}''',
        'net/minecraft/core/BlockPos.java': 'package net.minecraft.core; public record BlockPos(int x,int y,int z){public BlockPos immutable(){return this;}}',
        'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction {UP}',
        'net/minecraft/world/level/block/state/BlockState.java': 'package net.minecraft.world.level.block.state; public class BlockState {}',
        'net/minecraft/world/item/ItemStack.java': 'package net.minecraft.world.item; public class ItemStack {}',
        'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level; import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey; import net.minecraft.server.MinecraftServer;
public class Level {public ResourceKey<Level> dimension(){return null;} public boolean hasChunkAt(BlockPos p){return true;}
public MinecraftServer getServer(){return null;} public long getGameTime(){return 0;}}''',
        'net/minecraft/server/level/ServerLevel.java': 'package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}',
        'net/minecraft/server/MinecraftServer.java': 'package net.minecraft.server; public class MinecraftServer { public net.minecraft.server.level.ServerLevel overworld(){return null;} }',
    }
    if loader == 'fabric':
        files['net/fabricmc/api/ModInitializer.java'] = 'package net.fabricmc.api; public interface ModInitializer {void onInitialize();}'
        files['net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java'] = '''package net.fabricmc.fabric.api.event.lifecycle.v1;
public final class ServerLifecycleEvents {public static final Event SERVER_STARTED=new Event();
public static class Event {public void register(java.util.function.Consumer<net.minecraft.server.MinecraftServer> listener){}}}'''
    else:
        base = 'net.minecraftforge' if loader == 'forge' else 'net.neoforged'
        game = base if loader == 'forge' else base + '.neoforge'
        for fq, body in {
            base+'.bus.api.IEventBus': 'public interface IEventBus {<T>void addListener(java.util.function.Consumer<T> listener);}',
            base+'.fml.common.Mod': 'public @interface Mod {String value();}',
            base+'.fml.event.lifecycle.FMLCommonSetupEvent': 'public class FMLCommonSetupEvent {public void enqueueWork(Runnable r){}}',
            game+'.event.server.ServerStartedEvent': 'public class ServerStartedEvent {public net.minecraft.server.MinecraftServer getServer(){return null;}}',
            game+'.common.'+('MinecraftForge' if loader=='forge' else 'NeoForge'): f'public class {"MinecraftForge" if loader=="forge" else "NeoForge"} {{public static {base}.bus.api.IEventBus EVENT_BUS;}}',
        }.items():
            files[fq.replace('.', '/')+'.java'] = 'package '+fq.rsplit('.',1)[0]+'; '+body
        if loader == 'forge':
            files['net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext.java'] = '''package net.minecraftforge.fml.javafmlmod;
public class FMLJavaModLoadingContext {public static FMLJavaModLoadingContext get(){return null;}
public net.minecraftforge.bus.api.IEventBus getModEventBus(){return null;}}'''
            files['net/minecraftforge/event/level/LevelEvent.java'] = '''package net.minecraftforge.event.level;
public class LevelEvent { public static class Load {public net.minecraft.world.level.Level getLevel(){return null;}}}'''
    return files


class ApiTestAddonTests(unittest.TestCase):
    def test_identifier_for_every_target(self):
        self.assertEqual(set(target_ids()), set(IDENTIFIER_TYPES))
        for target, name in IDENTIFIER_TYPES.items():
            with self.subTest(target=target):
                # Compare the generated helper against the effective public API,
                # not just the hand-written Minecraft dependency doubles.
                module = read_materialized_java(target, 'src/main/java/' + API + 'module/ModuleInfo.java')
                self.assertIn(f'import net.minecraft.resources.{name};', module)
                self.assertIn(f'ModuleInfo({name} id,', module)
                source = BUILDER.identifier_source(target)
                self.assertIn(f'import net.minecraft.resources.{name};', source)
                self.assertIn(f'static {name} id(String path)', source)
                factory = f'new {name}' if target.endswith('-forge') else f'{name}.fromNamespaceAndPath'
                self.assertIn(f'return {factory}(ApiSmokeProbe.MOD_ID, path);', source)

    def test_metadata_for_every_target(self):
        for target in target_ids():
            with self.subTest(target=target):
                loader, java, mc, _ = BUILDER.target_properties(target)
                name, content = BUILDER.metadata(target)
                if loader == 'fabric':
                    mod = json.loads(content)
                    self.assertEqual(mod['entrypoints']['main'], [BUILDER.ENTRY])
                    self.assertEqual(mod['depends']['java'], f'>={java}')
                    self.assertEqual(mod['depends']['minecraft'], mc)
                    self.assertEqual(mod['environment'], 'server')
                else:
                    self.assertIn(b'modId="bcceapitest"', content)
                    self.assertIn(b'modId="buildcraftlib"', content)
                    self.assertIn(b'ordering="AFTER"', content)
                    self.assertIn(b'mandatory=true' if loader == 'forge' else b'type="required"', content)
                    self.assertEqual(name, 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml')

    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_independent_jar_compile_and_candidate_binding(self):
        # Cover both sides of the 1.21.11 identifier rename and all loaders. Lower the classfile target ONLY for this
        # offline stub check so the CI validation JDK 21 can also test 26.x source.
        # The real build task always uses the actual target JDK and --release.
        for target in ('1.19.2-forge', '1.21.1-neoforge', '1.21.11-neoforge',
                       '26.3-neoforge', '26.3-fabric'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as td:
                root = Path(td)
                dep, candidate, addon = (root / n for n in ('deps.jar','bcce.jar','addon.jar'))
                compile_jar(root/'deps', dependencies(target), dep)
                compile_jar(root/'api', selected_api(target), candidate, (dep,))
                original = BUILDER.target_properties
                def offline(t):
                    loader, _, mc, version = original(t)
                    return loader, 17, mc, version
                with patch.object(BUILDER, 'target_properties', side_effect=offline):
                    BUILDER.build(target, candidate, addon, str(dep), 'javac')
                    BUILDER.check_addon(target, candidate, addon)
                    with zipfile.ZipFile(addon) as z:
                        self.assertTrue(all(not n.startswith(('buildcraft/', 'net/')) for n in z.namelist()))
                        self.assertIn(BUILDER.PACKAGE+'ApiSmokeProbe.class', z.namelist())
                    with candidate.open('ab') as f:
                        f.write(b'changed candidate')
                    with self.assertRaisesRegex(ValueError, 'exact candidate'):
                        BUILDER.check_addon(target, candidate, addon)

    def test_packaged_probe_rejects_internal_dependency_and_shaded_classes(self):
        target = '1.20.1-forge'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate, addon = root / 'candidate.jar', root / 'addon.jar'
            candidate.write_bytes(b'candidate')
            name, metadata = BUILDER.metadata(target)
            def write_probe(payload, extra=None):
                with zipfile.ZipFile(addon, 'w') as jar:
                    jar.writestr(name, metadata)
                    jar.writestr(BUILDER.BINDING, json.dumps(BUILDER.binding(target, candidate)))
                    for cls in ('LoaderEntry.class', 'ApiSmokeProbe.class', 'Ids.class'):
                        jar.writestr(BUILDER.PACKAGE + cls, payload)
                    if extra:
                        jar.writestr(extra, b'fake class')
            write_probe(b'Lbuildcraft/api/v2/BuildCraftApi;')
            BUILDER.check_addon(target, candidate, addon)
            write_probe(b'Lbuildcraft/lib/Internal;')
            with self.assertRaisesRegex(ValueError, 'internal BuildCraft'):
                BUILDER.check_addon(target, candidate, addon)
            write_probe(b'Lbuildcraft/api/v2/BuildCraftApi;', 'net/minecraft/Shaded.class')
            with self.assertRaisesRegex(ValueError, 'Unexpected class'):
                BUILDER.check_addon(target, candidate, addon)

    def test_runtime_markers_fail_closed(self):
        # Exercise the production marker checker, without requiring Bash on Windows.
        command = [sys.executable, str(ROOT/'scripts/ci-api-test-markers.py')]
        with tempfile.TemporaryDirectory() as td:
            log = Path(td)/'server.log'
            for content, expected in [
                ('Done (1.0s)!\n', False), ('BCCE_API_TEST PASS v1\n', False),
                ('BCCE_API_TEST REGISTERED v1\n', False),
                ('BCCE_API_TEST REGISTERED v1\nBCCE_API_TEST PASS v10\n', False),
                ('BCCE_API_TEST REGISTERED v1\nBCCE_API_TEST PASS v1 extra\n', False),
                ('[main/INFO]: BCCE_API_TEST REGISTERED v1\n[Server thread/INFO]: BCCE_API_TEST PASS v1\n', True),
                ('BCCE_API_TEST REGISTERED v1 \r\nBCCE_API_TEST PASS v1\t\r\n', True),
                ('BCCE_API_TEST REGISTERED v1\nBCCE_API_TEST PASS v1\nBCCE_API_TEST FAIL v1\n', False),
            ]:
                log.write_bytes(content.encode('utf-8'))
                result = subprocess.run([*command, str(log)], capture_output=True)
                self.assertEqual(result.returncode, 0 if expected else 1, content)

            second = Path(td)/'latest.log'
            log.write_bytes(b'BCCE_API_TEST REGISTERED v1\r\n')
            second.write_bytes(b'BCCE_API_TEST PASS v1')
            self.assertEqual(subprocess.run([*command, str(log), str(second)]).returncode, 0)
            # Keep checking all logs even after the registration and PASS are found.
            failure = Path(td)/'failure.log'
            failure.write_bytes(b'BCCE_API_TEST FAIL v1\n')
            for files in ([], [log], [log, second, failure], [log, second, Path(td)/'missing.log']):
                self.assertEqual(subprocess.run([*command, *map(str, files)]).returncode, 1)

    @unittest.skipUnless(os.name == 'posix' and shutil.which('bash'), 'requires POSIX Bash')
    def test_runtime_marker_shell_wrapper(self):
        with tempfile.TemporaryDirectory() as td:
            log = Path(td)/'server.log'
            for content, expected in [
                (b'BCCE_API_TEST REGISTERED v1\nBCCE_API_TEST PASS v1\n', 0),
                (b'BCCE_API_TEST REGISTERED v1\nBCCE_API_TEST PASS v1\nBCCE_API_TEST FAIL v1\n', 1),
            ]:
                log.write_bytes(content)
                result = subprocess.run(['bash', '-c', 'source "$1"; api_test_addon_passed "$2"', '_',
                    str(ROOT/'scripts/ci-smoke-common.sh'), str(log)], capture_output=True)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_release_pipeline_requires_separate_probe(self):
        gates = (ROOT/'build-logic/api-v2-gates.gradle').read_text()
        ci = (ROOT/'.github/workflows/ci.yml').read_text()
        smoke = (ROOT/'scripts/ci-server-smoke.sh').read_text()
        self.assertIn("tasks.register('verifyApi2Fixtures')", gates)
        self.assertIn('dependsOn verifyApi2AddonBinary, verifyApi2Compatibility, buildApiTestAddon', gates)
        self.assertIn('dependsOn verifyApi2Fixtures', gates)
        self.assertTrue((ROOT/'addon-fixture/src/testaddon/java/dev/bcce/apitest/ApiSmokeProbe.java').is_file())
        self.assertFalse(any((ROOT/'api-test-addon').rglob('*.java')), 'Remove the obsolete test addon source tree')
        self.assertIn('build/addon-fixture/installable/${bc.targetName}/bcce-api-test.jar', gates)
        self.assertIn('buildApiTestAddon.configure { dependsOn reobfTask }', gates)
        self.assertIn('BCCE_API_TEST_ADDON="${PWD}/build/addon-fixture/staged/', ci)
        self.assertLess(ci.index('BCCE_API_TEST_ADDON='), ci.index('Upload verified production JAR'))
        self.assertIn('api_test_addon_passed "${log_files[@]}"', smoke)
        self.assertIn('check --target "$target"', smoke)
        builder = (ROOT/'scripts/build-api-test-addon.py').read_text()
        helper = (ROOT/'scripts/addon_fixture_common.py').read_text()
        self.assertIn('API_TEST_ADDON_SOURCE_ROOT', builder)
        self.assertIn('addon-fixture', helper)
        self.assertNotIn("ROOT / 'api-test-addon'", builder)


if __name__ == '__main__':
    unittest.main()
