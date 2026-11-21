"""Stage 24: real JVM implementations of Builder batch planning and ledger.

Native BlueprintBuilder still owns ordering and ResourceIdBlock claims. These
fixtures assert we preserve a strict prefix and release every unused claim.
"""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.test_robot_processor_stage21_builder_sources import ROOT, STUBS

PLANNER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderBatchPlanner.java'
ADAPTER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderBatchAdapter.java'
SCHEDULER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderSourceScheduler.java'

ADAPTER_STUBS = dict(STUBS)
ADAPTER_STUBS['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;
public class EntityRobotBase {
 public static final int SAFETY_ENERGY=20000;
 private final long id;private final BlockPos at;public int energy=90000;
 public EntityRobotBase(long id,BlockPos at){this.id=id;this.at=at;}
 public long getRobotId(){return id;}public BlockPos blockPosition(){return at;}
 public int getEnergy(){return energy;}
}'''
ADAPTER_STUBS['net/minecraft/core/BlockPos.java'] = STUBS['net/minecraft/core/BlockPos.java'].replace(' public long asLong()', ''' public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public long asLong()''')
ADAPTER_STUBS['net/minecraft/world/item/ItemStack.java'] = '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(0);
 private final int count;public ItemStack(int count){this.count=count;}
 public int getCount(){return count;}public boolean isEmpty(){return count<=0;}
 public Object getItem(){return "item";}
}'''
ADAPTER_STUBS['buildcraft/builders/snapshot/BlueprintBuilder.java'] = '''package buildcraft.builders.snapshot;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.item.ItemStack;
public class BlueprintBuilder {
 public record RobotBuildTask(BlockPos pos,List<ItemStack> requirements,int energyCost,boolean breakTask){}
}'''
ADAPTER_STUBS['buildcraft/builders/tile/IRobotBuilderTarget.java'] = '''package buildcraft.builders.tile;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
public interface IRobotBuilderTarget {
 Level getLevel();BlockPos getBlockPos();boolean canRobotsBuild();
 void releaseRobotBuildTask(EntityRobotBase robot,RobotBuildTask task);
}'''
ADAPTER_STUBS['buildcraft/builders/tile/TileBuilder.java'] = '''package buildcraft.builders.tile;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
public class TileBuilder implements IRobotBuilderTarget {
 public static final List<TileBuilder> all=new ArrayList<>();
 public final Level world;public final BlockPos at;
 public final List<RobotBuildTask> released=new ArrayList<>();public boolean available=true;
 public TileBuilder(Level world,BlockPos at){this.world=world;this.at=at;}
 public static List<TileBuilder> getLoadedBuilders(){return all;}
 public Level getLevel(){return world;}public BlockPos getBlockPos(){return at;}
 public boolean canRobotsBuild(){return available;}
 public void releaseRobotBuildTask(EntityRobotBase robot,RobotBuildTask task){released.add(task);}
}'''
ADAPTER_STUBS['buildcraft/builders/tile/TileConstructionMarker.java'] = '''package buildcraft.builders.tile;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
public class TileConstructionMarker implements IRobotBuilderTarget {
 public static final List<TileConstructionMarker> all=new ArrayList<>();
 private final Level world;private final BlockPos at;
 public TileConstructionMarker(Level world,BlockPos at){this.world=world;this.at=at;}
 public static List<TileConstructionMarker> getLoadedMarkers(){return all;}
 public Level getLevel(){return world;}public BlockPos getBlockPos(){return at;}
 public boolean canRobotsBuild(){return true;}
 public void releaseRobotBuildTask(EntityRobotBase robot,RobotBuildTask task){}
}'''

SCENARIO = '''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import buildcraft.builders.tile.*;
import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class BuilderBatchFixture {
 static void ok(boolean b,String message){if(!b)throw new AssertionError(message);}
 static RobotBuildTask task(int x,int items,int energy){return new RobotBuildTask(new BlockPos(x,64,1),
    items==0?List.of():List.of(new ItemStack(items)),energy,false);}
 static RobotBuilderSourceScheduler.Operation op(int x,int items){return new RobotBuilderSourceScheduler.Operation(new BlockPos(x,64,1),items);}
 public static void main(String[] args){
  ServerLevel level=new ServerLevel();level.load(0,0);
  var close=new TileBuilder(level,new BlockPos(2,64,1));
  var other=new TileBuilder(level,new BlockPos(6,64,1));
  TileBuilder.all.addAll(List.of(close,other));
  level.blocks.put(close.at,close);level.blocks.put(other.at,other);
  var a=new EntityRobotBase(1,new BlockPos(0,64,1));
  var b=new EntityRobotBase(2,new BlockPos(0,64,1));
  var scheduler=new RobotBuilderSourceScheduler();
  scheduler.tick(level,List.of(a,b));
  ok(scheduler.preferred(b,1024).get(0)==close,"nearest source should initially win");
  List<RobotBuildTask> tasks=new ArrayList<>();
  for(int i=0;i<12;i++)tasks.add(task(i+1,3,50));
  List<RobotBuildTask> original=new ArrayList<>(tasks);
  var selected=RobotBuilderBatchAdapter.retainNativePrefix(a,close,tasks,4);
  ok(selected.size()==4 && selected.get(0)==original.get(0) && selected.get(3)==original.get(3),"prefix/queue limit was broken");
  ok(close.released.size()==8,"every unused native claim must be released");
  for(int i=0;i<8;i++)ok(close.released.get(i)==original.get(11-i),"release suffix in reverse order");
  scheduler.claimed(a,close.at,List.of(op(1,3),op(2,3),op(3,3),op(4,3)));
  ok(scheduler.activity().operations()==4 && scheduler.activity().outstandingItems()==12,"task/material ledger wrong");
  ok(scheduler.preferred(b,1024).get(0)==other,"loaded source should win fair-score tie");
  scheduler.completed(a,close.at,new BlockPos(1,64,1));
  ok(scheduler.activity().operations()==3 && scheduler.activity().outstandingItems()==9,"completed task was not removed");
  scheduler.released(a,close.at);
  ok(scheduler.activity().robots()==0,"cancel did not discard temporary ledger");
  ok(scheduler.preferred(b,1024).get(0)==close,"cancel failed to remove load bias");
  scheduler.materialStalled(a,close.at);
  ok(scheduler.preferred(a,1024).get(0)==other,"missing material cooldown was ignored");
  ok(scheduler.preferred(b,1024).get(0)==close,"one robot's missing items blocked other builders");
  level.time=399;scheduler.tick(level,List.of(a,b));
  ok(scheduler.preferred(a,1024).get(0)==other,"materials cooldown ended early");
  level.time=401;scheduler.tick(level,List.of(a,b));
  ok(scheduler.preferred(a,1024).get(0)==close,"material cooldown did not expire");
  a.energy=20000;
  var low=RobotBuilderBatchAdapter.retainNativePrefix(a,close,original,8);
  ok(low.size()==1,"low battery must keep only the first task");
  var p=new RobotBuilderBatchPlanner.Point(0,64,1);
  var ops=List.of(new RobotBuilderBatchPlanner.Operation(p,20,80,false),
    new RobotBuilderBatchPlanner.Operation(p,20,80,false),
    new RobotBuilderBatchPlanner.Operation(p,20,80,false));
  ok(RobotBuilderBatchPlanner.choose(ops,p,8,90000,20000).operations()==1,"combined material cap bypassed");
  ok(RobotBuilderBatchPlanner.choose(List.of(),p,8,90000,20000).operations()==0,"empty input");
  var huge=List.of(new RobotBuilderBatchPlanner.Operation(
    new RobotBuilderBatchPlanner.Point(Integer.MIN_VALUE,0,Integer.MAX_VALUE),Integer.MAX_VALUE,0,true),
    new RobotBuilderBatchPlanner.Operation(new RobotBuilderBatchPlanner.Point(0,0,0),Integer.MAX_VALUE,0,true));
  ok(RobotBuilderBatchPlanner.choose(huge,p,8,20000,20000).operations()==1,"coordinate/energy overflow");
  scheduler.claimed(b,other.at,List.of(op(5,1)));
  scheduler.tick(level,List.of(a));
  level.time+=40;scheduler.tick(level,List.of(a));
  ok(scheduler.activity().robots()==0,"unlinked robot leaked ledger");
  scheduler.clear();
  ok(scheduler.preferred(a,1024)==null,"removed controller still manages builder");
  System.out.println("PASS: strict native prefix, every release, materials, energy, fair sources, cleanup");
 }
}'''

class BuilderBatchRuntimeTests(unittest.TestCase):
 def test_real_jvm_batch_adapter_and_ledger_all_source_families(self):
  # All source-family/loader variants use the shared scheduler+adapter.
  for family, loader in [('old','forge'),('1.21.X','neoforge'),('26.X','neoforge'),('26.X','fabric')]:
   with self.subTest(family=family,loader=loader), tempfile.TemporaryDirectory(prefix='bc-builder-batch-') as directory:
    root=Path(directory);files=[]
    for path, content in ADAPTER_STUBS.items():
     p=root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);files.append(str(p))
    p=root/'fixture/BuilderBatchFixture.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(SCENARIO);files.append(str(p))
    files.extend(map(str,(PLANNER,ADAPTER,SCHEDULER)))
    process=subprocess.run(['javac','--release','17','-d',str(root/'classes'),*files],capture_output=True,text=True,timeout=50)
    self.assertEqual(process.returncode,0,process.stdout+process.stderr)
    process=subprocess.run(['java','-cp',str(root/'classes'),'fixture.BuilderBatchFixture'],capture_output=True,text=True,timeout=20)
    self.assertEqual(process.returncode,0,process.stdout+process.stderr)

 def test_both_builder_boards_keep_native_dependency_order_and_release_hooks(self):
  for family in ['old','1.21.X']:
   with self.subTest(family=family):
    text=(ROOT/f'source-families/{family}/src/main/java/buildcraft/robotics/boards/BoardRobotBuilder.java').read_text()
    self.assertIn('RobotBuilderBatchAdapter.retainNativePrefix(robot, builder, tasks, queueLimit)',text)
    self.assertIn('RobotProcessorWorkScheduler.builderBatchClaimed(robot, builder, tasks)',text)
    self.assertIn('RobotProcessorWorkScheduler.builderTaskFinished(robot, builderPos, task.pos()',text)
    self.assertIn('RobotProcessorWorkScheduler.builderBatchAborted(robot, builderPos)',text)
    self.assertIn('RobotProcessorWorkScheduler.builderMaterialStalled(robot, builderPos,',text)
    self.assertLess(text.index('builder.reserveRobotBuildTasks(robot, MAX_CARRIED_ITEMS)'),
                    text.index('RobotBuilderBatchAdapter.retainNativePrefix'))

if __name__=='__main__':unittest.main()
