"""Compile real carrier/delivery board and AI wrappers across source families with JVM API doubles."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.test_robot_processor_stage10_logistics import ROOT, STUBS, QUEUE
from scripts.source_preprocessor import preprocess_text

AIRobot = '''package buildcraft.robotics.internal.legacy.robots;
public class AIRobot {
 protected EntityRobotBase robot;
 public AIRobot(){}
 public AIRobot(EntityRobotBase robot){this.robot=robot;}
 public void start(){} public void update(){} public void end(){} public void onUnload(){}
 public boolean success(){return true;}
 public void delegateAIEnded(AIRobot ai){} public int getEnergyCost(){return 0;}
 protected void setSuccess(boolean success){}
 public boolean canLoadFromNBT(){return false;}
 public void writeSelfToNBT(net.minecraft.nbt.CompoundTag data){}
 public void loadSelfFromNBT(net.minecraft.nbt.CompoundTag data){}
 public void startDelegateAI(AIRobot ai){} public void terminate(){}
 public boolean isWithinWorkChain(AIRobot parent){return true;}
}'''

BOARD_STUB = '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot {
 public RedstoneBoardRobot(EntityRobotBase robot){super(robot);}
 public RedstoneBoardRobotNBT getNBTHandler(){return null;}
}'''

LOGISTICS_STUB = '''package buildcraft.robotics.scheduler;
import buildcraft.robotics.internal.legacy.robots.*;
import buildcraft.robotics.StackRequest;
public class RobotLogisticsScheduler {
 public record Dispatch(DockingStation station, StackRequest request, RobotLogisticsQueue.Key key, long leaseToken,
     DockingStation destination,net.minecraft.world.item.ItemStack itemPreview,long destinationToken) {}
 public record Destination(DockingStation station,long token) {}
 public static Destination recoverDestination(EntityRobotBase robot){return null;}
 public static boolean hasController(EntityRobotBase robot){return false;}
 public static boolean hasQueuedJob(EntityRobotBase robot){return false;}
 public static Dispatch acquire(EntityRobotBase robot){return null;}
 public static void finish(EntityRobotBase robot,RobotLogisticsQueue.Key key,long token,boolean success){}
 public static void failedDelivery(EntityRobotBase robot,RobotLogisticsQueue.Key key){}
 public static boolean ownsSource(EntityRobotBase robot,DockingStation station,
  RobotLogisticsQueue.Kind kind,long token){return true;}
 public static boolean ownsDestination(EntityRobotBase robot,DockingStation station,long token){return true;}
 public static void finishDestination(EntityRobotBase robot,DockingStation station,long token){}
}'''

class Stage10AICompilation(unittest.TestCase):
    def test_actual_carrier_fluid_delivery_and_wrapper_sources(self):
        targets = [
            ('1.19.2','old','forge','net.minecraftforge.fluids.FluidStack',
             'source-platforms/forge/src/main/java/buildcraft/robotics/boards/BoardRobotFluidCarrier.java'),
            ('1.21.11','1.21.X','neoforge','net.neoforged.neoforge.fluids.FluidStack',
             'source-platforms/neoforge/src/main/java/buildcraft/robotics/boards/BoardRobotFluidCarrier.java'),
            ('26.3','26.X','fabric','buildcraft.lib.fluid.FluidStack',
             'source-family-platforms/26.X/fabric/src/main/java/buildcraft/robotics/boards/BoardRobotFluidCarrier.java'),
        ]
        for version, family, platform, fluid_type, fluid_board in targets:
            with self.subTest(target=version+'-'+platform), tempfile.TemporaryDirectory(prefix='bc-stage10-ai-') as tmp:
                sources = STUBS.copy()
                sources['buildcraft/robotics/internal/legacy/robots/AIRobot.java']=AIRobot
                sources['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java']=BOARD_STUB
                sources['buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java']=(
                    'package buildcraft.robotics.internal.legacy.boards;public class RedstoneBoardRobotNBT{}')
                sources['buildcraft/robotics/scheduler/RobotLogisticsScheduler.java']=LOGISTICS_STUB
                sources['buildcraft/robotics/scheduler/RobotLogisticsQueue.java']=QUEUE.read_text()
                sources['buildcraft/robotics/BCRoboticsBoards.java']='''package buildcraft.robotics;
public class BCRoboticsBoards {
 public static Board getByKey(String key){return new Board();}
 public static class Board{
  public buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT nbt(){return null;}}
}'''
                sources['buildcraft/lib/inventory/filter/ArrayStackOrListFilter.java']='''package buildcraft.lib.inventory.filter;
import buildcraft.lib.internal.core.IStackFilter;import net.minecraft.world.item.ItemStack;
public class ArrayStackOrListFilter implements IStackFilter {
 public ArrayStackOrListFilter(ItemStack item){} public boolean matches(ItemStack item){return true;}}
'''
                sources['net/minecraft/nbt/CompoundTag.java']='''package net.minecraft.nbt;
public class CompoundTag {
 public boolean contains(String name){return false;}
 public CompoundTag getCompound(String name){return this;}
 public void put(String name,CompoundTag item){}
}'''
                sources['net/minecraft/world/item/ItemStack.java']=sources['net/minecraft/world/item/ItemStack.java'].replace(
                    'public class ItemStack{', 'public class ItemStack{public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return a.type.equals(b.type);}'
                    'public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.type.equals(b.type);}')
                sources['net/minecraft/world/level/Level.java']=sources['net/minecraft/world/level/Level.java'].replace(
                    'public long time=0;', 'public Object registryAccess(){return null;}public long time=0;')
                source_stack=sources['buildcraft/robotics/StackRequest.java']
                source_stack=source_stack.replace('public void setStation(DockingStation s){station=s;}',
                    '''public ItemStack getStack(){return stack;}
                    public DockingStation getStation(Level level){return station;}
                    public void writeToNBT(net.minecraft.nbt.CompoundTag tag){}
                    public void writeToNBT(net.minecraft.nbt.CompoundTag tag,Object registry){}
                    public static StackRequest loadFromNBT(net.minecraft.nbt.CompoundTag tag){return null;}
                    public static StackRequest loadFromNBT(net.minecraft.nbt.CompoundTag tag,Object registry){return null;}
                    public void setStation(DockingStation s){station=s;}''')
                sources['buildcraft/robotics/StackRequest.java']=source_stack
                sources[fluid_type.replace('.','/')+'.java']=(
                    'package '+fluid_type.rsplit('.',1)[0]+';public class FluidStack {'
                    'public boolean isEmpty(){return true;}public int getAmount(){return 0;}public '+fluid_type.rsplit('.',1)[1]+' copy(){return this;}}')
                sources['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java']=(
                    sources['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'].replace(
                        'public Fluid getFluidInTank(int slot){return new Fluid(fluid);}',
                        'public '+fluid_type+' getFluidInTank(int slot){return new '+fluid_type+'();}'))
                sources['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java']=(
                    sources['buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java'].replace(
                        'public boolean containsItems(){return cargo;}',
                        'public int getMaxStackSize(){return 64;}public boolean containsItems(){return cargo;}'))
                sources['buildcraft/robotics/ai/AIRobotLoad.java']='''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IStackFilter;
public class AIRobotLoad extends AIRobot {public static final int ANY_QUANTITY=-1;
 public AIRobotLoad(EntityRobotBase r,IStackFilter f,int q){super(r);}
 public static boolean load(EntityRobotBase r,DockingStation s,IStackFilter f,int q,boolean commit){return true;}
 public static net.minecraft.world.item.ItemStack previewCandidate(DockingStation s,IStackFilter f,int max){return new net.minecraft.world.item.ItemStack("ore",5);}
 public static java.util.List<net.minecraft.world.item.ItemStack> previewCandidates(DockingStation s,IStackFilter f,int max,int kinds){return java.util.List.of(new net.minecraft.world.item.ItemStack("ore",5));}
}'''
                sources['buildcraft/robotics/ai/AIRobotUnload.java']='''package buildcraft.robotics.ai;import buildcraft.robotics.internal.legacy.robots.*;import net.minecraft.world.item.ItemStack;
public class AIRobotUnload {public static int previewAcceptance(EntityRobotBase r,DockingStation s,ItemStack stack,boolean action){return 1;}public static boolean unload(EntityRobotBase r,DockingStation s,boolean doIt,boolean action){return true;}}'''
                sources['buildcraft/robotics/ai/AIRobotLoadFluids.java']='''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;import buildcraft.lib.internal.core.IFluidFilter;
public class AIRobotLoadFluids extends AIRobot {
 public AIRobotLoadFluids(EntityRobotBase r,IFluidFilter f){super(r);}
 public static int load(EntityRobotBase r,DockingStation s,IFluidFilter f,boolean commit){return 1;}
}'''
                sources['buildcraft/robotics/ai/AIRobotUnloadFluids.java']='''package buildcraft.robotics.ai;import buildcraft.robotics.internal.legacy.robots.*;
public class AIRobotUnloadFluids {public static int previewAcceptance(EntityRobotBase r,DockingStation s,Object fluid,boolean action){return 1000;}public static int unload(EntityRobotBase r,DockingStation s,boolean doIt,boolean action){return 1000;}}'''
                sources['buildcraft/lib/platform/storage/FluidStorage.java']='''package buildcraft.lib.platform.storage;public class FluidStorage<T> {public T drain(int a,boolean simulate){return null;}}'''
                sources['buildcraft/robotics/internal/legacy/robots/DockingStation.java']=sources['buildcraft/robotics/internal/legacy/robots/DockingStation.java'].replace(
                    'public boolean isInitialized(){return true;}',
                    'public buildcraft.lib.platform.storage.FluidStorage<'+fluid_type+'> getFluidInput(){return new buildcraft.lib.platform.storage.FluidStorage();}'
                    'public boolean isInitialized(){return true;}')
                mock_ai={
                  'AIRobotGotoStation': 'public AIRobotGotoStation(EntityRobotBase r,DockingStation s){super(r);}',
                  'AIRobotGotoStationToLoad': 'public AIRobotGotoStationToLoad(EntityRobotBase r,IStackFilter f,int n,boolean b){super(r);}',
                  'AIRobotGotoStationToLoadFluids': 'public AIRobotGotoStationToLoadFluids(EntityRobotBase r,IFluidFilter f){super(r);}',
                  'AIRobotGotoSleep': 'public AIRobotGotoSleep(EntityRobotBase r){super(r);}',
                  'AIRobotGotoStationAndUnload': 'public AIRobotGotoStationAndUnload(EntityRobotBase r){super(r);}'
                      'public AIRobotGotoStationAndUnload(EntityRobotBase r,boolean b){super(r);}'
                      'public AIRobotGotoStationAndUnload(EntityRobotBase r,DockingStation s,boolean b){super(r);}',
                  'AIRobotGotoStationAndUnloadFluids': 'public AIRobotGotoStationAndUnloadFluids(EntityRobotBase r,boolean b){super(r);}'
                      'public AIRobotGotoStationAndUnloadFluids(EntityRobotBase r,DockingStation s,boolean b){super(r);}',
                  'AIRobotDeliverRequested': 'public AIRobotDeliverRequested(EntityRobotBase r,buildcraft.robotics.StackRequest req){super(r);}',
                  'AIRobotSearchStackRequest': 'public buildcraft.robotics.StackRequest request;'
                      'public AIRobotSearchStackRequest(EntityRobotBase r,IStackFilter f,java.util.Collection<net.minecraft.world.item.ItemStack> black){super(r);}',
                }
                for name, methods in mock_ai.items():
                    sources[f'buildcraft/robotics/ai/{name}.java']=(
                        'package buildcraft.robotics.ai;'
                        'import buildcraft.robotics.internal.legacy.robots.*;'
                        'import buildcraft.lib.internal.core.*;'
                        f'public class {name} extends AIRobot {{ {methods} }}')
                real_files=[
                    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotGotoStationAndLoad.java',
                    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotGotoStationAndLoadFluids.java',
                    'source-shared/src/main/java/buildcraft/robotics/ai/AIRobotWaitForLogistics.java',
                    'source-shared/src/main/java/buildcraft/robotics/boards/BoardRobotCarrier.java',
                    fluid_board,
                    f'source-families/{"old" if version=="1.19.2" else "1.21.X"}/src/main/java/buildcraft/robotics/boards/BoardRobotDelivery.java',
                ]
                for relative in real_files:
                    file=ROOT/relative
                    package_path='buildcraft/robotics/'+relative.split('/buildcraft/robotics/',1)[1]
                    sources[package_path]=preprocess_text(file.read_text(),
                        minecraft=version,family=family,platform=platform,source=relative)
                out=Path(tmp)
                java_files=[]
                for name,content in sources.items():
                    file=out/name
                    file.parent.mkdir(parents=True,exist_ok=True)
                    file.write_text(content)
                    java_files.append(str(file))
                classes=out/'classes'
                classes.mkdir()
                result=subprocess.run(['javac','--release','17','-d',str(classes),*java_files],
                    capture_output=True,text=True,timeout=45)
                self.assertEqual(result.returncode,0,result.stderr)
