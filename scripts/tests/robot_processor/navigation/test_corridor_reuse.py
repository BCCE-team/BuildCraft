"""Stage 31: reuse positive A* witnesses from later positions on the same corridor.

Compile the production cache and the actual AIRobotSearchNearestBlock against
both old/new Minecraft signature shims. Do not substitute a mock search AI.
"""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.test_miner_nearest_route_runtime import ROOT, REAL, STUBS, materialize_old_modern

SCENARIO = r'''package fixture;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
public class Stage31CorridorFixture {
 static int checks;
 static void ok(boolean yes,String message){checks++;if(!yes)throw new AssertionError(message);}
 static BlockPos at(int x,int z){return new BlockPos(x,32,z);}
 static int expanded(AIRobotSearchNearestBlock ai){try{
   Field f=AIRobotSearchNearestBlock.class.getDeclaredField("expanded");f.setAccessible(true);return f.getInt(ai);
 }catch(Exception ex){throw new RuntimeException(ex);}}
 static void run(AIRobotSearchNearestBlock ai){for(int i=0;i<200&&ai.blockFound==null;i++)ai.update();}
 public static void main(String...unused){
  RobotProcessorWorkScheduler.managed=true;
  var world=new Level();BlockPos goal=at(16,0);
  world.put(goal,new BlockState(new Block("iron_ore")));
  var first=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(0,0)),
      (w,p)->p.equals(goal),0,List.of(goal));
  run(first);ok(first.blockFound!=null,"first full path not found");
  var cache=RobotProcessorWorkScheduler.positive.get(world);
  ok(cache!=null&&cache.entries()==1,"first path not cached");
  // A robot already halfway along the same corridor can reuse its suffix
  // with no further A* expansions: every path edge is still revalidated.
  var inside=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(7,0)),
      (w,p)->p.equals(goal),0,List.of(goal));
  run(inside);
  ok(inside.blockFound!=null&&inside.blockFound.toBlockPos().equals(goal),"interior-start miss");
  ok(inside.path.size()==8,"interior path must contain only the remaining suffix");
  ok(expanded(inside)==0,"unchanged interior suffix repeated A*");
  // Adjacent, not ON the path: can validate and seed an actual A*, but
  // never shortcut it (a different route from the neighbouring cave may be cheaper).
  var neighbour=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(7,1)),
      (w,p)->p.equals(goal),0,List.of(goal));
  run(neighbour);
  ok(neighbour.blockFound!=null&&expanded(neighbour)>0,"adjacent start wrongly shortcut A*");
  // The positive shortcut is not valid after any world mutation. A verified
  // suffix may still warm the ordinary search without creating a false proof.
  cache.worldChanged(at(55,55).asLong());
  var revised=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(8,0)),
      (w,p)->p.equals(goal),0,List.of(goal));
  run(revised);
  ok(revised.blockFound!=null&&expanded(revised)>0,"changed world kept direct hit");
  // A newly discovered closer target cannot lose to the old corridor.
  BlockPos closer=at(9,1);
  world.put(closer,new BlockState(new Block("iron_ore")));
  cache.worldChanged(closer.asLong());
  var other=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(8,0)),
      (w,p)->p.equals(goal)||p.equals(closer),0,List.of(goal,closer));
  run(other);
  ok(other.blockFound!=null&&other.blockFound.toBlockPos().equals(closer),
     "reuse of old corridor bypassed closer newly discovered ore");
  // An obstacle in the reused suffix must be validated and never treated
  // as a usable cached path, even if a plugin missed the change event.
  world.put(at(12,0),new BlockState(new Block("wall")));
  var blocked=new AIRobotSearchNearestBlock(new EntityRobotBase(world,at(7,0)),
      (w,p)->p.equals(goal),0,List.of(goal));
  run(blocked);
  ok(blocked.blockFound!=null&&expanded(blocked)>0,"stale corridor was taken through wall");
  // The suffix can be used even when an unrelated earlier chunk of the
  // saved corridor has unloaded. No path validation may load that chunk.
  var tailWorld=new Level();BlockPos tailOre=at(40,0);
  tailWorld.put(tailOre,new BlockState(new Block("iron_ore")));
  var tailFirst=new AIRobotSearchNearestBlock(new EntityRobotBase(tailWorld,at(0,0)),
      (w,p)->p.equals(tailOre),0,List.of(tailOre));
  run(tailFirst);ok(tailFirst.blockFound!=null,"long corridor for suffix test missing");
  tailWorld.unloadXChunk(0);
  var tailRobot=new AIRobotSearchNearestBlock(new EntityRobotBase(tailWorld,at(24,0)),
      (w,p)->p.equals(tailOre),0,List.of(tailOre));
  run(tailRobot);
  ok(tailRobot.blockFound!=null&&expanded(tailRobot)==0,
      "suffix reused an unloaded prefix or repeated A*");
  System.out.println("PASS "+checks+" real A*: interior exact, adjacent warm, revised-world, closer-goal, blocker");
 }
}'''

class CorridorSuffixTests(unittest.TestCase):
    def test_real_ai_suffix_and_adjacent_starts_across_families(self):
        for version in ('forge','neoforge-1.21.11','neoforge-26.2'):
            with self.subTest(version=version),tempfile.TemporaryDirectory(prefix='bc-stage31-suffix-') as td:
                root=Path(td);files=[];stubs=dict(STUBS)
                stubs.pop('fixture/NearestOreFixture.java',None)
                for rel,code in stubs.items():
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code);files.append(str(p))
                for rel in REAL:
                    p=root/rel;p.parent.mkdir(parents=True,exist_ok=True)
                    code=(ROOT/rel).read_text()
                    if rel.endswith('AIRobotSearchNearestBlock.java'):
                        code=materialize_old_modern(code,version=='forge')
                    p.write_text(code);files.append(str(p))
                test=root/'fixture/Stage31CorridorFixture.java'
                test.parent.mkdir(parents=True,exist_ok=True);test.write_text(SCENARIO);files.append(str(test))
                output=root/'classes';output.mkdir()
                proc=subprocess.run(['javac','--release','17','-d',str(output),*files],capture_output=True,text=True,timeout=50)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)
                proc=subprocess.run(['java','-cp',str(output),'fixture.Stage31CorridorFixture'],capture_output=True,text=True,timeout=30)
                self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

if __name__=='__main__':unittest.main()
