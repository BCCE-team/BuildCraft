"""Compile and run loader-independent configuration and balance contracts."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "source-shared/src/main/java/buildcraft"
PROBE = r'''
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import buildcraft.lib.balance.BalanceIni;
import java.nio.file.*;
import java.util.*;
public class Probe {
 static void ok(boolean b) { if (!b) throw new AssertionError(); }
 static ConfigDefinition def(String id, Section sec, Scope scope, Kind kind, String value) { return new ConfigDefinition(id,sec,List.of("Test"),scope,kind,value,1,64,List.of(),"name","desc","tip","Name","Description","Tooltip",scope==Scope.CLIENT?ApplyPolicy.RESTART:ApplyPolicy.WORLD_RESTART); }
 public static void main(String[] args) throws Exception {
  Path root=Files.createTempDirectory("config-probe"); var r=new ConfigurationRegistry(root.resolve("config"));
  var c=r.register(def("test:client",Section.CLIENT,Scope.CLIENT,Kind.BOOLEAN,"true"),"false");
  var s=r.register(def("test:server",Section.WORLD,Scope.SERVER,Kind.INTEGER,"4"),"8");
  ok(c.get().equals("false")&&s.get().equals("8")); r.persistDefaults();
  Path w=root.resolve("world");r.openWorld(w);long rev=r.snapshot(Scope.SERVER).revision();r.commit(Scope.SERVER,rev,Map.of("test:server","12"));ok(s.get().equals("8"));
  try{r.commit(Scope.SERVER,rev,Map.of("test:server","13"));throw new AssertionError("stale revision");}catch(IllegalArgumentException expected){}
  try{r.commit(Scope.SERVER,r.snapshot(Scope.SERVER).revision(),Map.of("test:server","100","test:client","true"));throw new AssertionError("invalid transaction");}catch(IllegalArgumentException expected){}
  ok(r.snapshot(Scope.SERVER).values().get("test:server").equals("12"));r.closeWorld();r.openWorld(w);ok(s.get().equals("12"));r.closeWorld();r.openWorld(root.resolve("other"));ok(s.get().equals("8"));r.closeWorld();
  // Title screen edits a personal profile, with a separate guarded entry point.
  long personalRev=r.snapshot(Scope.SERVER).revision();
  try {r.commit(Scope.SERVER,personalRev,Map.of("test:server","20"));throw new AssertionError("world commit without world");}catch(IllegalStateException expected){}
  r.commitLocalDefaults(personalRev,Map.of("test:server","20"));ok(r.snapshot(Scope.SERVER).values().get("test:server").equals("20"));
  try {r.commitLocalDefaults(personalRev,Map.of("test:server","21"));throw new AssertionError("stale local revision");}catch(IllegalArgumentException expected){}
  r.openLocalWorld();ok(s.get().equals("20"));
  try {r.commitLocalDefaults(r.snapshot(Scope.SERVER).revision(),Map.of("test:server","21"));throw new AssertionError("offline edit while world open");}catch(IllegalStateException expected){}
  r.commit(Scope.SERVER,r.snapshot(Scope.SERVER).revision(),Map.of("test:server","24"));ok(s.get().equals("20"));
  r.closeWorld(); r.openLocalWorld();ok(s.get().equals("24"));r.closeWorld();
  // Existing dedicated-world values remain independent from the personal profile.
  r.openWorld(w);ok(s.get().equals("12"));r.commit(Scope.SERVER,r.snapshot(Scope.SERVER).revision(),Map.of("test:server","30"));r.closeWorld();
  ok(r.snapshot(Scope.SERVER).values().get("test:server").equals("24"));
  r.openWorld(w);ok(s.get().equals("30"));r.closeWorld();
  r.openLocalWorld();ok(s.get().equals("24"));r.closeWorld();
  r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of("test:client","true"));ok(c.get().equals("false"));
  var r2=new ConfigurationRegistry(root.resolve("config"));ok(r2.register(def("test:client",Section.CLIENT,Scope.CLIENT,Kind.BOOLEAN,"true"),"false").get().equals("true"));
  ok(r2.register(def("test:server",Section.WORLD,Scope.SERVER,Kind.INTEGER,"4"),"8").get().equals("24"));
  var addon=r.registerAddon(def("addon:capacity",Section.ADDONS,Scope.SERVER,Kind.INTEGER,"8"));ok(addon.get().equals("8"));ok(r.definitions().stream().anyMatch(d->d.category().equals(List.of("addon","Test"))));
  for(var p:BalanceIni.parameters())new ConfigDefinition("test:"+p.key(),Section.BALANCE,List.of("Test"),Scope.SERVER,p.integral()?Kind.INTEGER:Kind.DECIMAL,p.defaultValue(),p.minimum(),p.maximum(),List.of(),"n","d","t","N","D","T",ApplyPolicy.WORLD_RESTART);
  System.out.println("Config registry tests passed; balance definitions validated: "+BalanceIni.parameters().size());
 }
}
'''

class ConfigurationRegistryTest(unittest.TestCase):
    def test_registry(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            probe = work / "Probe.java"
            probe.write_text(PROBE, encoding="utf-8")
            sources = list((JAVA / "api/v2/config").glob("*.java"))
            sources += [JAVA / path for path in (
                "lib/internal/config/ConfigurationRegistry.java",
                "lib/platform/config/BCConfigSpec.java",
                "lib/platform/config/ConfigBinding.java",
                "lib/platform/config/LegacyToml.java",
                "lib/balance/BalanceIni.java",
                "lib/balance/BalanceConfig.java",
            )]
            subprocess.run(["javac", "--release", "17", "-d", str(work), *map(str, sources), str(probe)], check=True, timeout=60)
            result = subprocess.run(["java", "-cp", str(work), "Probe"], cwd=work, capture_output=True, text=True, check=True, timeout=30)
            self.assertIn("Config registry tests passed", result.stdout)

if __name__ == "__main__":
    unittest.main()
