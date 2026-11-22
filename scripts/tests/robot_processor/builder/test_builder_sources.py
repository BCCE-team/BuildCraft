#!/usr/bin/env python3
"""Exercise the production Builder source index without Minecraft/Gradle dependencies."""
from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderSourceScheduler.java'

STUBS = {
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public long asLong(){return ((long)x & 0x3ffffffL)<<38 | ((long)z&0x3ffffffL)<<12 | ((long)y&0xfffL);}
 public BlockPos immutable(){return this;}
 public double distSqr(BlockPos other){long dx=x-other.x,dy=y-other.y,dz=z-other.z;return dx*dx+dy*dy+dz*dz;}
}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import java.util.*;import net.minecraft.core.BlockPos;
public class Level {
 public long time;public final Map<BlockPos,Object> blocks=new HashMap<>();
 public final Set<Long> loadedChunks=new HashSet<>();
 public long getGameTime(){return time;}
 public boolean hasChunkAt(BlockPos pos){return loadedChunks.contains(((long)(pos.x()>>4)<<32) | ((pos.z()>>4)&0xffffffffL));}
 public Object getBlockEntity(BlockPos pos){if(!hasChunkAt(pos))throw new AssertionError("unloaded chunk access");return blocks.get(pos);}
 public void load(int cx,int cz){loadedChunks.add(((long)cx<<32)|(cz&0xffffffffL));}
}''',
    'net/minecraft/server/level/ServerLevel.java': '''package net.minecraft.server.level;
public class ServerLevel extends net.minecraft.world.level.Level {}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(0);
 private final int n;public ItemStack(int n){this.n=n;}
 public boolean isEmpty(){return n<=0;} public int getCount(){return n;}
 public Object getItem(){return "item";}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;
public class EntityRobotBase {
 private final long id;private final BlockPos at;
 public EntityRobotBase(long id,BlockPos at){this.id=id;this.at=at;}
 public long getRobotId(){return id;}
 public BlockPos blockPosition(){return at;}
}''',
    'buildcraft/builders/tile/IRobotBuilderTarget.java': '''package buildcraft.builders.tile;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public interface IRobotBuilderTarget {
 Level getLevel();BlockPos getBlockPos();boolean canRobotsBuild();
}''',
    'buildcraft/builders/tile/TileBuilder.java': '''package buildcraft.builders.tile;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import java.util.*;
