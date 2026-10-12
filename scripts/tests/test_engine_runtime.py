#!/usr/bin/env python3
"""Compile the production engine host and an addon consuming only the public engine contracts."""
from __future__ import annotations
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_machine_runtime import SOURCES, STUBS

ENGINE_SOURCES = [
    'buildcraft/api/v2/energy/MjPortDescriptor.java',
    'buildcraft/api/v2/energy/MjConnectionContext.java',
    'buildcraft/api/v2/energy/MjPortRole.java',
    *('buildcraft/api/v2/machine/' + name + '.java' for name in (
        'HeatPort', 'HeatState', 'HeatProfile', 'EngineType', 'EngineProfile', 'EngineStage',
        'EngineView', 'EngineFactory', 'EngineComponent', 'EngineHeatSettings', 'EngineVisualState',
        'EngineHostOwner', 'EngineExecutionContext', 'EngineHost', 'EngineHostProvider', 'EngineService')),
    'buildcraft/lib/internal/api/v2/EngineRuntimeHost.java',
    'buildcraft/lib/internal/api/v2/EngineServiceImpl.java',
]

STUBS = dict(STUBS)
STUBS.pop('buildcraft/api/v2/machine/HeatPort.java')
STUBS['net/minecraft/resources/ResourceLocation.java'] = '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
 public static ResourceLocation tryParse(String value){
  if(value==null || !value.contains(":"))return null;
  String[] p=value.split(":",2);return new ResourceLocation(p[0],p[1]);
 }
 public String getNamespace(){return namespace;}
 public String getPath(){return path;}
 public String toString(){return namespace+":"+path;}
}'''
STUBS['net/minecraft/core/Direction.java'] = '''package net.minecraft.core;
public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST;
 public Direction getOpposite(){return switch(this){case DOWN->UP;case UP->DOWN;case NORTH->SOUTH;case SOUTH->NORTH;case WEST->EAST;case EAST->WEST;};}
}'''
STUBS['net/minecraft/core/BlockPos.java'] = '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public BlockPos relative(Direction d){return switch(d){case DOWN->new BlockPos(x,y-1,z);case UP->new BlockPos(x,y+1,z);case NORTH->new BlockPos(x,y,z-1);case SOUTH->new BlockPos(x,y,z+1);case WEST->new BlockPos(x-1,y,z);case EAST->new BlockPos(x+1,y,z);};}
}'''
STUBS['buildcraft/api/v2/registry/RegistryKey.java'] = 'package buildcraft.api.v2.registry; public record RegistryKey<T>(String id) {}'
STUBS['buildcraft/api/v2/BuildCraftRegistries.java'] = '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.*;import buildcraft.api.v2.machine.*;
public class BuildCraftRegistries {
 public static final RegistryKey<MachineType> MACHINE_TYPES=new RegistryKey<>("machines");
 public static final RegistryKey<MachineComponentType<?>> MACHINE_COMPONENT_TYPES=new RegistryKey<>("components");
 public static final RegistryKey<EngineType> ENGINE_TYPES=new RegistryKey<>("engines");
}'''
STUBS['buildcraft/api/v2/BuildCraftServices.java'] = '''package buildcraft.api.v2;
public class BuildCraftServices {
 public static final Object MACHINE_RECIPES=new Object(), ENERGY_FLUIDS=new Object(), ENERGY=new Object();
}'''
STUBS['buildcraft/api/v2/BuildCraftApi.java'] = '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.*;import java.util.*;
public class BuildCraftApi {
 private static final Map<RegistryKey<?>,ApiRegistry<?>> registries=new HashMap<>();
 private static final Map<Object,Object> services=new HashMap<>();
 @SuppressWarnings("unchecked") public static <T> ApiRegistry<T> registry(RegistryKey<T> id){return (ApiRegistry<T>)registries.computeIfAbsent(id, x->new ApiRegistry<>());}
 @SuppressWarnings("unchecked") public static <T> T service(Object id){return (T)services.get(id);}
 public static void bind(Object key,Object value){services.put(key,value);}
}'''
STUBS['buildcraft/api/v2/energy/EnergyService.java'] = '''package buildcraft.api.v2.energy;
import java.util.*;import net.minecraft.world.level.Level;import net.minecraft.core.*;
public interface EnergyService {
 Optional<MjPort> port(Level level,BlockPos pos,Direction side);
 Optional<MjPortDescriptor> descriptor(Level level,BlockPos pos,Direction side);
 boolean canConnect(MjConnectionContext context);
}'''

