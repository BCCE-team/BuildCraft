"""Compile real panel scheduler AND real Builder source scheduler together."""
from __future__ import annotations
import subprocess, tempfile, unittest
from pathlib import Path
from scripts.tests.test_robot_processor_scheduler_runtime import STUBS, SOURCES, ROOT
from scripts.tests.test_robot_processor_stage21_builder_sources import STUBS as BUILDER_STUBS
from scripts.source_preprocessor import preprocess_text

FIXTURE = '''package fixture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.builders.tile.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.tile.TileRobotProcessor;
import java.util.*;
public class BuilderPanelFixture {
 static void ok(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[]args) {
  ServerLevel level=new ServerLevel();
  EntityRobotBase a=new EntityRobotBase(level,13,new BlockPos(1,65,1));
  EntityRobotBase b=new EntityRobotBase(level,14,new BlockPos(2,65,1));
  a.setBuilder();
  RobotManager.registryProvider=world -> new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==13?a:id==14?b:null;}
   public boolean isTaken(ResourceIdBlock r){return false;}
   public long robotIdTaking(ResourceIdBlock r){return 0;}
  };
  TileBuilder build=new TileBuilder(level,new BlockPos(4,65,1));
  TileBuilder.all.add(build);level.blocks.put(build.getBlockPos(),build);
  TileRobotProcessor panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.add(13L);
  RobotProcessorWorkScheduler scheduler=new RobotProcessorWorkScheduler(panel);
  level.time++;scheduler.tick(level);
  var offered=RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64);
  ok(offered!=null && offered.size()==1 && offered.get(0)==build,"Managed Builder did not see central source");
  ok(RobotProcessorWorkScheduler.hasAvailableBuilderSource(a),"Builder sleep did not observe ready work");
  ok(RobotProcessorWorkScheduler.builderSourcesFor(b,3*64*64)==null,"Unlinked robot lost native mode");
  RobotProcessorWorkScheduler.builderSourceAttempted(a,build,false);
  ok(RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64).isEmpty(),"Empty-job backoff was ignored");
  scheduler.onLinkChanged();
  ok(RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64)==null,"Old panel policy still offered source");
  level.time++;scheduler.tick(level);
  ok(RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64).size()==1,"Relink was not refreshed");
  ok(RobotProcessorWorkScheduler.builderQueueLimit(a)==5,"Managed queue limit not inherited");
  ok(RobotProcessorWorkScheduler.builderQueueLimit(b)==0,"Unlinked robot unexpectedly managed");
  var job=new buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask(
      new BlockPos(5,65,1),List.of(),200,false);
  RobotProcessorWorkScheduler.builderBatchClaimed(a,build,List.of(job));
  RobotProcessorWorkScheduler.builderTaskFinished(a,build.getBlockPos(),job.pos(),true);
  RobotProcessorWorkScheduler.builderBatchClaimed(a,build,List.of(job));
  RobotProcessorWorkScheduler.builderBatchAborted(a,build.getBlockPos());
  RobotProcessorWorkScheduler.builderMaterialStalled(a,build.getBlockPos());
  ok(RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64).isEmpty(),
      "Material starvation must suppress repeat native reservations");
  scheduler.onRemoved();
  ok(RobotProcessorWorkScheduler.builderSourcesFor(a,3*64*64)==null,"Removed panel leaked ownership");
  System.out.println("PASS: managed Builder, isolated ownership, source backoff, lifecycle");
 }
}'''

class BuilderPanelIntegrationTests(unittest.TestCase):
 def test_panel_and_native_builder_index_with_both_stonecutter_families(self):
  for version, family, loader in (('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),('26.3','26.X','fabric')):
   with self.subTest(version=version, loader=loader), tempfile.TemporaryDirectory(prefix='bc-builder-panel-') as td:
    base=Path(td)
    stubs=dict(STUBS)
    # The actual Builder source scheduler replaces only the stand-in class.
    del stubs['buildcraft/robotics/scheduler/RobotBuilderSourceScheduler.java']
    stubs['buildcraft/builders/tile/IRobotBuilderTarget.java']=BUILDER_STUBS['buildcraft/builders/tile/IRobotBuilderTarget.java']
    stubs['buildcraft/builders/tile/TileBuilder.java']=BUILDER_STUBS['buildcraft/builders/tile/TileBuilder.java']
    stubs['buildcraft/builders/tile/TileConstructionMarker.java']=BUILDER_STUBS['buildcraft/builders/tile/TileConstructionMarker.java']
    world='net/minecraft/world/level/Level.java'
    stubs[world]=stubs[world].replace(' public long time; public boolean sectionAir;',
      ' public java.util.Map<BlockPos,Object> blocks = new java.util.HashMap<>();\n public Object getBlockEntity(BlockPos pos){if(!hasChunkAt(pos))throw new AssertionError("chunk load");return blocks.get(pos);}\n public long time; public boolean sectionAir;')
    bp='net/minecraft/core/BlockPos.java'
    stubs[bp]=stubs[bp].replace(' public BlockPos immutable(){return this;}',
       ' public BlockPos immutable(){return this;} public double distSqr(BlockPos other){long dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}')
    entity='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
    stubs[entity]=stubs[entity].replace('private final BoardRobotGenericSearchBlock board=new BoardRobotGenericSearchBlock();',
      'private buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot board=new BoardRobotGenericSearchBlock();\n public void setBuilder(){board=new buildcraft.robotics.boards.BoardRobotBuilder();}')
    # An empty Builder constructor is enough to exercise the production panel scheduling.
    classes=[]
    for rel, text in stubs.items():
     p=base/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);classes.append(str(p))
    for rel in [*SOURCES,'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderSourceScheduler.java']:
     p=base/rel.removeprefix('source-shared/src/main/java/')
     p.parent.mkdir(parents=True,exist_ok=True)
     p.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,family=family,platform=loader,source=rel))
     classes.append(str(p))
    f=base/'fixture/BuilderPanelFixture.java';f.parent.mkdir(parents=True,exist_ok=True);f.write_text(FIXTURE);classes.append(str(f))
    output=subprocess.run(['javac','--release','17','-d',str(base/'classes'),*classes],
                           capture_output=True,text=True,timeout=50)
    self.assertEqual(output.returncode,0,output.stdout+output.stderr)
    output=subprocess.run(['java','-cp',str(base/'classes'),'fixture.BuilderPanelFixture'],
                          capture_output=True,text=True,timeout=15)
    self.assertEqual(output.returncode,0,output.stdout+output.stderr)

if __name__ == '__main__':unittest.main()
