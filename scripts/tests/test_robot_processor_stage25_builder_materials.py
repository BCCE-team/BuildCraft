"""JVM verification for Stage 25 Builder material supply reservations.

No Minecraft distribution is needed; production lease book and production
material scheduler run against deliberately small inventory/docking stubs.
"""
from __future__ import annotations
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.source_preprocessor import preprocess_text

ROOT = Path(__file__).resolve().parents[2]
LEASE = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderMaterialLeaseBook.java'
SCHEDULER = ROOT / 'source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderMaterialScheduler.java'

STUBS = {
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {
 public long asLong(){return ((long)x&0x3ffffffL)<<38|((long)z&0x3ffffffL)<<12|((long)y&0xfffL);}
 public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
}''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import java.util.*;import net.minecraft.core.BlockPos;
public class Level {
 public long tick;public boolean loaded=true;
 public long getGameTime(){return tick;}
 public boolean hasChunkAt(BlockPos pos){return loaded;}
}''',
    'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
import net.minecraft.core.BlockPos;
public class BlockEntity {
 private final BlockPos pos;
 public BlockEntity(BlockPos p){pos=p;}
 public BlockPos getBlockPos(){return pos;}
}''',
    'net/minecraft/world/Container.java': '''package net.minecraft.world;
import java.util.*;import net.minecraft.world.item.ItemStack;
public interface Container {java.util.List<ItemStack> stacks();}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
 public String item,tag; public int count;
 public ItemStack(String item,String tag,int n){this.item=item;this.tag=tag;this.count=n;}
 public Object getItem(){return item;} public int getCount(){return count;}
 public void setCount(int n){count=n;} public boolean isEmpty(){return count<=0;}
 public ItemStack copy(){return new ItemStack(item,tag,count);}
 public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return a.item.equals(b.item)&&a.tag.equals(b.tag);}
 public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return isSameItemSameTags(a,b);}
}''',
    'buildcraft/lib/internal/area/IZone.java': '''package buildcraft.lib.internal.area;
public interface IZone {boolean contains(net.minecraft.world.phys.Vec3 pos);}''',
    'net/minecraft/world/phys/Vec3.java': '''package net.minecraft.world.phys;
public record Vec3(double x,double y,double z){}''',
    'buildcraft/lib/internal/core/IStackFilter.java': '''package buildcraft.lib.internal.core;
public interface IStackFilter {boolean matches(net.minecraft.world.item.ItemStack stack);}''',
    'buildcraft/lib/inventory/filter/ArrayStackFilter.java': '''package buildcraft.lib.inventory.filter;
import net.minecraft.world.item.ItemStack;
public class ArrayStackFilter implements buildcraft.lib.internal.core.IStackFilter {
 private final ItemStack target;
 public ArrayStackFilter(ItemStack stack){target=stack;}
 public boolean matches(ItemStack stack){return ItemStack.isSameItemSameComponents(target,stack);}
}''',
    'buildcraft/robotics/RobotTrace.java': '''package buildcraft.robotics;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotTrace {
 public static boolean enabled(){return false;}
 public static void event(EntityRobotBase robot,String name,String message,Object... args){}
}''',
    'buildcraft/robotics/internal/legacy/robots/DockingStation.java': '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.BlockPos;import net.minecraft.world.Container;import net.minecraft.world.level.Level;
public class DockingStation {
 public final Level world;public final BlockPos pos;public final Container inventory;
 public boolean taken=false;public boolean forbidden=false;public boolean initial=true;
 public DockingStation(Level world,BlockPos pos,Container inventory){this.world=world;this.pos=pos;this.inventory=inventory;}
 public Level level(){return world;}public boolean isInitialized(){return initial;}
 public boolean isTaken(){return taken;}public long robotIdTaking(){return 999;}
 public int x(){return pos.x();}public int y(){return pos.y();}public int z(){return pos.z();}
 public Index index(){return new Index(pos);}
 public Container getItemInput(){return inventory;}
 public record Index(BlockPos pos){public BlockPos toBlockPos(){return pos;}}
}''',
    'buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java': '''package buildcraft.robotics.internal.legacy.robots;
import java.util.*;
public class IRobotRegistry {
 public final java.util.List<DockingStation> stations=new java.util.ArrayList<>();
 public Iterable<DockingStation> getStations(){return stations;}
}''',
    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.lib.internal.area.IZone;import net.minecraft.world.level.Level;
public class EntityRobotBase {
 public final Level level;public final IRobotRegistry registry;
 public long id, panel; public IZone zone;
 public int x,z;
 public EntityRobotBase(Level w,IRobotRegistry reg,long id,long panel){level=w;registry=reg;this.id=id;this.panel=panel;}
 public Level level(){return level;}public long getRobotId(){return id;}
 public IRobotRegistry getRegistry(){return registry;}
 public IZone getZoneToLoadUnload(){return zone;}
 public double getX(){return x;}public double getY(){return 65;}public double getZ(){return z;}
}''',
    'buildcraft/robotics/scheduler/RobotProcessorWorkScheduler.java': '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotProcessorWorkScheduler {
 public static Long builderControllerPanelId(EntityRobotBase r){return r.panel==-1?null:r.panel;}
}''',
    'buildcraft/robotics/statements/ActionStationForbidRobot.java': '''package buildcraft.robotics.statements;