MAIN = r'''package buildcraft.lib.internal.api.v2;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.machine.*;
import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.persistence.*;
import dev.bcce.apifixture.CustomEngineFixtureAddon;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.resources.*;

public class EngineRuntimeFixture {
 static void ok(boolean b,String label){if(!b) throw new AssertionError(label);}
 static void fails(Runnable task){try{task.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Must refuse");}
 static final long MJ=MjAmount.MICRO_MJ_PER_MJ;
 static final ResourceLocation NET=ResourceLocation.tryParse("buildcraft:mj");
 static final BlockPos POS=new BlockPos(4,5,6),TARGET=POS.relative(Direction.NORTH);
 static final class Storage implements MjPort {
  long energy,capacity; boolean writable=true; boolean broken; boolean failExecute;
  Storage(long capacity){this.capacity=capacity;}
  public MjTransferResult insert(MjAmount amount,OperationMode mode){
   long count=Math.min(amount.microMj(),Math.max(0,capacity-energy));
   if(broken)count=0;
   if(mode==OperationMode.EXECUTE&&failExecute)throw new IllegalStateException("Remote energy port failed");
   if(mode==OperationMode.EXECUTE)energy+=count;
   return MjTransferResult.of(amount,MjAmount.ofMicro(count));
  }
  public MjTransferResult extract(MjAmount amount,OperationMode mode){
   long count=Math.min(amount.microMj(),energy);
   if(mode==OperationMode.EXECUTE)energy-=count;
   return MjTransferResult.of(amount,MjAmount.ofMicro(count));
  }
  public MjAmount stored(){return MjAmount.ofMicro(energy);}
  public MjAmount capacity(){return MjAmount.ofMicro(capacity);}
 }
 static final class Lookup implements EnergyService {
   Storage receiver=new Storage(100*MJ); boolean pulsed=true; boolean connected=true;
   public Optional<MjPort> port(Level l,BlockPos p,Direction d){return p.equals(TARGET)?Optional.of(receiver):Optional.empty();}
   public Optional<MjPortDescriptor> descriptor(Level l,BlockPos p,Direction d){
    if(!p.equals(POS)&&!p.equals(TARGET))return Optional.empty();
    EnumSet<MjPortRole> roles=p.equals(TARGET)?EnumSet.of(MjPortRole.CONSUMER):EnumSet.of(MjPortRole.PROVIDER);
    if(p.equals(TARGET)&&pulsed)roles.add(MjPortRole.REDSTONE_RECEIVER);
    return Optional.of(new MjPortDescriptor(NET,roles,MjAmount.ofMj(100),MjAmount.ofMj(100)));
   }
   public boolean canConnect(MjConnectionContext context){return connected;}
 }
 static final EngineServiceImpl ENGINES=new EngineServiceImpl();
 static final MachineServiceImpl MACHINES=new MachineServiceImpl();
 static final class Owner extends BlockEntity implements EngineHostOwner,EngineHostProvider {
   Level world; Storage buffer=new Storage(4*MJ);
   boolean client; int changed;
   EngineHost host;
   Owner(boolean client){this.client=client;world=new Level();world.blocks.put(POS,this);
     host=ENGINES.createHost(CustomEngineFixtureAddon.ENGINE,this,
       ctx->new CustomEngineFixtureAddon.SteamEngine(),CustomEngineFixtureAddon.STATE,CustomEngineFixtureAddon.HEAT);
   }
   public BlockPos position(){return POS;}
   public Level level(){return world;}
   public boolean clientSide(){return client;}
   public void machineChanged(){changed++;}
   public MjPort outputBuffer(){return buffer;}
   public EngineHost engineHost(){return host;}
 }
 public static void main(String[] args) throws Exception {
   CustomEngineFixtureAddon.register();
   Lookup lookup=new Lookup();BuildCraftApi.bind(BuildCraftServices.ENERGY,lookup);
   Owner owner=new Owner(false);
   ok(owner.host.typeId().equals(CustomEngineFixtureAddon.ID),"registered type lost");
   ok(owner.host.mjPort(Direction.NORTH).orElseThrow().canExtract(),"Engine not publicly visible as MJ provider");
   ok(!owner.host.mjPort(Direction.NORTH).orElseThrow().canInsert(),"Engine accepted MJ despite profile policy");
   fails(()->owner.host.heatPort().orElseThrow().setTemperature(Double.NaN,OperationMode.SIMULATE));
   ok(ENGINES.engine(owner.world,POS).orElseThrow()==owner.host,"engine lookup");
   ok(MACHINES.machine(owner.world,POS).orElseThrow()==owner.host,"gates/host discovery");
   fails(()->owner.host.generate(MjAmount.ofMj(1),OperationMode.EXECUTE));
   owner.host.tick();
   ok(lookup.receiver.energy==MJ,"Piston pulse at midpoint missing");
   ok(owner.host.outputPerTick().microMj()==MJ,"Production exceeded profile rate");
   ok(owner.host.stage().id().equals(ResourceLocation.tryParse("test:warm")),"Warm stage");
   ok(owner.host.heatPort().orElseThrow().heatState().temperature()==34,"Heat management");
   owner.host.tick();
   ok(lookup.receiver.energy==MJ,"Repeated midpoint emitted twice");
   owner.host.tick();
   ok(lookup.receiver.energy==MJ,"Return stroke created a pulse");
   owner.host.tick();
   ok(lookup.receiver.energy==2*MJ,"Second valid pulse absent");
   ok(owner.host.outputBuffer().stored().microMj()==2*MJ,"Generated MJ balance");
   ok(owner.host.visualState().animationPhase()==0.6,"Animation signal absent");
   ok(owner.host.visualState().soundId().equals(CustomEngineFixtureAddon.SOUND),"Sound signal absent");
   ok(owner.host.component(CustomEngineFixtureAddon.ID,CustomEngineFixtureAddon.SteamEngine.class).orElseThrow().fuel==14,"Fuel not consumed");
   byte[] saved=owner.host.saveState();
   Owner restored=new Owner(false);restored.buffer.energy=owner.buffer.energy;
   restored.host.restoreState(saved);restored.host.onLoad();
   ok(restored.host.component(CustomEngineFixtureAddon.ID,CustomEngineFixtureAddon.SteamEngine.class).orElseThrow().ticks==4,"Reload lost logic component");
   ok(restored.host.visualState().heat().temperature()==61,"Reload lost heat");
   ok(restored.host.visualState().animationPhase()==0.6,"Reload lost animation");
   restored.host.tick();
   ok(lookup.receiver.energy==2*MJ,"Reload duplicated piston pulse");
   Owner client=new Owner(true);client.host.applySync(restored.host.fullSync());
   ok(client.host.stage().equals(restored.host.stage()),"Client stage not synchronized");
   ok(client.host.visualState().heat().equals(restored.host.visualState().heat()),"Client heat not synchronized");
   ok(client.host.visualState().soundId().equals(CustomEngineFixtureAddon.SOUND),"Client sound signal not synchronized");
   restored.host.tick();byte[] delta=restored.host.changedSync();
   ok(delta.length!=0,"No changed engine presentation");
   client.host.applySync(delta);
   ok(client.host.visualState().animationPhase()==restored.host.visualState().animationPhase(),"Client delta rejected");
   client.host.applySync(delta);
   ok(client.host.visualState().animationPhase()==restored.host.visualState().animationPhase(),"Replayed sync changed state");
   restored.host.setMode(MachineControlMode.OFF,OperationMode.EXECUTE);
   int before=restored.host.component(CustomEngineFixtureAddon.ID,CustomEngineFixtureAddon.SteamEngine.class).orElseThrow().ticks;
   restored.host.tick();
   ok(restored.host.component(CustomEngineFixtureAddon.ID,CustomEngineFixtureAddon.SteamEngine.class).orElseThrow().ticks==before,"OFF executed engine");
   restored.host.setMode(MachineControlMode.ON,OperationMode.EXECUTE);
   restored.host.tick();
   ok(restored.host.component(CustomEngineFixtureAddon.ID,CustomEngineFixtureAddon.SteamEngine.class).orElseThrow().ticks==before+1,"Engine failed to resume");
   lookup.connected=false;restored.host.tick();ok(lookup.receiver.energy>=2*MJ,"Connection refusal caused drain");lookup.connected=true;
   Owner unloaded=new Owner(false);unloaded.world.loaded=false;unloaded.host.tick();
   ok(unloaded.host.machineComponents().isEmpty(),"Unloaded engine ran");
   Owner corrupt=new Owner(false);byte[] garbage={1,4,9};corrupt.host.restoreState(garbage);corrupt.host.tick();
   ok(Arrays.equals(garbage,corrupt.host.saveState()),"Corrupt engine NBT discarded");
   Owner throwing=new Owner(false);
   long priorReceipt=lookup.receiver.energy;
   lookup.receiver.failExecute=true;
   throwing.host.tick();
   ok(lookup.receiver.energy==priorReceipt,"Receiver exception changed target MJ");
   ok(throwing.buffer.energy==MJ,"MJ lost on receiver exception");
   lookup.receiver.failExecute=false;
   ok(throwing.host.workStatus().state()==WorkState.ERROR,"Engine callback error not isolated");
   Owner overheated=new Owner(false);for(int i=0;i<10;i++)overheated.host.tick();
   ok(overheated.host.visualState().heat().critical(),"Overheating not tracked");
   ok(overheated.host.outputPerTick().isZero(),"Overheated engine generated MJ");
   ok(overheated.host.workStatus().state()==WorkState.BLOCKED,"Overheat status missing");
   fails(()->new EngineRuntimeHost(new EngineType(ResourceLocation.tryParse("test:missing"),CustomEngineFixtureAddon.ENGINE.profile()),
       new Owner(false),ctx->new CustomEngineFixtureAddon.SteamEngine(),CustomEngineFixtureAddon.STATE,CustomEngineFixtureAddon.HEAT));
   owner.host.removed();owner.host.invalidate();
   fails(owner.host::tick);
   System.out.println("Engine power, piston pulses, temperature, state, machine lifecycle, client view, lookup and crash safety OK");
 }
}'''

