#!/usr/bin/env python3
"""Compile real Miner/vein/search-wrapper/nearest BFS and exercise path-optimal selection."""
from pathlib import Path
import importlib.util
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
BASE_TEST = Path(__file__).with_name('test_miner_ore_vein_runtime.py')
spec = importlib.util.spec_from_file_location('bc_vein_fixture', BASE_TEST)
vein_test = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vein_test)

STUBS = dict(vein_test.STUBS)
STUBS.pop("buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java")
STUBS.pop("fixture/MinerVeinFixture.java")
STUBS['net/minecraft/core/Direction.java'] = '''package net.minecraft.core;
public enum Direction {
 DOWN(0,-1,0), UP(0,1,0), NORTH(0,0,-1), SOUTH(0,0,1), WEST(-1,0,0), EAST(1,0,0);
 public final int x,y,z;
 Direction(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
}'''
STUBS['net/minecraft/core/BlockPos.java'] = STUBS['net/minecraft/core/BlockPos.java'].replace(
    ' public BlockPos immutable(){return this;}',
    ' public BlockPos immutable(){return this;} public BlockPos relative(Direction d){return offset(d.x,d.y,d.z);}'
)
STUBS['net/minecraft/world/phys/shapes/VoxelShape.java'] = '''package net.minecraft.world.phys.shapes;
public final class VoxelShape {private final boolean empty;public VoxelShape(boolean empty){this.empty=empty;}
public boolean isEmpty(){return empty;}}'''
STUBS['net/minecraft/world/level/block/state/BlockState.java'] = STUBS[
    'net/minecraft/world/level/block/state/BlockState.java'].replace(
    ' public boolean isAir(){return air;}',
    ''' public boolean isAir(){return air;}
 public net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(Level level,BlockPos pos){
  return new net.minecraft.world.phys.shapes.VoxelShape(air);
 }''')
STUBS['net/minecraft/world/level/Level.java'] = STUBS['net/minecraft/world/level/Level.java'].replace(
    ' private final Map<BlockPos,BlockState> blocks=new HashMap<>();',
    ''' private final Map<BlockPos,BlockState> blocks=new HashMap<>();
 private boolean solidByDefault;
 public void setSolidByDefault(boolean v){solidByDefault=v;}
 public int getMinBuildHeight(){return -64;}
 public int getMaxBuildHeight(){return 320;}''').replace(
    'return blocks.getOrDefault(p,BlockState.AIR);',
    'return blocks.getOrDefault(p, solidByDefault ? new BlockState(new net.minecraft.world.level.block.Block("stone")) : BlockState.AIR);')
