"""Compile a separate typed addon and exercise real registry restart/freeze semantics."""
import os
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
JAVA = ROOT / 'source-shared/src/main/java'
API = JAVA / 'buildcraft/api/v2/config'
RUNTIME = JAVA / 'buildcraft/lib/internal/config/ConfigurationRegistry.java'
ADDON = r'''
package example;
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import java.util.List;
public final class TypedAddon {
    public enum Mode { FIRST, SECOND, THIRD }
    public static ConfigDefinition def(String name, Scope scope, ApplyPolicy policy, Kind kind,
                                       String value, double min, double max, List<String> choices) {
        return new ConfigDefinition("example:"+name, scope==Scope.CLIENT?Section.CLIENT:Section.WORLD,
            List.of("Test"),scope,kind,value,min,max,choices,"n","d","t","N","D","T",policy);
    }
    public static ConfigKey<Integer> key(String name, Scope scope, ApplyPolicy policy) {
        return ConfigKey.integer(def(name,scope,policy,Kind.INTEGER,"1",0,100,List.of()));
    }
    public static final ConfigKey<Integer> LIVE = key("live",Scope.CLIENT,ApplyPolicy.LIVE);
    public static final ConfigKey<Integer> SESSION = key("session",Scope.CLIENT,ApplyPolicy.WORLD_RESTART);
    public static final ConfigKey<Integer> CLIENT_RESTART = key("client_restart",Scope.CLIENT,ApplyPolicy.RESTART);
    public static final ConfigKey<Integer> WORLD = key("world",Scope.SERVER,ApplyPolicy.WORLD_RESTART);
    public static final ConfigKey<Integer> SERVER_RESTART = key("server_restart",Scope.SERVER,ApplyPolicy.RESTART);
    public static final ConfigKey<Boolean> BOOL = ConfigKey.bool(def("bool",Scope.CLIENT,ApplyPolicy.LIVE,Kind.BOOLEAN,"true",0,0,List.of()));
    public static final ConfigKey<Long> LONG = ConfigKey.longInteger(def("long",Scope.CLIENT,ApplyPolicy.LIVE,Kind.INTEGER,"9007199254740992",0,9007199254740992d,List.of()));
    public static final ConfigKey<Double> DECIMAL = ConfigKey.decimal(def("decimal",Scope.CLIENT,ApplyPolicy.LIVE,Kind.DECIMAL,"0.5",0,1,List.of()));
    public static final ConfigKey<String> STRING = ConfigKey.string(def("string",Scope.CLIENT,ApplyPolicy.LIVE,Kind.STRING,"initial",0,0,List.of()));
    public static final ConfigKey<Mode> MODE = ConfigKey.enumeration(def("mode",Scope.CLIENT,ApplyPolicy.LIVE,Kind.ENUM,"FIRST",0,0,List.of("FIRST","SECOND")), Mode.class);
    public final ConfigValue<Integer> live,session,clientRestart,world,serverRestart;
    public final ConfigValue<Boolean> bool;
    public final ConfigValue<Long> large;
    public final ConfigValue<Double> decimal;
    public final ConfigValue<String> string;
    public final ConfigValue<Mode> mode;
    public TypedAddon(ConfigurationService s) {
        live=s.register(LIVE);session=s.register(SESSION);clientRestart=s.register(CLIENT_RESTART);
        world=s.register(WORLD);serverRestart=s.register(SERVER_RESTART);bool=s.register(BOOL);
        large=s.register(LONG);decimal=s.register(DECIMAL);string=s.register(STRING);mode=s.register(MODE);
    }
}
'''
PROBE = r'''
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import example.TypedAddon;
import static example.TypedAddon.*;
import java.nio.file.*;
import java.util.*;
public final class Probe {
    static void eq(Object a,Object b) {if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void rejects(Class<? extends Throwable> type, Runnable r) {
        try { r.run(); } catch(Throwable e) { if(type.isInstance(e))return; throw new AssertionError(e); }
        throw new AssertionError("Expected "+type);
    }
    static void client(ConfigurationRegistry r,Map<String,String> values)throws Exception {
        r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),values);
    }
    static void server(ConfigurationRegistry r,Map<String,String> values)throws Exception {
        r.commit(Scope.SERVER,r.snapshot(Scope.SERVER).revision(),values);
    }
    static void offline(ConfigurationRegistry r,Map<String,String> values)throws Exception {
        r.commitLocalDefaults(r.snapshot(Scope.SERVER).revision(),values);
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);var r=new ConfigurationRegistry(root.resolve("local"));
        long stale=r.snapshot(Scope.CLIENT).revision();var a=new TypedAddon(r);
        eq(a.live.key(),LIVE);eq(r.read(LIVE),Optional.of(1));eq(r.value(LIVE.id()),Optional.of("1"));
        eq(r.read(key("unregistered",Scope.CLIENT,ApplyPolicy.LIVE)),Optional.empty());
        rejects(IllegalArgumentException.class,()->r.read(ConfigKey.string(def("live",Scope.CLIENT,ApplyPolicy.LIVE,Kind.STRING,"1",0,0,List.of()))));
        rejects(IllegalArgumentException.class,()->ConfigKey.integer(LONG.definition()));
        rejects(IllegalArgumentException.class,()->ConfigKey.bool(LIVE.definition()));
        rejects(IllegalArgumentException.class,()->ConfigKey.enumeration(def("bad",Scope.CLIENT,ApplyPolicy.LIVE,Kind.ENUM,"FIRST",0,0,List.of("FIRST","UNKNOWN")),TypedAddon.Mode.class));
        rejects(IllegalArgumentException.class,()->LONG.parse("9007199254740993"));eq(LONG.parse("9007199254740992"),9007199254740992L);
        var low=ConfigKey.longInteger(def("low",Scope.CLIENT,ApplyPolicy.LIVE,Kind.INTEGER,"-9007199254740992",-9007199254740992d,0,List.of()));
        rejects(IllegalArgumentException.class,()->low.parse("-9007199254740993"));
        var full=ConfigKey.longInteger(def("full",Scope.CLIENT,ApplyPolicy.LIVE,Kind.INTEGER,"0",(double)Long.MIN_VALUE,(double)Long.MAX_VALUE,List.of()));
        eq(full.parse(Long.toString(Long.MIN_VALUE)),Long.MIN_VALUE);eq(full.parse(Long.toString(Long.MAX_VALUE)),Long.MAX_VALUE);
        try {r.commit(Scope.CLIENT,stale,Map.of(LIVE.id(),"4"));throw new AssertionError("Stale schema revision");}
        catch(IllegalArgumentException expected){}
        var legacy=r.registerAddon(key("legacy",Scope.CLIENT,ApplyPolicy.LIVE).definition());
        r.freezeSchema();r.freezeSchema();
        rejects(IllegalStateException.class,()->r.register(key("late",Scope.CLIENT,ApplyPolicy.LIVE)));
        rejects(IllegalStateException.class,()->r.registerAddon(key("late",Scope.SERVER,ApplyPolicy.WORLD_RESTART).definition()));
        rejects(IllegalStateException.class,()->r.register(key("late_internal",Scope.CLIENT,ApplyPolicy.LIVE).definition(),null));
        client(r,Map.of(LIVE.id(),"2",SESSION.id(),"2",CLIENT_RESTART.id(),"2",BOOL.id(),"FALSE",DECIMAL.id(),"0.75",STRING.id(),"changed",MODE.id(),"SECOND","example:legacy","9"));
        eq(a.live.get(),2);eq(a.session.get(),1);eq(a.clientRestart.get(),1);eq(a.bool.get(),false);
        eq(a.decimal.get(),0.75);eq(a.string.get(),"changed");eq(a.mode.get(),TypedAddon.Mode.SECOND);eq(legacy.get(),"9");
        try {client(r,Map.of(MODE.id(),"THIRD",LIVE.id(),"3"));throw new AssertionError("Bad choice");}catch(IllegalArgumentException expected){}
        eq(a.live.get(),2);r.openClientSession();eq(a.session.get(),2);eq(a.clientRestart.get(),1);
        client(r,Map.of(SESSION.id(),"3"));eq(a.session.get(),2);r.openClientSession();eq(a.session.get(),3);
        offline(r,Map.of(WORLD.id(),"2",SERVER_RESTART.id(),"2"));
        r.openLocalWorld();eq(a.world.get(),2);eq(a.serverRestart.get(),1);
        server(r,Map.of(WORLD.id(),"3",SERVER_RESTART.id(),"3"));eq(a.world.get(),2);eq(a.serverRestart.get(),1);
        r.closeWorld();r.openLocalWorld();eq(a.world.get(),3);eq(a.serverRestart.get(),1);r.closeWorld();
        var r2=new ConfigurationRegistry(root.resolve("local"));var b=new TypedAddon(r2);r2.freezeSchema();
        eq(b.clientRestart.get(),2);eq(b.serverRestart.get(),3);eq(b.mode.get(),TypedAddon.Mode.SECOND);
        r2.openLocalWorld();eq(b.serverRestart.get(),3);r2.closeWorld();
        Path world=root.resolve("world");Files.createDirectories(world.resolve("buildcraft"));
        Files.writeString(world.resolve("buildcraft/configuration.properties"),"example\\:server_restart=8\nexample\\:world=7\n");
        var d=new ConfigurationRegistry(root.resolve("dedicated"));var c=new TypedAddon(d);d.freezeSchema();d.openWorld(world);
        eq(c.serverRestart.get(),8);eq(c.world.get(),7);
        server(d,Map.of(SERVER_RESTART.id(),"9",WORLD.id(),"10"));eq(c.serverRestart.get(),8);eq(c.world.get(),7);
        d.closeWorld();eq(c.serverRestart.get(),8);d.openWorld(world);eq(c.serverRestart.get(),8);eq(c.world.get(),10);
        d.closeWorld();d.openWorld(root.resolve("different"));eq(c.serverRestart.get(),8);eq(c.world.get(),1);d.closeWorld();
        var d2=new ConfigurationRegistry(root.resolve("dedicated"));var e=new TypedAddon(d2);d2.freezeSchema();d2.openWorld(world);eq(e.serverRestart.get(),9);eq(e.world.get(),10);d2.closeWorld();
        System.out.println("Typed addon and lifecycle contracts passed");
    }
}
'''

