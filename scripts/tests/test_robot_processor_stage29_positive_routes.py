"""Stage 29: positive path hints, exact vs warm reuse, invalidation and bounded memory.

Compile production Java helpers and real panel against Minecraft-signature
stand-ins. Real AI search is tested in stage29_positive_navigation.py.
"""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES, STUBS

CACHE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotPositiveRouteCache.java'

PURE = r'''
import java.util.*;
import buildcraft.robotics.scheduler.RobotPositiveRouteCache;
public final class PositivePathCacheFixture {
 static int assertions;
 static void ok(boolean v,String msg){assertions++;if(!v)throw new AssertionError(msg);}
 static long p(int x,int y,int z){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 static List<Long> line(int length){List<Long> out=new ArrayList<>();for(int i=1;i<=length;i++)out.add(p(i,65,0));return out;}
 public static void main(String...args){
  var index=new RobotPositiveRouteCache();
  var first=p(0,65,0);var target=p(10,65,0);var other=p(14,65,0);
  ok(index.remember(100,first,List.of(target,other),target,line(9)),"route store");
  var match=index.find(101,first,List.of(other,target));
  ok(match!=null&&match.exact()&&match.route().complete(),"goal-set order changed key");
  ok(match.route().length()==9,"waypoint length corrupted");
  var copy=match.route().waypoints();copy[0]=0L;
  ok(match.route().waypoints()[0]==p(1,65,0),"caller mutated cached coordinates");
  ok(index.find(101,p(0,65,1),List.of(target,other))==null,
    "nearest different cave inherited a successful route");
  ok(index.find(101,first,List.of(other))!=null
       &&!index.find(101,first,List.of(other)).exact(),"changed goals incorrectly short-circuited A*");
  ok(index.find(101,first,List.of(p(-200,65,0)))==null,"corridor away from work was seeded");
  index.worldChanged(p(100,65,100));
  var revised=index.find(102,first,List.of(target,other));
  ok(revised!=null&&!revised.exact(),"distant world mutation retained shortest-path proof");
  ok(index.isCurrent(102,match.route()),"safe passable corridor discarded unnecessarily");
  ok(!index.mayShortCircuit(102,match.route()),"world revision ignored by direct cache hit");
  index.worldChanged(target);
  ok(index.entries()==1,"mined target discarded its reusable air corridor");
  index.worldChanged(p(5,65,0));
  ok(index.entries()==0,"modified path block still offered as valid route");
  ok(!index.isCurrent(102,match.route()),"stale caller retained invalidated route");
  ok(index.find(102,first,List.of(target,other))==null,"invalid route still accessible");
  for(int i=0;i<RobotPositiveRouteCache.MAX_ROUTES+4;i++){
   long id=p(0,65,i);
   ok(index.remember(120,id,List.of(p(10,65,i)),p(10,65,i),List.of(p(1,65,i))),"bounded store");
  }
  ok(index.entries()==RobotPositiveRouteCache.MAX_ROUTES,"cache memory unbounded");
  ok(index.find(120,p(0,65,0),List.of(p(10,65,0)))==null,"evicted route still available");
  ok(index.find(120,p(0,65,67),List.of(p(10,65,67)))!=null,"latest LRU witness lost");
  index.clear();
  List<Long> many=line(300);
  ok(index.remember(150,first,List.of(p(301,65,0)),p(301,65,0),many),"long prefix store");
  var clipped=index.find(151,first,List.of(p(301,65,0)));
  ok(clipped!=null&&!clipped.exact()&&clipped.route().length()==256
     &&!clipped.route().complete(),"partial 256-cell corridor was treated as full route");
  ok(index.find(351,first,List.of(p(301,65,0)))==null,"expired positive witness used after TTL");
  ok(index.entries()==0,"TTL did not free memory");
  List<Long> oversize=new ArrayList<>();for(int i=0;i<200;i++)oversize.add(p(i,65,20));
  ok(!index.remember(400,first,oversize,target,line(9)),"oversized goals stored silently");
  ok(index.find(400,first,oversize)==null,"oversized lookup returned a route");
  ok(index.remember(500,p(-10,-30,-5),List.of(p(-4,-30,-5)),p(-4,-30,-5),
          List.of(p(-9,-30,-5),p(-8,-30,-5),p(-7,-30,-5),p(-6,-30,-5),p(-5,-30,-5))),
          "negative coordinates rejected");
  ok(index.find(501,p(-10,-30,-5),List.of(p(-4,-30,-5))).exact(),"negative coordinates failed roundtrip");
  index.find(0,first,List.of(target));
  ok(index.entries()==0,"game clock moving backward did not invalidate saved witnesses");
  System.out.println("PASS "+assertions+" immutable/TTL/LRU/scope/revisions/negative coords/capped routes");
 }
}
'''