import buildcraft.robotics.internal.legacy.robots.*;
public class ActionStationForbidRobot {
 public static boolean isForbidden(DockingStation station,EntityRobotBase robot){return station.forbidden;}
}''',
    'buildcraft/robotics/ai/AIRobotLoad.java': '''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.lib.internal.core.IStackFilter;
import net.minecraft.world.item.ItemStack;
public class AIRobotLoad {
 public static int previewExtractableCount(EntityRobotBase r,DockingStation s,IStackFilter filter){
  int n=0;for(ItemStack st:s.getItemInput().stacks())if(filter.matches(st))n+=st.getCount();return n;
 }
 public static boolean load(EntityRobotBase r,DockingStation s,IStackFilter filter,int quantity,boolean doLoad){
  return previewExtractableCount(r,s,filter)>0;
 }
}''',
}

SOURCE = '''package fixture;
import java.util.*;
import buildcraft.robotics.scheduler.*;
import buildcraft.robotics.internal.legacy.robots.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
public class MaterialFixture {
 static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 static ItemStack item(String id,String nbt,int n){return new ItemStack(id,nbt,n);}
 static class Chest extends BlockEntity implements Container {
  final List<ItemStack> contents=new ArrayList<>();
  Chest(BlockPos p){super(p);}
  public List<ItemStack> stacks(){return contents;}
 }
 static EntityRobotBase robot(Level level,IRobotRegistry reg,long id,long panel){return new EntityRobotBase(level,reg,id,panel);}
 public static void main(String[] args){
  // A pure material ledger handles overlap, partial stock, panel cleanup and stale tokens.
  RobotBuilderMaterialLeaseBook<Long,String> book=new RobotBuilderMaterialLeaseBook<>();
  var first=book.reserve(10L,"stone",0L,1,9,7,0,100);
  check(first!=null&&first.quantity()==7,"initial reservation");
  var second=book.reserve(10L,"stone",2L,2,9,7,0,100);
  check(second!=null&&second.quantity()==2,"same source must subtract earlier promise");
  check(book.reserve(10L,"stone",3L,3,9,4,0,100)==null,"oversubscribed physical stock");
  check(book.reserve(10L,"dirt",3L,3,5,4,0,100)!=null,"unrelated item should not compete");
  check(!book.release(1,second.token()),"stale token released another robot");
  check(book.ownsAt(1,first.token(),10L,1),"active ownership");
  check(!book.ownsAt(1,first.token(),11L,1),"cross-source token");
  book.releasePanel(0L);
  check(!book.owns(1,first.token(),1),"panel at world origin was not cleared");
  check(book.owns(2,second.token(),1),"another panel was cleared");
  check(book.available(10L,"stone",1,1)==0,"physical stock shrink should not over promise");
  book.expire(101);
  check(book.size()==0,"TTL cleanup leaked promises");
  // Real scheduler: supply is shared between panels and between stations pointing at one chest.
  Level world=new Level();IRobotRegistry registry=new IRobotRegistry();
  Chest chest=new Chest(new BlockPos(1,65,1));
  chest.contents.add(item("stone","",4));chest.contents.add(item("stone","",5));
  chest.contents.add(item("stone","enchanted",5));
  DockingStation close=new DockingStation(world,new BlockPos(0,65,1),chest);
  DockingStation sameInput=new DockingStation(world,new BlockPos(2,65,1),chest);
  registry.stations.add(close);registry.stations.add(sameInput);
  EntityRobotBase a=robot(world,registry,4,0), b=robot(world,registry,5,2), c=robot(world,registry,6,3);
  var aClaim=RobotBuilderMaterialScheduler.allocate(a,item("stone","",7));
  check(aClaim!=null&&aClaim.quantity()==7&&aClaim.station()==close,"first robot must get nearest 7");
  var bClaim=RobotBuilderMaterialScheduler.allocate(b,item("stone","",6));
  check(bClaim!=null&&bClaim.quantity()==2,"second panel must see 2 remaining across both station faces");
  check(RobotBuilderMaterialScheduler.allocate(c,item("stone","",1))==null,"shared chest was over-promised");
  check(RobotBuilderMaterialScheduler.owns(a,close,aClaim.token()),"valid lease rejected");
  check(RobotBuilderMaterialScheduler.owns(a,sameInput,aClaim.token()),"same underlying chest should share identity");
  check(!RobotBuilderMaterialScheduler.owns(a,close,bClaim.token()),"token theft");
  var enchanted=RobotBuilderMaterialScheduler.allocate(c,item("stone","enchanted",4));
  check(enchanted!=null&&enchanted.quantity()==4,"distinct data component was incorrectly aggregated");
  RobotBuilderMaterialScheduler.release(a,aClaim.token());
  check(!RobotBuilderMaterialScheduler.owns(a,close,aClaim.token()),"cancel leaked stock");
  var again=RobotBuilderMaterialScheduler.allocate(a,item("stone","",6));
  check(again!=null&&again.quantity()==6,"cancel did not free stock");
  RobotBuilderMaterialScheduler.releasePanel(world,0L);
  check(!RobotBuilderMaterialScheduler.owns(a,close,again.token()),"removed origin panel left route active");
  check(RobotBuilderMaterialScheduler.owns(b,close,bClaim.token()),"panel removal poisoned neighboring panel");
  RobotBuilderMaterialScheduler.release(b,bClaim.token());
  RobotBuilderMaterialScheduler.release(c,enchanted.token());
  b.panel=-1;
  check(RobotBuilderMaterialScheduler.allocate(b,item("stone","",1))==null,"unmanaged robot should be autonomous");
  b.panel=2;
  close.forbidden=true;sameInput.forbidden=true;
  check(RobotBuilderMaterialScheduler.allocate(b,item("stone","",1))==null,"station forbid gate bypassed");
  close.forbidden=false;sameInput.forbidden=false;
  b.zone=v -> false;
  check(RobotBuilderMaterialScheduler.allocate(b,item("stone","",1))==null,"load/unload zone bypassed");
  b.zone=null;
  world.loaded=false;
  check(RobotBuilderMaterialScheduler.allocate(b,item("stone","",1))==null,"unloaded chunk was probed");
  world.loaded=true;
  var later=RobotBuilderMaterialScheduler.allocate(b,item("stone","",1));
  check(later!=null,"stock did not return after chunk load");
  world.tick=2401;
  check(!RobotBuilderMaterialScheduler.owns(b,close,later.token()),"expired lease allowed stale load");
  Level other=new Level();IRobotRegistry otherRegistry=new IRobotRegistry();
  Chest otherChest=new Chest(new BlockPos(1,65,1));otherChest.contents.add(item("stone","",1));
  DockingStation otherStation=new DockingStation(other,new BlockPos(0,65,1),otherChest);
  otherRegistry.stations.add(otherStation);
  var foreign=robot(other,otherRegistry,8,0);
  check(RobotBuilderMaterialScheduler.allocate(foreign,item("stone","",1))!=null,"different dimension reused old lease");
  System.out.println("PASS: partial stock, cross-panel, source inventory identity, permissions, TTL, tags, world isolation");
 }
}'''

class BuilderMaterialRuntimeTests(unittest.TestCase):
    def test_real_jvm_material_ledger_and_coordinator(self):
        for version, family, loader in [('1.19.2','old','forge'),('1.21.11','1.21.X','neoforge'),
                                         ('26.3','26.X','neoforge'),('26.3','26.X','fabric')]:
            with self.subTest(version=version, loader=loader), tempfile.TemporaryDirectory(prefix='bc-material-') as td:
                folder=Path(td)
                sources=[]
                for rel,code in STUBS.items():
                    p=folder/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code);sources.append(str(p))
                sources.append(str(LEASE))
                rel='source-shared/src/main/java/buildcraft/robotics/scheduler/RobotBuilderMaterialScheduler.java'
                s=preprocess_text(SCHEDULER.read_text(),minecraft=version,family=family,platform=loader,source=rel)
                p=folder/'buildcraft/robotics/scheduler/RobotBuilderMaterialScheduler.java'
                p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s);sources.append(str(p))
                f=folder/'fixture/MaterialFixture.java';f.parent.mkdir(parents=True,exist_ok=True);f.write_text(SOURCE)
                sources.append(str(f))
                result=subprocess.run(['javac','--release','17','-d',str(folder/'classes'),*sources],
                                      capture_output=True,text=True,timeout=35)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                result=subprocess.run(['java','-cp',str(folder/'classes'),'fixture.MaterialFixture'],
                                      capture_output=True,text=True,timeout=15)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
                self.assertIn('PASS:',result.stdout)

    def test_real_ai_checks_material_token_at_transfer_and_cancel(self):
        ai = (ROOT/'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotGotoStationAndLoad.java').read_text()
        self.assertIn('RobotBuilderMaterialScheduler.release(robot, sourceToken)',ai)
        self.assertIn('RobotBuilderMaterialScheduler.owns(',ai)
        for family in ('old','1.21.X'):
            load=(ROOT/f'source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotLoad.java').read_text()
            board=(ROOT/f'source-families/{family}/src/main/java/buildcraft/robotics/boards/BoardRobotBuilder.java').read_text()
            self.assertIn('builderMaterialToken',load)
            self.assertIn('previewExtractableCount(',load)
            self.assertIn('RobotBuilderMaterialScheduler.allocate(robot, stack)',board)
            self.assertIn('RobotBuilderMaterialScheduler.releaseRobot(robot)',board)
            self.assertIn('new ArrayStackFilter(stack)',board)

if __name__=='__main__':unittest.main()
