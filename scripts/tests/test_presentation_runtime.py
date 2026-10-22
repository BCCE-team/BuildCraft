#!/usr/bin/env python3
"""Compile the public presentation contracts and real BCCE lookup against loader-neutral client stubs."""
from __future__ import annotations
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'source-shared/src/main/java'
PRODUCTION = [
    'buildcraft/api/v2/client/ClientPresentationService.java',
    'buildcraft/api/v2/client/ContentPresentation.java',
    'buildcraft/api/v2/client/PipePresentation.java',
    'buildcraft/api/v2/client/StatementPresentation.java',
    'buildcraft/api/v2/client/ParameterPresentation.java',
    'buildcraft/api/v2/client/PresentationFrame.java',
    'buildcraft/api/v2/client/DynamicPresentation.java',
    'buildcraft/api/v2/client/MenuPresentation.java',
    'buildcraft/api/v2/client/ActionPresentation.java',
    'buildcraft/lib/internal/api/v2/ClientPresentationServiceImpl.java',
]

STUBS = {
 'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String namespace,String path) {
 public static ResourceLocation tryParse(String s) {if(s==null||!s.contains(":"))return null;
 String[] p=s.split(":",2);return new ResourceLocation(p[0],p[1]);}
 public String toString(){return namespace+":"+path;}
}''',
 'buildcraft/api/v2/pipe/PipeView.java': '''package buildcraft.api.v2.pipe;
import java.util.*;import net.minecraft.resources.*;
public interface PipeView {
 ResourceLocation typeId();boolean isClientSide();
 <T> Optional<T> presentation(ResourceLocation id,Class<T> type);
}''',
 'buildcraft/api/v2/machine/MachineExecutionContext.java': '''package buildcraft.api.v2.machine;
import java.util.*;import net.minecraft.resources.*;
public interface MachineExecutionContext {
 ResourceLocation typeId();boolean isServerSide();WorkStatus workStatus();
 <T> Optional<T> presentation(ResourceLocation id,Class<T> type);
}''',
 'buildcraft/api/v2/machine/WorkStatus.java': '''package buildcraft.api.v2.machine;
public record WorkStatus(double progress) {}''',
 'buildcraft/api/v2/machine/HeatState.java': '''package buildcraft.api.v2.machine;
public record HeatState(double temperature,double criticalTemperature) {}''',
 'buildcraft/api/v2/machine/EngineVisualState.java': '''package buildcraft.api.v2.machine;
import net.minecraft.resources.ResourceLocation;
public record EngineVisualState(HeatState heat,double animationPhase,ResourceLocation soundId) {}''',
 'buildcraft/api/v2/machine/EngineHost.java': '''package buildcraft.api.v2.machine;
public interface EngineHost extends MachineExecutionContext {EngineVisualState visualState();}''',
 'buildcraft/api/v2/BuildCraftRegistries.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.client.*;
public class BuildCraftRegistries {
 public static final Key<ContentPresentation> CLIENT_PRESENTATIONS = new Key<>();
 public static final Key<PipePresentation> PIPE_PRESENTATIONS = new Key<>();
 public static final Key<StatementPresentation> STATEMENT_PRESENTATIONS = new Key<>();
 public static final Key<ParameterPresentation> PARAMETER_PRESENTATIONS = new Key<>();
 public static final Key<DynamicPresentation> DYNAMIC_PRESENTATIONS = new Key<>();
 public static final Key<MenuPresentation> MENU_PRESENTATIONS = new Key<>();
 public static final class Key<T> {}
}''',
 'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import net.minecraft.resources.*;import java.util.*;
public class BuildCraftApi {
 private static final Map<Object, Registry<?>> regs = new HashMap<>();
 @SuppressWarnings("unchecked") public static <T> Registry<T> registry(BuildCraftRegistries.Key<T> id) {
  return (Registry<T>)regs.computeIfAbsent(id,k->new Registry<>());
 }
 public static class Registry<T>{private final Map<ResourceLocation,T> values=new LinkedHashMap<>();
  public T get(ResourceLocation id){return values.get(id);}
  public void register(ResourceLocation id,T value){values.put(id,value);}
 }
}''',
 'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {public static final Logger logger=new Logger();
 public static class Logger{public void warn(String msg,Object...items){}}
}''',
}

