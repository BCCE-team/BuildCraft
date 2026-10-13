#!/usr/bin/env python3
"""BC8 P03: independent API-only MJ addon and genuine storage/service backend tests.

Minecraft/loader collaborators are lightweight Java stubs; full GameTests are a separate gate.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "source-shared/src/main/java"
PUBLIC = [
    "buildcraft/api/v2/OperationMode.java",
    *["buildcraft/api/v2/energy/" + name + ".java" for name in (
        "MjAmount", "MjTransferResult", "MjTransferPolicy", "MjPort",
        "MjDemandPort", "MjPassivePort", "MjStorage", "MjStoragePolicy",
        "MjStorageState", "MjPortRole", "MjPortDescriptor", "MjPortProvider",
        "MjConnectionContext", "MjConnectionRule", "EnergyConversion",
        "EnergyConversionStatus", "EnergyRateUnit", "EnergyService",
        "PowerLossContext", "PowerLossEffectService",
    )],
]
STUBS = {
    "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
public class ResourceLocation {
 private final String id;
 protected ResourceLocation(String id){this.id=id;}
 public static ResourceLocation tryParse(String value){return new ResourceLocation(value);}
 @Override public String toString(){return id;}
}""",
    "net/minecraft/resources/Identifier.java": """package net.minecraft.resources;
public final class Identifier extends ResourceLocation {
 private Identifier(String id){super(id);}
 public static Identifier tryParse(String id){return new Identifier(id);}
}""",
    "net/minecraft/core/BlockPos.java": """package net.minecraft.core;
public record BlockPos(int x,int y,int z) {}""",
    "net/minecraft/core/Direction.java": """package net.minecraft.core;
public enum Direction {NORTH,SOUTH,EAST,WEST,UP,DOWN;}""",
    "net/minecraft/world/level/block/entity/BlockEntity.java": """package net.minecraft.world.level.block.entity;
public class BlockEntity {}""",
    "net/minecraft/world/level/Level.java": """package net.minecraft.world.level;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
public class Level {
 public final Map<BlockPos,BlockEntity> tiles=new HashMap<>();
 public boolean loaded=true; public Object server=new Object(); public long time;
 public boolean hasChunkAt(BlockPos p){return loaded;}
 public Object getServer(){return server;}
 public long getGameTime(){return time;}
 public BlockEntity getBlockEntity(BlockPos p){return tiles.get(p);}
}""",
    "buildcraft/api/v2/machine/MachineView.java": """package buildcraft.api.v2.machine;
import java.util.*; import net.minecraft.core.Direction;
import buildcraft.api.v2.energy.MjPort;
public interface MachineView {
 default Optional<MjPort> mjPort(Direction side){return Optional.empty();}
}""",
    "buildcraft/api/v2/machine/MachineHostProvider.java": """package buildcraft.api.v2.machine;
public interface MachineHostProvider {MachineView machineHost();}""",
    "buildcraft/api/v2/BuildCraftRegistries.java": """package buildcraft.api.v2;
import buildcraft.api.v2.energy.MjConnectionRule;
public final class BuildCraftRegistries {
 public static final String MJ_CONNECTION_RULES="mj_connection_rules";
}""",
    "buildcraft/api/v2/BuildCraftServices.java": """package buildcraft.api.v2;
import buildcraft.api.v2.energy.PowerLossEffectService;
public final class BuildCraftServices {
 public static final String POWER_LOSS_EFFECTS="power_loss_effects";
}""",
    "buildcraft/api/v2/BuildCraftApi.java": """package buildcraft.api.v2;
import java.util.*;
import buildcraft.api.v2.energy.*;
public final class BuildCraftApi {
 public static long lost;
 public static Registry<MjConnectionRule> registry(String key){return new Registry<>();}
 public static class Registry<T> {public List<T> values(){return List.of();}}
 public static PowerLossEffectService service(String key) {
  return c -> lost+=c.lost().microMj();
 }
}""",
    "buildcraft/lib/BCLibConfig.java": """package buildcraft.lib;
public final class BCLibConfig {
 public static final Conversion mjFeConversion=new Conversion();
 public static final PowerMode powerMode=new PowerMode();
 public static TimeGap displayTimeGap=TimeGap.SECONDS;
 public static class Conversion {public long mjPerFe=1000L;}
 public static class PowerMode {
  public boolean isAutoconvertEnabled(){return true;}
  public boolean isDisplayFe(){return true;}
 }
 public enum TimeGap {SECONDS,TICKS;}
}""",
}
ADDON = r'''package addon;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
public final class IndependentEnergyAddon {
 public static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 public static void run(EnergyService service,Level level,BlockPos pos){
  MjStoragePolicy policy=new MjStoragePolicy(MjAmount.ofMicro(100),MjAmount.ofMicro(300),
    MjAmount.ofMicro(20),MjAmount.ofMicro(15),true,true);
  MjStorage buffer=service.createStorage(policy,MjAmount.ofMicro(190));
  check(buffer.capacity().microMj()==100,"nominal capacity");
  check(buffer.policy().maximumStored().microMj()==300,"overcharge capacity");
  check(buffer.insert(MjAmount.ofMicro(50),OperationMode.SIMULATE).transferred().microMj()==20,"simulation available room");
  check(buffer.stored().microMj()==190,"simulate mutated buffer");
  check(buffer.insert(MjAmount.ofMicro(50),OperationMode.EXECUTE).transferred().microMj()==20,"tick input rate");
  check(buffer.stored().microMj()==210,"deposit lost");
  check(buffer.insert(MjAmount.ofMicro(1),OperationMode.EXECUTE).transferred().isZero(),"rate limit bypass");
  check(buffer.extract(MjAmount.ofMicro(16),MjAmount.ofMicro(17),OperationMode.SIMULATE)
       .transferred().isZero(),"partial transfer violated minimum");
  check(buffer.extract(MjAmount.ofMicro(10),MjAmount.ofMicro(30),OperationMode.SIMULATE)
       .transferred().microMj()==15,"extract simulation min/max");
  check(buffer.stored().microMj()==210,"simulate extract mutated");
  check(buffer.extract(MjAmount.ofMicro(10),MjAmount.ofMicro(30),OperationMode.EXECUTE)
       .transferred().microMj()==15,"real minimum extraction");
  check(buffer.extract(MjAmount.ofMicro(1),OperationMode.EXECUTE).transferred().isZero(),"output rate bypass");
  MjStorageState saved=buffer.snapshot();
  check(saved.microMj()==195,"snapshot");
  buffer.restore(MjStorageState.fromMicroMj(210));
  check(buffer.stored().microMj()==210,"restore");
  level.time=1;
  check(service.tickStorage(buffer,level,pos).microMj()==1,"BC8 overcharge decay ceiling");
  check(buffer.stored().microMj()==209,"decay conservation");
  check(service.tickStorage(buffer,level,pos).isZero(),"same tick double decay");
  check(buffer.insert(MjAmount.ofMicro(21),OperationMode.EXECUTE).transferred().microMj()==20,"next tick budget");
  buffer.restore(saved);
  check(buffer.stored().microMj()==195,"restore full state");
  boolean invalid=false;
  try {buffer.restore(MjStorageState.fromMicroMj(301));} catch(IllegalArgumentException ok){invalid=true;}
  check(invalid,"oversized persisted state accepted");
  EnergyConversion conversion=service.conversion();
  check(conversion.feToMicroMj(3)==3000 && conversion.microMjToWholeFe(3499)==3,"FE/MJ unit conversion");
  check(conversion.conversionRemainder(3499)==499,"fractional FE remainder");
  check(conversion.feToMicroMjClamped(Long.MAX_VALUE)==Long.MAX_VALUE,"capacity overflow");
  check(MjAmount.ofMicro(10).plus(MjAmount.ofMicro(20)).microMj()==30,"micro unit sum");
  boolean overflow=false;
  try {MjAmount.ofMicro(Long.MAX_VALUE).plus(MjAmount.ofMicro(1));}catch(ArithmeticException ok){overflow=true;}
  check(overflow,"MJ overflow guard");
 }
}'''
HOST = r'''package buildcraft.lib.internal.api.v2;
import addon.IndependentEnergyAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.energy.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.*;
import java.util.*;
public final class EnergyConformanceHost {
 static class Tile extends BlockEntity implements MjPortProvider {
  MjPort port;
  Tile(MjPort port){this.port=port;}
  public Optional<MjPort> mjPort(Direction side){return Optional.of(port);}
 }
 public static void main(String[] args){
  EnergyService svc=new EnergyServiceImpl();
  BlockPos pos=new BlockPos(1,2,3);
  Level level=new Level();
  IndependentEnergyAddon.run(svc,level,pos);
  if(BuildCraftApi.lost!=1)throw new AssertionError("Power-loss effect service not called: "+BuildCraftApi.lost);
  MjStorage buffer=svc.createStorage(new MjStoragePolicy(MjAmount.ofMicro(100),
    MjAmount.ofMicro(200),MjAmount.ofMicro(12),MjAmount.ofMicro(8),true,true),MjAmount.ofMicro(33));
  level.tiles.put(pos,new Tile(buffer));
  if(svc.port(level,pos,Direction.NORTH).orElseThrow()!=buffer)throw new AssertionError("MJ port discovery");
  MjPortDescriptor descriptor=svc.descriptor(level,pos,Direction.NORTH).orElseThrow();
  if(!descriptor.has(MjPortRole.PASSIVE_PROVIDER) || !descriptor.has(MjPortRole.REDSTONE_RECEIVER)
    || descriptor.maxInsertPerTick().microMj()!=12 || descriptor.maxExtractPerTick().microMj()!=8)
    throw new AssertionError("MJ metadata/role visibility: "+descriptor);
  if(svc.requested(level,pos,Direction.NORTH).orElseThrow().microMj()!=12)
    throw new AssertionError("Live demand query");
  level.loaded=false;
  if(svc.port(level,pos,Direction.NORTH).isPresent())throw new AssertionError("Unloaded world capability access");
  if(!svc.tickStorage(buffer,level,pos).isZero())throw new AssertionError("Ticked unloaded world");
  level.loaded=true;level.server=null;
  if(!svc.tickStorage(buffer,level,pos).isZero())throw new AssertionError("Ticked client world");
  System.out.println("BC8-P03 PASS API-only addon energy, MJ/FE, buffer, rates, loss, persistence, demand, metadata");
 }
}'''