class EngineRuntimeTests(unittest.TestCase):
    def test_compiled_host_and_independent_addon_engine(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java tools unavailable')
        with tempfile.TemporaryDirectory(prefix='bcce-engine8-') as d:
            base=Path(d)
            for name,code in STUBS.items():
                p=base/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code)
            main=base/'buildcraft/lib/internal/api/v2/EngineRuntimeFixture.java'
            main.parent.mkdir(parents=True,exist_ok=True);main.write_text(MAIN)
            addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/CustomEngineFixtureAddon.java'
            self.assertNotIn('buildcraft.lib.',addon.read_text())
            sources=[str(base/p) for p in STUBS]
            sources += [str(ROOT/'source-shared/src/main/java'/name) for name in SOURCES+ENGINE_SOURCES]
            sources += [str(addon),str(main)]
            result=subprocess.run(['javac','-d',str(base/'classes'),*sources],capture_output=True,text=True,timeout=95)
            self.assertEqual(result.returncode,0,result.stderr)
            outcome=subprocess.run(['java','-cp',str(base/'classes'),'buildcraft.lib.internal.api.v2.EngineRuntimeFixture'],capture_output=True,text=True,timeout=25)
            self.assertEqual(outcome.returncode,0,outcome.stderr+'\n'+outcome.stdout)

    def test_shared_source_and_builtin_engine_order_preserved(self):
        from pathlib import Path
        for native in ('source-platforms/forge','source-platforms/neoforge','source-family-platforms/26.X/neoforge'):
            base=ROOT/native/'src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java'
            self.assertIn('getPistonSpeed()',base.read_text())
            self.assertIn('isPulsedPowerReceiver',base.read_text())
        for runtime in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
            file=ROOT/runtime/'src/main/java/buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java'
            self.assertIn('BuildCraftServices.ENGINES',file.read_text())

if __name__=='__main__': unittest.main()
