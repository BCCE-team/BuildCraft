#!/usr/bin/env python3
"""Energy, fluid transfer and pipe resource event contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile
import pathlib

ENERGY_SRC = ROOT / "source-shared/src/main/java"
ENERGY_PUBLIC = [
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
ENERGY_STUBS = {
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
ENERGY_ADDON = r'''package addon;
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
ENERGY_HOST = r'''package buildcraft.lib.internal.api.v2;
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
  System.out.println("API resource contracts PASS API-only addon energy, MJ/FE, buffer, rates, loss, persistence, demand, metadata");
 }
}'''

def energy_call(*args: str) -> str:
    p = subprocess.run(args, capture_output=True, text=True, timeout=105)
    if p.returncode:
        raise AssertionError(" ".join(args[:6])+"\n"+p.stdout+"\n"+p.stderr[-16000:])
    return p.stdout

def energy_write(base: Path, path: str, text: str) -> Path:
    full = base/path
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_text(text, encoding="utf-8")
    return full

class EnergyConformanceTest(unittest.TestCase):
    def test_api_only_jar_and_production_backends(self):
        if not all(shutil.which(it) for it in ("javac", "java", "jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-energy-") as td:
            root = Path(td)
            stubs = [energy_write(root/"stub", name, source) for name, source in ENERGY_STUBS.items()]
            api_dir = root/"api-classes"; api_dir.mkdir()
            energy_call("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-d",str(api_dir),
                *map(str,stubs),*(str(ENERGY_SRC/f) for f in ENERGY_PUBLIC))
            api_jar = root/"public-api.jar"
            energy_call("jar","--create","--file",str(api_jar),"-C",str(api_dir),".")
            addon_file = energy_write(root,"addon-src/addon/IndependentEnergyAddon.java",ENERGY_ADDON)
            addon_dir = root/"addon-classes"; addon_dir.mkdir()
            energy_call("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                 "-cp",str(api_jar),"-d",str(addon_dir),str(addon_file))
            addon_jar = root/"addon.jar"
            energy_call("jar","--create","--file",str(addon_jar),"-C",str(addon_dir),".")
            with zipfile.ZipFile(addon_jar) as addon:
                self.assertTrue(all(not x.startswith(("buildcraft/","net/minecraft/")) for x in addon.namelist()))
            host_file = energy_write(root,"host-src/buildcraft/lib/internal/api/v2/EnergyConformanceHost.java",ENERGY_HOST)
            for family, base in (
                ("legacy", "source-shared/src/main/java/buildcraft/lib/internal/api/v2"),
                ("26.x", "source-families/26.X/src/main/java/buildcraft/lib/internal/api/v2"),
            ):
                with self.subTest(family=family):
                    backend = [ROOT/base/"EnergyServiceImpl.java",
                        ROOT/base/"energy/MjStorageImpl.java",
                        ROOT/"source-shared/src/main/java/buildcraft/lib/internal/api/v2/energy/MjRuntimeLookup.java"]
                    classdir = root/(family+"-classes"); classdir.mkdir()
                    energy_call("javac","--release","17","-encoding","UTF-8","-sourcepath","",
                        "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),"-d",str(classdir),
                        str(host_file),*(str(p) for p in backend))
                    result = energy_call("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(classdir))),
                        "buildcraft.lib.internal.api.v2.EnergyConformanceHost")
                    self.assertIn("API resource contracts PASS",result)

    def test_native_bridge_and_document_independent_ci(self):
        paths = [
            "source-platforms/forge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
            "source-platforms/neoforge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
            "source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/internal/mj/MjApi2PlatformBridge.java",
        ]
        for path in paths:
            with self.subTest(path=path):
                from source_lookup import resolve_source_path
                text=resolve_source_path(path).read_text(encoding="utf-8")
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
                from source_lookup import resolve_source_path
                text=resolve_source_path(path).read_text(encoding="utf-8")
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
                from source_lookup import resolve_source_path
                text=resolve_source_path(path).read_text(encoding="utf-8")
                fragment=text.split("private static FluidStack stackOfVariantWithComponents",1)[1].split("public static FluidStack stackOf(",1)[0]
                self.assertIn("return FluidStack.EMPTY;",fragment)
                self.assertNotIn("return new FluidStack(fluid, amount);\n        } catch",fragment)
        self.assertNotIn('require("README.md",',(ROOT/"scripts/validate-fe-compat.py").read_text(encoding="utf-8"))
        for file in (ENERGY_SRC/"buildcraft/api/v2/energy").glob("*.java"):
            text=file.read_text(encoding="utf-8")
            self.assertNotIn("import net.minecraftforge",text)
            self.assertNotIn("import net.neoforged",text)
            self.assertNotIn("import net.fabricmc",text)


PIPE_RESOURCES_SRC = ROOT / 'source-shared/src/main/java'

PIPE_RESOURCES_STUBS = {
    'net/minecraft/core/Direction.java': 'package net.minecraft.core; public enum Direction { DOWN,UP,NORTH,SOUTH,WEST,EAST }',
    'net/minecraft/resources/ResourceLocation.java': 'package net.minecraft.resources; public record ResourceLocation(String id) {}',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
public class Level { public boolean client=false; public boolean isClientSide(){return client;}
public Server getServer(){return new Server();} public static class Server { public boolean isSameThread(){return true;} } }''',
    'buildcraft/api/v2/fluid/FluidVolume.java': 'package buildcraft.api.v2.fluid; public record FluidVolume(long amount) {}',
    'buildcraft/api/v2/pipe/PipeView.java': 'package buildcraft.api.v2.pipe; public interface PipeView { Object position(); }',
    'buildcraft/transport/internal/pipe/IPipe.java': 'package buildcraft.transport.internal.pipe; public interface IPipe {}',
    'buildcraft/transport/pipe/Pipe.java': '''package buildcraft.transport.pipe;
import java.util.*; import net.minecraft.world.level.Level;
import buildcraft.api.v2.pipe.*; import buildcraft.transport.internal.pipe.*;
public class Pipe implements IPipe,PipeView {
 private final List<PipeComponent> components=new ArrayList<>();
 public List<PipeComponent> components(){return components;}
 public Object position(){return "test-position";}
 public Holder getHolder(){return new Holder();}
 public static class Holder { public Level world=new Level(); public Level getPipeWorld(){return world;} }
}''',
    'buildcraft/transport/api2/ReadOnlyPipeView.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.PipeView;
public class ReadOnlyPipeView { public static PipeView of(PipeView pipe){return pipe;} }''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {public static final Logger logger=new Logger();
public static class Logger {public void warn(String message,Object... args){} }}''',
}
PIPE_RESOURCES_API_NAMES = (
    'OperationMode', 'pipe/PipeMedium', 'pipe/PipeComponent',
    'pipe/FluidMovementPhase', 'pipe/FluidMovementContext', 'pipe/FluidMovementComponent',
    'pipe/PowerMovementPhase', 'pipe/PowerMovementContext',
    'pipe/PowerFlowSettings', 'pipe/PowerFlowComponent',
)
PIPE_RESOURCES_ADDON = '''package addon;
import buildcraft.api.v2.pipe.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
public final class PipeResourceAddon {
 public static FluidMovementComponent fluid() { return new FluidMovementComponent() {
  public ResourceLocation typeId(){return new ResourceLocation("addon:fluid");}
  public int limitFluidMovement(FluidMovementContext c){
   if(c.phase()==FluidMovementPhase.BEFORE_CENTRE)return Math.min(3,c.offeredMb());
   if(c.phase()==FluidMovementPhase.TO_CENTRE)return Math.min(2,c.offeredMb());
   return c.offeredMb();
  }
 }; }
 public static PowerFlowComponent power() {return new PowerFlowComponent() {
  public ResourceLocation typeId(){return new ResourceLocation("addon:energy");}
  public PowerFlowSettings configure(PipeView pipe,PowerFlowSettings settings){
   return new PowerFlowSettings(settings.medium(),20,settings.lossPerTick(),settings.resistancePpm(),true,settings.disabled());
  }
  public long limitPower(PowerMovementContext c){return Math.min(4,c.offered());}
 }; }
}'''
PIPE_RESOURCES_HOST = '''package host;
import addon.PipeResourceAddon;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.api2.PipeFlowEventRuntime;
import buildcraft.api.v2.*; import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.core.Direction; import net.minecraft.resources.ResourceLocation;
public class PipeResourceHost {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  Pipe pipe=new Pipe(); pipe.components().add(PipeResourceAddon.fluid()); pipe.components().add(PipeResourceAddon.power());
  int before=PipeFlowEventRuntime.fluidLimit(pipe,FluidMovementPhase.BEFORE_CENTRE,Direction.NORTH,
    new FluidVolume(10),10,8,OperationMode.SIMULATE);
  check(before==3,"pre-move cap");
  int moving=PipeFlowEventRuntime.fluidLimit(pipe,FluidMovementPhase.TO_CENTRE,Direction.NORTH,
    new FluidVolume(3),3,8,OperationMode.EXECUTE);
  check(moving==2,"on-move cap");
  long budget=PipeFlowEventRuntime.powerLimit(pipe,PipeMedium.MJ,PowerMovementPhase.SEND,
    Direction.EAST,100,OperationMode.EXECUTE);
  check(budget==4,"mj send cap");
  check(PipeFlowEventRuntime.powerLimit(pipe,PipeMedium.EXTERNAL_ENERGY,PowerMovementPhase.REQUEST,
    Direction.WEST,10,OperationMode.SIMULATE)==4,"fe request cap");
  PowerFlowSettings base=new PowerFlowSettings(PipeMedium.MJ,8,-1,-1,false,false);
  check(PipeFlowEventRuntime.configure(pipe,base).maxPerTick()==20,"public power profile");
  pipe.components().add(new PowerFlowComponent(){
    public ResourceLocation typeId(){return new ResourceLocation("addon:overlimit");}
    public long limitPower(PowerMovementContext c){return Long.MAX_VALUE;}
  });
  check(PipeFlowEventRuntime.powerLimit(pipe,PipeMedium.MJ,PowerMovementPhase.SEND,
     Direction.EAST,100,OperationMode.EXECUTE)==4,"no power creation");
  pipe.components().add(new FluidMovementComponent(){
    public ResourceLocation typeId(){return new ResourceLocation("addon:negative");}
    public int limitFluidMovement(FluidMovementContext c){return -10;}
  });
  check(PipeFlowEventRuntime.fluidLimit(pipe,FluidMovementPhase.INSERT,Direction.NORTH,
    new FluidVolume(11),11,16,OperationMode.EXECUTE)==0,"negative fluid limit");
  pipe.components().add(new PowerFlowComponent(){
    public ResourceLocation typeId(){return new ResourceLocation("addon:throw");}
    public long limitPower(PowerMovementContext c){throw new IllegalStateException("broken addon");}
  });
  check(PipeFlowEventRuntime.powerLimit(pipe,PipeMedium.EXTERNAL_ENERGY,PowerMovementPhase.EXTRACT,
    Direction.EAST,9,OperationMode.EXECUTE)==0,"addon exception fails closed");
  pipe.components().clear();
  check(PipeFlowEventRuntime.fluidLimit(pipe,FluidMovementPhase.TO_CENTRE,Direction.NORTH,
    new FluidVolume(9),9,9,OperationMode.EXECUTE)==9,"native fluid default");
  check(PipeFlowEventRuntime.powerLimit(pipe,PipeMedium.MJ,PowerMovementPhase.SEND,
    Direction.EAST,9,OperationMode.EXECUTE)==9,"native power default");
  boolean invalid=false;
  try {new PowerFlowSettings(PipeMedium.EXTERNAL_ENERGY,(long)Integer.MAX_VALUE+1,-1,-1,true,false);}
  catch(IllegalArgumentException expected){invalid=true;}
  check(invalid,"FE overflow guard");
 }
}'''


def pipe_resources_write(root, name, data):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding='utf-8')
    return path


class PipeResourceEventTest(unittest.TestCase):
    def test_real_native_wiring(self):
        files = [p for p in ROOT.glob('source-*/**/buildcraft/transport/pipe/flow/PipeFlowFluids.java')]
        self.assertEqual(6, len(files))
        for f in files:
            with self.subTest(f=str(f)):
                s = f.read_text(encoding='utf-8')
                for phase in ('BEFORE_CENTRE', 'TO_CENTRE', 'FROM_CENTRE', 'OUTPUT', 'INSERT', 'PIPE_TRANSFER'):
                    self.assertIn('FluidMovementPhase.' + phase, s)
                self.assertIn('PipeFlowEventRuntime.fluidLimit', s)
                self.assertLess(s.index('FluidMovementPhase.BEFORE_CENTRE'), s.index('FluidMovementPhase.TO_CENTRE'))
        for name in ('PipeFlowPower', 'PipeFlowForgeEnergy'):
            files = [p for p in ROOT.glob('source-*/**/buildcraft/transport/pipe/flow/' + name + '.java')]
            self.assertEqual(6, len(files))
            for f in files:
                with self.subTest(f=str(f)):
                    s = f.read_text(encoding='utf-8')
                    for phase in ('REQUEST', 'EXTRACT', 'SEND', 'RECEIVE', 'PIPE_TRANSFER'):
                        self.assertIn('PowerMovementPhase.' + phase, s)
                    self.assertIn('PipeFlowEventRuntime.configure', s)
                    self.assertIn('target.apiPowerLimit', s)

    def test_independent_addon_and_callback_sandbox(self):
        with tempfile.TemporaryDirectory(prefix='bcce-pipe-resources-') as temp:
            root = pathlib.Path(temp)
            stubs = [pipe_resources_write(root/'stub', key, value) for key, value in PIPE_RESOURCES_STUBS.items()]
            api = [PIPE_RESOURCES_SRC / ('buildcraft/api/v2/' + name + '.java') for name in PIPE_RESOURCES_API_NAMES]
            backend = PIPE_RESOURCES_SRC/'buildcraft/transport/api2/PipeFlowEventRuntime.java'
            pubclasses, addonclasses, runtimeclasses = (root/'public', root/'addon', root/'runtime')
            for directory in (pubclasses, addonclasses, runtimeclasses):directory.mkdir()
            subprocess.run(['javac','-d',str(pubclasses), *[str(p) for p in stubs], *[str(p) for p in api]],check=True,capture_output=True,text=True)
            addon=pipe_resources_write(root/'addon-source','addon/PipeResourceAddon.java',PIPE_RESOURCES_ADDON)
            subprocess.run(['javac','-cp',str(pubclasses),'-d',str(addonclasses),str(addon)],check=True,capture_output=True,text=True)
            jar=root/'PipeResourceAddon.jar'
            subprocess.run(['jar','cf',str(jar),'-C',str(addonclasses),'.'],check=True,capture_output=True,text=True)
            host=pipe_resources_write(root/'host-source','host/PipeResourceHost.java',PIPE_RESOURCES_HOST)
            classpath=str(pubclasses)+__import__('os').pathsep+str(jar)
            subprocess.run(['javac','-cp',classpath,'-d',str(runtimeclasses),str(backend),str(host)],check=True,capture_output=True,text=True)
            subprocess.run(['java','-cp',classpath+__import__('os').pathsep+str(runtimeclasses),'host.PipeResourceHost'],check=True,capture_output=True,text=True)

if __name__ == "__main__":
    unittest.main()
