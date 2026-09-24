#!/usr/bin/env python3
"""Integration and contract checks for internal platform boundaries.

Compiles real Java boundaries against native API doubles, not a Minecraft build.
Baseline fingerprints protect config semantics and existing registry ID/order.
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
from source_layout import materialize_target, load_properties, target_layout, effective_source_files, resolve_effective_source
from platform_boundary_fixture import run
from platform_source_contract import config_tokens, catalog_entries, digest

TARGETS = ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge')


class PlatformBoundaries(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bc-platform-boundaries-')
        cls.work = Path(cls.temporary.name)
        cls.props = load_properties()
        cls.java = {}
        for target in TARGETS:
            materialize_target(target, cls.work/target, cls.props)
            cls.java[target] = cls.work/target/'src/main/java'
        cls.baseline = json.loads((ROOT/'scripts/tests/platform_baseline.json').read_text(encoding='utf-8'))['targets']

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def test_real_boundaries_on_all_supported_api_shapes(self):
        for target, java in self.java.items():
            with self.subTest(target=target):
                print(run(java, self.work/'probes'/target, target), flush=True)

    def test_config_values_defaults_ranges_keys_and_reload_logic_preserved(self):
        for target, expected in self.baseline.items():
            for name, fingerprint in expected['config'].items():
                with self.subTest(target=target, config=name):
                    source = (self.java[target]/'buildcraft'/(name+'.java')).read_text(encoding='utf-8')
                    self.assertEqual(fingerprint, digest(config_tokens(source)))
                    self.assertNotRegex(source, r'\b(?:ForgeConfigSpec|ModConfigSpec|ModConfigEvent)\b')
                    self.assertIn('BCConfigSpec', source)

    def test_existing_registration_names_and_order_preserved(self):
        for target, expected in self.baseline.items():
            for name, entries in expected['catalog'].items():
                with self.subTest(target=target, catalog=name):
                    source = (self.java[target]/'buildcraft'/name).read_text(encoding='utf-8')
                    self.assertEqual(entries, catalog_entries(source))

    def test_neutral_contracts_have_no_loader_or_api_dependency(self):
        base = ROOT/'source-shared/src/main/java/buildcraft/lib/platform'
        paths = list(base.rglob('*.java'))
        self.assertGreaterEqual(len(paths), 10)
        # Only the pre-existing permission-service integration may depend on API2 here.
        api_bridge = base/'permission/BCPermissions.java'
        for path in paths:
            source = path.read_text(encoding='utf-8')
            self.assertNotRegex(source, r'\b(?:net\.minecraftforge|net\.neoforged|net\.fabricmc)\.', str(path))
            if path != api_bridge:
                self.assertNotRegex(source, r'\bbuildcraft\.api\.', str(path))
            else:
                self.assertIn('BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(', source)

    def test_no_factory_is_evaluated_before_native_registration(self):
        entry = (ROOT/'source-shared/src/main/java/buildcraft/lib/platform/registry/BCRegistryEntry.java').read_text()
        self.assertIn('return registered.get()', entry)
        self.assertNotIn('factory.get()', entry)
        for target, java in self.java.items():
            with self.subTest(target=target):
                fluids = (java/'buildcraft/energy/BCEnergyFluids.java').read_text()
                self.assertNotIn('RegistryObject.create', fluids)
                self.assertIn('refs.source = FLUIDS.register(', fluids)
                self.assertIn('refs.flowing = FLUIDS.register(', fluids)
                self.assertIn('OIL_SOURCE.add(refs.source)', fluids)
                self.assertRegex(fluids, r'Keys\.FLUID_TYPES')
                binding = (java/'buildcraft/lib/platform/registry/RegistryBinding.java').read_text()
                self.assertIn('nativeRegister.register(bus)', binding)
                self.assertNotIn('entry.get()', binding)
                menu = (java/'buildcraft/lib/gui/BCContainerFactory.java').read_text()
                self.assertIn('implements BCMenuFactory<T>', menu)
                self.assertIn('PlatformMenus.create(', menu)

    def test_transfer_lookup_and_transaction_ownership_are_preserved(self):
        for target, java in self.java.items():
            with self.subTest(target=target):
                platform = (java/'buildcraft/lib/platform/storage/PlatformStorage.java').read_text()
                if target.endswith('-forge'):
                    self.assertIn('getCapability(CapUtil.CAP_ITEMS, face)', platform)
                    self.assertIn('CompatCapTransfromer.INSTANCE.getCap(provider, CapUtil.CAP_FLUIDS, face)', platform)
                    self.assertIn('getCapability(CapUtil.CAP_FE, face)', platform)
                else:
                    self.assertIn('CapUtil.getItemHandler(level, pos, face)', platform)
                    self.assertIn('CapUtil.getFluidHandler(level, pos, face)', platform)
                    self.assertIn('CapUtil.getEnergyStorage(level, pos, face)', platform)
                self.assertIn('new ', platform)
                self.assertIn('.items.wrapper.InvWrapper(inventory)', platform)
                schematic = (java/'buildcraft/builders/snapshot/SchematicBlockDefault.java').read_text()
                self.assertIn('PlatformStorage.localInventory(container)', schematic)
                self.assertIn('MutableItemStorage', schematic)
                self.assertNotRegex(schematic, r'\bIItemHandler(?:Modifiable)?\b')
                fluid_util = (java/'buildcraft/lib/misc/FluidUtilBC.java').read_text()
                self.assertIn('moveStorage(StorageAdapters.fromNativeFluids(from), StorageAdapters.fromNativeFluids(to), max)',fluid_util)
                self.assertIn('restoreFluid(StorageAdapters.toNativeFluids(from),',fluid_util)
                if target.startswith('1.21.11-'):
                    interop = (java/'buildcraft/lib/compat/transfer/TransferInterop.java').read_text()
                    self.assertIn('Transaction.open(TransferJournal.current())',interop)
                    self.assertNotIn('Transaction.openRoot()',interop)

    def test_storage_exposure_and_gameplay_lookups_cross_one_internal_boundary(self):
        migrated = (
            'core/statements/TriggerInventory.java',
            'core/statements/TriggerInventoryLevel.java',
            'core/statements/TriggerFluidContainer.java',
            'core/statements/TriggerFluidContainerLevel.java',
            'core/statements/CoreTriggerProvider.java',
            'lib/inventory/ItemTransactorHelper.java',
            'silicon/tile/TileChargingTable.java',
            'transport/pipe/flow/PipeFlowFluids.java',
            'transport/pipe/flow/PipeFlowForgeEnergy.java',
            'transport/pipe/flow/PipeFlowPower.java',
        )
        direct_lookup = re.compile(r'CapUtil\.get(?:ItemHandler|FluidHandler|EnergyStorage)|getCapability\([^\n]*(?:CAP_ITEMS|CAP_FLUIDS|CAP_FE|ForgeCapabilities\.(?:ITEM_HANDLER|FLUID_HANDLER|ENERGY)|Capabilities\.(?:ItemHandler|FluidHandler|Energy))')
        for target, java in self.java.items():
            with self.subTest(target=target):
                helper = (java/'buildcraft/lib/cap/CapabilityHelper.java').read_text()
                self.assertIn('StorageMap storages', helper)
                self.assertIn('addItemStorage(', helper)
                self.assertIn('addFluidStorage(', helper)
                self.assertIn('addEnergyStorage(', helper)
                for logical in migrated:
                    path = java/'buildcraft'/logical
                    if not path.exists():
                        continue
                    source = re.sub(r'/\*.*?\*/|//[^\n]*', '', path.read_text(), flags=re.S)
                    self.assertNotRegex(source, direct_lookup, str(path))
                for logical in ('energy/tile/TileEngineFE.java', 'energy/tile/TileDynamoMJ.java'):
                    source = (java/'buildcraft'/logical).read_text()
                    self.assertRegex(source, r'caps\.addEnergyStorage\(')
                    self.assertNotRegex(source, r'caps\.addCapability(?:Instance)?\([^\n]*(?:CAP_FE|ENERGY)')
                for logical in ('factory/tile/TileTank.java', 'factory/tile/TileFloodGate.java', 'factory/tile/TilePump.java'):
                    source = (java/'buildcraft'/logical).read_text()
                    self.assertRegex(source, r'caps\.addFluidStorage\(')
                    self.assertNotRegex(source, r'caps\.addCapability(?:Instance)?\([^\n]*(?:CAP_FLUIDS|FLUID_HANDLER)')

    def test_api2_transfer_services_share_one_operation_runtime(self):
        operation = (ROOT/'source-shared/src/main/java/buildcraft/lib/internal/transfer/OperationScope.java').read_text(encoding='utf-8')
        self.assertIn('ThreadLocal<Deque<OperationScope>>', operation)
        self.assertIn('Guard enter(Object endpointIdentity)', operation)
        self.assertIn('sharedAttachment(Object key', operation)
        self.assertIn('parent == null ? new RootState() : parent.root', operation)
        self.assertNotRegex(operation, r'net\.(?:minecraftforge|neoforged|fabricmc)\.')

        adapters = (ROOT/'source-shared/src/main/java/buildcraft/lib/internal/transfer/TransferAdapters.java').read_text(encoding='utf-8')
        for token in ('ItemStorage', 'FluidStorage<F>', 'EnergyStorage', 'OperationScope.Guard', 'ItemPort', 'FluidPort', 'ExternalEnergyPort'):
            self.assertIn(token, adapters)
        self.assertNotRegex(adapters, r'net\.(?:minecraftforge|neoforged|fabricmc)\.')

        services = (ROOT/'source-shared/src/main/java/buildcraft/lib/internal/api/v2/platform/DefaultPlatformServices.java').read_text(encoding='utf-8')
        self.assertIn('PlatformTransferLookup', services)
        self.assertIn('TransferAdapters::itemPort', services)
        self.assertIn('TransferAdapters::fluidPort', services)
        self.assertIn('TransferAdapters::energyPort', services)

        for target, java in self.java.items():
            with self.subTest(target=target):
                bootstrap = (java/'buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java').read_text(encoding='utf-8')
                self.assertIn('new DefaultPlatformServices(Lookup.INSTANCE)', bootstrap)
                self.assertIn('implements PlatformTransferLookup', bootstrap)
                self.assertNotRegex(bootstrap, r'(?:Forge|NeoForge)(?:Item|Fluid|Energy)Port')
                self.assertIn('TransferAdapters.items(storage)', bootstrap)
                self.assertIn('TransferAdapters.energy(storage)', bootstrap)
                fuel = (java/'buildcraft/lib/fluid/FuelApiBridge.java').read_text(encoding='utf-8')
                self.assertIn('FluidCarrier<FluidStack> CARRIER', fuel)

    def test_item_callbacks_and_promoted_gameplay_are_loader_neutral(self):
        callback = (ROOT/'source-shared/src/main/java/buildcraft/lib/tile/item/StackChangeCallback.java').read_text(encoding='utf-8')
        self.assertIn('MutableItemStorage itemHandler', callback)
        self.assertNotRegex(callback, r'IItemHandlerModifiable|net\.(?:minecraftforge|neoforged)')

        shared_gameplay = (
            'src/main/java/buildcraft/builders/tile/TileElectronicLibrary.java',
            'src/main/java/buildcraft/robotics/tile/TileRequester.java',
            'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourDirectional.java',
        )
        family_gameplay = (
            'src/main/java/buildcraft/builders/tile/TileConstructionMarker.java',
            'src/main/java/buildcraft/builders/tile/TileFiller.java',
            'src/main/java/buildcraft/builders/tile/TileReplacer.java',
            'src/main/java/buildcraft/robotics/tile/TileZonePlanner.java',
            'src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourDiamond.java',
        )
        for target in TARGETS:
            layout = target_layout(target, self.props)
            for logical in shared_gameplay:
                owner = resolve_effective_source(layout, self.props, logical)
                self.assertIsNotNone(owner, (target, logical))
                self.assertIn('/source-shared/', owner.as_posix(), (target, logical, owner))
            expected_family = '/source-families/legacy/' if target.endswith('-forge') else '/source-families/modern/'
            for logical in family_gameplay:
                owner = resolve_effective_source(layout, self.props, logical)
                self.assertIsNotNone(owner, (target, logical))
                self.assertIn(expected_family, owner.as_posix(), (target, logical, owner))

            electronic = (self.java[target]/'buildcraft/builders/tile/TileElectronicLibrary.java').read_text(encoding='utf-8')
            requester = (self.java[target]/'buildcraft/robotics/tile/TileRequester.java').read_text(encoding='utf-8')
            directional = (self.java[target]/'buildcraft/transport/pipe/behaviour/PipeBehaviourDirectional.java').read_text(encoding='utf-8')
            self.assertNotRegex(electronic, r'net\.(?:minecraftforge|neoforged)')
            self.assertNotRegex(requester, r'net\.(?:minecraftforge|neoforged)')
            self.assertNotRegex(directional, r'SidedThreadGroups|net\.(?:minecraftforge|neoforged)')
            self.assertIn('PlatformMenus.open(serverPlayer, this, worldPosition)', requester)
            self.assertIn('!pipe.getHolder().getPipeWorld().isClientSide()', directional)

    def test_menu_opening_is_a_loader_boundary(self):
        for target, java in self.java.items():
            with self.subTest(target=target):
                menus = (java/'buildcraft/lib/platform/registry/PlatformMenus.java').read_text(encoding='utf-8')
                self.assertIn('public static void open(ServerPlayer player, MenuProvider provider, BlockPos pos)', menus)
                requester = (java/'buildcraft/robotics/tile/TileRequester.java').read_text(encoding='utf-8')
                zone = (java/'buildcraft/robotics/tile/TileZonePlanner.java').read_text(encoding='utf-8')
                diamond = (java/'buildcraft/transport/pipe/behaviour/PipeBehaviourDiamond.java').read_text(encoding='utf-8')
                for source in (requester, zone, diamond):
                    self.assertIn('PlatformMenus.open(', source)
                    self.assertNotIn('NetworkHooks.openScreen', source)

    def test_storage_boundary_regressions_are_guarded(self):
        forge = self.java['1.20.1-forge']
        modern = self.java['1.21.11-neoforge']

        # Loader-specific BC capabilities must be accessed behind a bridge so the shared provider compiles on Forge.
        provider = (forge/'buildcraft/core/statements/CoreTriggerProvider.java').read_text()
        self.assertIn('PlatformCapabilities.hasWork(tile, null)', provider)
        self.assertNotIn('CapUtil.getCapability(', provider)
        bridge = (forge/'buildcraft/lib/platform/capability/PlatformCapabilities.java').read_text()
        self.assertIn('LazyOptional<IHasWork> capability = CapUtil.getCapability(tile, TilesAPI.CAP_HAS_WORK, face)', bridge)
        modern_bridge = (modern/'buildcraft/lib/platform/capability/PlatformCapabilities.java').read_text()
        self.assertIn('CapUtil.getCapability(tile.getLevel(), tile.getBlockPos(), capability, face)', modern_bridge)

        # Forge fluid lookup must retain optional-mod transformers before the native capability fallback.
        platform = (forge/'buildcraft/lib/platform/storage/PlatformStorage.java').read_text()
        self.assertIn('CompatCapTransfromer.INSTANCE.getCap(provider, CapUtil.CAP_FLUIDS, face)', platform)
        self.assertIn('pipeFluids(IPipeHolder holder, Direction face)', platform)
        self.assertIn('pipeEnergy(IPipeHolder holder, Direction face)', platform)
        transformer = (forge/'buildcraft/compat/CompatCapTransfromer.java').read_text()
        self.assertIn('getCap(ICapabilityProvider provider', transformer)

        # Storage adapters are directional; dual-interface implementations cannot create overload ambiguity.
        for target, java in self.java.items():
            adapters = (java/'buildcraft/lib/platform/storage/StorageAdapters.java').read_text()
            for name in ('fromNativeItems','toNativeItems','fromNativeFluids','toNativeFluids','fromNativeEnergy','toNativeEnergy'):
                self.assertIn(name+'(', adapters, (target, name))
            self.assertNotRegex(adapters, r'public static \S+\s+(?:items|fluids|energy)\(')

        # Forge-owned capability handles are cached and invalidated with their owning block entity.
        helper = (forge/'buildcraft/lib/cap/CapabilityHelper.java').read_text()
        self.assertIn('Map<EnumPipePart, Map<Capability<?>, LazyOptional<?>>> cached', helper)
        self.assertIn('public void invalidate()', helper)
        self.assertIn('optional.invalidate()', helper)
        tile = (forge/'buildcraft/lib/tile/TileBC_Neptune.java').read_text()
        self.assertIn('caps.invalidate();', tile)
        self.assertIn('caps.revive();', tile)


    def test_real_gradle_compile_regressions_are_guarded(self):
        for target, java in self.java.items():
            with self.subTest(target=target):
                # Packet contexts must remain canonical and loader-neutral at shared call sites.
                for relative in ('buildcraft/transport/pipe/PluggableHolder.java', 'buildcraft/lib/tile/TileBC_Neptune.java'):
                    source = (java/relative).read_text()
                    self.assertNotIn('BCPacketBCPacketContext', source)
                    self.assertNotRegex(source, r'import net\.(?:minecraftforge|neoforged).*BCPacketContext')

        forge119 = self.java['1.19.2-forge']
        forge120 = self.java['1.20.1-forge']

        update = (forge119/'buildcraft/lib/net/MessageUpdateTile.java').read_text()
        self.assertNotIn('context.getNetworkManager()', update)
        self.assertIn('MessageUpdateTileClientHandler.getClientLevel()', update)

        forestry = (forge119/'buildcraft/compat/forestry/pipe/ForestryPropolisNetwork.java').read_text()
        self.assertIn('new ForgePacketContext(context.get())', forestry)

        ic2 = (forge119/'buildcraft/compat/ic2/Ic2Compat.java').read_text()
        self.assertIn('List<BCRegistryEntry<? extends Item>> FLUID_CELLS', ic2)
        self.assertNotIn('RegistryObject<Item>', ic2)

        recipes = (forge119/'buildcraft/silicon/BCSiliconRecipes.java').read_text()
        self.assertIn('preInit(BCRegistryBinder modEventBus)', recipes)
        self.assertNotIn('IEventBus', recipes)

        transport = (forge119/'buildcraft/transport/BCTransportEventDist.java').read_text()
        setup_start = transport.index('onClientSetup(FMLClientSetupEvent event)')
        common_start = transport.index('onClientCommonSetup(FMLCommonSetupEvent event)')
        self.assertIn('PlatformClientRegistration.screens(event, BCTransportClientGuis::clientInit)', transport[setup_start:common_start])
        self.assertNotIn('PlatformClientRegistration.screens(', transport[common_start:])

        energy = (forge119/'buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java').read_text()
        self.assertIn('PlatformStorage.pipeEnergy(pipe.getHolder()', energy)
        self.assertNotIn('getCapabilityFromPipe(side, ForgeCapabilities.ENERGY)', energy)

        schematic = (forge120/'buildcraft/builders/snapshot/SchematicBlockDefault.java').read_text()
        self.assertIn('import net.minecraft.core.registries.BuiltInRegistries;', schematic)
        core_blocks = (forge120/'buildcraft/core/BCCoreBlocks.java').read_text()
        self.assertIn('import net.minecraft.world.level.material.MapColor;', core_blocks)

    def test_migrated_gameplay_events_are_no_longer_native_subscribers(self):
        owners = ('lib/BCLibEventDist','transport/BCTransportEventDist','builders/BCBuildersEventDist','robotics/SimpleRobotRegistryProvider','transport/stripes/PipeExtensionManager','energy/BCEnergy')
        for target, java in self.java.items():
            for name in owners:
                with self.subTest(target=target, owner=name):
                    text = (java/'buildcraft'/(name+'.java')).read_text()
                    self.assertIn('registerGameplayEvents()',text)
                    self.assertIn('gameplayEventsRegistered',text)
                    self.assertNotRegex(text,r'@SubscribeEvent\s+(?:public|private|protected)\s+(?:static\s+)?void\s+\w+\(BCEvents\.')
                    self.assertNotRegex(text,r'import net\.(?:minecraftforge|neoforged).*\.(?:LevelTickEvent|ServerTickEvent|PlayerTickEvent|EntityJoinLevelEvent|ChunkWatchEvent);')
            transport = (java/'buildcraft/transport/BCTransportEventDist.java').read_text()
            self.assertEqual(1,transport.count('PlatformEvents.levelTick(BCEvents.Phase.START'))
            self.assertEqual(1,transport.count('PlatformEvents.levelTick(BCEvents.Phase.END'))
            transport_bootstrap = (java/'buildcraft/transport/BCTransport.java').read_text()
            self.assertNotIn('EVENT_BUS.register(BCTransportEventDist.class)', transport_bootstrap)

    def test_optimized_lookup_matches_full_layer_resolution(self):
        for target in TARGETS:
            layout = target_layout(target, self.props)
            for logical, expected in effective_source_files(layout,self.props).items():
                self.assertEqual(expected,resolve_effective_source(layout,self.props,logical),(target,logical))
            for missing in ('src/main/java/buildcraft/NotExistingSourceProbe.java','../x','/etc/passwd','.bc-source-layer'):
                self.assertIsNone(resolve_effective_source(layout,self.props,missing))

if __name__=='__main__':
    unittest.main(verbosity=2)
