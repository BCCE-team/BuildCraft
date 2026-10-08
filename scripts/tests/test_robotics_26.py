#!/usr/bin/env python3
"""26.X robotics effective source, 26.1.2/26.2 freeze, and 26.3 robot transfers."""
from __future__ import annotations
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import effective_source_files,_materialize_text_file
from minecraft_compat_fixture import parse_sources
from robotics_26_fixture import execute

ROBOTICS='src/main/java/buildcraft/robotics'

class Robotics26Contracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bc-robotics-26-')
        cls.roots={}
        props=load_properties()
        for version in ('26.1.2','26.2','26.3'):
            target=version+'-neoforge'
            layout=target_layout(target,props)
            downs=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
            out=Path(cls.temp.name)/version
            for logical,src in sorted(effective_source_files(layout,props,ROBOTICS).items()):
                _materialize_text_file(src,out/logical,logical_relative=logical,
                    minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(src.is_relative_to(root) for root in downs))
            cls.roots[version]=out/ROBOTICS

    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()

    def text(self,version,name):return (self.roots[version]/name).read_text(encoding='utf-8')

    def hashes(self,version):
        root=self.roots[version]
        return {(Path(ROBOTICS)/p.relative_to(root)).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
                for p in root.rglob('*.java')}

    def test_2612_entire_robotics_frozen_byte_exact(self):
        baseline=json.loads((ROOT/'build-config/materialized-baselines/26.1.2-neoforge.json').read_text())['files']
        expected={k:v for k,v in baseline.items() if k.startswith(ROBOTICS+'/') and k.endswith('.java')}
        self.assertEqual(151,len(expected))
        self.assertEqual(expected,self.hashes('26.1.2'))

    def test_262_entire_robotics_frozen_byte_exact(self):
        baseline=json.loads((ROOT/'build-config/materialized-baselines/26.2-robotics.json').read_text())
        self.assertEqual(151,baseline['file_count'])
        self.assertEqual(baseline['files'],self.hashes('26.2'))

    def test_native_file_selection_and_entity_capability(self):
        self.assertEqual(152,len(self.hashes('26.3')))
        root=self.roots['26.3']
        self.assertTrue((root/'compat/RobotFluidResourceHandler263.java').exists())
        for version in ('26.1.2','26.2'):
            self.assertFalse((self.roots[version]/'compat/RobotFluidResourceHandler263.java').exists())
        registration=self.text('26.3','BCRobotics.java')
        self.assertIn('event.registerEntity(Capabilities.Fluid.ENTITY, BCRoboticsEntities.ROBOT.get()',registration)
        self.assertIn('entity.resourceHandler()',registration)
        self.assertNotIn('Capabilities.Fluid.ENTITY',self.text('26.2','BCRobotics.java'))

    def test_robot_resource_handler_transaction_boundary(self):
        handler=self.text('26.3','compat/RobotFluidResourceHandler263.java')
        robot=self.text('26.3','entity/EntityRobot.java')
        base=self.text('26.3','internal/legacy/robots/EntityRobotBase.java')
        for feature in ('implements ResourceHandler<FluidResource>','Transaction.open(context)',
                        'rollbackJournal.record()','TransferPreconditions.checkNonEmptyNonNegative',
                        'forRollback(FluidStack stored)','robot.fill(resource.toStack(amount), FluidAction.EXECUTE)',
                        'robot.drain(resource.toStack(amount), FluidAction.EXECUTE)'):
            self.assertIn(feature,handler)
        self.assertIn('fluidJournal.record()',robot)
        self.assertIn('ResourceHandler<FluidResource> resourceHandler()',base)
        self.assertIn('new RobotFluidResourceHandler263(this)',robot)
        self.assertNotIn('implements Container, IFluidHandler',base)

    def test_compiled_native_handler_and_nested_transactions(self):
        result=execute(
            self.roots['26.3']/'compat/RobotFluidResourceHandler263.java',
            ROOT/'source-families/26.X/src/main/java/buildcraft/transport/internal/pipe/FluidAction.java')
        self.assertEqual('55 assertions PASS',result)
        print(result,flush=True)

    def test_docking_station_fluid_pipe_and_ghostloading(self):
        station=self.text('26.3','DockingStationPipe.java')
        self.assertIn('new NativeFluidStorage263(nativeHandler)',station)
        self.assertIn('getCapability(Capabilities.Fluid.BLOCK, neighbourPos, inputSide.getOpposite())',station)
        self.assertIn('if (!stationLevel.isLoaded(neighbourPos)) return null;',station)
        self.assertIn('fluids.insertFluidsForce(resource.copy(), normalizeOutputSide(side()),',station)
        self.assertIn('simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE',station)
        self.assertIn('getItemOutput()',station)
        self.assertNotIn('StorageAdapters.fromNativeFluids',station)
        self.assertNotIn('IFluidHandler injectableFluidPipe',station)

    def test_robot_pump_restores_fluid_transactionally(self):
        pump=self.text('26.3','ai/AIRobotPumpBlock.java')
        self.assertIn('RobotAutomationSupport.permitsBlock(',pump)
        self.assertIn('RobotFluidResourceHandler263.forRollback(fluid)',pump)
        self.assertIn('FluidUtil.tryPlaceFluid(',pump)
        self.assertIn('placed.getAmount() == fluid.getAmount()',pump)
        self.assertNotIn('new FluidTank(',pump)
        self.assertIn('robot.fill(simulated, FluidAction.SIMULATE)',pump)
        self.assertIn('robot.drain(undo, FluidAction.EXECUTE)',pump)

    def test_fluid_load_unload_and_api2_port(self):
        for name in ('AIRobotLoadFluids','AIRobotUnloadFluids'):
            flow=self.text('26.3','ai/'+name+'.java')
            self.assertIn('FluidStorage<FluidStack>',flow)
            self.assertIn('FluidAction.EXECUTE',flow)
            self.assertNotIn('net.neoforged.neoforge.fluids.capability.IFluidHandler',flow)
        self.assertIn('FluidTransferResult.ofInsertion',self.text('26.3','internal/api2/RobotServiceImpl.java'))
        self.assertIn('getFirstStackContained(',self.text('26.3','statements/ActionRobotFilter.java'))

    def test_requester_callbacks_and_zone_import_callback(self):
        requester=self.text('26.3','tile/TileRequester.java')
        planner=self.text('26.3','tile/TileZonePlanner.java')
        self.assertIn('requests.setCallback(callback)',requester)
        self.assertIn('inv.setCallback(callback)',requester)
        self.assertIn('itemManager.callback.onStackChange(handler, slot, before, after)',requester)
        self.assertIn('level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock())',requester)
        self.assertIn('inv.setCallback((handler, slot, before, after) ->',planner)
        self.assertIn('slot == SLOT_IMPORT_MAP',planner)
        self.assertIn('importMap(after)',planner)
        self.assertIn('itemManager.callback.onStackChange(handler, slot, before, after)',planner)
        self.assertNotIn('IItemHandlerModifiable',requester)
        self.assertNotIn('IItemHandlerModifiable',planner)

    def test_energy_charging_v2_and_action_type(self):
        robot=self.text('26.3','entity/EntityRobot.java')
        plug=self.text('26.3','plug/RobotStationPluggable.java')
        self.assertIn('battery.insert(MjAmount.ofMicro(added), OperationMode.EXECUTE)',robot)
        self.assertIn('robot.getBattery().insert(MjAmount.ofMicro(accepted)',plug)
        self.assertIn('import buildcraft.transport.internal.pipe.FluidAction;',plug)
        self.assertNotIn('battery.addPower(added, FluidAction.EXECUTE)',robot)

    def test_zone_planner_ai_render_and_registry_are_preserved(self):
        for filename in ('zone/ZonePlan.java','zone/ZonePlannerMapDataClient.java',
                         'zone/MessageZoneMapRequest.java','ai/AIRobotSearchBlock.java',
                         'client/render/RenderRobot.java','client/render/RenderZonePlanner.java',
                         'SimpleRobotRegistryProvider.java', 'RoboticsNbtUtil.java'):
            with self.subTest(file=filename):
                self.assertEqual(self.text('26.2',filename),self.text('26.3',filename))

    def test_no_removed_neoforge_import_in_effective_robotics(self):
        names=('net.neoforged.neoforge.fluids.capability.IFluidHandler',
               'net.neoforged.neoforge.fluids.capability.templates.FluidTank',
               'net.neoforged.neoforge.fluids.FluidUtil',
               'net.neoforged.neoforge.items.IItemHandlerModifiable')
        for file in self.roots['26.3'].rglob('*.java'):
            text=file.read_text(encoding='utf-8')
            for name in names:
                with self.subTest(file=file.name,api=name):self.assertNotIn('import '+name,text)

    def test_java_syntax(self):
        parsed=parse_sources([self.roots[v] for v in ('26.2','26.3')])
        print(parsed,flush=True)
        self.assertIn('151 units, 0 errors',parsed)
        self.assertIn('152 units, 0 errors',parsed)

if __name__=='__main__':unittest.main()