FIXTURE = r'''package fixture;
import buildcraft.api.v2.*;
import buildcraft.api.v2.client.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.api.v2.machine.*;
import buildcraft.lib.internal.api.v2.*;
import net.minecraft.resources.*;
import java.util.*;

public class PresentationFixture {
 static ResourceLocation id(String path){return ResourceLocation.tryParse("addon:"+path);}
 static ResourceLocation built(String path){return ResourceLocation.tryParse("buildcraft:"+path);}
 static void ok(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static void rejected(Runnable cb){try{cb.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Expected invalid value");}
 static final ResourceLocation PIPE=id("pipe"), CHANNEL=id("light"), MACHINE=id("machine"), ENGINE=id("engine");
 static final ResourceLocation PIPE_MODEL=id("pipe_default"), GLOWING=id("pipe_lit");
 static final ResourceLocation MENU=id("machine_menu"), SOUND=id("piston_sound"), PHASE=id("phase");
 static final ClientPresentationServiceImpl API=new ClientPresentationServiceImpl();
 static final MenuPresentation GUI=new MenuPresentation(MENU,"menu.addon.machine",List.of(
  new ActionPresentation(id("configure"),"action.addon.configure",null)));
 static class Pipe implements PipeView {
  boolean client=true;int value;
  public ResourceLocation typeId(){return PIPE;}
  public boolean isClientSide(){return client;}
  public <T> Optional<T> presentation(ResourceLocation channel,Class<T> clazz){
   Object snapshot=channel.equals(CHANNEL)?value:null;
   return clazz.isInstance(snapshot)?Optional.of(clazz.cast(snapshot)):Optional.empty();
  }
 }
 static class Machine implements MachineExecutionContext {
  boolean client=true;int fuel=42;
  public ResourceLocation typeId(){return MACHINE;}
  public boolean isServerSide(){return !client;}
  public WorkStatus workStatus(){return new WorkStatus(.75);}
  public <T> Optional<T> presentation(ResourceLocation id,Class<T> type){
   Object state=id.equals(CHANNEL)?fuel:null;
   return type.isInstance(state)?Optional.of(type.cast(state)):Optional.empty();
  }
 }
 static class Engine extends Machine implements EngineHost {
  public ResourceLocation typeId(){return ENGINE;}
  public EngineVisualState visualState(){return new EngineVisualState(new HeatState(20,80),.6,SOUND);}
 }
 public static void main(String[] args) {
  ContentPresentation base=new ContentPresentation(PIPE,"tile.addon.pipe","",null,PIPE_MODEL);
  BuildCraftApi.registry(BuildCraftRegistries.PIPE_PRESENTATIONS).register(PIPE,new PipePresentation(base,List.of(id("pipe_tex"))));
  BuildCraftApi.registry(BuildCraftRegistries.CLIENT_PRESENTATIONS).register(MACHINE,
   new ContentPresentation(MACHINE,"tile.addon.machine","",null,id("machine_model")));
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).register(PIPE,new DynamicPresentation() {
   public Optional<PresentationFrame> pipe(PipeView view,float partial){
    int power=view.presentation(CHANNEL,Integer.class).orElse(0);
    return Optional.of(new PresentationFrame(power>0?GLOWING:null,List.of(),Map.of(PHASE,power/100d),null,null));
   }
  });
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).register(MACHINE,new DynamicPresentation() {
   public Optional<PresentationFrame> machine(MachineExecutionContext view,float partial){
    int fuel=view.presentation(CHANNEL,Integer.class).orElse(0);
    return Optional.of(new PresentationFrame(null,List.of(),Map.of(PHASE,fuel/100d),null,GUI));
   }
  });
  BuildCraftApi.registry(BuildCraftRegistries.MENU_PRESENTATIONS).register(PIPE,GUI);
  Pipe pipe=new Pipe();pipe.value=25;
  PresentationFrame frame=API.frame(pipe,.5f).orElseThrow();
  ok(frame.model().equals(GLOWING),"Dynamic pipe model ignored");
  ok(frame.textureLayers().equals(List.of(id("pipe_tex"))),"Static pipe textures not inherited");
  ok(frame.animations().get(PHASE)==.25,"Client pipe snapshot missing");
  ok(API.menu(PIPE).orElseThrow()==GUI && frame.menu()==GUI,"Registered pipe GUI missing");
  pipe.value=0;
  ok(API.frame(pipe,.5f).orElseThrow().model().equals(PIPE_MODEL),"Model fallback missing");
  pipe.client=false;
  ok(API.frame(pipe,.5f).isEmpty(),"Server pipe admitted to presentation");
  Machine machine=new Machine();
  PresentationFrame machineFrame=API.frame(machine,0).orElseThrow();
  ok(machineFrame.model().equals(id("machine_model")),"Machine model not registered");
  ok(machineFrame.animations().get(PHASE)==.42,"Machine typed snapshot ignored");
  ok(machineFrame.animations().get(built("work_progress"))==.75,"Work status sync missing");
  ok(machineFrame.menu().equals(GUI),"GUI action descriptor lost");
  machine.client=false;
  ok(API.frame(machine,0).isEmpty(),"Server machine resolved GUI");
  Engine engine=new Engine();
  PresentationFrame engineFrame=API.frame(engine,1).orElseThrow();
  ok(engineFrame.animations().get(built("engine_piston"))==.6,"Synced engine animation missing");
  ok(engineFrame.animations().get(built("engine_heat"))==.25,"Synced heat missing");
  ok(engineFrame.sound().equals(SOUND),"Synced engine sound missing");
  ok(engineFrame.animations().get(built("work_progress"))==.75,"Engine inherited work status missing");
  engine.client=false;
  ok(API.frame(engine,0).isEmpty(),"Server engine resolved visual data");
  BuildCraftApi.registry(BuildCraftRegistries.DYNAMIC_PRESENTATIONS).register(ENGINE,new DynamicPresentation() {
   public Optional<PresentationFrame> engine(EngineHost view,float partial){throw new IllegalStateException("Broken renderer");}
  });
  engine.client=true;
  ok(API.frame(engine,0).orElseThrow().sound().equals(SOUND),"Broken addon callback killed fallback");
  rejected(()->API.frame(pipe,Float.NaN));
  rejected(()->new PresentationFrame(null,List.of(),Map.of(PHASE,Double.NaN),null,null));
  rejected(()->new PresentationFrame(null,List.of(),Map.of(PHASE,1.01d),null,null));
  rejected(()->new MenuPresentation(MENU,"x",java.util.Collections.nCopies(65,GUI.actions().get(0))));
  ok(frame.over(new PresentationFrame(PIPE_MODEL,List.of(id("secondary")),Map.of(id("other"),0.2),null,null))
      .animations().size()==2,"Merged animation channels lost");
 }
}'''

