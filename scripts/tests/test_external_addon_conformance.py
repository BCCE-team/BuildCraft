#!/usr/bin/env python3
"""Compile independent addon JARs against public API bytecode and exercise the real runtime adapters.

Minecraft/loader stand-ins are used only for isolated Java tests. A successful result
is not a replacement for compiling and launching a real mod against a game distribution.
"""
from __future__ import annotations

import importlib
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "source-shared/src/main/java"
ADDONS = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture"


def run(*args: str, timeout: int = 90) -> None:
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise AssertionError(f"{' '.join(args[:4])}: {result.stdout}\n{result.stderr[-15000:]}")


def write(root: Path, relative: str, contents: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")
    return path


def public_source(path: str) -> bool:
    return path.startswith("buildcraft/api/v2/")


def compile_jar(sources: list[Path], output: Path, *, dependencies: tuple[Path, ...] = ()) -> None:
    target = output.parent / (output.stem + "-classes")
    target.mkdir(parents=True, exist_ok=True)
    cmd = ["javac", "--release", "17", "-encoding", "UTF-8", "-sourcepath", "", "-d", str(target)]
    if dependencies:
        cmd += ["-cp", os.pathsep.join(map(str, dependencies))]
    run(*cmd, *map(str, sources), timeout=95)
    run("jar", "--create", "--file", str(output), "-C", str(target), ".", timeout=30)


def compile_addon(addon: Path, jars: tuple[Path, ...], output: Path) -> None:
    text = addon.read_text(encoding="utf-8")
    forbidden = re.compile(r"\bbuildcraft\.(?!api\.v2\b)[a-zA-Z0-9_]+")
    if forbidden.search(text):
        raise AssertionError(f"{addon.name} imports implementation classes")
    compile_jar([addon], output, dependencies=jars)
    entries = subprocess.run(["jar", "--list", "--file", str(output)], capture_output=True, text=True, check=True).stdout
    if "buildcraft/" in entries or "net/minecraft/" in entries:
        raise AssertionError(f"Addon JAR contains shaded BuildCraft or Minecraft classes: {entries}")
    import zipfile
    with zipfile.ZipFile(output) as archive:
        for name in archive.namelist():
            if name.endswith(".class") and re.search(rb"buildcraft/(?!api/v2/)", archive.read(name)):
                raise AssertionError(f"Addon bytecode {name} references BCCE implementation packages")
    if "/" not in entries or ".class" not in entries:
        raise AssertionError("Addon compilation produced no bytecode")


def load_fixture(name: str):
    return importlib.import_module(name)


class ExternalAddonConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not all(shutil.which(name) for name in ("javac", "java", "jar")):
            raise unittest.SkipTest("JDK 17+ required")

    def test_custom_pipe_registration_jar(self) -> None:
        mod = load_fixture("test_custom_pipe_types")
        for registry in mod.REGISTRIES:
            with self.subTest(registry=registry.as_posix()):
                with tempfile.TemporaryDirectory(prefix="bcce-conformance-pipes-") as td:
                    root = Path(td)
                    stubs = [write(root / "host", name, content) for name, content in mod.STUBS.items()]
                    carrier = ("buildcraft.lib.platform.registry.BCRegistryEntry"
                               "<net.minecraft.world.level.block.Block>"
                               if "/old/" in registry.as_posix() else
                               "java.util.function.Supplier<? extends net.minecraft.world.level.block.Block>")
                    interface = write(root / "host", "buildcraft/transport/internal/pipe/IPipeRegistry.java",
                        "package buildcraft.transport.internal.pipe;\n"
                        "import net.minecraft.resources.ResourceLocation;\n"
                        "public interface IPipeRegistry {"
                        "void registerPipe(PipeDefinition d);"
                        f"IItemPipe registryItemForPipe({carrier} b,PipeDefinition d);"
                        "buildcraft.transport.item.ItemPipeHolder createItemForPipe(PipeDefinition d);"
                        "void setItemForPipe(PipeDefinition d,IItemPipe item);"
                        "IItemPipe getItemForPipe(PipeDefinition d);"
                        "PipeDefinition getDefinition(ResourceLocation id);"
                        "Iterable<PipeDefinition> getAllRegisteredPipes();}")
                    actual_method = mod.method_from_source(
                        (ROOT / "source-platforms/neoforge/src/main/java/buildcraft/transport/pipe/Pipe.java")
                        .read_text(encoding="utf-8"), "acceptItemInjection(")
                    pipe = write(root / "host", "buildcraft/transport/pipe/Pipe.java",
                        "package buildcraft.transport.pipe;\n"
                        "import java.util.*;import buildcraft.api.v2.*;"
                        "import buildcraft.api.v2.pipe.*;"
                        "import buildcraft.lib.internal.debug.BCLog;"
                        "import net.minecraft.core.*;import net.minecraft.world.item.*;"
                        "public class Pipe implements PipeMutationContext {"
                        "public List<PipeComponent> apiComponents=List.of();"
                        "public Set<Direction> connectedSides(){return Set.of();}"
                        "public void markChanged(){}"
                        "public BlockPos position(){return new BlockPos(0,0,0);}"
                        + actual_method + "\n}")
                    production = [mod.JAVA / path for path in mod.COMMON]
                    bcce_jar, addon_jar, test_jar = (root / name for name in
                                                     ("bcce.jar", "addon.jar", "host-tests.jar"))
                    compile_jar(stubs + production + [interface, pipe, registry], bcce_jar)
                    compile_addon(ADDONS / "CustomPipeFixtureAddon.java", (bcce_jar,), addon_jar)
                    test = write(root, "test/Main.java", mod.MAIN)
                    compile_jar([test], test_jar, dependencies=(bcce_jar, addon_jar))
                    run("java", "-cp", os.pathsep.join(map(str, (bcce_jar, addon_jar, test_jar))), "test.Main")

    def test_fluid_mj_fe_resource_jar(self) -> None:
        mod = load_fixture("test_pipe_resource_transfers")
        with tempfile.TemporaryDirectory(prefix="bcce-conformance-resources-") as td:
            root = Path(td)
            runtime_stubs = mod.native_flow_stubs()
            public_stubs = []
            backend_stubs = []
            for path, content in mod.STUBS.items():
                entry = write(root / "stubs", path, content)
                (backend_stubs if path.startswith(("buildcraft/transport/", "buildcraft/lib/"))
                 else public_stubs).append(entry)
            for path, content in runtime_stubs.items():
                backend_stubs.append(write(root / "stubs", path, content))
            public_sources = [path for path in mod.SOURCES if "buildcraft/api/v2/" in path.as_posix()]
            runtime_sources = [path for path in mod.SOURCES if "buildcraft/api/v2/" not in path.as_posix()]
            api_jar, addon_jar, backend_jar = (root / name for name in
                                               ("api.jar", "addon.jar", "backend.jar"))
            compile_jar(public_stubs + public_sources, api_jar)
            compile_addon(ADDONS / "ResourceConformanceAddon.java", (api_jar,), addon_jar)
            harness = mod.FIXTURE
            harness = harness.replace("service().transferFluid(",
                "dev.bcce.apifixture.ResourceConformanceAddon.fluid(service(),")
            harness = harness.replace("service().transferMj(",
                "dev.bcce.apifixture.ResourceConformanceAddon.mj(service(),")
            harness = harness.replace("service().transferExternalEnergy(",
                "dev.bcce.apifixture.ResourceConformanceAddon.fe(service(),")
            main = write(root, "test/Fixture.java", harness)
            compile_jar(backend_stubs + runtime_sources + [main], backend_jar,
                        dependencies=(api_jar, addon_jar))
            run("java", "-cp", os.pathsep.join(map(str, (api_jar, addon_jar, backend_jar))), "test.Fixture")

    def test_machine_jar(self) -> None:
        mod = load_fixture("test_machine_runtime")
        self._host_with_addon(
            mod.STUBS, mod.SOURCES,
            ADDONS / "CustomMachineFixtureAddon.java",
            "buildcraft.lib.internal.api.v2.MachineRuntimeFixture", mod.MAIN,
            "buildcraft/lib/internal/api/v2/MachineRuntimeFixture.java",
        )

    def test_engine_jar(self) -> None:
        mod = load_fixture("test_engine_runtime")
        self._host_with_addon(
            mod.STUBS, mod.SOURCES + mod.ENGINE_SOURCES,
            ADDONS / "CustomEngineFixtureAddon.java",
            "buildcraft.lib.internal.api.v2.EngineRuntimeFixture", mod.MAIN,
            "buildcraft/lib/internal/api/v2/EngineRuntimeFixture.java",
        )

    def test_presentation_jar(self) -> None:
        mod = load_fixture("test_presentation_runtime")
        self._host_with_addon(
            mod.STUBS, mod.PRODUCTION,
            ADDONS / "PresentationConformanceAddon.java",
            "fixture.PresentationConformance", PRESENTATION_TEST,
            "fixture/PresentationConformance.java",
        )

    def test_robot_task_jar(self) -> None:
        mod = load_fixture("test_robot_task_runtime")
        self._host_with_addon(
            mod.STUBS, mod.JAVA,
            ADDONS / "RobotTaskConformanceAddon.java",
            "fixture.RobotConformance", ROBOT_TEST,
            "fixture/RobotConformance.java",
            extra_host={"fixture/RobotTaskFixture.java": mod.MAIN},
            runtime_stub_prefixes=("buildcraft/robotics/", "buildcraft/lib/internal/"),
        )

    def test_requester_jar(self) -> None:
        mod = load_fixture("test_request_lifecycle")
        with tempfile.TemporaryDirectory(prefix="bcce-conformance-request-") as td:
            root = Path(td)
            stub_files = [write(root / "stubs", path, contents) for path, contents in mod.STUBS.items()]
            api_jar, addon_jar, host_jar = (root / name for name in ("api.jar", "addon.jar", "host.jar"))
            compile_jar(stub_files + [mod.SRC / item for item in mod.PUBLIC], api_jar)
            addon = write(root, "addon/ExampleRequester.java", mod.ADDON)
            compile_addon(addon, (api_jar,), addon_jar)
            test = write(root, "RequestTest.java", mod.TEST)
            compile_jar([mod.SRC / "buildcraft/robotics/internal/api2/RequestServiceImpl.java", test],
                        host_jar, dependencies=(api_jar, addon_jar))
            run("java", "-cp", os.pathsep.join(map(str, (api_jar, addon_jar, host_jar))), "RequestTest")

    def test_additional_pipes_teleporter_jar(self) -> None:
        mod = load_fixture("test_pipe_item_transit")
        with tempfile.TemporaryDirectory(prefix="bcce-conformance-teleport-") as td:
            root = Path(td)
            # Use the real public item transfer contracts rather than test-local lookalikes.
            override = {
                "buildcraft/api/v2/OperationMode.java",
                "buildcraft/api/v2/item/ItemPort.java",
                "buildcraft/api/v2/pipe/PipeComponent.java",
            }
            stub_files = [write(root / "stubs", path, contents) for path, contents in mod.STUBS.items()
                          if path not in override]
            api_files = [SOURCE / path for path in (
                "buildcraft/api/v2/OperationMode.java",
                "buildcraft/api/v2/item/ItemPort.java",
                "buildcraft/api/v2/item/ItemTransferPolicy.java",
                "buildcraft/api/v2/item/ItemTransferResult.java",
                "buildcraft/api/v2/pipe/PipeComponent.java",
                "buildcraft/api/v2/pipe/ItemTransitPhase.java",
                "buildcraft/api/v2/pipe/ItemTransitStatus.java",
                "buildcraft/api/v2/pipe/ItemTransitTransfer.java",
                "buildcraft/api/v2/pipe/ItemTransitContext.java",
                "buildcraft/api/v2/pipe/ItemTransitComponent.java",
                "buildcraft/api/v2/pipe/ItemTransitData.java",
            )]
            stub_files = [file for file in stub_files
                          if file.name != "ResourceLocation.java"]
            stub_files.append(write(root / "stubs", "net/minecraft/resources/ResourceLocation.java",
                "package net.minecraft.resources; public record ResourceLocation(String namespace, String path) {" 
                "public static ResourceLocation tryParse(String s) {String[] a=s.split(\":\",2);"
                "return a.length==2?new ResourceLocation(a[0],a[1]):null;} }"))
            # Stand-ins are restricted to non-tested interfaces and platform types.
            stub_files += [write(root / "stubs", "buildcraft/api/v2/item/ItemMatcher.java",
                "package buildcraft.api.v2.item; public interface ItemMatcher {}")]
            api_jar, addon_jar, host_jar = (root / name for name in ("api.jar", "addon.jar", "host.jar"))
            compile_jar(stub_files + api_files, api_jar)
            compile_addon(ADDONS / "AdditionalPipesConformanceAddon.java", (api_jar,), addon_jar)
            runtime_files = [SOURCE / "buildcraft/transport/api2/PipeItemTransitRuntime.java"]
            test = write(root, "fixture/TeleportConformance.java", TELEPORT_TEST)
            compile_jar(runtime_files + [test], host_jar, dependencies=(api_jar, addon_jar))
            run("java", "-cp", os.pathsep.join(map(str, (api_jar, addon_jar, host_jar))), "fixture.TeleportConformance")

    def _host_with_addon(self, stubs: dict[str, str], names: list[str], addon: Path,
                         main_class: str, main_source: str, main_name: str,
                         extra_host: dict[str, str] | None = None,
                         runtime_stub_prefixes: tuple[str, ...] = ()) -> None:
        with tempfile.TemporaryDirectory(prefix="bcce-conformance-runtime-") as td:
            root = Path(td)
            stub_files = [write(root / "stubs", path, contents) for path, contents in stubs.items()
                          if not path.startswith(runtime_stub_prefixes)]
            runtime_stubs = [write(root / "stubs", path, contents) for path, contents in stubs.items()
                             if path.startswith(runtime_stub_prefixes)]
            public = [SOURCE / path for path in names if public_source(path)]
            runtime = [SOURCE / path for path in names if not public_source(path)]
            api_jar, addon_jar, runtime_jar = (root / name for name in ("api.jar", "addon.jar", "runtime.jar"))
            compile_jar(stub_files + public, api_jar)
            compile_addon(addon, (api_jar,), addon_jar)
            main = write(root, main_name, main_source)
            extra = [write(root, name, contents) for name, contents in (extra_host or {}).items()]
            compile_jar(runtime_stubs + runtime + [main] + extra, runtime_jar,
                        dependencies=(api_jar, addon_jar))
            run("java", "-cp", os.pathsep.join(map(str, (api_jar, addon_jar, runtime_jar))), main_class)


PRESENTATION_TEST = r'''package fixture;
import java.util.*;
import dev.bcce.apifixture.PresentationConformanceAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.client.*;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.machine.*;
import buildcraft.lib.internal.api.v2.ClientPresentationServiceImpl;
import net.minecraft.resources.ResourceLocation;

public final class PresentationConformance {
 static void require(boolean valid,String why){if(!valid)throw new AssertionError(why);}
 static final PresentationConformanceAddon ADDON=new PresentationConformanceAddon();
 static ResourceLocation id(String x){return ResourceLocation.tryParse("api_fixture:"+x);}
 static final ResourceLocation SOUND=id("steam_sound");
 static class Pipe implements PipeView {
  boolean client=true;int value=37;
  public ResourceLocation typeId(){return PresentationConformanceAddon.PIPE;}
  public boolean isClientSide(){return client;}
  public <T> Optional<T> presentation(ResourceLocation key,Class<T> type){
   Object data=key.equals(PresentationConformanceAddon.CHANNEL)?value:null;
   return type.isInstance(data)?Optional.of(type.cast(data)):Optional.empty();
  }
 }
 static class Machine implements MachineExecutionContext {
  boolean client=true;int strength=77;
  public ResourceLocation typeId(){return PresentationConformanceAddon.MACHINE;}
  public boolean isServerSide(){return !client;}
  public WorkStatus workStatus(){return new WorkStatus(.5);}
  public <T> Optional<T> presentation(ResourceLocation key,Class<T> type){
   Object data=key.equals(PresentationConformanceAddon.CHANNEL)?strength:null;
   return type.isInstance(data)?Optional.of(type.cast(data)):Optional.empty();
  }
 }
 static class Engine extends Machine implements EngineHost {
  public ResourceLocation typeId(){return PresentationConformanceAddon.ENGINE;}
  public EngineVisualState visualState(){return new EngineVisualState(new HeatState(30,60),.25,SOUND);}
 }
 public static void main(String[] args) {
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS)
   .register(PresentationConformanceAddon.PIPE,ADDON);
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS)
   .register(PresentationConformanceAddon.MACHINE,ADDON);
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS)
   .register(PresentationConformanceAddon.ENGINE,ADDON);
  ClientPresentationServiceImpl service=new ClientPresentationServiceImpl();
  Pipe pipe=new Pipe();
  var first=service.frame(pipe,.25f).orElseThrow();
  require(first.model().equals(id("pipe_bright")) &&
   first.animations().get(PresentationConformanceAddon.CHANNEL_ANIMATION)==.37,
   "external dynamic pipe state not rendered");
  pipe.value=0;
  require(service.frame(pipe,0).orElseThrow().model().equals(id("pipe_dark")),
   "client delta did not update dynamic selector");
  pipe.client=false;
  require(service.frame(pipe,0).isEmpty(),"server pipe resolved presentation");
  Machine machine=new Machine();
  var machineFrame=service.frame(machine,0).orElseThrow();
  require(machineFrame.animations().get(PresentationConformanceAddon.CHANNEL_ANIMATION)==.77,
   "machine presentation did not receive sync state");
  require(machineFrame.animations().get(id("work_progress"))==null &&
   machineFrame.animations().get(ResourceLocation.tryParse("buildcraft:work_progress"))==.5,
   "machine runtime progress not inherited");
  Engine engine=new Engine();
  var frame=service.frame(engine,.4f).orElseThrow();
  require(frame.sound().equals(SOUND) &&
   frame.animations().get(ResourceLocation.tryParse("buildcraft:engine_piston"))==.25,
   "engine visuals/sound lost during selector resolution");
  machine.client=false;
  require(service.frame(machine,0).isEmpty(),"server machine resolved presentation");
  System.out.println("PASS external presentation addon JAR and runtime fallback");
 }
}
'''


ROBOT_TEST = r'''package fixture;
import dev.bcce.apifixture.RobotTaskConformanceAddon;
import buildcraft.api.v2.*;
import buildcraft.api.v2.robot.*;
import buildcraft.robotics.ai.AIRobotMain;
import buildcraft.robotics.internal.api2.ApiTaskAI;
import buildcraft.robotics.internal.legacy.robots.RobotManager;

public final class RobotConformance {
 static void require(boolean b,String detail){if(!b)throw new AssertionError(detail);}
 public static void main(String[] args) throws Exception {
  RobotManager.registerAIRobot(AIRobotMain.class,"main");
  BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES)
   .register(RobotTaskConformanceAddon.ID,RobotTaskConformanceAddon.TYPE);
  AIRobotMain robot=RobotTaskFixture.create(42);
  robot.setOverridingAI(new ApiTaskAI(robot.robot,new RobotTaskConformanceAddon(0)));
  AIRobotMain resumed=RobotTaskFixture.restore(robot);
  require(resumed.getOverridingAI() instanceof ApiTaskAI,"external task queue not restored");
  resumed.cycle();
  resumed.cycle();
  require(RobotTaskConformanceAddon.ticks()==2,"external task ticks missing");
  AIRobotMain afterReload=RobotTaskFixture.restore(resumed);
  require(afterReload.getActiveAI() instanceof ApiTaskAI,"external task missing after reload");
  require(((ApiTaskAI)afterReload.getActiveAI()).taskTypeId().orElseThrow()
   .equals(RobotTaskConformanceAddon.ID),"external task identity not persistent");
  afterReload.cycle();
  require(RobotTaskConformanceAddon.ticks()==3 && RobotTaskConformanceAddon.completions()==1,
   "external task state or terminal callback lost");
  require(RobotTaskConformanceAddon.resumes()>0,"reload resume callback not issued");
  System.out.println("PASS external robot task addon JAR persists and resumes");
 }
}
'''


TELEPORT_TEST = r'''package fixture;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.PipeItemTransitRuntime;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import dev.bcce.apifixture.AdditionalPipesConformanceAddon;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import java.util.*;

public final class TeleportConformance {
 static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static PipeItemTransitRuntime.Result fire(Pipe pipe,UUID token,int count,ItemTransitPhase phase){
  return PipeItemTransitRuntime.fire(pipe,phase,new ItemStack("iron",count),
   new ItemTransitData(Optional.empty(),0.06),Direction.WEST,Direction.EAST,token);
 }
 public static void main(String[] args){
  MinecraftServer server=new MinecraftServer();
  Level sourceWorld=new Level(server),targetWorld=new Level(server);
  Pipe source=new Pipe(sourceWorld),destination=new Pipe(targetWorld);
  PipeFlowItems intake=new PipeFlowItems();destination.flow=intake;
  targetWorld.tile=new TilePipeHolder(destination);
  AdditionalPipesConformanceAddon addon=new AdditionalPipesConformanceAddon();
  source.registered.add(addon);addon.destination(targetWorld,new BlockPos(10,0,0),Direction.WEST);
  addon.simulate(true);
  var simulated=fire(source,null,9,ItemTransitPhase.CENTER);
  require(simulated.stack().getCount()==9 && intake.insertions==0 &&
   addon.status()==ItemTransitStatus.SIMULATED,"simulation mutated a pipe");
  addon.simulate(false);
  targetWorld.loaded=false;
  var unloaded=fire(source,null,9,ItemTransitPhase.CENTER);
  require(unloaded.stack().getCount()==9 && unloaded.delay()==20 &&
   addon.status()==ItemTransitStatus.UNLOADED && intake.insertions==0,"unloaded destination must retry");
  targetWorld.loaded=true;intake.acceptedCap=4;
  var partial=fire(source,null,9,ItemTransitPhase.CENTER);
  require(partial.stack().getCount()==5 && partial.delay()==1 && intake.insertions==1 &&
   addon.status()==ItemTransitStatus.PARTIAL,"partial delivery lost resource ownership");
  UUID receipt=intake.receipts.iterator().next();
  var duplicate=fire(source,receipt,9,ItemTransitPhase.CENTER);
  require(duplicate.stack().isEmpty() && intake.insertions==1 &&
   addon.status()==ItemTransitStatus.MOVED,"retry inserted a duplicate");
  intake.acceptedCap=64;
  var remaining=fire(source,partial.id(),partial.stack().getCount(),ItemTransitPhase.CENTER);
  require(remaining.stack().isEmpty() && intake.insertions==2 && addon.completedTransfers()==3,
   "remaining transfer did not complete");
  var enter=fire(source,null,5,ItemTransitPhase.ENTER);
  require(enter.stack().getCount()==5 && intake.insertions==2,"READONLY phase mutated an item");
  boolean expired=false;
  try {addon.expiredContext().stack();}catch(IllegalStateException e){expired=true;}
  require(expired,"addon retained a live mutable callback context");
  sourceWorld.client=true;
  var client=fire(source,null,5,ItemTransitPhase.CENTER);
  require(client.stack().getCount()==5 && intake.insertions==2,"client created a second transfer");
  require(source.dirtied>0,"source pipe not marked dirty after transfer");
  System.out.println("PASS external Additional Pipes addon JAR item transit");
 }
}
'''


if __name__ == "__main__":
    unittest.main()
