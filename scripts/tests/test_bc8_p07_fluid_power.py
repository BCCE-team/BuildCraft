#!/usr/bin/env python3
"""BC8 P07: fluid and power event wiring, public addon contracts and sandboxed runtime limits."""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / 'source-shared/src/main/java'

STUBS = {
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
API_NAMES = (
    'OperationMode', 'pipe/PipeMedium', 'pipe/PipeComponent',
    'pipe/FluidMovementPhase', 'pipe/FluidMovementContext', 'pipe/FluidMovementComponent',
    'pipe/PowerMovementPhase', 'pipe/PowerMovementContext',
    'pipe/PowerFlowSettings', 'pipe/PowerFlowComponent',
)
ADDON = '''package addon;
import buildcraft.api.v2.pipe.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
public final class P07Addon {
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
HOST = '''package host;
import addon.P07Addon;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.api2.PipeFlowEventRuntime;
import buildcraft.api.v2.*; import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.core.Direction; import net.minecraft.resources.ResourceLocation;
public class P07Host {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  Pipe pipe=new Pipe(); pipe.components().add(P07Addon.fluid()); pipe.components().add(P07Addon.power());
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


def write(root, name, data):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding='utf-8')
    return path


class FluidPowerP07Tests(unittest.TestCase):
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
        with tempfile.TemporaryDirectory(prefix='bc8-p07-') as temp:
            root = pathlib.Path(temp)
            stubs = [write(root/'stub', key, value) for key, value in STUBS.items()]
            api = [SRC / ('buildcraft/api/v2/' + name + '.java') for name in API_NAMES]
            backend = SRC/'buildcraft/transport/api2/PipeFlowEventRuntime.java'
            pubclasses, addonclasses, runtimeclasses = (root/'public', root/'addon', root/'runtime')
            for directory in (pubclasses, addonclasses, runtimeclasses):directory.mkdir()
            subprocess.run(['javac','-d',str(pubclasses), *[str(p) for p in stubs], *[str(p) for p in api]],check=True,capture_output=True,text=True)
            addon=write(root/'addon-source','addon/P07Addon.java',ADDON)
            subprocess.run(['javac','-cp',str(pubclasses),'-d',str(addonclasses),str(addon)],check=True,capture_output=True,text=True)
            jar=root/'P07Addon.jar'
            subprocess.run(['jar','cf',str(jar),'-C',str(addonclasses),'.'],check=True,capture_output=True,text=True)
            host=write(root/'host-source','host/P07Host.java',HOST)
            classpath=str(pubclasses)+__import__('os').pathsep+str(jar)
            subprocess.run(['javac','-cp',classpath,'-d',str(runtimeclasses),str(backend),str(host)],check=True,capture_output=True,text=True)
            subprocess.run(['java','-cp',classpath+__import__('os').pathsep+str(runtimeclasses),'host.P07Host'],check=True,capture_output=True,text=True)


if __name__ == '__main__':
    unittest.main()
