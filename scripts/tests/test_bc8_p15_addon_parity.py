#!/usr/bin/env python3
"""Independent addon-inspired Java bytecode checks using production public API contracts.

These are compatibility probes, not ports of historical addon source trees and not GameTests.
"""
from __future__ import annotations

import os
import shutil
import tempfile
import unittest
from pathlib import Path

import test_external_addon_conformance as external
import test_request_lifecycle as requests

ROOT = Path(__file__).resolve().parents[2]
PUBLIC = ROOT / "source-shared/src/main/java"
ADDONS = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture"


def source(relative: str) -> Path:
    result = PUBLIC / relative
    if not result.is_file():
        raise AssertionError(f"Missing production API contract: {relative}")
    return result


class AddonPortingConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not all(shutil.which(x) for x in ("javac", "java", "jar")):
            raise unittest.SkipTest("Java toolchain is required")

    def test_logistics_pipes_request_delivery_and_route_metadata(self):
        with tempfile.TemporaryDirectory(prefix="bcce-p15-logistics-") as td:
            root = Path(td)
            stubs = [external.write(root / "stubs", name, content)
                     for name, content in requests.STUBS.items()
                     if name.startswith("net/minecraft/") or name.startswith("buildcraft/api/v2/")]
            files = [source(name) for name in requests.PUBLIC]
            files.append(source("buildcraft/api/v2/pipe/ItemTransitPayload.java"))
            api, addon, probe = (root / name for name in ("api.jar", "addon.jar", "probe.jar"))
            external.compile_jar(stubs + files, api)
            external.compile_addon(ADDONS / "LogisticsPipesParityAddon.java", (api,), addon)
            test = external.write(root, "probe/Logistics.java", LOGISTICS_PROBE)
            external.compile_jar([test], probe, dependencies=(api, addon))
            external.run("java", "-cp", os.pathsep.join(map(str, (api, addon, probe))), "probe.Logistics")

    def test_buildcraft_additions_canister_transactions_and_components(self):
        with tempfile.TemporaryDirectory(prefix="bcce-p15-additions-") as td:
            root = Path(td)
            stubs = [external.write(root, "stubs/net/minecraft/resources/ResourceLocation.java",
                                    RESOURCE_LOCATION),
                     external.write(root, "stubs/net/minecraft/world/item/ItemStack.java", ITEM_STACK)]
            files = [source("buildcraft/api/v2/" + name) for name in (
                "OperationMode.java", "item/ItemMatcher.java",
                "persistence/ApiCodec.java", "persistence/CodecResult.java",
                "fluid/FluidAmount.java", "fluid/FluidComponentPayload.java",
                "fluid/FluidVariant.java", "fluid/FluidVariantData.java",
                "fluid/FluidVolume.java", "fluid/FluidMatchContext.java",
                "fluid/FluidMatcher.java", "fluid/FluidTransferResult.java",
                "fluid/FluidPort.java", "fluid/FluidItemAdapter.java",
            )]
            api, addon, probe = (root / name for name in ("api.jar", "addon.jar", "probe.jar"))
            external.compile_jar(stubs + files, api)
            external.compile_addon(ADDONS / "BuildCraftAdditionsParityAddon.java", (api,), addon)
            test = external.write(root, "probe/Additions.java", ADDITIONS_PROBE)
            external.compile_jar([test], probe, dependencies=(api, addon))
            external.run("java", "-cp", os.pathsep.join(map(str, (api, addon, probe))), "probe.Additions")

    def test_additional_pipes_teleport_ownership_still_conforms(self):
        # This uses the actual BCCE PipeItemTransitRuntime, not a mock of its transfer semantics.
        probe = external.ExternalAddonConformanceTest(methodName="test_additional_pipes_teleporter_jar")
        probe.test_additional_pipes_teleporter_jar()


RESOURCE_LOCATION = """package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
 public static ResourceLocation tryParse(String value) {
  String[] parts = value.split(":",2);
  return parts.length == 2 ? new ResourceLocation(parts[0], parts[1]) : null;
 }
 public String toString() {return namespace+":"+path;}
} """
ITEM_STACK = """package net.minecraft.world.item;
public class ItemStack {
 public static final ItemStack EMPTY = new ItemStack("",0);
 private final String type;private int count;
 public ItemStack(String type,int count){this.type=type;this.count=count;}
 public String type(){return type;}
 public int getCount(){return count;}
 public void setCount(int value){count=value;}
 public boolean isEmpty(){return count <= 0;}
 public ItemStack copy(){return new ItemStack(type,count);}
 public static boolean isSameItem(ItemStack a,ItemStack b){return a.type.equals(b.type);}
 public static boolean matches(ItemStack a,ItemStack b){return isSameItem(a,b)&&a.count==b.count;}
} """

