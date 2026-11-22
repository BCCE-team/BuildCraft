"""Compile and exercise real AIRobotLoad across legacy/modern source families."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from scripts.source_preprocessor import preprocess_text

ROOT=Path(__file__).resolve().parents[4]

STUBS={
 'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
public interface IStackFilter {boolean matches(net.minecraft.world.item.ItemStack stack);}''',
 'buildcraft/api/v2/OperationMode.java': '''package buildcraft.api.v2;
public enum OperationMode {SIMULATE,EXECUTE}''',
 'buildcraft/robotics/internal/api2/RobotAutomationSupport.java': '''package buildcraft.robotics.internal.api2;
import buildcraft.api.v2.OperationMode;
import buildcraft.robotics.internal.legacy.robots.*;
public class RobotAutomationSupport {
 public static boolean allowed=true;
 public static boolean permitsInventory(EntityRobotBase r,DockingStation s,boolean read,OperationMode mode){return allowed;}
}''',
 'buildcraft/robotics/statements/ActionRobotFilter.java': '''package buildcraft.robotics.statements;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.lib.internal.core.IStackFilter;
public class ActionRobotFilter {
 public static boolean canInteractWithItem(DockingStation s,IStackFilter f,Class<?> action){return true;}
}''',
 'buildcraft/robotics/statements/ActionStationProvideItems.java': '''package buildcraft.robotics.statements;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import net.minecraft.world.item.ItemStack;
public class ActionStationProvideItems {
 public static boolean canExtractItem(DockingStation s,ItemStack st){return !s.forbidden;}
}''',
 'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction {NORTH,SOUTH,EAST,WEST,UP,DOWN}''',
 'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack("",0);
 public String item;private int count;
 public ItemStack(String item,int count){this.item=item;this.count=count;}
 public boolean isEmpty(){return count==0;}public int getCount(){return count;}
 public ItemStack copy(){return new ItemStack(item,count);}
 public void setCount(int n){count=n;}
 public int getMaxStackSize(){return 64;}
 public boolean isStackable(){return true;}
 public void grow(int n){count+=n;}public void shrink(int n){count-=n;}
 public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return a.item.equals(b.item);}
 public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return isSameItemSameTags(a,b);}
}''',
 'net/minecraft/world/Container.java': '''package net.minecraft.world;
import net.minecraft.world.item.ItemStack;
public interface Container {
 int getContainerSize();ItemStack getItem(int slot);void setItem(int slot,ItemStack st);
 ItemStack removeItem(int slot,int n);void setChanged();int getMaxStackSize();
}''',
 'net/minecraft/world/WorldlyContainer.java': '''package net.minecraft.world;
import net.minecraft.core.Direction;import net.minecraft.world.item.ItemStack;
public interface WorldlyContainer extends Container {
 int[] getSlotsForFace(Direction side);boolean canTakeItemThroughFace(int slot,ItemStack st,Direction side);
}''',
 'buildcraft/robotics/internal/legacy/robots/AIRobot.java': '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {
 protected EntityRobotBase robot;public boolean success;public boolean ended;
 public AIRobot(EntityRobotBase r){robot=r;}
 public void update(){}public int getEnergyCost(){return 0;}
 protected void setSuccess(boolean b){success=b;}protected void terminate(){ended=true;}
}''',
 'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.Direction;import net.minecraft.world.Container;
public class DockingStation {
 public Container box;public boolean forbidden;
 public DockingStation(Container c){box=c;}
 public Container getItemInput(){return box;}public Direction getItemInputSide(){return Direction.NORTH;}
}''',
 'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.item.ItemStack;
public class EntityRobotBase {
 public final ItemStack[] inv={ItemStack.EMPTY,ItemStack.EMPTY};public DockingStation dock;
 public DockingStation getDockingStation(){return dock;}
 public int getContainerSize(){return inv.length;}public ItemStack getItem(int i){return inv[i];}
 public void setItem(int i,ItemStack st){inv[i]=st;}public int getMaxStackSize(){return 64;}
 public void setChanged(){}public boolean canPlaceItem(int i,ItemStack st){return true;}
}''',
 'buildcraft/robotics/scheduler/RobotBuilderMaterialScheduler.java': '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.*;
public class RobotBuilderMaterialScheduler {
 public static boolean valid=true;
 public static boolean owns(EntityRobotBase robot,DockingStation source,long token){return valid;}
}''',
}

FIXTURE='''package fixture;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.lib.internal.core.IStackFilter;
import net.minecraft.core.Direction;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
public class LoadFixture {
 static void ok(boolean v,String message){if(!v)throw new AssertionError(message);}
 static class Chest implements WorldlyContainer {
  final ItemStack[] stacks={new ItemStack("stone",4),new ItemStack("stone",5),new ItemStack("dirt",3)};
  public int changes; public int getContainerSize(){return stacks.length;}
  public ItemStack getItem(int slot){return stacks[slot];}public void setItem(int i,ItemStack s){stacks[i]=s;}
  public ItemStack removeItem(int i,int n){ItemStack s=stacks[i];int taken=Math.min(n,s.getCount());
    ItemStack result=new ItemStack(s.item,taken);s.shrink(taken);return result;}
  public void setChanged(){changes++;}public int getMaxStackSize(){return 64;}
  public int[] getSlotsForFace(Direction side){return new int[]{0,1,2};}
  public boolean canTakeItemThroughFace(int slot,ItemStack stack,Direction side){return slot!=2;}
 }
 public static void main(String[] args){
  Chest inventory=new Chest();DockingStation station=new DockingStation(inventory);
  EntityRobotBase robot=new EntityRobotBase();robot.dock=station;
  IStackFilter stone=st -> st.item.equals("stone");
  ok(AIRobotLoad.previewExtractableCount(robot,station,stone)==9,"stock across slots");
  station.forbidden=true;
  ok(AIRobotLoad.previewExtractableCount(robot,station,stone)==0,"gate permission ignored");
  station.forbidden=false;
  RobotAutomationSupport.allowed=false;
  ok(AIRobotLoad.previewExtractableCount(robot,station,stone)==0,"API permission ignored");
  RobotAutomationSupport.allowed=true;
  var ai=new AIRobotLoad(robot,stone,7,station,1);
  buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.valid=false;
  for(int i=0;i<12&&!ai.ended;i++)ai.update();
  ok(ai.ended&&!ai.success,"stale token loaded items");
  ok(AIRobotLoad.previewExtractableCount(robot,station,stone)==9,"invalid token mutated inventory");
  buildcraft.robotics.scheduler.RobotBuilderMaterialScheduler.valid=true;
  var load=new AIRobotLoad(robot,stone,7,station,2);
  for(int i=0;i<12&&!load.ended;i++)load.update();
  ok(load.ended&&load.success,"reserved source failed to load");
  ok(AIRobotLoad.previewExtractableCount(robot,station,stone)==2,"native load did not debit source");
  ok(robot.getItem(0).getCount()==7,"native load did not credit robot");
  station.forbidden=true;
  ok(!AIRobotLoad.load(robot,station,stone,2,false),"disabled station accepted transfer");
  System.out.println("PASS: real native loading, stock preview, component/sided checks, permissions, token revocation");
 }
}'''

class NativeBuilderLoadRuntimeTests(unittest.TestCase):
 def test_actual_native_load_guard_and_preview(self):
  for version,family,platform in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),('26.3','26.X','fabric')]:
   with self.subTest(version=version),tempfile.TemporaryDirectory(prefix='bc-native-load-') as tmp:
    root=Path(tmp);sources=[]
    for rel,s in STUBS.items():
     f=root/rel;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s);sources.append(str(f))
    rel=f'source-families/{"old" if family=="old" else "1.21.X"}/src/main/java/buildcraft/robotics/ai/AIRobotLoad.java'
    f=root/'buildcraft/robotics/ai/AIRobotLoad.java';f.parent.mkdir(parents=True,exist_ok=True)
    f.write_text(preprocess_text((ROOT/rel).read_text(),minecraft=version,family=family,platform=platform,source=rel));sources.append(str(f))
    f=root/'fixture/LoadFixture.java';f.parent.mkdir(parents=True,exist_ok=True);f.write_text(FIXTURE);sources.append(str(f))
    run=subprocess.run(['javac','--release','17','-d',str(root/'classes'),*sources],capture_output=True,text=True,timeout=35)
    self.assertEqual(run.returncode,0,run.stderr)
    run=subprocess.run(['java','-cp',str(root/'classes'),'fixture.LoadFixture'],capture_output=True,text=True,timeout=15)
    self.assertEqual(run.returncode,0,run.stderr+run.stdout)

if __name__=='__main__':unittest.main()