PANEL = r'''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class PositiveRoutesPanelFixture {
 static void ok(boolean v,String msg){if(!v)throw new AssertionError(msg);}
 public static void main(String...args){
  var level=new ServerLevel();level.time=10;
  var firstRobot=new EntityRobotBase(level,10,new BlockPos(0,65,0));
  var secondRobot=new EntityRobotBase(level,11,new BlockPos(0,65,0));
  var unlinked=new EntityRobotBase(level,12,new BlockPos(0,65,0));
  firstRobot.setBoardId("buildcraft:miner");secondRobot.setBoardId("buildcraft:miner");
  unlinked.setBoardId("buildcraft:miner");
  RobotManager.registryProvider=world -> new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return id==10?firstRobot:id==11?secondRobot:id==12?unlinked:null;}
   public boolean isTaken(ResourceIdBlock key){return false;}
   public long robotIdTaking(ResourceIdBlock key){return 0;}
  };
  var panel=new TileRobotProcessor(level,new BlockPos(0,65,0));
  panel.linked.addAll(List.of(10L,11L));
  var scheduler=new RobotProcessorWorkScheduler(panel);
  scheduler.tick(level);
  BlockPos start=firstRobot.blockPosition(),ore=new BlockPos(8,65,0);
  List<BlockPos> goals=List.of(ore), corridor=List.of(new BlockPos(1,65,0),
      new BlockPos(2,65,0),new BlockPos(3,65,0),new BlockPos(4,65,0),
      new BlockPos(5,65,0),new BlockPos(6,65,0),new BlockPos(7,65,0));
  int reads=level.blockReads;
  RobotProcessorWorkScheduler.rememberPositiveRoute(firstRobot,start,goals,ore,corridor);
  var exact=RobotProcessorWorkScheduler.positiveRouteCandidate(secondRobot,start,goals);
  ok(exact!=null&&exact.exact(),"linked second miner cannot find positive route");
  ok(level.blockReads==reads,"route lookup read world blocks / loaded a chunk");
  ok(RobotProcessorWorkScheduler.positiveRouteCandidate(unlinked,start,goals)==null,
     "unlinked miner reused another panel's hint");
  ok(RobotProcessorWorkScheduler.positiveRouteCandidate(secondRobot,
      new BlockPos(0,65,1),goals)==null,"different start reused a disconnected route");
  RobotProcessorWorkScheduler.worldBlockChanged(level,new BlockPos(14,65,14));
  ok(!RobotProcessorWorkScheduler.positiveRouteCurrent(secondRobot,exact.route(),true),
      "changed world retained exact A* shortcut");
  ok(RobotProcessorWorkScheduler.positiveRouteCurrent(secondRobot,exact.route(),false),
      "unrelated change discarded safe corridor");
  RobotProcessorWorkScheduler.worldBlockChanged(level,new BlockPos(4,65,0));
  ok(!RobotProcessorWorkScheduler.positiveRouteCurrent(secondRobot,exact.route(),false),
     "modified walkway remained cached");
  RobotProcessorWorkScheduler.rememberPositiveRoute(firstRobot,start,goals,ore,corridor);
  ok(RobotProcessorWorkScheduler.positiveRouteCandidate(secondRobot,start,goals)!=null,
     "restored route missing");
  scheduler.onLinkChanged();
  ok(RobotProcessorWorkScheduler.positiveRouteCandidate(secondRobot,start,goals)==null,
     "relink did not clear stale positive route");
  RobotProcessorWorkScheduler.rememberPositiveRoute(firstRobot,start,goals,ore,corridor);
  scheduler.onRemoved();
  ok(RobotProcessorWorkScheduler.positiveRouteCandidate(secondRobot,start,goals)==null,
     "unloaded panel retained positive routes");
  System.out.println("PASS real panel positive route ownership/mutation/no world reads/clear");
 }
}'''

class PositiveRouteCacheTests(unittest.TestCase):
    def test_bounded_java_cache_and_revision(self):
        with tempfile.TemporaryDirectory(prefix='bc-positive-pure-') as tmp:
            p=Path(tmp)/'PositivePathCacheFixture.java';p.write_text(PURE)
            compiled=subprocess.run(['javac','--release','17','-d',tmp,str(CACHE),str(p)],
                                    capture_output=True,text=True,timeout=40)
            self.assertEqual(compiled.returncode,0,compiled.stdout+compiled.stderr)
            run=subprocess.run(['java','-cp',tmp,'PositivePathCacheFixture'],
                               capture_output=True,text=True,timeout=20)
            self.assertEqual(run.returncode,0,run.stdout+run.stderr)

    def test_panel_scope_world_changes_and_unload(self):
        for version,family,loader in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),
                                      ('26.3','26.X','fabric')]:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-positive-panel-') as tmp:
                folder=Path(tmp);files=[];stubs=dict(STUBS)
                board='buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[board]=stubs[board].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt=new NBT("lumberjack");\n'
                    ' public void setBoardId(String id){nbt=new NBT(id);}\n'
                    ' public boolean processorSupportsQueue(){return true;}')
                entity='buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[entity]=stubs[entity].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                for rel,code in stubs.items():
                    file=folder/rel;file.parent.mkdir(parents=True,exist_ok=True)
                    file.write_text(code);files.append(str(file))
                for rel in SOURCES:
                    file=folder/rel.removeprefix('source-shared/src/main/java/')
                    file.parent.mkdir(parents=True,exist_ok=True)
                    file.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                         family=family,platform=loader,source=rel))
                    files.append(str(file))
                fixture=folder/'fixture/PositiveRoutesPanelFixture.java'
                fixture.parent.mkdir(parents=True,exist_ok=True)
                fixture.write_text(PANEL);files.append(str(fixture))
                result=subprocess.run(['javac','--release','17','-d',str(folder/'classes'),*files],
                                      capture_output=True,text=True,timeout=70)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                result=subprocess.run(['java','-cp',str(folder/'classes'),'fixture.PositiveRoutesPanelFixture'],
                                      capture_output=True,text=True,timeout=20)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)

if __name__ == '__main__':unittest.main()
