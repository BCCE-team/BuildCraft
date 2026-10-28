#!/usr/bin/env python3
"""Compile the real AI task adapter and classic scheduler against isolated Minecraft stubs."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "source-shared/src/main/java"
JAVA = [
    *(f"buildcraft/api/v2/persistence/{n}.java" for n in (
        "ApiCodec", "CodecResult", "EncodedPayload", "KnownPayload", "OpaqueData",
        "PayloadCopier", "PayloadResolution", "PersistentType", "SchemaMigration",
        "UnknownPayload", "UnknownPayloadReason")),
    "buildcraft/api/v2/registry/RegistrationContext.java",
    "buildcraft/lib/internal/api/v2/persistence/PersistenceRegistryBuilder.java",
    "buildcraft/lib/internal/api/v2/persistence/PersistenceRegistrySnapshot.java",
    # Compile the production balance layer, not an approximation: the classic
    # robot AI now consults BalanceConfig when calculating movement/energy.
    "buildcraft/lib/balance/BalanceIni.java",
    "buildcraft/lib/balance/BalanceConfig.java",
    "buildcraft/api/v2/OperationMode.java",
    *(f"buildcraft/api/v2/robot/{n}.java" for n in (
        "RobotTask", "RobotTaskResult", "RobotTaskLifecycle", "RobotTaskContext",
        "RobotTaskType", "RobotResource", "RobotResourceLease", "RobotResourceRequest",
        "RobotResourceAcquirer", "RobotResourceType")),
    "buildcraft/robotics/internal/legacy/robots/RobotManager.java",
    "buildcraft/robotics/internal/legacy/robots/AIRobot.java",
    "buildcraft/robotics/ai/AIRobotMain.java",
    "buildcraft/robotics/internal/api2/ApiTaskAI.java",
]
STUBS = {
    "javax/annotation/Nullable.java": "package javax.annotation; public @interface Nullable {}",
    "net/minecraft/resources/ResourceLocation.java": '''package net.minecraft.resources;
public record ResourceLocation(String namespace,String path){
 public static ResourceLocation tryParse(String s){if(s==null||!s.contains(":"))return null;String[] parts=s.split(":",2);
 if(parts[0].isEmpty()||parts[1].isEmpty())return null;return new ResourceLocation(parts[0],parts[1]);}
 public String toString(){return namespace+":"+path;}
}''',
    "net/minecraft/core/BlockPos.java": 'package net.minecraft.core; public record BlockPos(int x,int y,int z) {}',
    "net/minecraft/core/Direction.java": '''package net.minecraft.core;
public enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST;
 public int getStepX(){return 0;}public int getStepY(){return 0;}public int getStepZ(){return 0;}
}''',
    "net/minecraft/world/item/ItemStack.java": 'package net.minecraft.world.item; public class ItemStack {}',
    "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
public class Level {public boolean isClientSide(){return false;}}
''',
    "net/minecraft/server/level/ServerLevel.java": 'package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}',
    "net/minecraft/nbt/ByteArrayTag.java": '''package net.minecraft.nbt;
public class ByteArrayTag {private final byte[] data;public ByteArrayTag(byte[] value){data=value.clone();}
public byte[] getAsByteArray(){return data.clone();}}
''',
    "net/minecraft/nbt/CompoundTag.java": '''package net.minecraft.nbt;
import java.util.*;
public class CompoundTag {
 private final Map<String,Object> values=new HashMap<>();
 public Object get(String name){return values.get(name);}
 public void put(String name,Object value){values.put(name,value);}
 public void putString(String name,String value){values.put(name,value);}
 public String getString(String name){return (String)values.getOrDefault(name,"");}
 public boolean contains(String name){return values.containsKey(name);}
 public CompoundTag getCompound(String name){return (CompoundTag)values.getOrDefault(name,new CompoundTag());}
 public void putByteArray(String name,byte[] value){values.put(name,new ByteArrayTag(value));}
}''',
    "buildcraft/lib/internal/debug/BCLog.java": '''package buildcraft.lib.internal.debug;
public class BCLog {public static final Logger logger=new Logger();
public static class Logger {public void warn(String message,Object... args){}public void error(String msg,Object... args){}
 public void info(String message,Object... args){}}
}''',
    "buildcraft/api/v2/registry/RegistryKey.java": 'package buildcraft.api.v2.registry; public record RegistryKey<T>(String name) {}',
    "buildcraft/api/v2/registry/ApiRegistry.java": '''package buildcraft.api.v2.registry;
import java.util.*;import net.minecraft.resources.ResourceLocation;
public class ApiRegistry<T> {
 private final Map<ResourceLocation,T> entries=new LinkedHashMap<>();
 public T get(ResourceLocation id){return entries.get(id);}
 public void register(ResourceLocation id,T type){entries.put(id,type);}
 public Collection<T> values(){return entries.values();}
 public void clear(){entries.clear();}
}''',
    "buildcraft/api/v2/service/ServiceKey.java": 'package buildcraft.api.v2.service; public record ServiceKey<T>(String id) {}',
    "buildcraft/api/v2/BuildCraftRegistries.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.*;import buildcraft.api.v2.robot.*;
public class BuildCraftRegistries {
 public static final RegistryKey<RobotTaskType<?>> ROBOT_TASK_TYPES=new RegistryKey<>("robot_tasks");
 public static final RegistryKey<RobotResourceType<?>> ROBOT_RESOURCE_TYPES=new RegistryKey<>("robot_resources");
}''',
    "buildcraft/api/v2/BuildCraftServices.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.service.*;import buildcraft.api.v2.robot.*;
public class BuildCraftServices {public static final ServiceKey<RobotService> ROBOTS=new ServiceKey<>("robots");
 public static final ServiceKey<buildcraft.api.v2.request.RequestService> REQUESTS=new ServiceKey<>("requests");}
''',
    "buildcraft/api/v2/BuildCraftApi.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.registry.*;import buildcraft.api.v2.service.*;import java.util.*;
public class BuildCraftApi {
 private static final Map<RegistryKey<?>,ApiRegistry<?>> registries=new HashMap<>();
 private static final Map<ServiceKey<?>,Object> services=new HashMap<>();
 @SuppressWarnings("unchecked") public static <T> ApiRegistry<T> registry(RegistryKey<T> id){return (ApiRegistry<T>)registries.computeIfAbsent(id,x->new ApiRegistry<>());}
 @SuppressWarnings("unchecked") public static <T> T service(ServiceKey<T> id){return (T)services.get(id);}
 public static <T> void bind(ServiceKey<T> key,T value){services.put(key,value);}
}''',
    "buildcraft/api/v2/request/RequestLedger.java": '''package buildcraft.api.v2.request;
public class RequestLedger { public static class PromiseView {} public static class DeliveryResult {} }
''',
    "buildcraft/api/v2/request/RequestService.java": '''package buildcraft.api.v2.request;
import buildcraft.api.v2.OperationMode;import buildcraft.api.v2.permission.AutomationActor;
import java.util.*;import net.minecraft.world.item.ItemStack;import net.minecraft.world.level.Level;
import net.minecraft.core.*;import net.minecraft.resources.ResourceLocation;
public interface RequestService {
 Optional<RequestLedger.PromiseView> reserve(Level level, BlockPos origin, BlockPos destination,
 Direction side, ResourceLocation requestId, UUID reservationId, int quantity, AutomationActor actor, long durationTicks);
 boolean cancel(Level level, BlockPos origin, BlockPos destination, Direction side,
 ResourceLocation requestId, AutomationActor actor, String reason);
 RequestLedger.DeliveryResult deliver(Level level, BlockPos origin, BlockPos destination, Direction side,
 UUID reservationId, UUID deliveryId, ItemStack stack, AutomationActor actor, OperationMode mode);
}
''',
    "buildcraft/api/v2/permission/AutomationActor.java": 'package buildcraft.api.v2.permission;public record AutomationActor() {}',
    "buildcraft/api/v2/robot/RobotHandle.java": 'package buildcraft.api.v2.robot; public interface RobotHandle {long id();}',
    "buildcraft/api/v2/robot/RobotService.java": '''package buildcraft.api.v2.robot;
import java.util.*;import net.minecraft.world.level.Level;
public interface RobotService {Optional<RobotResourceLease> acquire(Level level,long id,RobotResourceRequest request);}
''',
    "buildcraft/robotics/internal/legacy/robots/IRobotRegistryProvider.java": 'package buildcraft.robotics.internal.legacy.robots; public interface IRobotRegistryProvider {}',
    "buildcraft/robotics/internal/legacy/robots/ResourceId.java": 'package buildcraft.robotics.internal.legacy.robots; public class ResourceId {}',
    "buildcraft/robotics/internal/legacy/robots/ResourceIdBlock.java": 'package buildcraft.robotics.internal.legacy.robots; public class ResourceIdBlock extends ResourceId {}',
    "buildcraft/robotics/internal/legacy/robots/ResourceIdRequest.java": 'package buildcraft.robotics.internal.legacy.robots; public class ResourceIdRequest extends ResourceId {}',
    "buildcraft/robotics/internal/legacy/robots/ResourceIdApiRequest.java": 'package buildcraft.robotics.internal.legacy.robots; public class ResourceIdApiRequest extends ResourceId {}',
    "buildcraft/robotics/internal/legacy/robots/DockingStation.java": '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.core.Direction;
public class DockingStation {public boolean providesPower(){return false;}
 public Direction side(){return Direction.NORTH;}
 public int x(){return 0;}public int y(){return 0;}public int z(){return 0;}}
''',
    "buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java": '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import net.minecraft.world.level.Level;
public class EntityRobotBase {
 public static final int SAFETY_ENERGY=2000,SHUTDOWN_ENERGY=500,MAX_ENERGY=1000000;
 public static final class Battery{public long extracted;public boolean extractPower(long power){extracted+=power;return true;}}
 private final Battery battery=new Battery();private final Level level=new net.minecraft.server.level.ServerLevel();
 private AIRobot main;private final long id;
 public EntityRobotBase(long id){this.id=id;}
 public void main(AIRobot ai){main=ai;}
 public AIRobot getActiveAI(){return main==null?null:main.getActiveAI();}
 public long getRobotId(){return id;}
 public Level level(){return level;}
 public Battery getBattery(){return battery;}
 public int getEnergy(){return 500000;}
 public DockingStation getDockingStation(){return null;}
 public DockingStation getLinkedStation(){return null;}
 public double getX(){return 0;}public double getY(){return 0;}public double getZ(){return 0;}
 public RedstoneBoardRobot getBoard(){return null;}
}''',
    "buildcraft/robotics/internal/api2/RobotServiceImpl.java": '''package buildcraft.robotics.internal.api2;
import buildcraft.api.v2.robot.*;import buildcraft.api.v2.permission.*;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
public class RobotServiceImpl {
 public static RobotHandle view(EntityRobotBase robot){return ()->robot.getRobotId();}
 public static AutomationActor actorForTask(EntityRobotBase robot){return new AutomationActor();}
 public static boolean trackLease(RobotResourceLease lease){return ApiTaskAI.trackIfExecuting(lease);}
}''',
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobotNBT.java": '''package buildcraft.robotics.internal.legacy.boards;
public class RedstoneBoardRobotNBT {public String getID(){return "boardRobotMiner";}}
''',
    "buildcraft/robotics/internal/legacy/boards/RedstoneBoardRobot.java": '''package buildcraft.robotics.internal.legacy.boards;
import buildcraft.robotics.internal.legacy.robots.*;
public class RedstoneBoardRobot extends AIRobot {
 public RedstoneBoardRobot(EntityRobotBase r){super(r);}
 public RedstoneBoardRobotNBT getNBTHandler(){return new RedstoneBoardRobotNBT();}
 public AIRobot createWorkAI(){return this;}
}
''',
}
for name in ("AIRobotReturnToLostStation", "AIRobotGotoSleep", "AIRobotSleep", "AIRobotShutdown", "AIRobotRecharge"):
    STUBS[f"buildcraft/robotics/ai/{name}.java"] = f'''package buildcraft.robotics.ai;
import buildcraft.robotics.internal.legacy.robots.*;
public class {name} extends AIRobot {{public {name}(EntityRobotBase robot){{super(robot);}}}}
'''

MAIN = r'''package fixture;
import buildcraft.api.v2.*;
import buildcraft.api.v2.persistence.*;
import buildcraft.api.v2.robot.*;
import buildcraft.robotics.ai.*;
import buildcraft.robotics.internal.api2.*;
import buildcraft.robotics.internal.legacy.robots.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RobotTaskFixture {
 static void ok(boolean result,String msg){if(!result)throw new AssertionError(msg);}
 static final ResourceLocation ID=ResourceLocation.tryParse("fixture:count"), OLD=ResourceLocation.tryParse("fixture:old_count"),
  FORMAT=ResourceLocation.tryParse("fixture:count_binary"),RES=ResourceLocation.tryParse("fixture:resource");
 static int assigned,resumed,suspended,completed,failed,cancelled,totalTicks,leased,closed;
 static final Map<String,Long> reservations=new HashMap<>();
 public static class CountTask implements RobotTask,RobotTaskLifecycle {
  int progress;boolean acquired;
  public CountTask(int initial){progress=initial;}
  public ResourceLocation typeId(){return ID;}
  public RobotTaskResult tick(RobotTaskContext ctx){
   totalTicks++;
   if(progress==0){var lease=ctx.acquire(new RobotResourceRequest(new Resource("field"),1));
    if(lease.isEmpty())throw new AssertionError("lease denied");acquired=true;}
   progress++;return progress>=3?RobotTaskResult.complete():RobotTaskResult.running();
  }
  public void onAssigned(RobotTaskContext c){assigned++;}
  public void onResumed(RobotTaskContext c){resumed++;}
  public void onSuspended(RobotTaskContext c){suspended++;}
  public void onCompleted(RobotTaskContext c){completed++;}
  public void onFailed(RobotTaskContext c){failed++;}
  public void onCancelled(RobotTaskContext c){cancelled++;}
 }
 public record Resource(String key) implements RobotResource {public ResourceLocation typeId(){return RES;}}
 static final class Lease implements RobotResourceLease {
  final long id;final Resource resource;boolean active=true;
  Lease(long id,Resource resource){this.id=id;this.resource=resource;}
  public long robotId(){return id;}public RobotResource resource(){return resource;}
  public long amount(){return 1;}public boolean active(){return active;}
  public void close(){if(active){reservations.remove(resource.key(),id);closed++;active=false;}}
 }
 public static class Service implements RobotService {
  public Optional<RobotResourceLease> acquire(Level world,long id,RobotResourceRequest request){
   if(!(request.resource() instanceof Resource resource)||request.amount()!=1)return Optional.empty();
   Long owner=reservations.get(resource.key());if(owner!=null&&owner!=id)return Optional.empty();
   leased++;reservations.put(resource.key(),id);return Optional.<RobotResourceLease>of(new Lease(id,resource)).filter(RobotServiceImpl::trackLease);
  }
 }
 static byte[] i32(int value){return ByteBuffer.allocate(4).putInt(value).array();}
 static void register(){
  ApiCodec<CountTask,OpaqueData> countCodec=new ApiCodec<>(){
   public CodecResult<OpaqueData> encode(CountTask t){return CodecResult.success(new OpaqueData(FORMAT,i32(t.progress)));}
   public CodecResult<CountTask> decode(OpaqueData p){if(!p.format().equals(FORMAT)||p.size()!=4)return CodecResult.failure("invalid");
    return CodecResult.success(new CountTask(ByteBuffer.wrap(p.bytes()).getInt()));}
  };
  var migration=new SchemaMigration<OpaqueData>() {
   public int fromVersion(){return 0;} public int toVersion(){return 1;}
   public CodecResult<OpaqueData> migrate(OpaqueData payload){return CodecResult.success(new OpaqueData(FORMAT,payload.bytes()));}
  };
  var persisted=PersistentType.<CountTask,OpaqueData>builder(ID,1,countCodec).alias(OLD).migration(migration).build();
  BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).register(ID,new RobotTaskType<>(ID,CountTask.class,persisted));
  ApiCodec<Resource,OpaqueData> resourceCodec=new ApiCodec<>(){
   public CodecResult<OpaqueData> encode(Resource r){return CodecResult.success(new OpaqueData(RES,r.key().getBytes(StandardCharsets.UTF_8)));}
   public CodecResult<Resource> decode(OpaqueData data){return CodecResult.success(new Resource(new String(data.bytes(),StandardCharsets.UTF_8)));}
  };
  BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES).register(RES,
   new RobotResourceType<>(RES,Resource.class,PersistentType.<Resource,OpaqueData>builder(RES,0,resourceCodec).build(),
    (world,id,res,amount)->new Service().acquire(world,id,new RobotResourceRequest(res,amount))));
  BuildCraftApi.bind(BuildCraftServices.ROBOTS,new Service());
 }
 static AIRobotMain create(long id){EntityRobotBase robot=new EntityRobotBase(id);AIRobotMain main=new AIRobotMain(robot);
  robot.main(main);main.start();return main;}
 static AIRobotMain restore(AIRobotMain main){CompoundTag saved=new CompoundTag();main.writeToNBT(saved);
  EntityRobotBase robot=new EntityRobotBase(main.robot.getRobotId());
  AIRobotMain restored=(AIRobotMain)AIRobot.loadAI(saved,robot);robot.main(restored);return restored;}
 static byte[] frame(AIRobotMain ai){CompoundTag nbt=new CompoundTag();ai.writeToNBT(nbt);
  CompoundTag data=nbt.getCompound("delegateAI").getCompound("data");
  return ((ByteArrayTag)data.get("task")).getAsByteArray();}
 public static void main(String[]args) throws Exception {
  // The real config must run against an isolated temporary game directory.
  ok(buildcraft.lib.balance.BalanceConfig.robot("empty", "energy_cost_multiplier", 1.0) == 1.0,
   "built-in robot balance used when use_config=false");
  RobotManager.registerAIRobot(AIRobotMain.class,"main");
  register();
  ok("api2Task".equals(RobotManager.getAIRobotName(ApiTaskAI.class)),"AI registered by a stable name");
  AIRobotMain main=create(7);ApiTaskAI assignedTask=new ApiTaskAI(main.robot,new CountTask(0));
  main.setOverridingAI(assignedTask);
  AIRobotMain queued=restore(main);
  ok(queued.getOverridingAI() instanceof ApiTaskAI,"queued override survives save");
  ok(leased==0,"queued task never allocates resources");
  queued.cycle();
  ok(queued.getActiveAI() instanceof ApiTaskAI,"AI runner activated queued task");
  queued.cycle();
  ok(reservations.get("field")==7,"resource reserved");
  ok(totalTicks==2,"ticks before reload");
  AIRobotMain restarted=restore(queued);
  ok(restarted.getActiveAI() instanceof ApiTaskAI,"nested delegate restored");
  ok(((ApiTaskAI)restarted.getActiveAI()).taskTypeId().orElseThrow().equals(ID),"registered ID survives, not AIRobot class");
  int ticksBefore=totalTicks;
  restarted.cycle();
  ok(totalTicks==ticksBefore+1,"exactly one resumed tick");
  ok(resumed==2,"resume called once for each restored task");
  ok(completed==1,"completion called once");
  ok(!reservations.containsKey("field"),"terminal resource cleanup");
  ok(restarted.getActiveAI() instanceof AIRobotMain,"completed task removed from active delegate");
  AIRobotMain cancel=create(8);cancel.setOverridingAI(new ApiTaskAI(cancel.robot,new CountTask(0)));
  cancel.cycle();cancel.setOverridingAI(null);cancel.getActiveAI().abort();
  ok(cancelled>0,"cancellation callback");
  AIRobotMain unknown=create(9);unknown.setOverridingAI(new ApiTaskAI(unknown.robot,new CountTask(1)));
  unknown.cycle();byte[] original=frame(unknown);
  BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).clear();
  AIRobotMain missing=restore(unknown);
  ok(missing.getActiveAI() instanceof ApiTaskAI,"missing addon does not destroy task envelope");
  int afterUnknown=totalTicks; missing.cycle();
  ok(missing.getActiveAI().getEnergyCost()==0,"unknown task cannot drain battery");
  ok(Arrays.equals(original,frame(missing)),"unknown task kept bit-for-bit");
  ok(totalTicks==afterUnknown,"unknown task never executes");
  registerTaskAgain();
  AIRobotMain returning=restore(missing);
  ok(returning.getActiveAI() instanceof ApiTaskAI,"addon return restores saved task");
  returning.cycle();returning.cycle();
  ok(completed==2,"restored unknown task completes with addon installed");

  // A persisted alias at an older schema must resolve through the registered migrations.
  ByteArrayOutputStream oldBytes=new ByteArrayOutputStream();
  DataOutputStream old=new DataOutputStream(oldBytes);
  old.writeInt(0x42435254);old.writeByte(1);old.writeByte(RobotTaskLifecycle.Phase.RUNNING.ordinal());
  old.writeUTF(OLD.toString());old.writeInt(0);old.writeUTF(FORMAT.toString());
  old.writeInt(4);old.write(i32(2));old.writeByte(0);old.flush();
  ApiTaskAI migrated=new ApiTaskAI(new EntityRobotBase(10));
  CompoundTag legacyTag=new CompoundTag();legacyTag.putByteArray("task",oldBytes.toByteArray());
  migrated.loadSelfFromNBT(legacyTag);
  ok(migrated.taskTypeId().orElseThrow().equals(OLD),"stored alias preserved until checkpoint");
  migrated.start();migrated.update();
  ok(completed==3,"schema migration restored executable task");

  // Corrupt frames never execute and remain recoverable as exact uninterpreted bytes.
  byte[] corrupted={0x42,0x43,0x52,0x54,0,127,1,2,3,4};
  ApiTaskAI malformed=new ApiTaskAI(new EntityRobotBase(11));
  CompoundTag malformedInput=new CompoundTag();malformedInput.putByteArray("task",corrupted);
  malformed.loadSelfFromNBT(malformedInput);
  malformed.update();
  CompoundTag malformedSaved=new CompoundTag();malformed.writeSelfToNBT(malformedSaved);
  ok(Arrays.equals(corrupted,((ByteArrayTag)malformedSaved.get("task")).getAsByteArray()),
   "corrupt frame retained without execution");

  // Chunk unload releases its live lease; a competing robot blocks resurrection safely.
  AIRobotMain unloading=create(12);
  unloading.setOverridingAI(new ApiTaskAI(unloading.robot,new CountTask(0)));
  unloading.cycle();unloading.cycle();
  ok(reservations.get("field")==12,"live lease held before unload");
  ApiTaskAI active=(ApiTaskAI)unloading.getActiveAI();
  active.suspendForUnload();
  ok(!reservations.containsKey("field"),"lease closed on chunk unload");
  ok(active.phase()==RobotTaskLifecycle.Phase.SUSPENDED,"unloaded task suspended");
  AIRobotMain unloadedRestored=restore(unloading);
  reservations.put("field",999L);
  int beforeBlocked=totalTicks;
  unloadedRestored.cycle();
  ok(totalTicks==beforeBlocked,"recovery never ticks without its lease");
  ok(((ApiTaskAI)unloadedRestored.getActiveAI()).phase()==RobotTaskLifecycle.Phase.SUSPENDED,
   "unavailable lease keeps task suspended");
  ok(unloadedRestored.getActiveAI().getEnergyCost()==0,"blocked robot task does not drain battery");
  reservations.remove("field");
  unloadedRestored.cycle();
  ok(totalTicks==beforeBlocked+1 && completed==4,"task resumes only after lease reacquisition");
  ok(!reservations.containsKey("field"),"reacquired lease released after completion");
  System.out.println("PASS: queued, save/reload, migration, corrupt, unknown, lease, unload, resume, terminal");
 }
 static void registerTaskAgain(){register();}
}'''

class RobotTaskRuntimeTest(unittest.TestCase):
    def test_compiled_runtime_restore_and_lifecycle(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("JDK not installed")
        with tempfile.TemporaryDirectory(prefix="bc-api-robot-") as tmp:
            root = Path(tmp)
            sources = []
            for rel, code in STUBS.items():
                dest = root / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(code, encoding="utf-8")
                sources.append(str(dest))
            main = root / "fixture/RobotTaskFixture.java"
            main.parent.mkdir(parents=True, exist_ok=True)
            main.write_text(MAIN, encoding="utf-8")
            sources.append(str(main))
            sources.extend(str(SRC / rel) for rel in JAVA)
            classes = root / "classes"
            classes.mkdir()
            compile_result = subprocess.run(
                ["javac", "-encoding", "UTF-8", "-d", str(classes), *sources],
                capture_output=True, text=True, timeout=60,
            )
            self.assertEqual(0, compile_result.returncode, compile_result.stderr[-8000:])
            executed = subprocess.run(
                ["java", "-cp", str(classes), "fixture.RobotTaskFixture"],
                cwd=root,  # isolate generated INI files from the developer's workspace
                capture_output=True, text=True, timeout=15,
            )
            self.assertEqual(0, executed.returncode, executed.stderr[-8000:])
            for filename in ("buildcraft-machines.ini", "buildcraft-robots.ini"):
                self.assertTrue((root / "config" / filename).is_file(), filename)
            self.assertIn("PASS:", executed.stdout)
            print(executed.stdout.strip(), flush=True)

    def test_real_services_use_authoritative_adapter(self):
        roots = [
            *ROOT.glob("source-platforms/*/src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java"),
            *ROOT.glob("source-family-platforms/26.X/neoforge/src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java"),
            *ROOT.glob("source-downports/26.X/*/neoforge/src/main/java/buildcraft/robotics/internal/api2/RobotServiceImpl.java"),
        ]
        self.assertEqual(5, len(roots))
        for path in roots:
            text = path.read_text(encoding="utf-8")
            with self.subTest(path=path):
                self.assertIn("ApiTaskAI::trackIfExecuting", text)
                self.assertIn("new ApiTaskAI(robot, task)", text)
                self.assertIn("apiTask.taskTypeId()", text)
                self.assertIn("current.abort()", text)
                self.assertNotIn("private static final class ApiTaskAI", text)

if __name__ == "__main__":
    unittest.main()
