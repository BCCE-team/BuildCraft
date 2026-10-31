"""Exercise real AIRobotSearchAndGotoBlock reservation transfer with stubbed AI runtime."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SEARCH = ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java'

STUBS = {
    'buildcraft/lib/internal/core/BlockIndex.java': '''
        package buildcraft.lib.internal.core;
        import net.minecraft.nbt.CompoundTag;
        public class BlockIndex {
          public BlockIndex(int x,int y,int z){}
          public BlockIndex(CompoundTag tag){}
          public void writeTo(CompoundTag tag){}
        }
    ''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''
        package buildcraft.robotics.internal.legacy.robots;
        public class EntityRobotBase {
          public final Registry registry = new Registry();
          public Registry getRegistry(){return registry;}
          public static class Registry {
            public int released, taken;
            public boolean take(ResourceIdBlock id,EntityRobotBase robot){taken++;return true;}
            public void release(ResourceIdBlock id){released++;}
          }
        }
    ''',
    'buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java': '''
        package buildcraft.robotics.internal.legacy.robots;
        import buildcraft.lib.internal.core.BlockIndex;
        public class ResourceIdBlock {
          public ResourceIdBlock(BlockIndex pos){}
        }
    ''',
    'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''
        package buildcraft.robotics.internal.legacy.robots;
        import net.minecraft.nbt.CompoundTag;
        import net.minecraft.world.item.ItemStack;
        public class AIRobot {
          public final EntityRobotBase robot;
          private boolean success=true;
          public AIRobot(EntityRobotBase robot){this.robot=robot;}
          public void start(){}
          public void end(){}
          public void delegateAIEnded(AIRobot ai){}
          public void startDelegateAI(AIRobot delegate){delegate.start();}
          public void terminate(){end();}
          protected void setSuccess(boolean success){this.success=success;}
          public boolean success(){return success;}
          public void fail(){setSuccess(false);}
          public boolean canLoadFromNBT(){return false;}
          public boolean shouldSaveToNBT(){return false;}
          public void writeSelfToNBT(CompoundTag tag){}
          public void loadSelfFromNBT(CompoundTag tag){}
        }
    ''',
    'net/minecraft/nbt/CompoundTag.java': '''
        package net.minecraft.nbt;
        public class CompoundTag {
          public void put(String key,CompoundTag value){}
          public boolean contains(String key){return false;}
          public CompoundTag getCompound(String key){return new CompoundTag();}
        }
    ''',
    'net/minecraft/world/item/ItemStack.java': '''
        package net.minecraft.world.item;
        public class ItemStack {}
    ''',
    'buildcraft/robotics/ai/IBlockFilter.java': '''
        package buildcraft.robotics.ai;
        public interface IBlockFilter {}
    ''',
    'buildcraft/robotics/ai/AIRobotSearchBlock.java': '''
        package buildcraft.robotics.ai;
        import buildcraft.lib.internal.core.BlockIndex;
        import buildcraft.robotics.internal.legacy.robots.*;
        import java.util.LinkedList;
        public class AIRobotSearchBlock extends AIRobot {
          public BlockIndex blockFound;
          public LinkedList<BlockIndex> path = new LinkedList<>();
          public AIRobotSearchBlock(EntityRobotBase robot,boolean random,IBlockFilter filter,double distance){super(robot);}
          public boolean takeResource(){return robot.registry.take(new ResourceIdBlock(blockFound),robot);}
        }
    ''',
    'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''
        package buildcraft.robotics.ai;
        import buildcraft.lib.internal.core.BlockIndex;
        import buildcraft.robotics.internal.legacy.robots.*;
        import java.util.LinkedList;
        public class AIRobotGotoBlock extends AIRobot {
          public AIRobotGotoBlock(EntityRobotBase robot,LinkedList<BlockIndex> path){super(robot);}
        }
    ''',
    'buildcraft/robotics/ai/ReservationFixture.java': '''
        package buildcraft.robotics.ai;
        import buildcraft.lib.internal.core.BlockIndex;
        import buildcraft.robotics.internal.legacy.robots.*;
        public class ReservationFixture {
          private static void check(boolean good,String msg){if(!good)throw new AssertionError(msg);}
          private static AIRobotSearchAndGotoBlock withReservedTarget(EntityRobotBase robot){
            AIRobotSearchAndGotoBlock ai = new AIRobotSearchAndGotoBlock(robot,false,null);
            AIRobotSearchBlock search = new AIRobotSearchBlock(robot,false,null,0);
            search.blockFound=new BlockIndex(4,5,6);
            ai.delegateAIEnded(search);
            return ai;
          }
          public static void main(String[] argv){
            EntityRobotBase interrupted = new EntityRobotBase();
            AIRobotSearchAndGotoBlock ai = withReservedTarget(interrupted);
            ai.end();
            check(interrupted.registry.released==1,"Interrupted navigation must release target");
            EntityRobotBase succeeded = new EntityRobotBase();
            ai=withReservedTarget(succeeded);
            ai.delegateAIEnded(new AIRobotGotoBlock(succeeded,new java.util.LinkedList<>()));
            check(succeeded.registry.released==0,"Successful arrival must transfer ownership to board");
            check(ai.success(),"Successful arrival must report success");
            EntityRobotBase failed = new EntityRobotBase();
            ai=withReservedTarget(failed);
            AIRobotGotoBlock noPath = new AIRobotGotoBlock(failed,new java.util.LinkedList<>());
            noPath.fail();
            ai.delegateAIEnded(noPath);
            check(failed.registry.released==1,"Failed navigation must release reservation");
            check(!ai.success(),"Failed navigation must report failure");
            System.out.println("All AI reservation transfer checks passed");
          }
        }
    ''',
}


class RobotReservationRuntime(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK is unavailable')
    def test_cancel_failure_and_successful_handoff(self):
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            sources = [SEARCH]
            for name, data in STUBS.items():
                target = temp / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(data, encoding='utf-8')
                sources.append(target)
            classes = temp / 'classes'
            classes.mkdir()
            compiled = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '-d', str(classes), *(str(s) for s in sources)],
                capture_output=True, text=True, timeout=40,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            launched = subprocess.run(
                ['java', '-cp', str(classes), 'buildcraft.robotics.ai.ReservationFixture'],
                capture_output=True, text=True, timeout=20,
            )
            self.assertEqual(launched.returncode, 0, launched.stderr)
            self.assertIn('All AI reservation transfer checks passed', launched.stdout)


if __name__ == '__main__':
    unittest.main()
