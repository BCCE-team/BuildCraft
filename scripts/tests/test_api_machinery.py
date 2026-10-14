#!/usr/bin/env python3
"""Laser receivers, work allocation and thermal engine bridges."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import shutil
import subprocess
import tempfile
import unittest

LASER_STUBS = {
 'net/minecraft/core/Direction.java': 'package net.minecraft.core;public enum Direction{NORTH,SOUTH,UP,DOWN,EAST,WEST}',
 'net/minecraft/core/BlockPos.java': 'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/resources/ResourceLocation.java': 'package net.minecraft.resources;public record ResourceLocation(String id){public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}}',
 'net/minecraft/world/level/block/entity/BlockEntity.java': 'package net.minecraft.world.level.block.entity;public class BlockEntity{}',
 'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
 import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.entity.BlockEntity;
 public class Level{public boolean loaded=true;public final Map<BlockPos,BlockEntity> blocks=new HashMap<>();
 public boolean hasChunkAt(BlockPos p){return loaded;}public BlockEntity getBlockEntity(BlockPos p){return blocks.get(p);}}''',
}
LASER_MAIN = r'''package buildcraft.lib.internal.api.v2;
import java.util.*;import buildcraft.api.v2.*;import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.machine.*;import dev.bcce.apifixture.*;
import net.minecraft.core.*;import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
public class LaserRuntimeProbe {
 static void ok(boolean value,String msg){if(!value)throw new AssertionError(msg);}
 static final BlockPos POS=new BlockPos(2,4,6);
 static class AddonBE extends BlockEntity implements LaserTargetProvider {
  final CustomLaserTargetFixtureAddon target=new CustomLaserTargetFixtureAddon(3_000_000);
  public Optional<LaserTarget> laserTarget(Direction face){return target.laserTarget(face);}
 }
 static class DirectBE extends BlockEntity implements LaserTarget {
  final MjPort port=new MjPort(){
   public MjTransferResult insert(MjAmount a,OperationMode m){return MjTransferResult.of(a,a);}
   public MjTransferResult extract(MjAmount a,OperationMode m){return MjTransferResult.none(a);}
   public MjAmount stored(){return MjAmount.ZERO;}
   public MjAmount capacity(){return MjAmount.ofMj(100);}
  };
  public MjPort laserPort(){return port;}
 }
 static class RejectBE extends DirectBE implements LaserTargetProvider {
  public Optional<LaserTarget> laserTarget(Direction d){return Optional.empty();}
 }
 static class ThrowBE extends BlockEntity implements LaserTargetProvider {
  public Optional<LaserTarget> laserTarget(Direction d){throw new IllegalStateException("addon error");}
 }
 static class NullBE extends BlockEntity implements LaserTargetProvider {
  public Optional<LaserTarget> laserTarget(Direction d){return null;}
 }
 public static void main(String[] args){
  LaserTargetService service=new LaserTargetServiceImpl();Level world=new Level();AddonBE addon=new AddonBE();
  world.blocks.put(POS,addon);
  ok(service.target(world,POS,Direction.SOUTH).isEmpty(),"Wrong laser face accepted");
  var target=service.target(world,POS,Direction.NORTH).orElseThrow();
  ok(target.typeId().orElseThrow().equals(CustomLaserTargetFixtureAddon.TABLE),"Addon type id lost");
  ok(addon.target.stored()==0,"Query mutated addon laser storage");
  long probe=target.laserPort().insert(MjAmount.ofMj(1),OperationMode.SIMULATE).transferred().microMj();
  ok(probe==1_000_000 && addon.target.stored()==0,"Laser simulation mutated storage");
  target.laserPort().insert(MjAmount.ofMj(2),OperationMode.EXECUTE);
  ok(addon.target.stored()==2_000_000,"Addon laser did not receive MJ");
  ok(target.laserPort().insert(MjAmount.ofMj(2),OperationMode.EXECUTE).transferred().microMj()==1_000_000,"Laser ignored demand");
  world.blocks.put(POS,new DirectBE());ok(service.target(world,POS,Direction.WEST).isPresent(),"Legacy direct receiver unavailable");
  world.blocks.put(POS,new RejectBE());ok(service.target(world,POS,Direction.NORTH).isEmpty(),"Provider side denial bypassed");
  world.blocks.put(POS,new ThrowBE());ok(service.target(world,POS,Direction.NORTH).isEmpty(),"Provider exception escaped");
  world.blocks.put(POS,new NullBE());ok(service.target(world,POS,Direction.NORTH).isEmpty(),"Null result escaped");
  world.loaded=false;world.blocks.put(POS,new AddonBE());
  ok(service.target(world,POS,Direction.NORTH).isEmpty(),"Unloaded chunk queried");
  ok(service.target(null,POS,Direction.NORTH).isEmpty(),"Null level not rejected");
  System.out.println("Laser target discovery, sided providers, simulations and safety OK");
 }
}'''

class LaserReceiverConformanceTest(unittest.TestCase):
 def test_laser_runtime_for_all_source_families(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  base=ROOT/'source-shared/src/main/java'
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/CustomLaserTargetFixtureAddon.java'
  self.assertNotIn('buildcraft.lib.',addon.read_text())
  for family in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
   with self.subTest(family=family),tempfile.TemporaryDirectory(prefix='laser-receiver-') as d:
    temp=Path(d)
    for name,source in LASER_STUBS.items():
     file=temp/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(source)
    main=temp/'buildcraft/lib/internal/api/v2/LaserRuntimeProbe.java'
    main.parent.mkdir(parents=True,exist_ok=True);main.write_text(LASER_MAIN)
    sources=[str(temp/name) for name in LASER_STUBS]+[
     str(base/'buildcraft/api/v2/OperationMode.java'),
     str(base/'buildcraft/api/v2/energy/MjAmount.java'),
     str(base/'buildcraft/api/v2/energy/MjTransferResult.java'),
     str(base/'buildcraft/api/v2/energy/MjTransferPolicy.java'),
     str(base/'buildcraft/api/v2/energy/MjPort.java'),
     str(base/'buildcraft/api/v2/machine/LaserTarget.java'),
     str(base/'buildcraft/api/v2/machine/LaserTargetProvider.java'),
     str(base/'buildcraft/api/v2/machine/LaserTargetService.java'),
     str(ROOT/family/'src/main/java/buildcraft/lib/internal/api/v2/LaserTargetServiceImpl.java'),
     str(addon),str(main)]
    compiled=subprocess.run(['javac','-d',str(temp/'classes'),*sources],text=True,capture_output=True,timeout=40)
    self.assertEqual(compiled.returncode,0,compiled.stderr)
    executed=subprocess.run(['java','-cp',str(temp/'classes'),'buildcraft.lib.internal.api.v2.LaserRuntimeProbe'],text=True,capture_output=True,timeout=20)
    self.assertEqual(executed.returncode,0,executed.stderr+'\n'+executed.stdout)


MACHINE_STUBS = {
 'javax/annotation/Nullable.java': 'package javax.annotation;public @interface Nullable{}',
 'net/minecraft/core/Direction.java': 'package net.minecraft.core;public enum Direction{NORTH}',
 'net/minecraft/core/BlockPos.java': 'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/world/level/Level.java': 'package net.minecraft.world.level;public class Level{}',
 'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
 public class BlockEntity{public net.minecraft.world.level.Level getLevel(){return new net.minecraft.world.level.Level();}
 public net.minecraft.core.BlockPos getBlockPos(){return new net.minecraft.core.BlockPos(0,0,0);}}''',
 'buildcraft/lib/internal/tiles/IHasWork.java': 'package buildcraft.lib.internal.tiles;public interface IHasWork{boolean hasWork();}',
 'buildcraft/api/v2/machine/MachineHostProvider.java': '''package buildcraft.api.v2.machine;
 public interface MachineHostProvider{MachineHost machineHost();}''',
 'buildcraft/api/v2/machine/MachineHost.java': 'package buildcraft.api.v2.machine;public interface MachineHost{boolean hasWork();}',
 'net/minecraftforge/common/capabilities/Capability.java': 'package net.minecraftforge.common.capabilities;public class Capability<T>{}',
 'net/minecraftforge/common/util/LazyOptional.java': '''package net.minecraftforge.common.util;
 public class LazyOptional<T>{private final T value;public LazyOptional(T v){value=v;}public T orElse(T fallback){return value==null?fallback:value;}}''',
 'net/neoforged/neoforge/capabilities/BlockCapability.java': 'package net.neoforged.neoforge.capabilities;public class BlockCapability<T,C>{}',
 'buildcraft/lib/internal/tiles/TilesAPI.java': '''package buildcraft.lib.internal.tiles;
 import net.minecraft.core.Direction;
 public final class TilesAPI{
 public static final net.minecraftforge.common.capabilities.Capability<IHasWork> FORGE_WORK = new net.minecraftforge.common.capabilities.Capability<>();
 public static final net.neoforged.neoforge.capabilities.BlockCapability<IHasWork,Direction> NEO_WORK=new net.neoforged.neoforge.capabilities.BlockCapability<>();
 public static final Object CAP_HAS_WORK=FORGE_WORK;
 }''',
}

MACHINE_MAIN='''package check;
import buildcraft.lib.platform.capability.PlatformCapabilities;
import buildcraft.lib.internal.tiles.IHasWork;
import buildcraft.api.v2.machine.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
public class NativeWorkCheck {
 static class Worker extends BlockEntity implements MachineHostProvider {
  public boolean busy=true; public MachineHost machineHost(){return ()->busy;}
 }
 static class Native extends BlockEntity implements IHasWork {
  public boolean hasWork(){return true;}
 }
 public static void main(String[] args){
  Worker addon=new Worker();IHasWork bridge=PlatformCapabilities.hasWork(addon,Direction.NORTH);
  if(bridge==null || !bridge.hasWork())throw new AssertionError("Missing addon work capability");
  addon.busy=false;
  if(bridge.hasWork())throw new AssertionError("Work capability used stale snapshot");
  if(PlatformCapabilities.hasWork(null,Direction.NORTH)!=null)throw new AssertionError("Null tile returned work");
  System.out.println("Native capability work fallback OK");
 }
}'''

class NativeMachineConformanceTest(unittest.TestCase):
 def test_native_work_fallback_compile_and_execute(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  targets=(
    ('source-platforms/forge','forge'),
    ('source-platforms/neoforge','neo'),
    ('source-families/26.X','neo'),
    ('source-family-platforms/26.X/neoforge','neo'),
  )
  for family,platform in targets:
   with self.subTest(family=family),tempfile.TemporaryDirectory(prefix='machine-bridge-') as d:
    root=Path(d)
    for name,source in MACHINE_STUBS.items():
     if name.endswith('TilesAPI.java'):
      source=source.replace('public static final Object CAP_HAS_WORK=FORGE_WORK;',
                            'public static final '+('net.minecraftforge.common.capabilities.Capability<IHasWork>' if platform=='forge' else 'net.neoforged.neoforge.capabilities.BlockCapability<IHasWork,Direction>')+' CAP_HAS_WORK='+('FORGE_WORK;' if platform=='forge' else 'NEO_WORK;'))
     path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source)
    cap='''package buildcraft.lib.misc;
 import buildcraft.lib.internal.tiles.IHasWork;
 import net.minecraft.core.*;import net.minecraft.world.level.*;
 import net.minecraft.world.level.block.entity.*;
 public class CapUtil {
  public static net.minecraftforge.common.util.LazyOptional<IHasWork> getCapability(BlockEntity tile,
    net.minecraftforge.common.capabilities.Capability<IHasWork> cap,Direction side){return new net.minecraftforge.common.util.LazyOptional<>(null);}
  public static IHasWork getCapability(Level level,BlockPos pos,
    net.neoforged.neoforge.capabilities.BlockCapability<IHasWork,Direction> cap,Direction side){return null;}
 }'''
    file=root/'buildcraft/lib/misc/CapUtil.java';file.parent.mkdir(parents=True,exist_ok=True);file.write_text(cap)
    check=root/'check/NativeWorkCheck.java';check.parent.mkdir(parents=True,exist_ok=True);check.write_text(MACHINE_MAIN)
    native=ROOT/family/'src/main/java/buildcraft/lib/platform/capability/PlatformCapabilities.java'
    sources=[str(root/name) for name in MACHINE_STUBS]+[str(file),str(check),str(native)]
    result=subprocess.run(['javac','-d',str(root/'classes'),*sources],capture_output=True,text=True,timeout=40)
    self.assertEqual(result.returncode,0,result.stderr)
    run=subprocess.run(['java','-cp',str(root/'classes'),'check.NativeWorkCheck'],capture_output=True,text=True,timeout=15)
    self.assertEqual(run.returncode,0,run.stdout+'\n'+run.stderr)

 def test_native_engine_heat_read_only(self):
  targets=(
   'source-platforms/forge','source-platforms/neoforge','source-families/26.X',
   'source-family-platforms/26.X/neoforge'
  )
  for family in targets:
   text=(ROOT/family/'src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java').read_text()
   with self.subTest(family=family):
    self.assertIn('Optional<HeatPort> heatPort()',text)
    self.assertIn('canSetTemperature() { return false; }',text)
    self.assertIn('new HeatProfile(MIN_HEAT, IDEAL_HEAT, MAX_HEAT)',text)
    self.assertIn('UnsupportedOperationException("Native engine temperature',text)

if __name__ == "__main__":
    unittest.main()