LOGISTICS_PROBE = r'''package probe;
import java.util.*;
import java.nio.charset.StandardCharsets;
import buildcraft.api.v2.*;
import buildcraft.api.v2.permission.*;
import buildcraft.api.v2.request.*;
import buildcraft.api.v2.pipe.*;
import net.minecraft.world.item.*;
import net.minecraft.resources.*;
import dev.bcce.apifixture.LogisticsPipesParityAddon;
public final class Logistics {
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 public static void main(String[] args){
  var actor=AutomationActor.system(ResourceLocation.tryParse("lp_fixture:router"));
  var lp=new LogisticsPipesParityAddon(5,100);
  lp.request(6,actor,200);
  UUID promise=UUID.randomUUID();
  require(lp.promise(promise,6,actor,150).remaining()==6,"reservation");
  UUID delivery=UUID.randomUUID();
  var simulated=lp.accept(promise,delivery,new ItemStack("gear",3),actor,OperationMode.SIMULATE);
  require(simulated.status()==RequestLedger.DeliveryStatus.SIMULATED && lp.stock()==0,"simulation mutated inventory");
  var accepted=lp.accept(promise,delivery,new ItemStack("gear",3),actor,OperationMode.EXECUTE);
  require(accepted.accepted()==3 && lp.stock()==3,"delivery did not debit promised quantity");
  var duplicate=lp.accept(promise,delivery,new ItemStack("gear",3),actor,OperationMode.EXECUTE);
  require(duplicate.status()==RequestLedger.DeliveryStatus.DUPLICATE && lp.stock()==3,"duplicate delivery");
  byte[] saved=lp.saveLedger();
  var loaded=new LogisticsPipesParityAddon(5,100);
  require(loaded.loadLedger(saved),"ledger could not load");
  loaded.request(6,actor,200); // bind matcher to persistent request ID
  var replay=loaded.accept(promise,delivery,new ItemStack("gear",3),actor,OperationMode.EXECUTE);
  require(replay.status()==RequestLedger.DeliveryStatus.DUPLICATE && loaded.stock()==0,"receipt lost after reload");
  var continuation=loaded.accept(promise,UUID.randomUUID(),new ItemStack("gear",5),actor,OperationMode.EXECUTE);
  require(continuation.accepted()==3 && loaded.stock()==3,"remaining reservation incorrect");
  var other=AutomationActor.system(ResourceLocation.tryParse("lp_fixture:stranger"));
  require(loaded.accept(promise,UUID.randomUUID(),new ItemStack("gear",1),other,OperationMode.EXECUTE)
   .status()==RequestLedger.DeliveryStatus.DENIED,"actor changed owner");
  var route=LogisticsPipesParityAddon.route(ItemTransitPayload.EMPTY,"overworld:router-42");
  require(LogisticsPipesParityAddon.destination(route).equals("overworld:router-42"),"route lost");
  byte[] raw=route.get("lp_fixture:destination").orElseThrow();raw[0]=0;
  require(LogisticsPipesParityAddon.destination(route).equals("overworld:router-42"),"route exposed mutable bytes");
  require(ItemTransitPayload.EMPTY.get("lp_fixture:destination").isEmpty(),"shared route metadata mutated");
 }
}'''

ADDITIONS_PROBE = r'''package probe;
import java.util.*;
import buildcraft.api.v2.*;
import buildcraft.api.v2.fluid.*;
import buildcraft.api.v2.item.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import dev.bcce.apifixture.BuildCraftAdditionsParityAddon;
public final class Additions {
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 public static void main(String[] args) {
  var vessel=new BuildCraftAdditionsParityAddon(stack->stack.type().equals("canister"),1000);
  var components=FluidComponentPayload.of(ResourceLocation.tryParse("additions:components"),new byte[]{4,5,6});
  var oil=FluidVariant.of(ResourceLocation.tryParse("buildcraft:oil"),components);
  var different=FluidVariant.of(ResourceLocation.tryParse("buildcraft:oil"));
  var offer=FluidVolume.of(oil,1500);
  require(vessel.supports(new ItemStack("canister",1)),"canister was not recognized");
  require(!vessel.supports(new ItemStack("stick",1)),"unexpected item was treated as a canister");
  require(vessel.insert(offer,OperationMode.SIMULATE).transferredAmount().milliBuckets()==1000,"capacity simulation");
  require(vessel.content().isEmpty(),"simulation changed tank state");
  var transfer=vessel.insert(offer,OperationMode.EXECUTE);
  require(transfer.transferredAmount().milliBuckets()==1000 && transfer.remainderAmount().milliBuckets()==500,"partial insertion lost volume");
  require(vessel.fluid(new ItemStack("canister",1)).requireVariant().equals(oil),"fluid components lost");
  require(vessel.fluid(new ItemStack("stick",1)).isEmpty(),"unrecognized item exposed fluid");
  require(vessel.insert(FluidVolume.of(different,250),OperationMode.EXECUTE).transferredAmount().isZero(),"mixed component variants");
  require(vessel.extract(FluidMatcher.exact(oil),FluidAmount.of(600),OperationMode.SIMULATE).transferredAmount().milliBuckets()==600,"simulate extraction");
  require(vessel.content().amount().milliBuckets()==1000,"simulated extraction drained storage");
  require(vessel.extract(FluidMatcher.exact(different),FluidAmount.of(500),OperationMode.EXECUTE).transferredAmount().isZero(),"wrong fluid extracted");
  var drained=vessel.extract(FluidMatcher.exact(oil),FluidAmount.of(600),OperationMode.EXECUTE);
  require(drained.transferredAmount().milliBuckets()==600 && vessel.content().amount().milliBuckets()==400,"extraction conservation");
  require(vessel.extract(FluidMatcher.exact(oil),FluidAmount.of(1000),OperationMode.EXECUTE).transferredAmount().milliBuckets()==400,"final drain");
  require(vessel.content().isEmpty(),"drained canister not empty");
  var encoded=FluidVariant.CODEC.encode(oil).valueOrThrow();
  require(FluidVariant.CODEC.decode(encoded).valueOrThrow().equals(oil),"component-bearing fluid serialization");
 }
}'''

if __name__ == "__main__":
    unittest.main()
