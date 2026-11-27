"""Exercise the materialized production runtimes against minimal domain stubs.
This checks publication, not in-game domain behavior. Identical source views share a JVM run.
"""
import hashlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import target_ids
from source_lookup import read_materialized_java

API = 'buildcraft/api/v2/'
LIB = 'buildcraft/lib/internal/api/v2/'
SOURCES = [API+n+'.java' for n in ('ApiFeature','ApiFeatureSet','ApiLifecycle','ApiRuntime','ApiVersion',
    'BuildCraftApi','BuildCraftFeatures','BuildCraftRegistries','BuildCraftServices','registry/ApiRegistry',
    'registry/RegistryEntry','registry/RegistrationContext','registry/RegistryKey','service/ServiceKey','platform/PlatformServices')]
SOURCES += [LIB+n+'.java' for n in ('BuildCraftApiRuntime','BuildCraftApiRuntimeProvider','RuntimeFeatures','registry/ApiRegistryImpl')]

PROBE = r'''
package buildcraft.lib.internal.api.v2;
import buildcraft.api.v2.*;
import buildcraft.api.v2.registry.*;
import buildcraft.api.v2.platform.*;
import buildcraft.api.v2.service.*;
import buildcraft.lib.internal.api.v2.registry.ApiRegistryImpl;
import net.minecraft.resources.LOC;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public final class RuntimeProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static LOC id(String p){return LOC.tryParse("fixture:"+p);}
 static <T>T fake(Class<T> t){return t.cast(Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},(p,m,a)->{throw new AssertionError(m);}));}
 static void reject(Runnable r){try{r.run();}catch(IllegalStateException|IllegalArgumentException|UnsupportedOperationException e){return;}throw new AssertionError("Expected rejection");}
 static void has(ApiRuntime r,LOC f,int l){check(r.features().supports(f,l),"Missing feature "+f+" level "+l);}
 static void absent(ApiRuntime r,LOC f){check(!r.features().supports(f),"False feature "+f);}
 static class Partial implements ApiRuntime {
  Set<ServiceKey<?>> services=new HashSet<>(); Set<LOC> registries=new HashSet<>();
  @Override public <T>Optional<T> service(ServiceKey<T> key){return services.contains(key)?Optional.of((T)new Object()):Optional.empty();}
  @Override public <T>Optional<ApiRegistry<T>> registry(LOC id){return registries.contains(id)?Optional.of(new ApiRegistryImpl<>()):Optional.empty();}
 }
 static void matrix()throws Exception{
  Field d=RuntimeFeatures.class.getDeclaredField("REQUIREMENTS"), l=RuntimeFeatures.class.getDeclaredField("LEVEL_TWO");d.setAccessible(true);l.setAccessible(true);
  Map<LOC,Object> base=(Map<LOC,Object>)d.get(null), extra=(Map<LOC,Object>)l.get(null);
  Set<LOC> ids=new HashSet<>();for(Field f:BuildCraftFeatures.class.getFields())if(f.getType()==LOC.class)ids.add((LOC)f.get(null));check(ids.equals(base.keySet()),"Unmapped feature");
  for(var e:base.entrySet()){
   LOC f=e.getKey();if(f.equals(BuildCraftFeatures.TRANSFER))continue;
   Object b=e.getValue(), x=extra.get(f);Method sm=b.getClass().getDeclaredMethod("services"), rm=b.getClass().getDeclaredMethod("registries");sm.setAccessible(true);rm.setAccessible(true);
   List<ServiceKey<?>> bs=(List<ServiceKey<?>>)sm.invoke(b);List<RegistryKey<?>> br=(List<RegistryKey<?>>)rm.invoke(b);
   Partial r=new Partial();r.services.addAll(bs);for(var k:br)r.registries.add(k.id());
   int level=x==null?1:2;var want=ApiFeatureSet.of(List.of(new ApiFeature(f,level)));
   if(x!=null){r.services.addAll((List<ServiceKey<?>>)sm.invoke(x));for(var k:(List<RegistryKey<?>>)rm.invoke(x))r.registries.add(k.id());}
   check(RuntimeFeatures.available(r,want).supports(f,level),"Complete set rejected "+f);
   for(var k:bs){r.services.remove(k);check(!RuntimeFeatures.available(r,want).supports(f),"Missing service accepted "+k);r.services.add(k);}
   for(var k:br){r.registries.remove(k.id());check(!RuntimeFeatures.available(r,want).supports(f),"Missing registry accepted "+k);r.registries.add(k.id());}
   if(x!=null){
    for(var k:(List<ServiceKey<?>>)sm.invoke(x))if(!bs.contains(k)){r.services.remove(k);var a=RuntimeFeatures.available(r,want);check(a.supports(f)&&!a.supports(f,2),"Level fallback "+k);r.services.add(k);}
    for(var k:(List<RegistryKey<?>>)rm.invoke(x))if(!br.contains(k)){r.registries.remove(k.id());var a=RuntimeFeatures.available(r,want);check(a.supports(f)&&!a.supports(f,2),"Level fallback "+k);r.registries.add(k.id());}
   }
  }
 }
 public static void main(String[]args)throws Exception{
  matrix();var internal=BuildCraftApiRuntime.INSTANCE;ApiRuntime r=BuildCraftApi.runtime();
  check(r!=ApiRuntime.EMPTY&&r.version().equals(BuildCraftApi.VERSION),"ServiceLoader provider missing");var early=r.features();
  has(r,BuildCraftFeatures.CONFIGURATION,1);has(r,BuildCraftFeatures.PERSISTENCE,1);
  absent(r,BuildCraftFeatures.PIPES);absent(r,BuildCraftFeatures.ROBOTS);absent(r,BuildCraftFeatures.LOGISTICS);absent(r,BuildCraftFeatures.SCHEMATICS);absent(r,BuildCraftFeatures.LIBRARY_FORMATS);absent(r,BuildCraftFeatures.TRANSFER);
  BuildCraftApiRuntime.bootstrap();
  internal.installService(BuildCraftServices.PIPES,fake(buildcraft.api.v2.pipe.PipeService.class));has(r,BuildCraftFeatures.PIPES,1);check(!r.features().supports(BuildCraftFeatures.PIPES,2),"Premature level 2");
  internal.installService(BuildCraftServices.PIPE_RESOURCES,fake(buildcraft.api.v2.pipe.PipeResourceService.class));has(r,BuildCraftFeatures.PIPES,2);has(r,BuildCraftFeatures.PIPE_FLUID_MOVEMENT,1);has(r,BuildCraftFeatures.PIPE_POWER_MOVEMENT,1);has(r,BuildCraftFeatures.PIPE_ENDPOINTS,1);check(!early.supports(BuildCraftFeatures.PIPES),"Old snapshot changed");
  internal.installService(BuildCraftServices.ROBOTS,fake(buildcraft.api.v2.robot.RobotService.class));has(r,BuildCraftFeatures.ROBOTS,2);
  internal.installService(BuildCraftServices.REQUESTS,fake(buildcraft.api.v2.request.RequestService.class));has(r,BuildCraftFeatures.LOGISTICS,1);
  internal.installService(BuildCraftServices.SCHEMATICS,fake(buildcraft.api.v2.schematic.SchematicService.class));has(r,BuildCraftFeatures.SCHEMATICS,1);
  internal.installService(BuildCraftServices.LIBRARY,fake(buildcraft.api.v2.library.LibraryService.class));has(r,BuildCraftFeatures.LIBRARY_FORMATS,1);
  internal.installService(BuildCraftServices.GATES,fake(buildcraft.api.v2.gate.GateService.class));has(r,BuildCraftFeatures.STATEMENTS,1);
  internal.installService(BuildCraftServices.SIGNALS,fake(buildcraft.api.v2.signal.SignalService.class));has(r,BuildCraftFeatures.SIGNALS,1);
  var platform=new PlatformServices(){boolean complete;public Optional<ItemTransfer> itemTransfer(){return Optional.of(fake(ItemTransfer.class));}public Optional<FluidTransfer> fluidTransfer(){return Optional.of(fake(FluidTransfer.class));}public Optional<EnergyTransfer> energyTransfer(){return complete?Optional.of(fake(EnergyTransfer.class)):Optional.empty();}};
  internal.installService(BuildCraftServices.PLATFORM,platform);absent(r,BuildCraftFeatures.TRANSFER);platform.complete=true;has(r,BuildCraftFeatures.TRANSFER,1);
  var reg=new ApiRegistryImpl<String>();reg.register(id("first"),"one",()->"fixture");var entries=reg.entries();var vals=reg.values();var aliases=reg.aliases();reg.registerAlias(id("alias"),id("first"),()->"fixture");reg.registerAlias(id("pending"),id("future"),()->"fixture");reject(()->reg.registerAlias(id("future"),id("pending"),()->"fixture"));reject(reg::freeze);check(!reg.frozen(),"Failed freeze mutated");reg.register(id("future"),"future",()->"fixture");check(entries.size()==1&&vals.size()==1&&aliases.isEmpty(),"Live view leaked");reject(entries::clear);reject(aliases::clear);
  var pool=Executors.newFixedThreadPool(5);try{var start=new CountDownLatch(1);var tasks=new ArrayList<Future<?>>();tasks.add(pool.submit(()->{start.await();for(int i=0;i<600;i++)reg.register(id("key"+i),"value",()->"fixture");return null;}));for(int j=0;j<4;j++)tasks.add(pool.submit(()->{start.await();for(int i=0;i<300;i++){for(var e:reg.entries())check(e.value()!=null,"Partial entry");check("one".equals(reg.get(id("alias"))),"Broken alias");has(r,BuildCraftFeatures.PIPES,2);}return null;}));start.countDown();for(var task:tasks)task.get(15,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  reg.freeze();reject(()->reg.register(id("late"),"late"));check(reg.entries().size()==602,"Lost entries");
  internal.advanceLifecycle(ApiLifecycle.CONTENT_REGISTRATION);internal.advanceLifecycle(ApiLifecycle.FROZEN);internal.advanceLifecycle(ApiLifecycle.RUNNING);check(r.lifecycle()==ApiLifecycle.RUNNING&&r.requireRegistry(BuildCraftRegistries.PIPE_TYPES).frozen(),"Freeze failed");reject(()->internal.installService(ServiceKey.of(id("late")),new Object()));check(r.features().all().size()==34,"Missing advertised features "+r.features().all());
  System.out.println("Runtime contracts PASS");
 }
}
'''


