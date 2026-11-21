"""Compile real shared scanner with eight linked panels using one server Level.

The stand-in world counts every block read. This validates that the production
WorldState actually uses the new allocator, not just that its standalone unit
test passes. Scanning never loads an ungenerated chunk and no panel starves.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES, STUBS
from scripts.source_preprocessor import preprocess_text

FIXTURE = r'''
package fixture;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public final class AdaptivePanelsFixture {
 static int assertions;
 static void check(boolean ok,String msg){assertions++;if(!ok)throw new AssertionError(msg);}
 public static void main(String[]args) throws Exception {
  ServerLevel world=new ServerLevel();
  Map<Long,EntityRobotBase> entities=new HashMap<>();
  List<TileRobotProcessor> panels=new ArrayList<>();
  List<RobotProcessorWorkScheduler> schedulers=new ArrayList<>();
  for(int i=0;i<8;i++){
   var panel=new TileRobotProcessor(world,new BlockPos(i,65,0));
   for(int j=0;j<2;j++){
    long id=i*2L+j+1;
    entities.put(id,new EntityRobotBase(world,id,new BlockPos(2,65,2)));
    panel.linked.add(id);
   }
   panels.add(panel);schedulers.add(new RobotProcessorWorkScheduler(panel));
  }
  RobotManager.registryProvider=ignored -> new IRobotRegistry(){
   public EntityRobotBase getLoadedRobot(long id){return entities.get(id);}
   public boolean isTaken(ResourceIdBlock resource){return false;}
   public long robotIdTaking(ResourceIdBlock resource){return 0L;}
  };
  var worlds=RobotProcessorWorkScheduler.class.getDeclaredField("WORLDS");
  worlds.setAccessible(true);
  for(int tick=1;tick<=8;tick++){
   world.time=tick;
   int before=world.blockReads;
   int scanned=0;
   for(RobotProcessorWorkScheduler scheduler:schedulers){
    var diagField=RobotProcessorWorkScheduler.class.getDeclaredField("diagnostics");
    diagField.setAccessible(true);
    var diag=diagField.get(scheduler);
    var reads=diag.getClass().getDeclaredField("inspected");
    reads.setAccessible(true);
    int seen=reads.getInt(diag);
    scheduler.tick(world);
    scanned+=reads.getInt(diag)-seen;
   }
   int readsInWorld=world.blockReads-before;
   check(scanned<=2048,"common scanner exceeded world budget: "+scanned);
   // Native route/queue validation uses a few extra reads outside the
   // common scanner. It has its own existing budgets and must not be charged
   // against scanner credits or falsely fail the scan-only guarantee.
   check(readsInWorld<=scanned+64,"excessive non-scanner reads during planning");
   if(tick>=2){
    var byLevel=(Map<?,?>)worlds.get(null);
    var state=byLevel.get(world);
    var allocation=state.getClass().getDeclaredField("adaptiveBudget");
    allocation.setAccessible(true);
    var budget=(RobotAdaptiveScanBudget)allocation.get(state);
    int quotaSum=0;
    for(TileRobotProcessor panel:panels){
     int quota=budget.quotaThisTick(panel.getBlockPos().asLong());
     check(quota>0,"panel was starved by earlier tick order");
     quotaSum+=quota;
    }
    check(quotaSum<=2048,"combined panel quota exceeded level cap");
    check(budget.totalThisTick()<=2048,"actual total grant exceeded world cap");
   }
  }
  // A removed panel must be absent from the allocator's future demand.
  schedulers.get(7).onRemoved();
  world.time++;
  for(int i=0;i<7;i++)schedulers.get(i).tick(world);
  var state=((Map<?,?>)worlds.get(null)).get(world);
  var allocation=state.getClass().getDeclaredField("adaptiveBudget");
  allocation.setAccessible(true);
  var budget=(RobotAdaptiveScanBudget)allocation.get(state);
  check(budget.quotaThisTick(panels.get(7).getBlockPos().asLong())==0,
    "removed panel retained shared budget");
  for(int i=0;i<7;i++)schedulers.get(i).onRemoved();
  System.out.println("PASS "+assertions+" production multi-panel scan checks");
 }
}
'''

class MultiPanelAdaptiveTests(unittest.TestCase):
    def test_real_scheduler_prevents_level_overruns_and_panel_starvation(self):
        for version,family,loader in [('1.19.2','old','forge'),
                                      ('1.21.11','1.21.X','neoforge'),
                                      ('26.3','26.X','fabric')]:
            with self.subTest(version=version,loader=loader),tempfile.TemporaryDirectory(prefix='bc-stage27-world-') as td:
                base=Path(td);files=[]
                for rel,content in STUBS.items():
                    p=base/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);files.append(str(p))
                for rel in SOURCES:
                    p=base/rel.removeprefix('source-shared/src/main/java/')
                    p.parent.mkdir(parents=True,exist_ok=True)
                    p.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,
                                   family=family,platform=loader,source=rel))
                    files.append(str(p))
                p=base/'fixture/AdaptivePanelsFixture.java';p.parent.mkdir(parents=True,exist_ok=True)
                p.write_text(FIXTURE);files.append(str(p))
                classes=base/'classes';classes.mkdir()
                compile=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                                       capture_output=True,text=True,timeout=55)
                self.assertEqual(compile.returncode,0,compile.stdout+compile.stderr)
                result=subprocess.run(['java','-cp',str(classes),'fixture.AdaptivePanelsFixture'],
                                      capture_output=True,text=True,timeout=25)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                self.assertIn('production multi-panel scan checks',result.stdout)

if __name__=='__main__':unittest.main()
