"""Compile actual Stripes/Bomber boards on Forge and NeoForge and exercise
managed handoff, Gate-safe fallback, token fencing and release on AI abort.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest
from scripts.source_preprocessor import preprocess_text

ROOT=Path(__file__).resolve().parents[4]
STUBS={
'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z){
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
 public BlockPos offset(int dx,int dy,int dz){return new BlockPos(x+dx,y+dy,z+dz);}
}''',
'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;import java.util.*;
public class CompoundTag{
 private final Map<String,Object> map=new HashMap<>();
 public void put(String k,CompoundTag n){map.put(k,n);}
 public boolean contains(String k){return map.containsKey(k);}
 public CompoundTag getCompound(String k){return (CompoundTag)map.get(k);}
}''',
'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){}''',
'net/minecraft/world/level/block/state/BlockState.java': '''package net.minecraft.world.level.block.state;
public class BlockState{public final boolean air;public BlockState(boolean a){air=a;}
public boolean isAir(){return air;}}
''',
'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.*;import net.minecraft.world.entity.item.PrimedTnt;
public class Level{
 public boolean isClientSide;public int explosions;
 public int getMaxBuildHeight(){return 320;}
 public boolean isLoaded(BlockPos pos){return true;}
 public boolean isEmptyBlock(BlockPos pos){return getBlockState(pos).isAir();}
 public BlockState getBlockState(BlockPos pos){return new BlockState(pos.getY()!=70);}
 public boolean addFreshEntity(PrimedTnt entity){explosions++;return true;}
 public void playSound(Object p,double x,double y,double z,SoundEvents e,SoundSource source,float a,float b){}
}''',
'net/minecraft/world/entity/EquipmentSlot.java': '''package net.minecraft.world.entity;public enum EquipmentSlot{MAINHAND}''',
'net/minecraft/world/entity/item/PrimedTnt.java': '''package net.minecraft.world.entity.item;
import net.minecraft.world.level.Level;import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class PrimedTnt{
 private final double x,y,z;
 public PrimedTnt(Level level,double x,double y,double z,EntityRobotBase owner){this.x=x;this.y=y;this.z=z;}
 public void setFuse(int ticks){} public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
}''',
'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;public class Item{}''',
'net/minecraft/world/item/BlockItem.java': '''package net.minecraft.world.item;public class BlockItem extends Item{}''',
'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack{
 public static final ItemStack EMPTY=new ItemStack(null,0);
 private final Item item;private int count;
 public ItemStack(Item item){this(item,1);}
 public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public boolean isEmpty(){return item==null||count<=0;}
 public Item getItem(){return item;}
 public void remove(int howMany){count-=howMany;}
}''',
'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;
public class Items{public static final Item TNT=new BlockItem();}''',
'net/minecraft/sounds/SoundEvents.java': '''package net.minecraft.sounds;public class SoundEvents{public static final SoundEvents TNT_PRIMED=new SoundEvents();}''',
'net/minecraft/sounds/SoundSource.java': '''package net.minecraft.sounds;public enum SoundSource{BLOCKS}''',
'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
import net.minecraft.core.BlockPos;import net.minecraft.world.phys.Vec3;
public interface IZone{boolean contains(BlockPos pos);boolean contains(Vec3 pos);}''',
'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
public interface IStackFilter{boolean matches(net.minecraft.world.item.ItemStack stack);}''',
'buildcraft/lib/inventory/filter/ArrayStackOrListFilter.java': '''package buildcraft.lib.inventory.filter;
public class ArrayStackOrListFilter implements buildcraft.lib.internal.core.IStackFilter{
 private final net.minecraft.world.item.Item type;
 public ArrayStackOrListFilter(net.minecraft.world.item.ItemStack stack){type=stack.getItem();}
 public boolean matches(net.minecraft.world.item.ItemStack stack){return !stack.isEmpty()&&stack.getItem()==type;}
}''',
'buildcraft/lib/internal/core/BlockIndex.java': '''package buildcraft.lib.internal.core;
import net.minecraft.core.BlockPos;import net.minecraft.nbt.CompoundTag;
public class BlockIndex{
 private final BlockPos pos;
 public BlockIndex(BlockPos pos){this.pos=pos;}
 public BlockIndex(CompoundTag tag){pos=new BlockPos(0,70,0);}
 public BlockPos toBlockPos(){return pos;}
 public void writeTo(CompoundTag tag){}
}''',
'buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java': '''package buildcraft.robotics.internal.legacy.robots;
public class ResourceIdBlock{
 public ResourceIdBlock(net.minecraft.core.BlockPos pos){}
 public ResourceIdBlock(buildcraft.lib.internal.core.BlockIndex pos){}
}''',
'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
public class IRobotRegistry{
 public boolean isTaken(ResourceIdBlock pos){return false;}
 public void release(ResourceIdBlock pos){}
}''',
'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;import net.minecraft.core.BlockPos;import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;import buildcraft.lib.internal.area.IZone;
public class EntityRobotBase{
 public final Level level;public Level level(){return level;}
 public ItemStack held=new ItemStack(new BlockItem());public ItemStack[] cargo={new ItemStack(Items.TNT,2)};
 public IRobotRegistry registry=new IRobotRegistry();
 public EntityRobotBase(Level level){this.level=level;}
 public ItemStack getItemBySlot(EquipmentSlot slot){return held;}
 public IRobotRegistry getRegistry(){return registry;}
 public IZone getZoneToWork(){return null;}
 public Level getCommandSenderWorld(){return level;}
 public int getContainerSize(){return cargo.length;}
 public ItemStack getItem(int slot){return cargo[slot];}
 public ItemStack removeItem(int slot,int amount){cargo[slot].remove(amount);return cargo[slot];}
 public void setChanged(){} public void setItemActive(boolean v){}
 public void aimItemAt(BlockPos pos){}public void setItemInUse(ItemStack stack){}
 public BlockPos blockPosition(){return new BlockPos(0,71,0);}
 public double getX(){return 0;}public double getY(){return 71;}public double getZ(){return 0;}
}''',
'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot{
 public final EntityRobotBase robot;public AIRobot delegate;
 public boolean success=true;
 public AIRobot(EntityRobotBase robot){this.robot=robot;}
 public void update(){}public void end(){}public void onUnload(){}public void terminate(){end();}
 public void delegateAIEnded(AIRobot ai){}public void delegateAIAborted(AIRobot ai){}
 public boolean success(){return success;}public void setSuccess(boolean v){success=v;}
 public void startDelegateAI(AIRobot ai){delegate=ai;}
 public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(net.minecraft.nbt.CompoundTag nbt){}
 public void loadSelfFromNBT(net.minecraft.nbt.CompoundTag nbt){}
}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java': '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobot extends buildcraft.robotics.internal.legacy.robots.AIRobot{
 public RedstoneBoardRobot(buildcraft.robotics.internal.legacy.robots.EntityRobotBase robot){super(robot);}
 public RedstoneBoardRobotNBT getNBTHandler(){return new RedstoneBoardRobotNBT();}
}''',
'buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java': '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobotNBT{}''',
'buildcraft/robotics/BCRoboticsBoards.java': '''package buildcraft.robotics;
public class BCRoboticsBoards{
 public static Entry getByKey(String id){return new Entry();}
 public static class Entry{public buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT nbt(){return new buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT();}}
}''',
'buildcraft/robotics/ai/IBlockFilter.java': '''package buildcraft.robotics.ai;
public interface IBlockFilter{boolean matches(net.minecraft.world.level.Level world,net.minecraft.core.BlockPos pos);}''',
'buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.core.BlockPos;
import buildcraft.lib.internal.core.BlockIndex;
public class AIRobotSearchAndGotoBlock extends AIRobot{
 public final java.util.List<BlockPos> exclusive;
 public BlockIndex found;
 public AIRobotSearchAndGotoBlock(EntityRobotBase robot,boolean random,IBlockFilter filter){super(robot);exclusive=null;}
 public AIRobotSearchAndGotoBlock(EntityRobotBase robot,boolean random,IBlockFilter filter,java.util.List<BlockPos> target){super(robot);exclusive=target;}
 public BlockIndex getBlockFound(){return found;}
}''',
'buildcraft/robotics/ai/AIRobotStripesHandler.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.BlockIndex;
public class AIRobotStripesHandler extends AIRobot{
 public AIRobotStripesHandler(EntityRobotBase robot,BlockIndex target){super(robot);}
}''',
'buildcraft/robotics/ai/AIRobotFetchAndEquipItemStack.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotFetchAndEquipItemStack extends AIRobot{
 public AIRobotFetchAndEquipItemStack(EntityRobotBase robot,IStackFilter filter){super(robot);}
}''',
'buildcraft/robotics/ai/AIRobotGotoSleep.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoSleep extends AIRobot{public AIRobotGotoSleep(EntityRobotBase r){super(r);}}''',
'buildcraft/robotics/ai/AIRobotGotoStationAndUnload.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoStationAndUnload extends AIRobot{public AIRobotGotoStationAndUnload(EntityRobotBase r){super(r);}}''',
'buildcraft/robotics/ai/AIRobotGotoStationAndLoad.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotGotoStationAndLoad extends AIRobot{
 public AIRobotGotoStationAndLoad(EntityRobotBase r,IStackFilter f,int count,boolean allowPartial){super(r);}
}''',
'buildcraft/robotics/ai/AIRobotLoad.java': '''package buildcraft.robotics.ai; public class AIRobotLoad{}''',
'buildcraft/robotics/ai/AIRobotSearchRandomGroundBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.BlockIndex;
public class AIRobotSearchRandomGroundBlock extends AIRobot{
 public BlockIndex blockFound;
 public AIRobotSearchRandomGroundBlock(EntityRobotBase r,int range,IBlockFilter filter,IZone zone){super(r);}
}''',
'buildcraft/robotics/ai/AIRobotGotoBlock.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotGotoBlock extends AIRobot{
 public final int x,y,z;
 public AIRobotGotoBlock(EntityRobotBase r,int x,int y,int z){super(r);this.x=x;this.y=y;this.z=z;}
}''',
'buildcraft/robotics/scheduler/RobotAreaActionScheduler.java': '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;import net.minecraft.core.BlockPos;
public class RobotAreaActionScheduler{
 public record Assignment(BlockPos target,long token){}
 public static boolean managed;public static Assignment upcoming;
 public static int completed,failed;public static boolean validClaim=true;
 public static boolean hasController(EntityRobotBase r){return managed;}
 public static Assignment acquire(EntityRobotBase r){Assignment a=upcoming;upcoming=null;return a;}
 public static boolean owns(EntityRobotBase r,BlockPos p,long token){return validClaim;}
 public static void finished(EntityRobotBase r,BlockPos p,long token,boolean success){if(success)completed++;else failed++;}
}''',
'buildcraft/robotics/internal/api2/RobotAutomationSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
public class RobotAutomationSupport{
 public static boolean permitsBlock(EntityRobotBase r,Level world,BlockPos p,WorldOperationKind kind,OperationMode mode){return true;}
}''',
'buildcraft/api/v2/OperationMode.java': '''package buildcraft.api.v2;public enum OperationMode{SIMULATE,EXECUTE}''',
'buildcraft/api/v2/permission/WorldOperationKind.java': '''package buildcraft.api.v2.permission;
public enum WorldOperationKind{BLOCK_BREAK,BLOCK_PLACE,ITEM_USE}''',
}
FIXTURE=r'''
package fixture;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.Level;
import net.minecraft.world.item.*;import buildcraft.robotics.boards.*;
import buildcraft.robotics.ai.*;import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.scheduler.RobotAreaActionScheduler;
public class AreaBoardFixture {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void reset(){RobotAreaActionScheduler.managed=false;RobotAreaActionScheduler.upcoming=null;
  RobotAreaActionScheduler.validClaim=true;RobotAreaActionScheduler.completed=0;RobotAreaActionScheduler.failed=0;}
 public static void main(String...args){
  reset(); Level level=new Level();EntityRobotBase robot=new EntityRobotBase(level);
  var stripes=new BoardRobotStripes(robot);
  stripes.update();
  check(stripes.delegate instanceof AIRobotSearchAndGotoBlock,"autonomous Stripes did not search");
  check(((AIRobotSearchAndGotoBlock)stripes.delegate).exclusive==null,"autonomous Stripes had central only constraint");
  RobotAreaActionScheduler.managed=true;
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(2,71,3),101);
  stripes.update();
  check(stripes.delegate instanceof AIRobotSearchAndGotoBlock,"managed Stripes did not navigate");
  var guided=(AIRobotSearchAndGotoBlock)stripes.delegate;
  check(guided.exclusive!=null&&guided.exclusive.equals(java.util.List.of(new BlockPos(2,71,3))),"managed Stripes fell back to world scan");
  guided.found=new buildcraft.lib.internal.core.BlockIndex(new BlockPos(2,71,3));
  stripes.delegateAIEnded(guided);
  check(stripes.delegate instanceof AIRobotStripesHandler,"navigation did not hand off Stripes handler");
  check(stripes.processorAllowsExecution(new BlockPos(2,71,3)),"valid Stripes claim refused");
  RobotAreaActionScheduler.validClaim=false;
  check(!stripes.processorAllowsExecution(new BlockPos(2,71,3)),"stale Stripes claim accepted");
  RobotAreaActionScheduler.validClaim=true;
  stripes.delegateAIEnded(stripes.delegate);
  check(RobotAreaActionScheduler.completed==1,"Stripes completion not reported");
  stripes.end();
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(4,71,3),103);
  stripes.update();stripes.onUnload();
  check(!stripes.processorAllowsExecution(new BlockPos(4,71,3)),
    "unloaded managed Stripes retained permission to use items");
  check(RobotAreaActionScheduler.failed==1,"unload failed to release Stripes position");
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(3,71,3),102);
  stripes.update();stripes.delegateAIAborted(stripes.delegate);
  check(RobotAreaActionScheduler.failed==2,"Stripes aborted reservation not released");
  // Bomber must retain its conservative permission test and TNT inventory.
  reset();var bomber=new BoardRobotBomber(robot);
  bomber.update();check(bomber.delegate instanceof AIRobotSearchRandomGroundBlock,"autonomous Bomber not random");
  RobotAreaActionScheduler.managed=true;
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(2,70,5),201);
  bomber.update();
  check(bomber.delegate instanceof AIRobotGotoBlock,"managed Bomber not directed to reserved ground");
  var travel=(AIRobotGotoBlock)bomber.delegate;
  check(travel.y==90,"Bomber flight height changed");
  RobotAreaActionScheduler.validClaim=false;
  bomber.delegateAIEnded(travel);
  check(level.explosions==0&&RobotAreaActionScheduler.failed==1,"Bomber dropped TNT after losing lease");
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(2,70,5),202);
  RobotAreaActionScheduler.validClaim=true;
  bomber.update();bomber.delegateAIEnded(bomber.delegate);
  check(level.explosions==1&&RobotAreaActionScheduler.completed==1,"Bomber did not drop exactly one primed TNT");
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(3,70,5),203);
  bomber.update();bomber.delegateAIAborted(bomber.delegate);
  check(RobotAreaActionScheduler.failed==2,"Bomber aborted claim was not released");
  RobotAreaActionScheduler.upcoming=new RobotAreaActionScheduler.Assignment(new BlockPos(4,70,5),204);
  bomber.update();var moving=bomber.delegate;bomber.onUnload();bomber.delegateAIEnded(moving);
  check(level.explosions==1,"unloaded Bomber dropped TNT after losing processor reservation");
  System.out.println("PASS: actual Stripes/Bomber board handoff, no autonomous managed scan, lease fencing");
 }
}
'''

class AreaActionBoardTests(unittest.TestCase):
    def test_real_boards_on_version_families(self):
        versions=[('1.19.2','old','forge'),('1.20.1','old','forge'),
                  ('1.21.11','1.21.X','neoforge'),('26.3','26.X','neoforge'),
                  ('26.3','26.X','fabric')]
        for minecraft,family,platform in versions:
            with self.subTest(minecraft=minecraft,platform=platform),tempfile.TemporaryDirectory(prefix='bc-area-board-') as d:
                folder=Path(d);files=[]
                for rel,body in STUBS.items():
                    path=folder/rel;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body);files.append(str(path))
                rel_stripes='source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotStripes.java'
                rel_bomber=f'source-families/{"old" if family=="old" else "1.21.X"}/src/main/java/buildcraft/robotics/boards/BoardRobotBomber.java'
                for rel in (rel_stripes,rel_bomber):
                    path=folder/'buildcraft/robotics/boards'/Path(rel).name
                    path.parent.mkdir(parents=True,exist_ok=True)
                    path.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=minecraft,
                                          family=family,platform=platform,source=rel))
                    files.append(str(path))
                path=folder/'fixture/AreaBoardFixture.java';path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(FIXTURE);files.append(str(path))
                classes=folder/'classes';classes.mkdir()
                compile=subprocess.run(['javac','--release','17','-d',str(classes),*files],
                                       capture_output=True,text=True,timeout=45)
                self.assertEqual(compile.returncode,0,compile.stderr)
                run=subprocess.run(['java','-cp',str(classes),'fixture.AreaBoardFixture'],
                                   capture_output=True,text=True,timeout=20)
                self.assertEqual(run.returncode,0,run.stdout+run.stderr)
if __name__=='__main__':unittest.main()
