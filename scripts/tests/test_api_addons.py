#!/usr/bin/env python3
"""Independent addon compatibility and production JAR ABI checks."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import shutil
import tempfile
import unittest
import test_external_addon_conformance as external
import test_request_lifecycle as requests
import importlib.util
import subprocess
import zipfile
from unittest.mock import patch

ADDON_PARITY_PUBLIC = ROOT / "source-shared/src/main/java"
ADDON_PARITY_ADDONS = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture"


def addon_parity_source(relative: str) -> Path:
    result = ADDON_PARITY_PUBLIC / relative
    if not result.is_file():
        raise AssertionError(f"Missing production API contract: {relative}")
    return result


class AddonPortingConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not all(shutil.which(x) for x in ("javac", "java", "jar")):
            raise unittest.SkipTest("Java toolchain is required")

    def test_logistics_pipes_request_delivery_and_route_metadata(self):
        with tempfile.TemporaryDirectory(prefix="bcce-addons-logistics-") as td:
            root = Path(td)
            stubs = [external.write(root / "stubs", name, content)
                     for name, content in requests.STUBS.items()
                     if name.startswith("net/minecraft/") or name.startswith("buildcraft/api/v2/")]
            files = [addon_parity_source(name) for name in requests.PUBLIC]
            files.append(addon_parity_source("buildcraft/api/v2/pipe/ItemTransitPayload.java"))
            api, addon, probe = (root / name for name in ("api.jar", "addon.jar", "probe.jar"))
            external.compile_jar(stubs + files, api)
            external.compile_addon(ADDON_PARITY_ADDONS / "LogisticsPipesParityAddon.java", (api,), addon)
            test = external.write(root, "probe/Logistics.java", ADDON_PARITY_LOGISTICS_PROBE)
            external.compile_jar([test], probe, dependencies=(api, addon))
            external.run("java", "-cp", os.pathsep.join(map(str, (api, addon, probe))), "probe.Logistics")

    def test_buildcraft_additions_canister_transactions_and_components(self):
        with tempfile.TemporaryDirectory(prefix="bcce-addons-additions-") as td:
            root = Path(td)
            stubs = [external.write(root, "stubs/net/minecraft/resources/ResourceLocation.java",
                                    ADDON_PARITY_RESOURCE_LOCATION),
                     external.write(root, "stubs/net/minecraft/world/item/ItemStack.java", ADDON_PARITY_ITEM_STACK)]
            files = [addon_parity_source("buildcraft/api/v2/" + name) for name in (
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
            external.compile_addon(ADDON_PARITY_ADDONS / "BuildCraftAdditionsParityAddon.java", (api,), addon)
            test = external.write(root, "probe/Additions.java", ADDON_PARITY_ADDITIONS_PROBE)
            external.compile_jar([test], probe, dependencies=(api, addon))
            external.run("java", "-cp", os.pathsep.join(map(str, (api, addon, probe))), "probe.Additions")

ADDON_PARITY_RESOURCE_LOCATION = """package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
 public static ResourceLocation tryParse(String value) {
  String[] parts = value.split(":",2);
  return parts.length == 2 ? new ResourceLocation(parts[0], parts[1]) : null;
 }
 public String toString() {return namespace+":"+path;}
} """
ADDON_PARITY_ITEM_STACK = """package net.minecraft.world.item;
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

ADDON_PARITY_LOGISTICS_PROBE = r'''package probe;
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

ADDON_PARITY_ADDITIONS_PROBE = r'''package probe;
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


ADDON_BINARY_BINARY = ROOT / "scripts/verify-addon-binary.py"


class ProductionJarAddonGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("addon_binary", ADDON_BINARY_BINARY)
        cls.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.module)

    def test_reject_non_public_buildcraft_import(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "Addon.java"
            path.write_text("import buildcraft.api.v2.OperationMode;\n" +
                            "import buildcraft.transport.pipe.Pipe;\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-public"):
                self.module.ensure_public_sources([path])
            path.write_text("import buildcraft.api.v2.pipe.PipeView;\n", encoding="utf-8")
            self.module.ensure_public_sources([path])

    def test_reject_internal_bytecode_and_empty_compilation(self):
        with tempfile.TemporaryDirectory() as temporary:
            classes = Path(temporary)
            with self.assertRaisesRegex(ValueError, "No independently"):
                self.module.ensure_public_bytecode(classes)
            unit = classes / "addon/Example.class"
            unit.parent.mkdir(parents=True)
            unit.write_bytes(b"Lbuildcraft/api/v2/pipe/PipeView;")
            self.assertEqual(self.module.ensure_public_bytecode(classes), [unit])
            unit.write_bytes(b"Lbuildcraft/lib/internal/api/v2/BuildCraftApiRuntime;")
            with self.assertRaisesRegex(ValueError, "internal BuildCraft"):
                self.module.ensure_public_bytecode(classes)

    def test_all_three_named_addon_fixtures_are_in_binary_gate(self):
        names = set(self.module.ADDONS)
        for name in ("AdditionalPipesConformanceAddon.java", "LogisticsPipesParityAddon.java",
                     "BuildCraftAdditionsParityAddon.java"):
            self.assertIn(name, names)
        self.module.ensure_public_sources([self.module.FIXTURE_ROOT / name for name in names])

    def test_every_loader_runs_gate_after_jar(self):
        for path in (ROOT / "build-logic/loaders/forge-target.gradle",
                     ROOT / "build-logic/loaders/neoforge-target.gradle"):
            with self.subTest(loader=path.name):
                text = path.read_text(encoding="utf-8")
                self.assertIn("dependsOn tasks.named('jar')", text)
                self.assertIn("tasks.register('verifyApi2AddonBinary', Exec)", text)
                self.assertIn("dependsOn verifyApi2AddonBinary", text)
                self.assertIn("--fixture-root", text)
                self.assertIn("--bcce-jar", text)
                self.assertIn("'--javac', javac", text)
                self.assertIn(".javaCompiler.get().executablePath.asFile.absolutePath", text)

    def test_uses_requested_toolchain_javac(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar = root / "buildcraft.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                for name in ("buildcraft/api/v2/BuildCraftApi.class", "buildcraft/api/v2/BuildCraftRegistries.class"):
                    archive.writestr(name, b"test-api-class")
            fixtures = root / "fixtures"
            fixtures.mkdir()
            for name in self.module.ADDONS:
                (fixtures / name).write_text("// independent addon fixture\n", encoding="utf-8")
            output = root / "compiled-fixtures.jar"
            used_compiler = str(root / "jdk21" / "bin" / "javac")
            commands = []

            def compile_fixture(command, **kwargs):
                commands.append(command)
                classes = Path(command[command.index("-d") + 1])
                classes.mkdir(parents=True, exist_ok=True)
                (classes / "Fixture.class").write_bytes(b"Lbuildcraft/api/v2/BuildCraftApi;")
                return subprocess.CompletedProcess(command, 0, "", "")

            with patch.object(self.module.subprocess, "run", side_effect=compile_fixture):
                code = self.module.main([
                    "--bcce-jar", str(jar), "--dependencies", "", "--release", "21",
                    "--javac", used_compiler, "--fixture-root", str(fixtures), "--output", str(output)
                ])
            self.assertEqual(code, 0)
            self.assertEqual(len(commands), 1)
            self.assertEqual(commands[0][0], used_compiler)
            self.assertEqual(commands[0][1:3], ["--release", "21"])
            with zipfile.ZipFile(output) as archive:
                self.assertIn("Fixture.class", archive.namelist())

if __name__ == "__main__":
    unittest.main()