def compile_probe(sources, root):
    files=dict(sources)
    loc='Identifier' if 'import net.minecraft.resources.Identifier;' in sources[API+'BuildCraftFeatures.java'] else 'ResourceLocation'
    files[f'net/minecraft/resources/{loc}.java']=f'''package net.minecraft.resources; public record {loc}(String namespace,String path) {{ public static {loc} tryParse(String v){{String[] a=v.split(":",2);return new {loc}(a[0],a[1]);}} public String getNamespace(){{return namespace;}} public String getPath(){{return path;}} }}'''
    # Use actual typed key declarations; stub only leaf domain contracts not under test.
    types={}
    for keyfile, key in [('BuildCraftServices','ServiceKey'),('BuildCraftRegistries','RegistryKey')]:
        src=sources[API+keyfile+'.java'];imports={f.rsplit('.',1)[-1]:f for f in re.findall(r'import (buildcraft\.api\.v2\.[\w.]+);',src)}
        for typename,field in re.findall(rf'{key}<([\w.]+(?:<\?>)?)>\s+(\w+)\s*=',src):
            raw=typename.split('<')[0];fq=raw if '.' in raw else imports[raw];name=fq.rsplit('.',1)[-1];path=fq.replace('.','/')+'.java'
            if key=='ServiceKey':types[field]=fq
            if path in files:continue
            body=f'net.minecraft.resources.{loc} id();' if name=='MachineProperty' else ''
            files[path]=f'package {fq.rsplit(".",1)[0]}; public interface {name}{"<T>" if "<" in typename else ""} {{ {body} }}'
    for name in ('ItemTransfer','FluidTransfer','EnergyTransfer'):
        files[API+'platform/'+name+'.java']=f'package buildcraft.api.v2.platform; public interface {name} {{}}'
    files[API+'machine/BuiltInMachineProperties.java']='package buildcraft.api.v2.machine; public final class BuiltInMachineProperties {public static java.util.List<MachineProperty<?>> values(){return java.util.List.of();}}'
    runtime=sources[LIB+'BuildCraftApiRuntime.java'];variables={name:cls for cls,name in re.findall(r'private final (\w+Impl) (\w+)\s*=',runtime)};imports={fq.rsplit('.',1)[-1]:fq for fq in re.findall(r'import (buildcraft\.lib\.[\w.]+);',runtime)}
    for field,value in re.findall(r'services\.put\(BuildCraftServices\.(\w+),\s*([\w.]+)\);',runtime):
        if field=='CONFIGURATION':fq='buildcraft.lib.internal.config.ConfigurationRegistry';cls='ConfigurationRegistry'
        else:cls=variables[value];fq=imports.get(cls,'buildcraft.lib.internal.api.v2.'+cls)
        files[fq.replace('.','/')+'.java']=f'package {fq.rsplit(".",1)[0]}; public final class {cls} implements {types[field]} {{ public static final {cls} INSTANCE=new {cls}(); public void validateFreeze(){{}} public void freeze(){{}} public void freezeSchema(){{}} }}'
    files['buildcraft/lib/balance/BalanceConfig.java']='package buildcraft.lib.balance; public final class BalanceConfig {public static void initialize(){}}'
    files[LIB+'RuntimeProbe.java']=PROBE.replace('LOC',loc)
    paths=[]
    for name,content in files.items():
        path=root/'src'/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');paths.append(str(path))
    classes=root/'classes';classes.mkdir();descriptor=classes/'META-INF/services/buildcraft.api.v2.ApiRuntime';descriptor.parent.mkdir(parents=True);descriptor.write_text('buildcraft.lib.internal.api.v2.BuildCraftApiRuntimeProvider\n')
    result=subprocess.run(['javac','-d',str(classes),*paths],capture_output=True,text=True,timeout=45)
    if result.returncode:raise AssertionError(result.stderr)
    result=subprocess.run(['java','-cp',str(classes),'buildcraft.lib.internal.api.v2.RuntimeProbe'],capture_output=True,text=True,timeout=35)
    if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
    return result.stdout


class RuntimeFeaturesTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'),'Java toolchain required')
    def test_selected_production_runtime_all_targets(self):
        seen=set()
        for target in target_ids():
            with self.subTest(target=target):
                sources={p:read_materialized_java(target,'src/main/java/'+p) for p in SOURCES}
                digest=hashlib.sha256(''.join(sources.values()).encode()).digest()
                if digest in seen:continue
                with tempfile.TemporaryDirectory(prefix='bcce-runtime-features-') as td:
                    self.assertIn('contracts PASS',compile_probe(sources,Path(td)))
                seen.add(digest)
        self.assertGreaterEqual(len(seen),3,'Expected legacy, modern NeoForge and Fabric runtime views')

if __name__=='__main__':unittest.main()