class TypedConfigurationLifecycleTest(unittest.TestCase):
    def test_independent_addon_jar_and_real_registry(self):
        with tempfile.TemporaryDirectory() as td:
            work = Path(td)
            api_classes, addon_classes, host_classes = (work / x for x in ('api', 'addon', 'host'))
            for directory in (api_classes, addon_classes, host_classes): directory.mkdir()
            def run(*args):
                out = subprocess.run(args, cwd=work, text=True, capture_output=True, timeout=60)
                self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
                return out.stdout
            run('javac', '--release', '17', '-d', str(api_classes), *map(str, sorted(API.glob('*.java'))))
            api_jar = work / 'api.jar'
            run('jar', 'cf', str(api_jar), '-C', str(api_classes), '.')
            addon_source = work / 'TypedAddon.java'; addon_source.write_text(ADDON)
            run('javac', '--release', '17', '-sourcepath', '', '-cp', str(api_jar), '-d', str(addon_classes), str(addon_source))
            addon_jar = work / 'addon.jar'
            run('jar', 'cf', str(addon_jar), '-C', str(addon_classes), '.')
            probe = work / 'Probe.java'; probe.write_text(PROBE)
            cp = os.pathsep.join(map(str, (api_jar, addon_jar)))
            run('javac', '--release', '17', '-sourcepath', '', '-cp', cp, '-d', str(host_classes), str(RUNTIME), str(probe))
            self.assertIn('Typed addon and lifecycle contracts passed', run('java', '-cp', cp + os.pathsep + str(host_classes), 'Probe', str(work / 'data')))

    def test_lifecycle_and_login_are_wired_on_all_targets(self):
        from source_lookup import read_materialized_java
        for target in ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge','26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric'):
            with self.subTest(target=target):
                prefix = 'src/main/java/buildcraft/lib/'
                runtime = read_materialized_java(target, prefix + 'internal/api/v2/BuildCraftApiRuntime.java')
                freeze = runtime.split('if (next == ApiLifecycle.FROZEN)', 1)[1].split('lifecycle = next;', 1)[0]
                self.assertLess(freeze.index('BalanceConfig.initialize()'), freeze.index('ConfigurationRegistry.INSTANCE.freezeSchema()'))
                self.assertLess(freeze.index('modules.validateFreeze()'), freeze.index('ConfigurationRegistry.INSTANCE.freezeSchema()'))
                client = read_materialized_java(target, prefix + 'platform/events/PlatformConfigurationClient.java')
                self.assertIn('PlatformClientEvents.login(buildcraft.lib.internal.config.ConfigurationRegistry.INSTANCE::openClientSession)', client)

if __name__ == '__main__':
    unittest.main()