class PresentationRuntimeTests(unittest.TestCase):
    def test_real_public_contracts_and_client_resolution(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Requires JDK')
        with tempfile.TemporaryDirectory(prefix='bcce-presentation-') as directory:
            temp=Path(directory)
            paths=[]
            for name in PRODUCTION:
                path=BASE / name
                self.assertTrue(path.is_file(),name)
                paths.append(str(path))
            for name,content in STUBS.items():
                path=temp/name
                path.parent.mkdir(parents=True,exist_ok=True)
                path.write_text(content,encoding='utf-8')
                paths.append(str(path))
            fixture=temp/'fixture/PresentationFixture.java'
            fixture.parent.mkdir(parents=True,exist_ok=True)
            fixture.write_text(FIXTURE,encoding='utf-8')
            paths.append(str(fixture))
            subprocess.run(['javac','-encoding','UTF-8','-d',str(temp/'classes'),*paths],check=True,capture_output=True,text=True,cwd=ROOT)
            run=subprocess.run(['java','-cp',str(temp/'classes'),'fixture.PresentationFixture'],capture_output=True,text=True,cwd=ROOT)
            self.assertEqual(run.returncode,0,run.stderr+run.stdout)

    def test_native_26x_client_resolution(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Requires JDK')
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_config import load_properties, target_ids, target_layout
        from source_layout import resolve_effective_source
        props = load_properties()
        selected = set()
        relative = Path('src/main/java/buildcraft/lib/internal/api/v2/ClientPresentationServiceImpl.java')
        for target in target_ids(props):
            if not target.startswith('26.'):
                continue
            owner = resolve_effective_source(target_layout(target, props), props, relative)
            self.assertIsNotNone(owner, target)
            selected.add(owner)
        self.assertTrue(selected, 'No 26.X presentation source resolved')
        for implementation in selected:
            with self.subTest(implementation=str(implementation)):
                with tempfile.TemporaryDirectory(prefix='bcce-presentation-native-') as directory:
                    temp = Path(directory)
                    paths = []
                    for relative in PRODUCTION:
                        src = implementation if relative.endswith('/ClientPresentationServiceImpl.java') else BASE / relative
                        text = src.read_text(encoding='utf-8')
                        if src != implementation:
                            text = text.replace('ResourceLocation', 'Identifier')
                        output = temp / 'prod' / relative
                        output.parent.mkdir(parents=True, exist_ok=True)
                        output.write_text(text, encoding='utf-8')
                        paths.append(str(output))
                    for name, contents in STUBS.items():
                        output = temp / 'stubs' / name.replace('ResourceLocation', 'Identifier')
                        output.parent.mkdir(parents=True, exist_ok=True)
                        output.write_text(contents.replace('ResourceLocation', 'Identifier'), encoding='utf-8')
                        paths.append(str(output))
                    fixture = temp / 'fixture/PresentationFixture.java'
                    fixture.parent.mkdir(parents=True, exist_ok=True)
                    fixture.write_text(FIXTURE.replace('ResourceLocation', 'Identifier'), encoding='utf-8')
                    paths.append(str(fixture))
                    compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(temp/'classes'), *paths],
                        cwd=ROOT, capture_output=True, text=True)
                    self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
                    run = subprocess.run(['java', '-cp', str(temp/'classes'), 'fixture.PresentationFixture'],
                        cwd=ROOT, capture_output=True, text=True)
                    self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_pipe_views_implemented_by_every_effective_target(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        from source_layout import load_properties, target_ids, target_layout, resolve_effective_source, preprocess_text
        from source_preprocessor import strip_source_condition
        props=load_properties()
        for target in target_ids(props):
            layout=target_layout(target,props)
            path=resolve_effective_source(layout,props,Path('src/main/java/buildcraft/transport/pipe/Pipe.java'))
            self.assertIsNotNone(path,target)
            source,_=strip_source_condition(path.read_text(encoding='utf-8'))
            code=preprocess_text(source,minecraft=props[f'target.{target}.deps.minecraft'],
                family=layout.family,platform=layout.platform)
            self.assertIn('boolean isClientSide()',code,target)
            self.assertIn('Optional<T> presentation(',code,target)
            self.assertIn('apiRuntime.presentation(channelId, type)',code,target)

    def test_client_sync_reaches_model_refresh_on_every_platform(self):
        files = (
            ROOT/'source-platforms/forge/src/main/java/buildcraft/transport/tile/TilePipeHolder.java',
            ROOT/'source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/transport/tile/TilePipeHolder.java',
            ROOT/'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/transport/tile/TilePipeHolder.java',
            ROOT/'source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/transport/tile/TilePipeHolder.java',
        )
        for path in files:
            text=path.read_text(encoding='utf-8')
            self.assertIn('pipe.readApiComponentSync(buffer);\n                }\n                ',text,str(path))
            next_line=text.split('pipe.readApiComponentSync(buffer);\n                }\n                ')[1].splitlines()[0]
            self.assertTrue('refreshClientPipeModel()' in next_line or 'requestModelDataUpdate()' in next_line,str(path))

if __name__=='__main__':
    unittest.main()