public class TileBuilder implements IRobotBuilderTarget {
 public static final List<TileBuilder> all=new ArrayList<>();
 public final Level world;public final BlockPos at;public boolean available=true;
 public TileBuilder(Level world,BlockPos at){this.world=world;this.at=at;}
 public static List<TileBuilder> getLoadedBuilders(){return all;}
 public Level getLevel(){return world;}public BlockPos getBlockPos(){return at;}
 public boolean canRobotsBuild(){return available;}
}''',
    'buildcraft/builders/tile/TileConstructionMarker.java': '''package buildcraft.builders.tile;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public class TileConstructionMarker implements IRobotBuilderTarget {
 public static final List<TileConstructionMarker> all=new ArrayList<>();
 private final Level world;private final BlockPos at;
 public TileConstructionMarker(Level world,BlockPos at){this.world=world;this.at=at;}
 public static List<TileConstructionMarker> getLoadedMarkers(){return all;}
 public Level getLevel(){return world;}public BlockPos getBlockPos(){return at;}
 public boolean canRobotsBuild(){return true;}
}''',
}
SCENARIO = '''package fixture;
import java.util.*;import net.minecraft.core.BlockPos;import net.minecraft.server.level.ServerLevel;
import buildcraft.builders.tile.*;import buildcraft.robotics.scheduler.RobotBuilderSourceScheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class BuilderSourcesFixture {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[]args){
  ServerLevel world=new ServerLevel(),other=new ServerLevel();world.load(0,0);other.load(0,0);
  TileBuilder close=new TileBuilder(world,new BlockPos(2,70,2));
  TileConstructionMarker marker=new TileConstructionMarker(world,new BlockPos(8,70,2));
  TileBuilder foreign=new TileBuilder(other,new BlockPos(1,70,1));
  TileBuilder unloaded=new TileBuilder(world,new BlockPos(64,70,2));
  TileBuilder.all.addAll(List.of(close,foreign,unloaded));
  TileConstructionMarker.all.add(marker);
  world.blocks.put(close.at,close);world.blocks.put(marker.getBlockPos(),marker);
  EntityRobotBase a=new EntityRobotBase(1,new BlockPos(0,70,0)),b=new EntityRobotBase(2,new BlockPos(1,70,0));
  var index=new RobotBuilderSourceScheduler();
  check(index.preferred(a,1000)==null,"Not-yet-registered panel changed native behavior");
  index.tick(world,List.of(a,b));
  check(index.preferred(a,1000).size()==2,"Wrong dimension/unloaded source was selected");
  check(index.preferred(a,1000).get(0)==close,"Nearest source not first");
  index.attempted(a,close,false);
  check(index.preferred(a,1000).size()==1 && index.preferred(a,1000).get(0)==marker,
   "Failed builder was immediately retried by the same worker");
  check(index.preferred(b,1000).get(0)==close,"Robot-local backoff poisoned another worker");
  world.time=80;index.tick(world,List.of(a,b));
  check(index.preferred(a,1000).size()==1,"Cooldown lost on index refresh");
  world.time=161;index.tick(world,List.of(a,b));
  check(index.preferred(a,1000).size()==2,"Expired source did not return to consideration");
  close.available=false;world.time=201;index.tick(world,List.of(a,b));
  check(index.preferred(a,1000).size()==1,"Inactive Builder considered executable");
  index.tick(world,List.of());
  check(index.preferred(a,1000)==null,"Unlinked controller still holds sources");
  index.tick(world,List.of(a,b));
  check(index.preferred(a,1000).size()==1,"Relinking did not refresh Builder sources");
  index.clear();
  check(index.preferred(a,1000)==null,"Removed panel still overrides native Builder selection");
  System.out.println("PASS: loaded-only directory, dimension, distance, independent cooldown, links");
 }
}'''


class BuilderSourceRuntimeTests(unittest.TestCase):
    def test_source_index_sorts_safely_and_respects_unloads(self):
        with tempfile.TemporaryDirectory(prefix='bc-builder-sources-') as tmp:
            folder = Path(tmp)
            src = []
            for rel, contents in STUBS.items():
                file = folder / rel
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(contents)
                src.append(str(file))
            fixture = folder / 'fixture/BuilderSourcesFixture.java'
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(SCENARIO)
            src.extend((str(JAVA), str(fixture)))
            out = folder / 'classes'
            result = subprocess.run(['javac', '--release', '17', '-d', str(out), *src],
                                    capture_output=True, text=True, timeout=40)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', str(out), 'fixture.BuilderSourcesFixture'],
                                    capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
            self.assertIn('PASS:', result.stdout)

    def test_builder_preserves_native_blueprint_batch_semantics(self):
        for family in ('old', '1.21.X'):
            with self.subTest(family=family):
                source = (ROOT / f'source-families/{family}/src/main/java/'
                          'buildcraft/robotics/boards/BoardRobotBuilder.java').read_text()
                self.assertIn('builderSourcesFor(robot, MAX_RANGE_SQ)', source)
                self.assertIn('reserveRobotBuildTasks(robot, MAX_CARRIED_ITEMS)', source)
                self.assertIn('builderSourceAttempted(robot, builder, !tasks.isEmpty())', source)
                self.assertIn('builder.buildRobotTaskResult(robot, task)', source)
                self.assertIn('builder.releaseRobotBuildTask(robot, task)', source)
                self.assertIn('builders.addAll(TileBuilder.getLoadedBuilders())', source)
                self.assertIn('builders.addAll(TileConstructionMarker.getLoadedMarkers())', source)


if __name__ == '__main__':
    unittest.main()
