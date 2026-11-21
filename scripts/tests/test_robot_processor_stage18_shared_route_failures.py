"""Stage 18: shared transient negative-route hints without false permanent ore bans.

The actual value cache and actual panel scheduler are compiled on JVM stand-ins
for old Forge and modern NeoForge/Fabric. No fake path-search results are relied
on for the panel's ownership or world-change logic.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from scripts.source_preprocessor import preprocess_text
from scripts.tests.test_robot_processor_scheduler_runtime import ROOT, SOURCES, STUBS

CACHE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotSharedRouteFailures.java'

CACHE_FIXTURE = r'''import java.util.*;
import net.minecraft.core.BlockPos;
import buildcraft.robotics.scheduler.RobotSharedRouteFailures;
public class VerifySharedRouteFailures {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static BlockPos p(int x,int y,int z){return new BlockPos(x,y,z);}
 public static void main(String[]args){
  var failures=new RobotSharedRouteFailures();
  BlockPos a=p(0,64,0), b=p(2,64,0), sealed=p(1,64,0), remote=p(65,64,0);
  BlockPos target=p(22,12,2), second=p(3,55,3);
  // A reached B's start through open cells. A did NOT reach the equally
  // close cell behind a solid wall; it must never suppress searches from it.
  check(failures.remember(a,List.of(target),List.of(a,b,p(1,64,1)),100,false)==1,
        "Failed to store a bounded search");
  check(failures.shouldDefer(b,target.asLong(),100),"Same explored cave repeats A* unnecessarily");
  check(!failures.shouldDefer(sealed,target.asLong(),100),"Nearby disconnected start was suppressed");
  check(!failures.shouldDefer(remote,target.asLong(),100),"Distant robot inherited another cave failure");
  check(failures.deferUntil(b,List.of(target),110)==160,"Unexpected node-cap retry TTL");
  check(failures.deferUntil(b,List.of(target,second),110)==0,"Untested new target was suppressed");
  check(failures.deferUntil(b,List.of(target),160)==0,"Node-limited search backoff became permanent");
  failures.remember(a,List.of(target,second),List.of(a,b),200,true);
  check(failures.deferUntil(b,List.of(target,second),210)==400,"Exhausted search backoff TTL incorrect");
  failures.worldChanged();
  check(failures.deferUntil(b,List.of(target),210)==0,"Opened route not invalidated by block change");
  // Bounded growth, expiry, and no cross-world/panel state (one cache per panel).
  for(int i=0;i<50;i++)failures.remember(a,List.of(p(i,64,20)),List.of(a,b),300,true);
  check(failures.cachedSearches()==32,"Unbounded historical failures");
  check(!failures.shouldDefer(b,p(0,64,20).asLong(),300),"Evicted failure still referenced");
  check(failures.shouldDefer(b,p(49,64,20).asLong(),300),"Latest failure lost");
  failures.expire(500);
  check(failures.cachedSearches()==0,"TTL did not prune the cache");
  List<BlockPos> dense=new ArrayList<>();
  for(int x=-6;x<=6;x++)for(int y=58;y<=70;y++)for(int z=-6;z<=6;z++)dense.add(p(x,y,z));
  // This start is deliberately late in the iteration order: it must still
  // be retained when all nearby air cells were discovered in one search.
  failures.remember(a,List.of(target),dense,600,true);
  check(failures.shouldDefer(p(6,70,6),target.asLong(),600),
      "Large cave discarded a genuinely explored start due to witness cap");
  check(new RobotSharedRouteFailures().deferUntil(b,List.of(target),300)==0,
        "Cache leaked into another panel");
  System.out.println("PASS shared route cache: explored starts, safe scope, TTL, new goals, mutation, bounded memory");
 }
}'''

SCHEDULER_FIXTURE = r'''package fixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.tile.TileRobotProcessor;
public class SharedRoutePanelFixture {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[]args){
  var world=new ServerLevel();
  var a=new EntityRobotBase(world,1,new BlockPos(0,65,0));
  var b=new EntityRobotBase(world,2,new BlockPos(2,65,0));
  var c=new EntityRobotBase(world,3,new BlockPos(0,65,0));
  a.setBoardId("buildcraft:miner");b.setBoardId("buildcraft:miner");c.setBoardId("buildcraft:miner");
  RobotManager.registryProvider=level->new IRobotRegistry(){
    public EntityRobotBase getLoadedRobot(long id){return id==1?a:id==2?b:id==3?c:null;}
    public boolean isTaken(ResourceIdBlock id){return false;}
    public long robotIdTaking(ResourceIdBlock id){return 0;}
  };
  TileRobotProcessor first=new TileRobotProcessor(world,new BlockPos(0,65,0));
  first.linked.add(1L);first.linked.add(2L);
  var scheduler=new RobotProcessorWorkScheduler(first);
  world.time=1;scheduler.tick(world);
  check(RobotProcessorWorkScheduler.isManagedMiner(a),"Controller is missing");
  check(RobotProcessorWorkScheduler.isManagedMiner(b),"Second miner not linked");
  BlockPos ore=new BlockPos(7,40,1), alternate=new BlockPos(8,40,1);
  RobotProcessorWorkScheduler.rememberFailedRoute(a,a.blockPosition(),List.of(ore),
      List.of(a.blockPosition(),b.blockPosition()),false,32768);
  long retry=RobotProcessorWorkScheduler.sharedRouteDeferUntil(b,b.blockPosition(),List.of(ore));
  check(retry>world.time,"A* failure was not shared across linked miners");
  check(RobotProcessorWorkScheduler.sharedRouteDeferUntil(b,b.blockPosition(),List.of(ore,alternate))==0,
      "A new target should bypass the cache immediately");
  check(RobotProcessorWorkScheduler.sharedRouteDeferUntil(c,c.blockPosition(),List.of(ore))==0,
      "Unlinked miner inherited route backoff");
  RobotProcessorWorkScheduler.worldBlockChanged(world,new BlockPos(1,65,0));
  check(RobotProcessorWorkScheduler.sharedRouteDeferUntil(b,b.blockPosition(),List.of(ore))==0,
      "World mutation did not invalidate panel route cache");
  RobotProcessorWorkScheduler.rememberFailedRoute(a,a.blockPosition(),List.of(ore),
      List.of(a.blockPosition(),b.blockPosition()),true,30);
  scheduler.onLinkChanged();
  check(RobotProcessorWorkScheduler.sharedRouteDeferUntil(b,b.blockPosition(),List.of(ore))==0,
      "Link policy change retained negative route hint");
  scheduler.onRemoved();
  check(RobotProcessorWorkScheduler.sharedRouteDeferUntil(b,b.blockPosition(),List.of(ore))==0,
      "Removed panel leaked its route cache");
  System.out.println("PASS: linked miners share failure; other panels and mutation are isolated");
 }
}'''


class SharedRouteFailuresTests(unittest.TestCase):
    def test_bounded_negative_cache_has_no_global_unreachable_assumption(self):
        with tempfile.TemporaryDirectory(prefix='bc-route-failure-core-') as d:
            folder = Path(d)
            pos = folder / 'net/minecraft/core/BlockPos.java'
            pos.parent.mkdir(parents=True, exist_ok=True)
            pos.write_text(STUBS['net/minecraft/core/BlockPos.java'])
            fixture = folder / 'VerifySharedRouteFailures.java'
            fixture.write_text(CACHE_FIXTURE)
            out = folder / 'classes'
            out.mkdir()
            result = subprocess.run(['javac', '--release', '17', '-d', str(out),
                                     str(pos), str(CACHE), str(fixture)],
                                    capture_output=True, text=True, timeout=40)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(['java', '-cp', str(out), 'VerifySharedRouteFailures'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_real_scheduler_wires_invalidation_and_primary_controller(self):
        for minecraft, family, loader in [('1.19.2', 'old', 'forge'),
                                          ('1.21.11', '1.21.X', 'neoforge'),
                                          ('26.3', '26.X', 'fabric')]:
            with self.subTest(target=minecraft+'-'+loader), tempfile.TemporaryDirectory(prefix='bc-route-panel-') as d:
                folder = Path(d)
                stubs = dict(STUBS)
                board = 'buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java'
                stubs[board] = stubs[board].replace('private final NBT nbt = new NBT("lumberjack");',
                    'private NBT nbt=new NBT("lumberjack");\n public void setBoardId(String id){nbt=new NBT(id);}\n'
                    'public boolean processorSupportsQueue(){return true;}')
                entity = 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'
                stubs[entity] = stubs[entity].replace(' public long getRobotId(){return id;}',
                    ' public void setBoardId(String id){board.setBoardId(id);}\n public long getRobotId(){return id;}')
                sources = []
                for rel, content in stubs.items():
                    file = folder / rel
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(content)
                    sources.append(str(file))
                for rel in SOURCES:
                    src = ROOT / rel
                    file = folder / rel.removeprefix('source-shared/src/main/java/')
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_text(preprocess_text(src.read_text(), minecraft=minecraft,
                                         family=family, platform=loader, source=rel))
                    sources.append(str(file))
                file = folder / 'fixture/SharedRoutePanelFixture.java'
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(SCHEDULER_FIXTURE)
                sources.append(str(file))
                out = folder / 'classes'
                out.mkdir()
                compiled = subprocess.run(['javac', '--release', '17', '-d', str(out), *sources],
                                          capture_output=True, text=True, timeout=45)
                self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
                runtime = subprocess.run(['java', '-cp', str(out), 'fixture.SharedRoutePanelFixture'],
                                         capture_output=True, text=True, timeout=20)
                self.assertEqual(runtime.returncode, 0, runtime.stdout + runtime.stderr)

    def test_directional_search_only_waits_on_shared_hint_before_exploring(self):
        source = (ROOT / 'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchNearestBlock.java').read_text()
        self.assertIn('expanded == 0 && queuedTargets != null', source)
        self.assertIn('RobotProcessorWorkScheduler.sharedRouteDeferUntil', source)
        self.assertIn('RobotProcessorWorkScheduler.rememberFailedRoute', source)
        self.assertIn('sharedRoutePaused', source)


if __name__ == '__main__':
    unittest.main()
