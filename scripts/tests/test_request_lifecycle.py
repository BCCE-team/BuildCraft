#!/usr/bin/env python3
"""Compile production request accounting and exercise an independent requester and live service."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "source-shared/src/main/java"
PUBLIC = [
    "buildcraft/api/v2/OperationMode.java",
    "buildcraft/api/v2/item/ItemMatcher.java",
    "buildcraft/api/v2/item/ItemTransferResult.java",
    "buildcraft/api/v2/permission/ActorType.java",
    "buildcraft/api/v2/permission/AutomationActor.java",
    "buildcraft/api/v2/permission/PermissionVerdict.java",
    "buildcraft/api/v2/permission/PermissionDecision.java",
    "buildcraft/api/v2/permission/PermissionService.java",
    *(f"buildcraft/api/v2/request/{name}.java" for name in (
        "ItemRequest", "RequestProvider", "RequestService", "ManagedRequestProvider", "RequestLedger"
    )),
]
STUBS = {
    "net/minecraft/resources/ResourceLocation.java": '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String s) {
        if(s==null || !s.contains(":"))return null;
        String[] part=s.split(":",2);
        return part[0].isEmpty() || part[1].isEmpty() ? null : new ResourceLocation(part[0],part[1]);
    }
    public String toString(){return namespace+":"+path;}
}''',
    "net/minecraft/core/BlockPos.java": '''package net.minecraft.core;
public record BlockPos(int x, int y, int z) {public BlockPos immutable(){return this;}}''',
    "net/minecraft/core/Direction.java": '''package net.minecraft.core;
public enum Direction {DOWN, UP, NORTH, SOUTH, WEST, EAST;}''',
    "net/minecraft/world/item/ItemStack.java": '''package net.minecraft.world.item;
public class ItemStack {
    public static final ItemStack EMPTY=new ItemStack("",0);
    private final String kind; private int count;
    public ItemStack(String kind,int count){this.kind=kind;this.count=count;}
    public String kind(){return kind;}
    public int getCount(){return count;}
    public void setCount(int value){count=value;}
    public boolean isEmpty(){return count<=0;}
    public ItemStack copy(){return new ItemStack(kind,count);}
    public static boolean isSameItem(ItemStack a, ItemStack b){return a.kind.equals(b.kind);}
    public static boolean matches(ItemStack a, ItemStack b){return isSameItem(a,b)&&a.count==b.count;}
}''',
    "net/minecraft/world/level/block/entity/BlockEntity.java": '''package net.minecraft.world.level.block.entity;
public class BlockEntity {}''',
    "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.entity.*;
public class Level {
    public boolean client;public boolean loaded=true;public long tick=100;
    private final Map<BlockPos,BlockEntity> entities=new HashMap<>();
    public boolean isClientSide(){return client;}
    public boolean hasChunkAt(BlockPos pos){return loaded;}
    public long getGameTime(){return tick;}
    public BlockEntity getBlockEntity(BlockPos pos){return entities.get(pos);}
    public void put(BlockPos pos,BlockEntity entity){entities.put(pos,entity);}
}''',
    "buildcraft/api/v2/permission/WorldOperationContext.java": '''package buildcraft.api.v2.permission;
import buildcraft.api.v2.OperationMode; import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos; import net.minecraft.resources.ResourceLocation;
public record WorldOperationContext(AutomationActor actor,Level level,BlockPos origin,
    WorldOperationTarget target,WorldOperationKind operation,OperationMode mode,ResourceLocation reason) {}''',
    "buildcraft/api/v2/permission/WorldOperationKind.java": '''package buildcraft.api.v2.permission;
public enum WorldOperationKind {INVENTORY_ACCESS}''',
    "buildcraft/api/v2/permission/WorldOperationTarget.java": '''package buildcraft.api.v2.permission;
import net.minecraft.core.BlockPos;
public record WorldOperationTarget(BlockPos pos){public static WorldOperationTarget block(BlockPos p){return new WorldOperationTarget(p);}}''',
    "buildcraft/api/v2/BuildCraftServices.java": '''package buildcraft.api.v2;
public class BuildCraftServices {public static final Object PERMISSIONS=new Object();}''',
    "buildcraft/api/v2/BuildCraftApi.java": '''package buildcraft.api.v2;
import buildcraft.api.v2.permission.PermissionService;
public class BuildCraftApi {
    public static PermissionService permissions=PermissionService.passThrough();
    public static PermissionService service(Object key){return permissions;}
}''',
    "buildcraft/robotics/internal/legacy/robots/IRobotRegistry.java": '''package buildcraft.robotics.internal.legacy.robots;
import java.util.Collection;
public interface IRobotRegistry {Collection<DockingStation> getStations();
DockingStation getStation(net.minecraft.core.BlockPos pos,net.minecraft.core.Direction side);}''',
    "buildcraft/robotics/internal/legacy/robots/DockingStation.java": '''package buildcraft.robotics.internal.legacy.robots;
import buildcraft.api.v2.request.RequestProvider;
public class DockingStation {
 public RequestProvider getRequestProvider(){return null;}
}''',
    "buildcraft/robotics/internal/legacy/robots/RobotManager.java": '''package buildcraft.robotics.internal.legacy.robots;
import net.minecraft.world.level.Level;
public class RobotManager {
 public static RegistryProvider registryProvider;
 public interface RegistryProvider {IRobotRegistry getRegistry(Level level);}
}''',
}
ADDON = '''package addon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.item.*;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.request.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.*;

public final class ExampleRequester extends BlockEntity implements ManagedRequestProvider {
    public int dirty;
    public final RequestLedger book=new RequestLedger(()->dirty++);
    public long time=100;
    public int capacity=100,stored;
    public boolean broken;
    public int insertCalls;
    public long requestTime(){return time;}
    public RequestLedger requestLedger(){return book;}
    public ItemTransferResult insertRequested(ResourceLocation id,ItemStack offered,OperationMode mode){
        insertCalls++;
        int take=Math.min(offered.getCount(),capacity-stored);
        if(broken){ if(mode==OperationMode.EXECUTE) stored+=take;throw new IllegalStateException("broken callback"); }
        if(mode==OperationMode.EXECUTE)stored+=take;
        return ItemTransferResult.ofInsertion(offered,take);
    }
    public void create(String id,int min,int max,int prio,AutomationActor actor,long ttl){
        book.open(new ItemRequest(ResourceLocation.tryParse(id),new ItemMatcher(){
            public boolean matches(ItemStack s){return s.kind().equals("iron");}
            public java.util.List<ItemStack> examples(){return java.util.List.of(new ItemStack("iron",1));}
        },min,max,prio),actor,time,ttl);
    }
}
'''
TEST = '''import addon.ExampleRequester;
import buildcraft.api.v2.*;
import buildcraft.api.v2.item.*;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.request.*;
import buildcraft.robotics.internal.api2.RequestServiceImpl;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import java.util.*;

public final class RequestTest {
    static final AutomationActor A=AutomationActor.system(ResourceLocation.tryParse("addon:robot_a"));
    static final AutomationActor B=AutomationActor.system(ResourceLocation.tryParse("addon:robot_b"));
    static final ResourceLocation ID=ResourceLocation.tryParse("addon:order_1");
    static final ItemStack IRON=new ItemStack("iron",20);
    static void check(boolean okay,String text){if(!okay)throw new AssertionError(text);}
    static UUID uid(int n){return new UUID(2,n);}
    static void testReservePartialAndDeduplicate() throws Exception {
        ExampleRequester p=new ExampleRequester();p.create(ID.toString(),2,10,8,A,1000);
        check(p.requests().size()==1 && p.requests().iterator().next().priority()==8
            && !p.requests().iterator().next().matcher().examples().isEmpty(),"discover request with robot-compatible example");
        check(p.book.reserve(ID,uid(1),7,A,100,40).isPresent(),"reserve A");
        check(!p.book.reserve(ID,uid(2),4,B,100,40).isPresent(),"overcommit refused");
        check(p.book.reserve(ID,uid(2),3,B,100,50).isPresent(),"reserve B");
        check(p.requests().isEmpty(),"promised units no longer advertised");
        p.capacity=5;
        int beforeSimDirty=p.dirty;
        var sim=p.book.deliver(p,uid(1),uid(11),IRON,A,OperationMode.SIMULATE);
        check(sim.status()==RequestLedger.DeliveryStatus.SIMULATED && sim.acknowledged()==5 && p.stored==0
            && p.dirty==beforeSimDirty,"simulation never dirties stored state");
        var part=p.book.deliver(p,uid(1),uid(11),IRON,A,OperationMode.EXECUTE);
        check(part.accepted()==5 && part.remaining()==15 && part.status()==RequestLedger.DeliveryStatus.PARTIAL,"partial insertion and full source remainder");
        check(p.stored==5 && p.book.request(ID).orElseThrow().confirmed()==5 && p.dirty>beforeSimDirty,"count actual inventory and dirty event");
        p.capacity=20;
        int calls=p.insertCalls;
        var repeat=p.book.deliver(p,uid(1),uid(11),IRON,A,OperationMode.EXECUTE);
        check(repeat.status()==RequestLedger.DeliveryStatus.DUPLICATE && repeat.accepted()==0
            && repeat.acknowledged()==5 && p.insertCalls==calls,"idempotent confirmation");
        check(p.book.deliver(p,uid(1),uid(12),IRON,A,OperationMode.EXECUTE).accepted()==2,"remaining A");
        check(p.book.deliver(p,uid(2),uid(13),IRON,B,OperationMode.EXECUTE).accepted()==3,"remaining B");
        check(p.book.request(ID).orElseThrow().phase()==RequestLedger.Phase.COMPLETE,"completed");
        check(p.book.request(ID).orElseThrow().reserved()==0 && p.stored==10,"conservation");
        check(p.book.deliver(p,uid(2),uid(99),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.CLOSED,"cannot overdeliver completed");
    }
    static void testLegacyRobotsAndTimeouts() {
        ExampleRequester p=new ExampleRequester();p.create(ID.toString(),0,10,2,A,100);
        p.book.reserve(ID,uid(1),7,A,100,30);
        var result=p.offer(ID,IRON,OperationMode.EXECUTE);
        check(result.transferredCount()==3 && p.stored==3,"legacy robot cannot steal promise");
        check(!p.book.release(uid(1),B),"foreign actor cannot release promise");
        check(p.book.release(uid(1),A),"release residual promise");
        check(p.book.request(ID).orElseThrow().available()==7,"remaining is exactly 7");
        p.time=201;p.book.advance(201);
        check(p.book.deliver(p,uid(1),uid(44),IRON,A,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.CLOSED,"released lease stays closed");
        check(p.book.request(ID).orElseThrow().phase()==RequestLedger.Phase.TIMED_OUT,"timeout request");
        check(p.requests().isEmpty(),"timed out not advertised");
        check(p.offer(ID,IRON,OperationMode.EXECUTE).transferredCount()==0,"no expired delivery");
        ExampleRequester expired=new ExampleRequester();expired.create("addon:expires",0,10,2,A,1000);
        var expId=ResourceLocation.tryParse("addon:expires");
        expired.book.reserve(expId,uid(12),5,B,100,4);
        expired.time=105;
        check(expired.book.deliver(expired,uid(12),uid(47),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.EXPIRED,"expired promise diagnosed");
        check(expired.book.request(expId).orElseThrow().available()==10,"expired promises are released");
    }
    static void testCancelAndRebindAfterReload() throws Exception {
        ExampleRequester p=new ExampleRequester();p.create(ID.toString(),1,9,4,A,400);
        p.book.reserve(ID,uid(1),6,B,100,100);
        check(p.book.deliver(p,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).accepted()==6,"initial shipment");
        p.book.reserve(ID,uid(2),3,B,100,200);
        byte[] bytes=p.book.save();
        ExampleRequester restored=new ExampleRequester();restored.stored=6;
        restored.book.load(bytes);
        check(restored.requests().isEmpty(),"unknown matcher is inert after reload");
        check(restored.book.deliver(restored,uid(2),uid(22),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.UNAVAILABLE,"delivery blocked until rebind");
        restored.create(ID.toString(),1,9,4,A,400);
        check(restored.book.deliver(restored,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.DUPLICATE,"receipt restored");
        check(restored.book.deliver(restored,uid(2),uid(22),IRON,B,OperationMode.EXECUTE).accepted()==3,"active lease restored");
        check(restored.stored==9 && restored.book.request(ID).orElseThrow().phase()==RequestLedger.Phase.COMPLETE,"restored complete");
        byte[] valid=restored.book.save();byte[] truncated=Arrays.copyOf(valid,valid.length-9);
        boolean thrown=false;try{restored.book.load(truncated);}catch(java.io.IOException expected){thrown=true;}
        check(thrown && Arrays.equals(valid,restored.book.save()),"corrupt restore transactional");
        byte[] future=valid.clone();future[7]=2;
        thrown=false;try{restored.book.load(future);}catch(java.io.IOException expected){thrown=true;}
        check(thrown,"future format rejected, payload remains in addon");
        ExampleRequester unknown=new ExampleRequester();
        check(!unknown.book.loadPreserving(future) && Arrays.equals(unknown.book.save(),future),
            "unsupported future ledger preserved byte-for-byte");
        boolean sealed=false;
        try{unknown.create(ID.toString(),1,9,4,A,400);}catch(IllegalStateException expected){sealed=true;}
        check(sealed && unknown.requests().isEmpty(),"unreadable ledger cannot be overwritten or executed");
        ExampleRequester cancelled=new ExampleRequester();cancelled.create(ID.toString(),1,9,4,A,100);
        cancelled.book.reserve(ID,uid(1),8,B,100,100);
        check(!cancelled.book.cancel(ID,B,"no"),"owner required to cancel");
        check(cancelled.book.cancel(ID,A,"user_cancelled"),"cancel by owner");
        check(cancelled.book.request(ID).orElseThrow().reserved()==0,"cancel frees all residual reservations");
        check(cancelled.book.promise(uid(1)).orElseThrow().phase()==RequestLedger.PromisePhase.CANCELLED,"lease closed");
        check(cancelled.book.forget(ID,A),"owner can prune a terminal request");
        check(cancelled.book.request(ID).isEmpty() && cancelled.book.promise(uid(1)).isEmpty(),"no leaked terminal promises");
        cancelled.create(ID.toString(),1,9,4,A,100);
        check(cancelled.book.request(ID).isPresent(),"recreate after explicit forget");
    }
    static void testPermissionAndUnloaded() {
        Level level=new Level();BlockPos origin=new BlockPos(1,2,3),target=new BlockPos(5,6,7);
        ExampleRequester p=new ExampleRequester();p.create(ID.toString(),0,8,9,A,1000);
        level.put(target,p);
        RequestService service=new RequestServiceImpl();
        check(service.managedProvider(level,target,null).orElseThrow()==p,"real service discovery");
        level.loaded=false;
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.UNAVAILABLE,"unloaded target never called");
        level.loaded=true;level.client=true;
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.UNAVAILABLE,"client never mutates");
        level.client=false;
        BuildCraftApi.permissions=c->PermissionDecision.deny(ResourceLocation.tryParse("test:deny"),"blocked");
        check(service.reserve(level,origin,target,null,ID,uid(1),8,B,100).isEmpty(),"reservation permission denied");
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.DENIED,"permission denied");
        BuildCraftApi.permissions=c->{check(c.operation()==WorldOperationKind.INVENTORY_ACCESS,"inventory policy");return PermissionDecision.pass();};
        check(service.reserve(level,origin,target,null,ID,uid(1),8,B,100).isPresent(),"service reservation");
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,A,OperationMode.EXECUTE).status()
            ==RequestLedger.DeliveryStatus.DENIED,"wrong actor");
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,B,OperationMode.SIMULATE).status()
            ==RequestLedger.DeliveryStatus.SIMULATED && p.stored==0,"service simulation");
        check(service.deliver(level,origin,target,null,uid(1),uid(11),IRON,B,OperationMode.EXECUTE).accepted()==8
            && p.stored==8,"real service execute");
        ExampleRequester cancelled=new ExampleRequester();cancelled.create("addon:cancel",0,6,4,A,1000);
        BlockPos other=new BlockPos(8,8,8);level.put(other,cancelled);
        check(service.reserve(level,origin,other,null,ResourceLocation.tryParse("addon:cancel"),uid(40),6,B,100).isPresent(),"cancel setup");
        check(!service.cancel(level,origin,other,null,ResourceLocation.tryParse("addon:cancel"),B,"bad"),"cancel actor denied");
        check(service.cancel(level,origin,other,null,ResourceLocation.tryParse("addon:cancel"),A,"by_owner"),"authorized cancel");
    }
    static void testFailedProviderIsolation() {
        ExampleRequester p=new ExampleRequester();p.create(ID.toString(),0,10,5,A,100);
        p.book.reserve(ID,uid(1),10,B,100,100);
        p.broken=true;
        var result=p.book.deliver(p,uid(1),uid(11),IRON,B,OperationMode.EXECUTE);
        check(result.status()==RequestLedger.DeliveryStatus.FAILED,"bad callback failed");
        check(p.book.request(ID).orElseThrow().phase()==RequestLedger.Phase.CANCELLED,"uncertain transfer halted");
        check(p.book.request(ID).orElseThrow().reserved()==0,"uncertain lease released");
        check(p.book.request(ID).orElseThrow().diagnostic().equals("provider_exception"),"diagnostic retained");
    }
    public static void main(String[] args) throws Exception {
        testReservePartialAndDeduplicate();testLegacyRobotsAndTimeouts();
        testCancelAndRebindAfterReload();testPermissionAndUnloaded();testFailedProviderIsolation();
        System.out.println("PASS independent requester, legacy robots, reservations, receipts, reload, cancellation, permissions");
    }
}
'''

class RequestLifecycleTest(unittest.TestCase):
    def test_live_request_contract(self) -> None:
        with tempfile.TemporaryDirectory(prefix="bcce-request-") as tmp:
            work = Path(tmp)
            stubs = work / "stubs"
            classes = work / "classes"
            classes.mkdir()
            stub_files = []
            for rel, text in STUBS.items():
                path = stubs / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
                stub_files.append(str(path))
            self.compile([*(str(SRC / file) for file in PUBLIC), *stub_files], classes)
            addon = work / "addon/ExampleRequester.java"
            addon.parent.mkdir(parents=True)
            addon.write_text(ADDON, encoding="utf-8")
            # Addon sees public API, Minecraft stubs, but not any BuildCraft implementation classes.
            self.compile([str(addon)], classes, classes)
            backend = SRC / "buildcraft/robotics/internal/api2/RequestServiceImpl.java"
            self.compile([str(backend)], classes, classes)
            test = work / "RequestTest.java"
            test.write_text(TEST, encoding="utf-8")
            self.compile([str(test)], classes, classes)
            result = subprocess.run(["java", "-cp", str(classes), "RequestTest"],
                                    check=False, text=True, capture_output=True, timeout=25)
            self.assertEqual(result.returncode, 0, result.stdout + "\n" + result.stderr)
            self.assertIn("PASS independent requester", result.stdout)

    @staticmethod
    def compile(files: list[str], classes: Path, classpath: Path | None = None) -> None:
        cmd = ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(classes)]
        if classpath is not None:
            cmd += ["-cp", str(classpath)]
        result = subprocess.run([*cmd, *files], text=True, capture_output=True, timeout=35)
        if result.returncode:
            raise AssertionError(result.stdout + "\n" + result.stderr)


if __name__ == "__main__":
    unittest.main()
