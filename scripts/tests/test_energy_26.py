#!/usr/bin/env python3
"""Energy module 26.X effective source, downport, and transfer transaction contracts."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))

from source_config import load_properties, target_layout
from source_layout import effective_source_files, _materialize_text_file
from minecraft_compat_fixture import parse_sources
from energy_26_fixture import execute as execute_fluid_transactions

TARGETS = ('26.1.2', '26.2', '26.3')
ENERGY = 'src/main/java/buildcraft/energy'


class Energy26EffectiveContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='bc-energy-26-')
        cls.materialized = {}
        properties = load_properties()
        for version in TARGETS:
            target = version + '-neoforge'
            layout = target_layout(target, properties)
            minecraft = properties[f'target.{target}.deps.minecraft']
            downport_roots = tuple(root for root in (
                layout.family_downport_root,
                layout.family_platform_downport_root,
            ) if root)
            output = Path(cls.temp.name) / target
            entries = effective_source_files(layout, properties, ENERGY)
            for relative, source in sorted(entries.items()):
                _materialize_text_file(
                    source, output / relative, logical_relative=relative,
                    minecraft=minecraft, family=layout.family,
                    platform=layout.platform, preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downport_roots),
                )
            cls.materialized[version] = output

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def text(self, version, name):
        return (self.materialized[version] / ENERGY / name).read_text(encoding='utf-8')

    def test_2612_energy_sources_materialize(self):
        root = self.materialized["26.1.2"] / ENERGY
        for relative in ("BCEnergy.java", "tile/TileEngineIron_BC8.java"):
            with self.subTest(source=relative):
                self.assertTrue((root / relative).is_file())

    def test_26_2_legacy_energy_api_compatibility(self):
        for version in ("26.1.2", "26.2"):
            with self.subTest(version=version):
                self.assertIn("CapUtil.CAP_FLUIDS", self.text(version, "BCEnergy.java"))
                self.assertIn("InternalFluidHandler", self.text(version, "tile/TileEngineIron_BC8.java"))
                self.assertFalse((self.materialized[version] / ENERGY / "tile/EnergyFluidResourceHandler.java").exists())

    def test_native_fluid_capability_registration(self):
        current = self.text('26.3', 'BCEnergy.java')
        self.assertIn('Capabilities.Fluid.BLOCK', current)
        self.assertIn('engine.getFluidResourceHandler(side)', current)
        self.assertIn('CapUtil.CAP_ITEMS', current)
        self.assertNotIn('event, CapUtil.CAP_FLUIDS, BCEnergyBlocks.ENGINE_IRON_TILE_BC8', current)
        for version in ('26.1.2', '26.2'):
            self.assertIn('CapUtil.CAP_FLUIDS', self.text(version, 'BCEnergy.java'))
            self.assertNotIn('Capabilities.Fluid.BLOCK', self.text(version, 'BCEnergy.java'))

    def test_combustion_engine_fluid_handler_boundary(self):
        current = self.text('26.3', 'tile/TileEngineIron_BC8.java')
        self.assertIn('ResourceHandler<FluidResource>', current)
        self.assertIn('new EnergyFluidResourceHandler(this, tankFuel, tankCoolant, tankResidue)', current)
        self.assertIn('FluidUtil.interactWithFluidHandler(player, hand, worldPosition, fluidResourceHandler, null)', current)
        self.assertIn('EnergyFluidResourceHandler.insertInternal(tankResidue, residueFluid, this)', current)
        self.assertIn('EnergyFluidResourceHandler.drainInternal(', current)
        self.assertNotIn('InternalFluidHandler', current)
        self.assertNotIn('IFluidHandlerAdv', current)
        self.assertNotIn('FluidAction', current)
        for version in ('26.1.2', '26.2'):
            old = self.text(version, 'tile/TileEngineIron_BC8.java')
            self.assertIn('InternalFluidHandler', old)
            self.assertIn('IFluidHandlerAdv', old)
            self.assertIn('FluidUtilBC.onTankActivated', old)

    def test_native_resource_handler_stored_in_correct_target(self):
        self.assertFalse((self.materialized['26.1.2'] / ENERGY / 'tile/EnergyFluidResourceHandler.java').exists())
        self.assertFalse((self.materialized['26.2'] / ENERGY / 'tile/EnergyFluidResourceHandler.java').exists())
        native = self.text('26.3', 'tile/EnergyFluidResourceHandler.java')
        self.assertIn('implements ResourceHandler<FluidResource>', native)
        self.assertIn('Transaction.open(context)', native)
        self.assertIn('TransferJournal.notifyAfterCommit', native)
        self.assertIn('index == 2', native)
        self.assertIn('index != 2', native)

    def test_engine_fuel_and_mj_existing_contracts(self):
        for version in ('26.2', '26.3'):
            with self.subTest(version=version):
                stone = self.text(version, 'tile/TileEngineStone_BC8.java')
                self.assertIn(
                    'ItemCompat.getBurnTime(itemstack, this, contents)' if version == '26.3'
                    else 'ItemCompat.getBurnTime(itemstack)', stone)
                self.assertIn('ItemCompat.getCraftingRemainingItem(fuel)', stone)
                if version == '26.3':
                    self.assertNotIn('IItemHandlerModifiable', stone)
                    self.assertIn('ItemResource.of(stack)', stone)
                    self.assertIn('refreshForcedFuelState()', stone)
                    self.assertIn('ItemCompat.getBurnTime(itemstack, this, contents)', stone)
                self.assertIn('invFuel.extractItem(0, 1, false)', stone)
                mj = self.text(version, 'tile/TileEngineFE.java')
                self.assertIn('MjAmount.MICRO_MJ_PER_MJ', mj)

    def test_energy_fluids_registered_and_rendered(self):
        for version in ('26.2', '26.3'):
            with self.subTest(version=version):
                fluids = self.text(version, 'BCEnergyFluids.java')
                self.assertIn('public static final BCFluid[] crudeOil = new BCFluid[3]', fluids)
                self.assertIn('public static final BCFluid[] oilResidue = new BCFluid[3]', fluids)
                self.assertIn('OIL_BUCKET.add(bucket)', fluids)
                proxy = self.text(version, 'BCEnergyClientProxy.java')
                self.assertIn('RegisterFluidModelsEvent', proxy)
                self.assertIn('registerFluidClientExtensions', proxy)

    def test_native_resource_handler_transaction_runtime(self):
        print(execute_fluid_transactions(
            self.materialized['26.3'] / ENERGY / 'tile/EnergyFluidResourceHandler.java'), flush=True)

    def test_no_removed_neoforge_energy_interfaces_on_26_3(self):
        unavailable = (
            'net.neoforged.neoforge.items.IItemHandlerModifiable',
            'net.neoforged.neoforge.fluids.capability.IFluidHandler',
            'net.neoforged.neoforge.fluids.FluidUtil',
            'net.neoforged.neoforge.fluids.capability.IFluidHandlerItem',
        )
        for file in (self.materialized['26.3'] / ENERGY).rglob('*.java'):
            text = file.read_text(encoding='utf-8')
            for name in unavailable:
                with self.subTest(file=file.name, api=name):
                    self.assertNotIn('import ' + name, text)

    def test_native_energy_java_syntax(self):
        roots = [self.materialized[version] / ENERGY for version in ('26.2', '26.3')]
        result = parse_sources(roots)
        self.assertIn('35 units, 0 errors', result)
        self.assertIn('36 units, 0 errors', result)
        print(result, flush=True)


if __name__ == '__main__':
    unittest.main()