STUBS['buildcraft/lib/internal/core/BlockIndex.java'] = '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public final class BlockIndex {
 public final int x,y,z;
 public BlockIndex(BlockPos p){this(p.x,p.y,p.z);}
 public BlockIndex(int x,int y,int z){this.x=x;this.y=y;this.z=z;}
 public BlockIndex(CompoundTag n){this(n.getCompound("x").contains("unused")?0:(int)n.getLong("x"),(int)n.getLong("y"),(int)n.getLong("z"));}
 public void writeTo(CompoundTag n){n.putLong("x",x);n.putLong("y",y);n.putLong("z",z);}
 public BlockPos toBlockPos(){return new BlockPos(x,y,z);}
}'''
STUBS['buildcraft/robotics/internal/legacy/robots/RobotRegistry.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;import java.util.*;
public final class RobotRegistry {
 private final Set<BlockPos> taken=new HashSet<>();
 public boolean isTaken(ResourceIdBlock id){return taken.contains(id.pos);}
 public boolean take(ResourceIdBlock id,EntityRobotBase robot){return taken.add(id.pos);}
 public void release(ResourceIdBlock id){taken.remove(id.pos);}
}'''
STUBS['buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;import buildcraft.lib.internal.core.BlockIndex;
public final class ResourceIdBlock {
 public final BlockPos pos;public ResourceIdBlock(BlockPos p){pos=p;}
 public ResourceIdBlock(BlockIndex idx){pos=idx.toBlockPos();}
}'''
STUBS['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.entity.EquipmentSlot;import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;import buildcraft.lib.internal.area.IZone;
import net.minecraft.core.BlockPos;
public final class EntityRobotBase {
 public final Level level;
 public IZone zone;
 public BlockPos position;
 private final RobotRegistry registry=new RobotRegistry();
 private final ItemStack held=new ItemStack(new PickaxeItem());
 public EntityRobotBase(Level w,BlockPos p){level=w;position=p;}
 public Level level(){return level;}
 public BlockPos blockPosition(){return position;}
 public void moveTo(BlockPos p){position=p;}
 public RobotRegistry getRegistry(){return registry;}
 public Object getLinkedStation(){return null;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public IZone getZoneToWork(){return zone;}
}'''
STUBS['buildcraft/robotics/internal/legacy/robots/AIRobot.java'] = '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.nbt.CompoundTag;
public class AIRobot {
 public final EntityRobotBase robot;
 private AIRobot delegate,parent;
 private boolean success=true,ended;
 public AIRobot(EntityRobotBase r){robot=r;}
 public boolean success(){return success;}
 protected void setSuccess(boolean value){success=value;}
 public void start(){}
 public void update(){}
 public void end(){}
 public void delegateAIEnded(AIRobot child){}
 public boolean canLoadFromNBT(){return false;}
 public boolean shouldSaveToNBT(){return canLoadFromNBT();}
 public void writeSelfToNBT(CompoundTag n){}
 public void loadSelfFromNBT(CompoundTag n){}
 public int getEnergyCost(){return 1;}
 public void cycle(){if(delegate!=null)delegate.cycle();else update();}
 public final AIRobot getDelegateAI(){return delegate;}
 public final void startDelegateAI(AIRobot child){if(delegate!=null)delegate.abort();delegate=child;child.parent=this;child.start();}
 public final void terminate(){if(ended)return;ended=true;end();if(parent!=null){parent.delegate=null;parent.delegateAIEnded(this);}}
 public final void abort(){if(ended)return;ended=true;if(delegate!=null)delegate.abort();end();parent=null;}
}'''
STUBS['buildcraft/robotics/RobotTrace.java'] = '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public final class RobotTrace {
 public static boolean enabled(){return false;}
 public static void event(EntityRobotBase r,String what,String format,Object... args){}
}'''
STUBS['buildcraft/robotics/ai/AIRobotSearchBlock.java'] = '''package buildcraft.robotics.ai;
import java.util.LinkedList;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotSearchBlock extends AIRobot {
 public BlockIndex blockFound;
 public LinkedList<BlockIndex> path;
 public AIRobotSearchBlock(EntityRobotBase r,boolean random,IBlockFilter filter,double maxDistance){super(r);}
 public boolean takeResource(){return false;}
}'''
STUBS['buildcraft/robotics/ai/AIRobotGotoBlock.java'] = '''package buildcraft.robotics.ai;
import java.util.LinkedList;import net.minecraft.core.BlockPos;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoBlock extends AIRobot {
 public final LinkedList<BlockIndex> path;
 public AIRobotGotoBlock(EntityRobotBase robot,LinkedList<BlockIndex> route){super(robot);path=route;}
 @Override public void update(){
  for(BlockIndex b:path){BlockPos p=b.toBlockPos();
   if(!robot.level().getBlockState(p).isAir()){setSuccess(false);terminate();return;}
   robot.moveTo(p);
  }
  terminate();
 }
}'''
STUBS['buildcraft/robotics/ai/AIRobotGotoSleep.java'] = '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoSleep extends AIRobot {public AIRobotGotoSleep(EntityRobotBase r){super(r);}}'''
STUBS['buildcraft/robotics/ai/IBlockFilter.java'] = '''package buildcraft.robotics.ai;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
public interface IBlockFilter {boolean matches(Level level,BlockPos pos);}'''
STUBS['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java'] = '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot{
 public RedstoneBoardRobot(EntityRobotBase r){super(r);}
}'''
STUBS['buildcraft/robotics/statements/ActionRobotFilter.java'] = '''package buildcraft.robotics.statements;
import java.util.List;import net.minecraft.world.item.ItemStack;
public final class ActionRobotFilter {
 public static List<ItemStack> getGateFilterStacks(Object station){return List.of();}
}'''
STUBS['net/minecraft/world/item/BlockItem.java'] = '''package net.minecraft.world.item;
import net.minecraft.world.level.block.Block;
public final class BlockItem extends Item {private final Block b;public BlockItem(Block b){this.b=b;}
public Block getBlock(){return b;}}'''
STUBS['buildcraft/robotics/boards/BoardRobotGenericBreakBlock.java'] = '''package buildcraft.robotics.boards;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.ai.*;import net.minecraft.world.item.ItemStack;
public abstract class BoardRobotGenericBreakBlock extends BoardRobotGenericSearchBlock {
 public BoardRobotGenericBreakBlock(EntityRobotBase r){super(r);}
 public abstract RedstoneBoardRobotNBT getNBTHandler();
 public abstract boolean isExpectedTool(ItemStack item);
 @Override public void delegateAIEnded(AIRobot ai){
  if(ai instanceof AIRobotBreak)releaseBlockFound(ai.success());
  super.delegateAIEnded(ai);
 }
}'''
STUBS['fixture/NearestOreFixture.java'] = '''package fixture;
import java.util.*;
import buildcraft.robotics.boards.*;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.legacy.robots.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

