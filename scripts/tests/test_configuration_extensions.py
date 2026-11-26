"""Exercise addon schema ownership, display preferences, migration and reversible units."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'
sys.path.insert(0, str(ROOT / 'scripts'))
PROBE = r'''
package buildcraft.lib.client.config;
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.api.v2.config.DisplayUnits.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import buildcraft.lib.platform.config.*;
import buildcraft.lib.client.gui.ClientGuiModePreferences;
import java.nio.file.*;
import java.util.*;
import java.math.BigDecimal;
public class ExtensionProbe {
 static void ok(boolean b) { if (!b) throw new AssertionError(); }
 static void eq(Object a,Object b) { if (!Objects.equals(a,b)) throw new AssertionError(a+" != "+b); }
 static ConfigDefinition def(String id,Section sec,Scope scope,Kind kind,String value,List<String> choices,ApplyPolicy policy) {
  return new ConfigDefinition(id,sec,List.of("Own","Nested"),scope,kind,value,0,Integer.MAX_VALUE,choices,"n","d","t","Name","Description","Tooltip",policy);
 }
 static ConfigDefinition integer(String id,Section sec,Scope scope,ApplyPolicy policy) { return def(id,sec,scope,Kind.INTEGER,"10",List.of(),policy); }
 public static void main(String[] args) throws Exception {
  // Defaults must agree before registration, after fresh registration and after reset.
  var fresh=new ConfigurationRegistry(Path.of("fresh-config"));
  eq(fresh.displayUnits(),new DisplayUnits(RateUnit.TICKS,FluidUnit.MILLIBUCKETS));
  var rateDef=def(DisplayUnits.RATE_ID,Section.CLIENT,Scope.CLIENT,Kind.ENUM,DisplayUnits.DEFAULT.rate().name(),List.of("TICKS","SECONDS"),ApplyPolicy.LIVE);
  var fluidDef=def(DisplayUnits.FLUID_ID,Section.CLIENT,Scope.CLIENT,Kind.ENUM,DisplayUnits.DEFAULT.fluid().name(),List.of("BUCKETS","MILLIBUCKETS"),ApplyPolicy.LIVE);
  fresh.register(rateDef,null);fresh.register(fluidDef,null);
  eq(fresh.displayUnits().formatFluidRate(50),"50 mB/t");
  fresh.commit(Scope.CLIENT,fresh.snapshot(Scope.CLIENT).revision(),Map.of(DisplayUnits.RATE_ID,"SECONDS",DisplayUnits.FLUID_ID,"BUCKETS"));
  var reopened=new ConfigurationRegistry(Path.of("fresh-config"));
  reopened.register(rateDef,null);reopened.register(fluidDef,null);
  eq(reopened.displayUnits().formatFluidRate(50),"1 B/s");
  reopened.commit(Scope.CLIENT,reopened.snapshot(Scope.CLIENT).revision(),Map.of(DisplayUnits.RATE_ID,rateDef.defaultValue(),DisplayUnits.FLUID_ID,fluidDef.defaultValue()));
  eq(reopened.displayUnits().formatFluidRate(50),"50 mB/t");
  // Old settings in the BCCE properties file override old loader TOML.
  Files.createDirectories(Path.of("config/buildcraft"));
  Files.writeString(Path.of("config/buildcraft/client.properties"),"buildcraftcore\\:display/useBucketsStatic=false\nbuildcraftcore\\:display/useBucketsFlow=true\nbuildcraftcore\\:display/timeGap=TICKS\n");
  var b=new BCConfigSpec.Builder();b.push("display");
  var rate=b.defineEnum("timeGap",DisplayUnits.DEFAULT.rate());
  var fluid=b.defineEnum("fluidVolumeUnit",DisplayUnits.DEFAULT.fluid());
  var zone=b.defineEnum("zonePlannerMode",ScreenMode.SWITCH);
  var library=b.defineEnum("electronicLibraryMode",ScreenMode.SWITCH);b.pop();
  ConfigBinding.bind("buildcraftcore",b.build(),m->{});
  var r=ConfigurationRegistry.INSTANCE;
  eq(rate.get(),RateUnit.TICKS);eq(fluid.get(),FluidUnit.MILLIBUCKETS);
  eq(zone.get(),ScreenMode.SWITCH);eq(library.get(),ScreenMode.SWITCH);
  eq(r.displayUnits().formatFluid(500),"500 mB");eq(r.displayUnits().formatMjRate(50_000),"0.05 MJ/t");
  eq(r.definitions().size(),4);ok(r.definitions().stream().noneMatch(ConfigDefinition::isAddon));
  eq(r.definitions().stream().filter(d->d.id().equals(DisplayUnits.RATE_ID)).findFirst().orElseThrow().category(),List.of("Interface","Units"));
  eq(r.definitions().stream().filter(d->d.id().endsWith("zonePlannerMode")).findFirst().orElseThrow().category(),List.of("Interface","Screens"));
  var addonClient=r.registerAddon(integer("testaddon:client",Section.CLIENT,Scope.CLIENT,ApplyPolicy.LIVE));
  var addonWorld=r.registerAddon(integer("testaddon:world",Section.WORLD,Scope.SERVER,ApplyPolicy.WORLD_RESTART));
  var addonBalance=r.registerAddon(integer("testaddon:balance",Section.BALANCE,Scope.SERVER,ApplyPolicy.WORLD_RESTART));
  var addonLegacy=r.registerAddon(integer("testaddon:legacy",Section.ADDONS,Scope.CLIENT,ApplyPolicy.RESTART));
  var addonScreen=r.registerAddon(def("testaddon:screen",Section.CLIENT,Scope.CLIENT,Kind.ENUM,"SWITCH",Arrays.stream(ScreenMode.values()).map(Enum::name).toList(),ApplyPolicy.LIVE));
  ok(r.definitions().stream().filter(ConfigDefinition::isAddon).count()==5);
  for(var d:r.definitions()) if(d.isAddon()) eq(d.category(),List.of("testaddon","Own","Nested"));
  eq(r.definitions().stream().filter(d->d.id().equals("testaddon:legacy")).findFirst().orElseThrow().editorSection(),Section.CLIENT);
  eq(r.definitions().stream().filter(d->d.id().equals("testaddon:balance")).findFirst().orElseThrow().editorSection(),Section.BALANCE);
  try { r.registerAddon(integer("buildcraft:fake",Section.CLIENT,Scope.CLIENT,ApplyPolicy.LIVE));throw new AssertionError(); }catch(IllegalArgumentException expected){}
  try { r.registerAddon(integer("testaddon:unsafe",Section.WORLD,Scope.SERVER,ApplyPolicy.LIVE));throw new AssertionError(); }catch(IllegalArgumentException expected){}
  String key=ClientGuiModePreferences.ZONE_PLANNER;
  ClientGuiModePreferences.set(key,ClientGuiModePreferences.Mode.LEGACY);
  ok(ClientGuiModePreferences.canSwitch(key));eq(ClientGuiModePreferences.get(key),ClientGuiModePreferences.Mode.LEGACY);
  r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of(DisplayUnits.RATE_ID,"SECONDS",DisplayUnits.FLUID_ID,"BUCKETS","buildcraftcore:display/zonePlannerMode","MODERN_ONLY","testaddon:client","42","testaddon:screen","LEGACY_ONLY"));
  eq(addonClient.get(),"42");eq(addonScreen.get(),"LEGACY_ONLY");ok(r.screenMode("testaddon:screen").useLegacy(false));
  eq(rate.get(),RateUnit.SECONDS);eq(fluid.get(),FluidUnit.BUCKETS);ok(!ClientGuiModePreferences.canSwitch(key));eq(ClientGuiModePreferences.get(key),ClientGuiModePreferences.Mode.MODERN);
  ClientGuiModePreferences.set(key,ClientGuiModePreferences.Mode.MODERN);
  r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of("buildcraftcore:display/zonePlannerMode","SWITCH"));
  eq(ClientGuiModePreferences.get(key),ClientGuiModePreferences.Mode.LEGACY);
  for(RateUnit ru:RateUnit.values()) for(FluidUnit fu:FluidUnit.values()) {
   var u=new DisplayUnits(ru,fu);
   eq(u.formatFluid(1500),fu==FluidUnit.BUCKETS?"1.5 B":"1500 mB");
   eq(u.formatFluidRate(50),(ru==RateUnit.SECONDS?(fu==FluidUnit.BUCKETS?"1 B/s":"1000 mB/s"):(fu==FluidUnit.BUCKETS?"0.05 B/t":"50 mB/t")));
   eq(u.formatRate(new BigDecimal("0.5"),"items"),ru==RateUnit.SECONDS?"10 items/s":"0.5 items/t");
   for(String id:List.of("buildcrafttransport:general/pipes/mjPerItem","buildcrafttransport:general/pipes/mjPerMillibucket","buildcrafttransport:general/pipes/baseFluidRate","buildcrafttransport:general/pipes/baseFeRate","buildcraft:balance/machine/buildcraftfactory/pump/tank_capacity_mb","buildcraft:balance/machine/buildcraftfactory/pump/energy_per_bucket_mj","buildcraft:balance/machine/buildcraftbuilders/quarry/max_tasks_per_tick")) {
    var d=integer(id,Section.BALANCE,Scope.SERVER,ApplyPolicy.WORLD_RESTART);
    var f=ConfigurationNumberFormat.forDefinition(d,u);ok(f!=null);eq(f.canonical(f.display("50001"),Kind.INTEGER),"50001");
   }
  }
  var f=ConfigurationNumberFormat.forDefinition(integer("buildcrafttransport:general/pipes/mjPerItem",Section.BALANCE,Scope.SERVER,ApplyPolicy.WORLD_RESTART),r.displayUnits());
  eq(f.display("1000000"),"1");eq(f.canonical("0.05",Kind.INTEGER),"50000");
  try { f.canonical("1E1000000000",Kind.INTEGER);throw new AssertionError(); }catch(IllegalArgumentException expected){}
  try { f.canonical("0.0000001",Kind.INTEGER);throw new AssertionError(); }catch(ArithmeticException expected){}
  var r2=new ConfigurationRegistry(Path.of("config/buildcraft"));
  r2.register(def(DisplayUnits.FLUID_ID,Section.CLIENT,Scope.CLIENT,Kind.ENUM,"MILLIBUCKETS",List.of("BUCKETS","MILLIBUCKETS"),ApplyPolicy.LIVE),"MILLIBUCKETS");
  eq(r2.displayUnits().fluid(),FluidUnit.BUCKETS);
  System.out.println("Extension contracts passed");
 }
}
'''

class ConfigurationExtensionTests(unittest.TestCase):
    def test_registry_preferences_and_conversions(self):
        with tempfile.TemporaryDirectory() as temp:
            work=Path(temp); p=work/'ExtensionProbe.java';p.write_text(PROBE)
            sources=list((JAVA/'api/v2/config').glob('*.java'))+[JAVA/x for x in (
                'lib/internal/config/ConfigurationRegistry.java','lib/platform/config/ConfigBinding.java',
                'lib/platform/config/BCConfigSpec.java','lib/platform/config/LegacyToml.java',
                'lib/client/gui/ClientGuiModePreferences.java','lib/client/config/ConfigurationNumberFormat.java')]
            subprocess.run(['javac','--release','17','-d',str(work),*map(str,sources),str(p)],check=True,timeout=60)
            result=subprocess.run(['java','-cp',str(work),'buildcraft.lib.client.config.ExtensionProbe'],cwd=work,capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('Extension contracts passed',result.stdout)
    def test_display_defaults_for_all_targets(self):
        from source_lookup import read_materialized_java
        targets=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge','26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                core=read_materialized_java(target,'src/main/java/buildcraft/core/BCCoreConfig.java')
                lib=read_materialized_java(target,'src/main/java/buildcraft/lib/BCLibConfig.java')
                self.assertIn('defineEnum("fluidVolumeUnit", DisplayUnits.DEFAULT.fluid())',core)
                self.assertIn('defineEnum("timeGap", TimeGap.valueOf(DisplayUnits.DEFAULT.rate().name()), TimeGap.values())',core)
                self.assertIn('useBucketsStatic = false;',lib)
                self.assertIn('useBucketsFlow = false;',lib)
                self.assertIn('displayTimeGap = TimeGap.TICKS;',lib)

    def test_all_effective_screens_and_tabs(self):
        from source_lookup import read_materialized_java
        targets=('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge','26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric')
        for target in targets:
            with self.subTest(target=target):
                prefix='src/main/java/buildcraft/'
                for module,screen,key in [('builders','GuiElectronicLibrary','ELECTRONIC_LIBRARY'),('robotics','GuiZonePlanner','ZONE_PLANNER')]:
                    for suffix in ('','Legacy'):
                        code=read_materialized_java(target,prefix+module+'/gui/'+screen+suffix+'.java')
                        self.assertEqual(code.count('if (!ClientGuiModePreferences.canSwitch(ClientGuiModePreferences.'+key+')) return;'),2)
                code=read_materialized_java(target,prefix+'lib/client/config/BuildCraftConfigurationScreen.java')
                for marker in ('d.isAddon()==addonsView','d.editorSection()==section','.filter(this::inView)','.filter(this::inSection)','addonsView=!addonsView','addonsView?"bcce":"addons"','d.isAddon()==targetAddons','BCCoreConfig.refreshDisplayUnits()'):
                    self.assertIn(marker,code)
                self.assertNotIn('section=Section.ADDONS',code)
                self.assertIn('ConfigurationNumberFormat.forDefinition',code)
                self.assertIn('numberDrafts.containsKey(d.id())',code)

if __name__=='__main__': unittest.main()