def call(*args: str) -> str:
    p = subprocess.run(args, capture_output=True, text=True, timeout=105)
    if p.returncode:
        raise AssertionError(" ".join(args[:6])+"\n"+p.stdout+"\n"+p.stderr[-16000:])
    return p.stdout

def write(base: Path, path: str, text: str) -> Path:
    full = base/path
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_text(text, encoding="utf-8")
    return full

class EnergyConformanceTest(unittest.TestCase):
    def test_api_only_jar_and_production_backends(self):
        if not all(shutil.which(it) for it in ("javac", "java", "jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-p03-") as td:
            root = Path(td)
            stubs = [write(root/"stub", name, source) for name, source in STUBS.items()]
            api_dir = root/"api-classes"; api_dir.mkdir()
            call("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-d",str(api_dir),
                *map(str,stubs),*(str(SRC/f) for f in PUBLIC))
            api_jar = root/"public-api.jar"
            call("jar","--create","--file",str(api_jar),"-C",str(api_dir),".")
            addon_file = write(root,"addon-src/addon/IndependentEnergyAddon.java",ADDON)
            addon_dir = root/"addon-classes"; addon_dir.mkdir()
            call("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                 "-cp",str(api_jar),"-d",str(addon_dir),str(addon_file))
            addon_jar = root/"addon.jar"
            call("jar","--create","--file",str(addon_jar),"-C",str(addon_dir),".")
            with zipfile.ZipFile(addon_jar) as addon:
                self.assertTrue(all(not x.startswith(("buildcraft/","net/minecraft/")) for x in addon.namelist()))
            host_file = write(root,"host-src/buildcraft/lib/internal/api/v2/EnergyConformanceHost.java",HOST)
            for family, base in (
                ("legacy", "source-shared/src/main/java/buildcraft/lib/internal/api/v2"),
                ("26.x", "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/internal/api/v2"),
            ):
                with self.subTest(family=family):
                    backend = [ROOT/base/"EnergyServiceImpl.java",
                        ROOT/base/"energy/MjStorageImpl.java",
                        ROOT/"source-shared/src/main/java/buildcraft/lib/internal/api/v2/energy/MjRuntimeLookup.java"]
                    classdir = root/(family+"-classes"); classdir.mkdir()
                    call("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                        "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),"-d",str(classdir),
                        str(host_file),*(str(p) for p in backend))
                    result = call("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(classdir))),
                        "buildcraft.lib.internal.api.v2.EnergyConformanceHost")
                    self.assertIn("BC8-P03 PASS",result)

    def test_native_bridge_and_document_independent_ci(self):
        paths = [
            "source-platforms/forge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
            "source-platforms/neoforge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
            "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
        ]
        for path in paths:
            with self.subTest(path=path):
                text=(ROOT/path).read_text(encoding="utf-8")
                self.assertIn("implements MjDemandPort, MjPassivePort",text)
                self.assertIn("provider.extractPower(minimum.microMj(), maximum.microMj()",text)
                self.assertIn("feToMicroMjClamped",text)
                self.assertIn("!level.hasChunkAt(pos)",text)
        for path in (
            "source-platforms/forge/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java",
            "source-platforms/neoforge/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java",
            "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/internal/api/v2/platform/PlatformApi2Bootstrap.java",
        ):
            with self.subTest(path=path):
                text=(ROOT/path).read_text(encoding="utf-8")
                self.assertIn("implements SlottedItemPort",text)
                self.assertIn("IItemHandlerFiltered",text)
                self.assertIn("ItemStack.matches(normalized, test)",text)
                self.assertIn("!level.hasChunkAt(pos)",text)
        for path in (
            "source-platforms/forge/src/main/java/buildcraft/lib/fluid/FuelApiBridge.java",
            "source-platforms/neoforge/src/main/java/buildcraft/lib/fluid/FuelApiBridge.java",
            "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/fluid/FuelApiBridge.java",
        ):
            with self.subTest(path=path):
                text=(ROOT/path).read_text(encoding="utf-8")
                fragment=text.split("private static FluidStack stackOfVariantWithComponents",1)[1].split("public static FluidStack stackOf(",1)[0]
                self.assertIn("return FluidStack.EMPTY;",fragment)
                self.assertNotIn("return new FluidStack(fluid, amount);\n        } catch",fragment)
        self.assertNotIn('require("README.md",',(ROOT/"scripts/validate-fe-compat.py").read_text(encoding="utf-8"))
        for file in (SRC/"buildcraft/api/v2/energy").glob("*.java"):
            text=file.read_text(encoding="utf-8")
            self.assertNotIn("import net.minecraftforge",text)
            self.assertNotIn("import net.neoforged",text)
            self.assertNotIn("import net.fabricmc",text)

if __name__ == "__main__":
    unittest.main()
