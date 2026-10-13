#!/usr/bin/env python3
"""Compile actual laser discovery services against an independent API-only addon receiver."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
 'net/minecraft/core/Direction.java': 'package net.minecraft.core;public enum Direction{NORTH,SOUTH,UP,DOWN,EAST,WEST}',
 'net/minecraft/core/BlockPos.java': 'package net.minecraft.core;public record BlockPos(int x,int y,int z){}',
 'net/minecraft/resources/ResourceLocation.java': 'package net.minecraft.resources;public record ResourceLocation(String id){public static ResourceLocation tryParse(String s){return new ResourceLocation(s);}}',
 'net/minecraft/world/level/block/entity/BlockEntity.java': 'package net.minecraft.world.level.block.entity;public class BlockEntity{}',
 'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
 import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.entity.BlockEntity;
 public class Level{public boolean loaded=true;public final Map<BlockPos,BlockEntity> blocks=new HashMap<>();
 public boolean hasChunkAt(BlockPos p){return loaded;}public BlockEntity getBlockEntity(BlockPos p){return blocks.get(p);}}''',
}
MAIN = r'''package buildcraft.lib.internal.api.v2;
import java.util.*;import buildcraft.api.v2.*;import buildcraft.api.v2.energy.*;
import buildcraft.api.v2.machine.*;import dev.bcce.apifixture.*;
import net.minecraft.core.*;import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
public class P11LaserRuntimeFixture {
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

class LaserP11Test(unittest.TestCase):
 def test_laser_runtime_for_all_source_families(self):
  if not shutil.which('javac') or not shutil.which('java'):self.skipTest('JDK unavailable')
  base=ROOT/'source-shared/src/main/java'
  addon=ROOT/'addon-fixture/src/main/java/dev/bcce/apifixture/CustomLaserTargetFixtureAddon.java'
  self.assertNotIn('buildcraft.lib.',addon.read_text())
  for family in ('source-shared','source-families/26.X','source-family-platforms/26.X/neoforge'):
   with self.subTest(family=family),tempfile.TemporaryDirectory(prefix='p11-laser-') as d:
    temp=Path(d)
    for name,source in STUBS.items():
     file=temp/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(source)
    main=temp/'buildcraft/lib/internal/api/v2/P11LaserRuntimeFixture.java'
    main.parent.mkdir(parents=True,exist_ok=True);main.write_text(MAIN)
    sources=[str(temp/name) for name in STUBS]+[
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
    executed=subprocess.run(['java','-cp',str(temp/'classes'),'buildcraft.lib.internal.api.v2.P11LaserRuntimeFixture'],text=True,capture_output=True,timeout=20)
    self.assertEqual(executed.returncode,0,executed.stderr+'\n'+executed.stdout)

if __name__=='__main__':unittest.main()
