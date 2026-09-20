#!/usr/bin/env python3
"""Actor/client boundary integration tests and offline typed Java probes.

The catalogue snapshot was captured from the full fixed-10.1 baseline. Neither
parsing nor API-shaped doubles substitute for Gradle against Minecraft/loader JARs.
"""
from __future__ import annotations
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts'))
from source_layout import materialize_target, load_properties, target_layout, resolve_effective_source
from actor_client_fixture import run_server
from client_registration_fixture import run_client

TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge')


def code(source: str) -> str:
    return re.sub(r'/\*[\s\S]*?\*/|//[^\n]*', '', source)


class ActorClientBoundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bc-actor-client-')
        cls.work = Path(cls.temporary.name)
        cls.props = load_properties()
        cls.java = {}
        for target in TARGETS:
            materialize_target(target, cls.work/target, cls.props)
            cls.java[target] = cls.work/target/'src/main/java/buildcraft'
        cls.baseline = json.loads((ROOT/'scripts/tests/client_catalog_baseline.json').read_text(encoding='utf-8'))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_real_actor_permission_ticket_boundaries(self):
        for target, root in self.java.items():
            with self.subTest(target=target):
                print(target, run_server(root.parent, self.work/'probe'/target/'server', target), flush=True)

    def test_real_client_registration_and_model_boundaries(self):
        for target, root in self.java.items():
            with self.subTest(target=target):
                print(target, run_client(root.parent, self.work/'probe'/target/'client', target), flush=True)

    def test_existing_screen_and_renderer_catalogues_preserved(self):
        for target, baseline in self.baseline.items():
            root = self.java[target]
            for relative, expected in baseline['renderers'].items():
                with self.subTest(target=target, catalog=relative):
                    module = relative.split('/')[0]
                    name = 'BC'+module.capitalize()+'ClientRenderers'
                    catalog = (root/module/(name+'.java')).read_text()
                    current = [[method,re.sub(r'\s+','',args)] for method,args in re.findall(
                        r'registry\.register(BlockEntityRenderer|EntityRenderer)\(([^;]+)\);', catalog)]
                    self.assertEqual(expected,current)
                    caller = (root/relative).read_text()
                    self.assertEqual(1,caller.count(name+'.register('),'catalogue must still have its event forwarding call')
                    self.assertNotRegex(caller, r'(?:event|e)\.register(?:BlockEntity|Entity)Renderer\(')
            for relative, expected in baseline['screens'].items():
                with self.subTest(target=target, catalog=relative):
                    catalog = (root/relative).read_text()
                    current = [re.sub(r'\s+','',args) for args in re.findall(r'event\.register\(([^;]+)\);',catalog)]
                    self.assertEqual(expected,current)
                    self.assertIn('ClientRegistration.Screens event',catalog)
                    self.assertNotRegex(catalog, r'net\.(?:minecraftforge|neoforged)\.')

            # Core, converter GUIs and Forestry have their own existing setup callbacks.
            # Their view conversion must keep the original registrations too.
            for relative, expected in baseline['extra_screens'].items():
                with self.subTest(target=target, direct_screens=relative):
                    caller=(root/relative).read_text()
                    entries=re.findall(r'\.register\(([^;]*?,\s*Gui\w+::new)\);',caller)
                    self.assertEqual(expected,[re.sub(r'\s+','',entry) for entry in entries])
                    self.assertNotIn('MenuScreens.register(',code(caller))

    def test_actor_and_ticket_lifetimes_reach_world_unload(self):
        for target, root in self.java.items():
            with self.subTest(target=target):
                provider=(root/'lib/misc/FakePlayerProvider.java').read_text()
                self.assertIn('BCActors.at(world, profile, pos)',provider)
                self.assertIn('BCActors.unloadWorld(world)',provider)
                events=(root/'lib/BCLibEventDist.java').read_text()
                self.assertIn('FakePlayerProvider.INSTANCE.unloadWorld(',events)
                self.assertIn('BCChunkTickets.unloadWorld(',events)
                tickets=(root/'lib/platform/chunk/BCChunkTickets.java').read_text()
                self.assertIn('unloadWorld(ServerLevel level) { LOADED_CHUNKS.remove(level); }',tickets)
                quarry=(root/'builders/tile/TileQuarry.java').read_text()
                self.assertIn('ChunkLoaderManager.loadChunksForTile(',quarry)
                self.assertIn('ChunkLoaderManager.releaseChunksFor(',quarry)
                block=(root/'lib/misc/BlockUtil.java').read_text()
                self.assertIn('PlatformWorldActions.canBreakBlock(world, pos, actor)',block)
                self.assertIn('PlatformWorldActions.placeBlock(level, pos, state, actor, placedAgainst, flags)',block)
                self.assertEqual(2,block.count('BCActors.withTool('))
                self.assertNotRegex(code(block), r'\b(?:new BreakEvent|BlockSnapshot\.create|(?:ForgeEventFactory|EventHooks)\.onBlockPlace)\b')

    def test_old_platform_copies_do_not_shadow_new_effective_owners(self):
        for target in TARGETS:
            layout=target_layout(target,self.props)
            for relative in ('lib/misc/FakePlayerProvider.java','lib/chunkload/ChunkLoaderManager.java'):
                selected=resolve_effective_source(layout,self.props,'src/main/java/buildcraft/'+relative)
                self.assertEqual(ROOT/'source-shared/src/main/java/buildcraft'/relative,selected)
            for module in ('builders','factory','robotics','silicon','transport'):
                relative=f'{module}/BC{module.capitalize()}ClientGuis.java'
                selected=resolve_effective_source(layout,self.props,'src/main/java/buildcraft/'+relative)
                self.assertEqual(ROOT/'source-shared/src/main/java/buildcraft'/relative,selected)

    def test_native_servers_and_registration_do_not_leak_into_implementations(self):
        native=r'net\.(?:minecraftforge|neoforged)\.'
        for target, root in self.java.items():
            with self.subTest(target=target):
                for rel in ('lib/platform/actor/BCActors.java','lib/platform/actor/ActorCache.java',
                            'lib/platform/permission/BCPermissions.java','lib/platform/chunk/BCChunkTickets.java'):
                    source=(root/rel).read_text()
                    self.assertNotRegex(source,native)
                    self.assertNotIn('net.minecraft.client.',source)
                for rel in ('lib/client/model/ModelHolder.java','lib/client/model/ModelHolderRegistry.java',
                            'lib/client/model/ModelHolderStatic.java','lib/client/model/ModelHolderVariable.java'):
                    source=(root/rel).read_text()
                    self.assertNotRegex(source,r'\b(?:ModelEvent|RegisterAdditional|RegisterStandalone|BakingCompleted|ModifyBakingResult)\b')
                if target.startswith('1.21.11'):
                    holder=(root/'lib/client/model/ModelHolderStatic.java').read_text()
                    self.assertNotIn('net.neoforged.neoforge.client.model.standalone.',holder)
                    self.assertIn('ClientStandaloneModel',holder)
                for path in root.rglob('*.java'):
                    relative=path.relative_to(root).as_posix()
                    source=code(path.read_text())
                    if re.search(r'\bnew\s+(?:FakePlayer|FakePlayerBC)\s*\(',source):
                        self.assertEqual('lib/platform/actor/PlatformActors.java',relative)
                    if re.search(r'\bForgeChunkManager\.|\bnew TicketController\(',source):
                        self.assertEqual('lib/platform/chunk/PlatformChunkTickets.java',relative)

    def test_legacy_render_layer_targets_and_order_are_not_expanded(self):
        for target, root in self.java.items():
            silicon=(root/'silicon/BCSilicon.java').read_text()
            factory=(root/'factory/BCFactory.java').read_text()
            self.assertEqual(target in ('1.20.1-forge','1.21.1-neoforge'),
                             'BCSiliconClientRenderers.layers(PlatformClientRegistration.layers())' in silicon)
            self.assertEqual(target=='1.20.1-forge',
                             'BCFactoryClientRenderers.layers(PlatformClientRegistration.layers())' in factory)
            catalogue=(root/'silicon/BCSiliconClientRenderers.java').read_text()
            self.assertIn('PROGRAMMING_TABLE_BLOCK.get(), ClientRegistration.BlockLayer.TRANSLUCENT',catalogue)
            self.assertEqual(4,catalogue.count('ClientRegistration.BlockLayer.CUTOUT'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
