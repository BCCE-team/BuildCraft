#!/usr/bin/env python3
"""Compile the real search-and-goto AI and test truthful navigation failures."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SEARCH_AND_GOTO = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai'
                   / 'AIRobotSearchAndGotoBlock.java')
STUBS = {
    'buildcraft/lib/internal/core/BlockIndex.java': '''package buildcraft.lib.internal.core;
import net.minecraft.nbt.CompoundTag;
public class BlockIndex {public final int x,y,z;
 public BlockIndex(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public BlockIndex(CompoundTag t){this(1,2,3);}
 public void writeTo(CompoundTag t){}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
public class EntityRobotBase {
 public Registry registry=new Registry();
 public Registry getRegistry(){return registry;}
 public static class Registry {
  public int releases;
  public boolean take(ResourceIdBlock id,EntityRobotBase robot){return true;}
  public void release(ResourceIdBlock id){releases++;}
 }
}''',
    'buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java': '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.lib.internal.core.BlockIndex;
public class ResourceIdBlock {public ResourceIdBlock(BlockIndex id){}}
''',
    'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot {
 public final EntityRobotBase robot;
 private boolean success=true;
 public AIRobot(EntityRobotBase robot){this.robot=robot;}
 public void start(){}public void end(){}public void delegateAIEnded(AIRobot ai){}
 public void startDelegateAI(AIRobot ai){ai.start();}
 public void terminate(){end();}
 public void fail(){setSuccess(false);}
 protected void setSuccess(boolean b){success=b;}
 public boolean success(){return success;}
 public boolean canLoadFromNBT(){return false;}
 public boolean shouldSaveToNBT(){return false;}
 public void writeSelfToNBT(CompoundTag tag){}
 public void loadSelfFromNBT(CompoundTag tag){}
}''',
    'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
public class CompoundTag {
 public void put(String k,CompoundTag t){}public boolean contains(String k){return false;}
 public CompoundTag getCompound(String k){return new CompoundTag();}
}''',
    'buildcraft/robotics/ai/IBlockFilter.java': 'package buildcraft.robotics.ai; public interface IBlockFilter {}',
    'buildcraft/robotics/ai/AIRobotSearchBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.BlockIndex;import java.util.LinkedList;
public class AIRobotSearchBlock extends AIRobot {
 public BlockIndex blockFound;public LinkedList<BlockIndex> path=new LinkedList<>();
 public AIRobotSearchBlock(EntityRobotBase r,boolean random,IBlockFilter filter,double distance){super(r);}
 public boolean takeResource(){return robot.getRegistry()!=null && robot.getRegistry().take(new ResourceIdBlock(blockFound),robot);}
}''',
    'buildcraft/robotics/ai/AIRobotSearchNearestBlock.java': '''package buildcraft.robotics.ai;
import java.util.LinkedList;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotSearchNearestBlock extends AIRobot {
 public BlockIndex blockFound;public LinkedList<BlockIndex> path=new LinkedList<>();
 public AIRobotSearchNearestBlock(EntityRobotBase r,IBlockFilter filter,double distance){super(r);}
 public boolean takeResource(){return robot.getRegistry()!=null && robot.getRegistry().take(new ResourceIdBlock(blockFound),robot);}
}''',
    'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.*;
import java.util.LinkedList;
public class AIRobotGotoBlock extends AIRobot {
 public AIRobotGotoBlock(EntityRobotBase r,LinkedList<BlockIndex> path){super(r);}
}''',
    'fixture/WorkRouteResultFixture.java': '''package fixture;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.BlockIndex;
import java.util.LinkedList;
public class WorkRouteResultFixture {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static AIRobotSearchAndGotoBlock target(EntityRobotBase r){
  var ai=new AIRobotSearchAndGotoBlock(r,false,null);
  var search=new AIRobotSearchBlock(r,false,null,0);
  search.blockFound=new BlockIndex(1,2,3);
  search.path.add(new BlockIndex(1,2,2)); // A real waypoint is needed to test Goto failure.
  ai.delegateAIEnded(search);
  return ai;
 }
 public static void main(String[]args){
  var successRobot=new EntityRobotBase();
  var completed=target(successRobot);
  completed.delegateAIEnded(new AIRobotGotoBlock(successRobot,new LinkedList<>()));
  check(completed.success(),"Successful movement did not pass through to the board");
  check(successRobot.registry.releases==0,"Successful target reservation was lost");
  var failedRobot=new EntityRobotBase();
  var failed=target(failedRobot);
  var badMove=new AIRobotGotoBlock(failedRobot,new LinkedList<>());badMove.fail();
  failed.delegateAIEnded(badMove);
  check(!failed.success(),"Blocked movement incorrectly reported success");
  check(failedRobot.registry.releases==1,"Blocked target was never released");
  // When the registry disappears during unload, end() cannot release the
  // target reference. The AI's status must still remain false.
  var unavailableRobot=new EntityRobotBase();
  var unavailable=target(unavailableRobot);
  unavailableRobot.registry=null;
  var aborted=new AIRobotGotoBlock(unavailableRobot,new LinkedList<>());aborted.fail();
  unavailable.delegateAIEnded(aborted);
  check(unavailable.getBlockFound()!=null,"Fixture did not preserve stale target");
  check(!unavailable.success(),"Stale block reference overrode failed movement status");
  System.out.println("PASS: worker navigation failure status, reservations and unloaded registry");
 }
}''',
}

class WorkerNavigationStatusTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_real_search_and_goto_error_propagation(self):
        with tempfile.TemporaryDirectory(prefix='bc-worker-route-') as tmp:
            root = Path(tmp)
            sources = [str(SEARCH_AND_GOTO)]
            for rel, code in STUBS.items():
                dest = root / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(code, encoding='utf-8')
                sources.append(str(dest))
            classes = root / 'classes'
            classes.mkdir()
            compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *sources],
                                      capture_output=True, text=True, timeout=40)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            executed = subprocess.run(['java', '-cp', str(classes), 'fixture.WorkRouteResultFixture'],
                                      capture_output=True, text=True, timeout=15)
            self.assertEqual(executed.returncode, 0, executed.stderr)
            self.assertIn('PASS:', executed.stdout)
            print(executed.stdout.strip(), flush=True)

if __name__ == '__main__':
    unittest.main()