public final class NearestOreFixture {
 static void check(boolean truth,String why){if(!truth)throw new AssertionError(why);}
 static BlockPos p(int x,int z){return new BlockPos(x,32,z);}
 static BlockState ore(String type){
  BlockState b=new BlockState(new Block(type));
  if(type.startsWith("copper"))return b.tags(BlockTags.COPPER_ORES,net.minecraftforge.common.Tags.Blocks.ORES,
      net.neoforged.neoforge.common.Tags.Blocks.ORES);
  if(type.startsWith("coal"))return b.tags(BlockTags.COAL_ORES,net.minecraftforge.common.Tags.Blocks.ORES,
      net.neoforged.neoforge.common.Tags.Blocks.ORES);
  return b;
 }
 static void run(AIRobot board){
  board.update();
  for(int i=0;i<3000 && board.getDelegateAI()!=null;i++) board.getDelegateAI().cycle();
  check(board.getDelegateAI()==null,"Board never finished searching/moving");
 }
 static BlockPos target(BoardRobotMiner b){
  CompoundTag data=new CompoundTag();b.writeSelfToNBT(data);
  var reserved=data.getCompound("indexStored");
  return reserved.contains("x") ? new BlockPos((int)reserved.getLong("x"),
      (int)reserved.getLong("y"),(int)reserved.getLong("z")) : null;
 }
 static void firstOreAcrossLoadedChunk(){
  var world=new Level();var robot=new EntityRobotBase(world,p(14,0));
  var miner=new BoardRobotMiner(robot);
  BlockPos copper=p(16,0), coal=p(6,0);
  world.put(copper,ore("copper"));world.put(coal,ore("coal"));
  run(miner);
  check(copper.equals(target(miner)),"Cross-chunk nearest copper lost to farther same-chunk coal");
  check(robot.position.equals(p(15,0)),"Route must end on the accessible side of ore");
 }
 static void shortestPathNotEuclidean(){
  var world=new Level();var robot=new EntityRobotBase(world,p(0,0));
  // A short straight line to copper is sealed by a wide wall. Going over
  // its top takes longer than the unobstructed route to distant coal.
  for(int y=28;y<=36;y++)for(int z=-12;z<=12;z++)
      world.put(new BlockPos(1,y,z),new BlockState(new Block("stone")));
  BlockPos copper=p(2,0), coal=p(0,8);
  world.put(copper,ore("copper"));world.put(coal,ore("coal"));
  var miner=new BoardRobotMiner(robot);
  run(miner);
  check(coal.equals(target(miner)),"Straight-line-nearer copper stole a shortest-path coal target");
  check(robot.position.equals(p(0,7)),"Unexpected actual travel corridor to coal");
 }
 static void continuationAndPersistence(){
  var world=new Level();var robot=new EntityRobotBase(world,p(14,0));
  BlockPos a=p(16,0), b=p(17,0), c=p(18,0), coal=p(8,0);
  world.put(a,ore("copper"));world.put(b,ore("copper"));world.put(c,ore("copper"));
  world.put(coal,ore("coal"));
  var miner=new BoardRobotMiner(robot);
  run(miner);
  check(a.equals(target(miner)),"First nearest ore not chosen");
  // Mimic finished break. The board's real callback removes the mined
  // block and retains the remaining vein as an absolute priority.
  world.mine(a);miner.delegateAIEnded(new AIRobotBreak(robot));
  check(!miner.isExpectedBlock(world,coal),"Coal must not interrupt chosen copper vein");
  CompoundTag nbt=new CompoundTag();miner.writeSelfToNBT(nbt);
  var restored=new BoardRobotMiner(robot);restored.loadSelfFromNBT(nbt);
  check(!restored.isExpectedBlock(world,coal),"Save/load lost current vein priority");
  // In this fixture copper at b is reachable on at least one side even
  // though the robot is already at a. It must be next after removing a.
  run(miner);
  check(b.equals(target(miner)),"Nearest remaining copper not picked");
  world.mine(b);miner.delegateAIEnded(new AIRobotBreak(robot));
  run(miner);
  check(c.equals(target(miner)),"Current deposit not finished before coal");
  world.mine(c);miner.delegateAIEnded(new AIRobotBreak(robot));
  check(miner.isExpectedBlock(world,coal),"Coal should become eligible after copper exhaustion");
 }
 static void unloadedChunkNeverRead(){
  var world=new Level();var robot=new EntityRobotBase(world,p(14,0));
  BlockPos copper=p(16,0),coal=p(8,0);
  world.put(copper,ore("copper"));world.put(coal,ore("coal"));world.unloadXChunk(1);
  var miner=new BoardRobotMiner(robot);run(miner);
  check(coal.equals(target(miner)),"Unloaded neighboring chunk should be ignored, not loaded");
 }
 static void boundedAcrossTicks(){
  var world=new Level();world.setSolidByDefault(true);
  for(int x=0;x<1050;x++)world.put(new BlockPos(x,32,0),BlockState.AIR);
  BlockPos longGoal=new BlockPos(1050,32,0);
  world.put(longGoal,ore("copper"));
  var robot=new EntityRobotBase(world,p(0,0));
  var search=new AIRobotSearchNearestBlock(robot,(lvl,pos)->pos.equals(longGoal),1100);
  search.update();
  check(search.blockFound==null,"More than 1024 path nodes were processed in one tick");
  check(!search.success() || search.blockFound==null,"Premature search completion");
  search.update();
  check(longGoal.equals(search.blockFound.toBlockPos()),"Long corridor beyond first tick not found");
  check(search.path.size()==1049,"Wrong long-corridor path length "+search.path.size());
 }
 static void noTunneling(){
  var world=new Level();var robot=new EntityRobotBase(world,p(0,0));
  for(net.minecraft.core.Direction d:net.minecraft.core.Direction.values())
      world.put(p(0,0).relative(d),new BlockState(new Block("stone")));
  BlockPos ore=p(3,0);world.put(ore,ore("copper"));
  var search=new AIRobotSearchNearestBlock(robot,(lvl,pos)->pos.equals(ore),0);
  search.update();
  check(!search.success() && search.blockFound==null,"Robot tunneled through stone to select ore");
 }
 static void defaultBoardsUnchanged(){
  var world=new Level();var robot=new EntityRobotBase(world,p(0,0));
  var other=new BoardRobotGenericSearchBlock(robot) {
   @Override public boolean isExpectedBlock(Level level,BlockPos pos){return true;}
  };
  other.update();
  check(other.getDelegateAI() instanceof AIRobotSearchAndGotoBlock,"Generic search wrapper changed");
  check(other.getDelegateAI().getDelegateAI() instanceof AIRobotSearchBlock,
      "Non-miner worker should still use classic target-first search");
 }
 public static void main(String[] args){
  firstOreAcrossLoadedChunk();shortestPathNotEuclidean();continuationAndPersistence();
  unloadedChunkNeverRead();boundedAcrossTicks();noTunneling();defaultBoardsUnchanged();
  System.out.println("PASS: cross-chunk nearest, shortest navigable route, vein priority, save, loading, budget, no tunneling, other boards");
 }
}'''

REAL = [
    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java',
    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchNearestBlock.java',
    'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotGenericSearchBlock.java',
    'source-shared/src/main/java/buildcraft/robotics/boards/RobotOreVeinMemory.java',
]


def materialize_old_modern(code: str, old: bool) -> str:
    pattern = (r'//\? if <1\.20 \{\s*return robot\.level;\s*'
               r'//\?\} else \{\s*/\*\?\s*return robot\.level\(\);\s*\?\*/\s*//\?\}')
    replacement = 'return robot.level;' if old else 'return robot.level();'
    out, n = re.subn(pattern, replacement, code)
    if n != 1:
        raise AssertionError(f'Expected one Stonecutter level() branch, found {n}')
    return out


class NearestOreRuntimeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK required')
    def test_route_optimal_across_loaders(self):
        for name, source in vein_test.BOARDS.items():
            with self.subTest(platform=name), tempfile.TemporaryDirectory(prefix='bc-nearest-ore-') as tmp:
                dest = Path(tmp)
                inputs = []
                for rel, code in STUBS.items():
                    path = dest / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(code, encoding='utf-8')
                    inputs.append(str(path))
                for rel in REAL:
                    code = (ROOT / rel).read_text(encoding='utf-8')
                    if rel.endswith('AIRobotSearchNearestBlock.java'):
                        code = materialize_old_modern(code, name == 'forge')
                    path = dest / rel
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(code, encoding='utf-8')
                    inputs.append(str(path))
                rel = f'source-platforms/{"forge" if name=="forge" else "neoforge"}/src/main/java/buildcraft/robotics/boards/BoardRobotMiner.java'
                src = (ROOT / rel).read_text(encoding='utf-8')
                src = vein_test.materialize_board(src, name)
                path = dest / 'buildcraft/robotics/boards/BoardRobotMiner.java'
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(src, encoding='utf-8')
                inputs.append(str(path))
                classes = dest / 'classes'
                classes.mkdir()
                result = subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*inputs],
                                        capture_output=True,text=True,timeout=45)
                self.assertEqual(result.returncode,0,f'{name}: {result.stderr}')
                result = subprocess.run(['java','-cp',str(classes),'fixture.NearestOreFixture'],
                                        capture_output=True,text=True,timeout=35)
                self.assertEqual(result.returncode,0,f'{name}: {result.stderr}')
                self.assertIn('PASS:',result.stdout)
                print(f'{name}: {result.stdout.strip()}',flush=True)


if __name__ == '__main__':
    unittest.main()