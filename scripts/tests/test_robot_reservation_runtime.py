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
          public AIRobot lastStartedDelegate;
          private boolean success=true;
          public AIRobot(EntityRobotBase robot){this.robot=robot;}
          public void start(){}
          public void end(){}
          public void delegateAIEnded(AIRobot ai){}
          public void startDelegateAI(AIRobot delegate){lastStartedDelegate=delegate;delegate.start();}
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
    # The nearest-by-route search is intentionally stubbed here: this fixture tests
    # reservation ownership in the real AIRobotSearchAndGotoBlock, not BFS pathfinding.
    # Keep its public contract aligned with AIRobotSearchNearestBlock in production.
    'buildcraft/robotics/ai/AIRobotSearchNearestBlock.java': '''
        package buildcraft.robotics.ai;
        import buildcraft.lib.internal.core.BlockIndex;
        import buildcraft.robotics.internal.legacy.robots.*;
        import java.util.LinkedList;
        public class AIRobotSearchNearestBlock extends AIRobot {
          public BlockIndex blockFound;
          public LinkedList<BlockIndex> path = new LinkedList<>();
          public AIRobotSearchNearestBlock(EntityRobotBase robot,IBlockFilter filter,double distance){super(robot);}
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
            // A non-empty path means arrival is still pending, so cancellation releases the claim.
            search.path.add(new BlockIndex(3,5,6));
            ai.delegateAIEnded(search);
            return ai;
          }
          private static AIRobotSearchAndGotoBlock withNearestReservedTarget(EntityRobotBase robot,boolean adjacent){
            AIRobotSearchAndGotoBlock ai = new AIRobotSearchAndGotoBlock(robot,false,null,0,true);
            AIRobotSearchNearestBlock search = new AIRobotSearchNearestBlock(robot,null,0);
            search.blockFound=new BlockIndex(4,5,6);
            if(!adjacent)search.path.add(new BlockIndex(3,5,6));
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
            // Route-based search is selected only for explicitly non-random nearest requests.
            EntityRobotBase routing = new EntityRobotBase();
            AIRobotSearchAndGotoBlock nearest = new AIRobotSearchAndGotoBlock(routing,false,null,0,true);
            nearest.start();
            check(nearest.lastStartedDelegate instanceof AIRobotSearchNearestBlock,
                "Nearest-route search must start the nearest search AI");
            AIRobotSearchAndGotoBlock random = new AIRobotSearchAndGotoBlock(routing,true,null,0,true);
            random.start();
            check(random.lastStartedDelegate instanceof AIRobotSearchBlock,
                "Random searches must preserve the classic search AI");

            EntityRobotBase nearestInterrupted = new EntityRobotBase();
            ai = withNearestReservedTarget(nearestInterrupted,false);
            check(nearestInterrupted.registry.taken==1,"Nearest search must reserve the target");
            ai.end();
            check(nearestInterrupted.registry.released==1,
                "Interrupted nearest-route navigation must release reservation");

            EntityRobotBase nearestSucceeded = new EntityRobotBase();
            ai = withNearestReservedTarget(nearestSucceeded,false);
            ai.delegateAIEnded(new AIRobotGotoBlock(nearestSucceeded,new java.util.LinkedList<>()));
            check(nearestSucceeded.registry.released==0,"Nearest arrival must preserve board reservation");
            check(ai.success(),"Nearest arrival must succeed");

            EntityRobotBase nearestFailed = new EntityRobotBase();
            ai = withNearestReservedTarget(nearestFailed,false);
            AIRobotGotoBlock unreachable = new AIRobotGotoBlock(nearestFailed,new java.util.LinkedList<>());
            unreachable.fail();
            ai.delegateAIEnded(unreachable);
            check(nearestFailed.registry.released==1,"Failed nearest-route navigation must release target");
            check(!ai.success(),"Failed nearest-route navigation must propagate failure");

            // An empty route is already adjacent: no movement should run or release the claim.
            EntityRobotBase adjacent = new EntityRobotBase();
            ai = withNearestReservedTarget(adjacent,true);
            check(adjacent.registry.taken==1,"Adjacent nearest target must be reserved");
            check(adjacent.registry.released==0,"Adjacent nearest target must remain owned by the board");
            check(ai.success(),"Adjacent nearest target must complete without navigation");
            check(ai.lastStartedDelegate==null,"Adjacent nearest target must not start an empty path");

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
