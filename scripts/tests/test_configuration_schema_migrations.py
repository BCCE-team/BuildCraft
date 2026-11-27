"""Compile a third-party schema against the public API, then run it on the real registry."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'
ADDON = r'''
package example;
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import java.util.*;
public final class MigrationAddon {
    public static ConfigDefinition def(String id, Scope scope) {
        return new ConfigDefinition(id,scope==Scope.CLIENT?Section.CLIENT:Section.WORLD,List.of("Test"),scope,
            Kind.INTEGER,"10",0,1000,List.of(),"n","d","t","Name","Description","Tooltip",
            scope==Scope.CLIENT?ApplyPolicy.LIVE:ApplyPolicy.WORLD_RESTART);
    }
    public static ConfigSchema schema(Scope scope) {
        return new ConfigSchema("example",scope,2,List.of(
            values->{String old=values.remove("old");if(old!=null)values.putIfAbsent("capacity",old);},
            values->{if(values.containsKey("capacity"))values.put("capacity",Integer.toString(Integer.parseInt(values.get("capacity"))*2));}));
    }
    public static ConfigValue<Integer> register(ConfigurationService service, Scope scope) {
        service.registerSchema(schema(scope));
        // A setting ID may be registered only once across scopes; use this addon on independent registries.
        return service.register(ConfigKey.integer(def("example:capacity",scope)));
    }
}
'''
PROBE = r'''
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import example.MigrationAddon;
import java.nio.file.*;
import java.io.*;
import java.util.*;
public final class Probe {
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError(a+" != "+b);}
    static void rejects(Class<? extends Throwable> type, Runnable r) {
        try { r.run(); } catch(Throwable e) {if(type.isInstance(e))return;throw new AssertionError(e);}
        throw new AssertionError("Expected "+type);
    }
    static void put(Path p,Map<String,String> data)throws Exception {
        Files.createDirectories(p.getParent());var prop=new Properties();prop.putAll(data);
        try(var w=Files.newBufferedWriter(p)){prop.store(w,"test");}
    }
    static Map<String,String> read(Path p)throws Exception {
        var prop=new Properties();try(var r=Files.newBufferedReader(p)){prop.load(r);}
        var map=new HashMap<String,String>();for(String key:prop.stringPropertyNames())map.put(key,prop.getProperty(key));return map;
    }
    static final String MARKER="@bcce.schema.example";
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Path config=root.resolve("config");Path client=config.resolve("client.properties");
        put(client,Map.of("example:old","12","example:unknown","keep","other:untouched","9"));
        var r=new ConfigurationRegistry(config);long revision=r.snapshot(Scope.CLIENT).revision();
        var handle=MigrationAddon.register(r,Scope.CLIENT);eq(handle.get(),24);eq(r.snapshot(Scope.CLIENT).revision(),revision+2);
        eq(read(client).get("example:old"),"12"); // registration is not a disk write
        rejects(IllegalArgumentException.class,()->r.registerSchema(MigrationAddon.schema(Scope.CLIENT)));
        r.freezeSchema();r.persistDefaults();var saved=read(client);
        eq(saved.get(MARKER),"2");eq(saved.get("example:capacity"),"24");eq(saved.containsKey("example:old"),false);
        eq(saved.get("example:unknown"),"keep");eq(saved.get("other:untouched"),"9");
        var reopened=new ConfigurationRegistry(config);eq(MigrationAddon.register(reopened,Scope.CLIENT).get(),24);
        var fresh=new ConfigurationRegistry(root.resolve("fresh"));eq(MigrationAddon.register(fresh,Scope.CLIENT).get(),10);
        fresh.freezeSchema();rejects(IllegalStateException.class,()->fresh.registerSchema(new ConfigSchema("late",Scope.CLIENT,0,List.of())));
        rejects(IllegalArgumentException.class,()->new ConfigSchema("buildcraft",Scope.CLIENT,0,List.of()));
        rejects(IllegalArgumentException.class,()->new ConfigSchema("missing",Scope.CLIENT,2,List.of(values->{})));
        var late=new ConfigurationRegistry(root.resolve("late"));late.registerAddon(MigrationAddon.def("example:capacity",Scope.CLIENT));
        rejects(IllegalStateException.class,()->late.registerSchema(MigrationAddon.schema(Scope.CLIENT)));
        // Failed and invalid migration output leave both the memory state and file unchanged.
        Path fail=root.resolve("fail/client.properties");put(fail,Map.of("example:old","15","other:x","safe"));var before=Files.readAllBytes(fail);
        var broken=new ConfigurationRegistry(fail.getParent());long rev=broken.snapshot(Scope.CLIENT).revision();
        rejects(IllegalStateException.class,()->broken.registerSchema(new ConfigSchema("example",Scope.CLIENT,2,List.of(v->v.put("capacity","99"),v->{throw new IllegalArgumentException("bad");}))));
        rejects(IllegalStateException.class,()->broken.registerSchema(new ConfigSchema("example",Scope.CLIENT,1,List.of(v->v.put("other:x","bad")))));
        eq(broken.snapshot(Scope.CLIENT).revision(),rev);eq(Arrays.equals(before,Files.readAllBytes(fail)),true);
        eq(MigrationAddon.register(broken,Scope.CLIENT).get(),30);
        Path newer=root.resolve("newer/client.properties");put(newer,Map.of(MARKER,"3","example:capacity","40"));
        var downgrade=new ConfigurationRegistry(newer.getParent());rejects(IllegalStateException.class,()->downgrade.registerSchema(MigrationAddon.schema(Scope.CLIENT)));
        eq(read(newer).get(MARKER),"3");
        Path invalid=root.resolve("invalid/client.properties");put(invalid,Map.of("example:old","600"));
        var invalidRegistry=new ConfigurationRegistry(invalid.getParent());rejects(IllegalStateException.class,()->MigrationAddon.register(invalidRegistry,Scope.CLIENT));
        rejects(IllegalStateException.class,invalidRegistry::freezeSchema);
        try{invalidRegistry.persistDefaults();throw new AssertionError("Invalid migrated data persisted");}catch(IOException expected){}
        eq(read(invalid).get("example:old"),"600");eq(read(invalid).containsKey(MARKER),false);
        // Dedicated worlds migrate independently of the current global server defaults.
        Path server=root.resolve("server");Path defaults=server.resolve("server-defaults.properties");
        put(defaults,Map.of("example:old","20","other:default","3"));
        Path world=root.resolve("world"), worldFile=world.resolve("buildcraft/configuration.properties");
        put(worldFile,Map.of("example:old","35","other:world","4"));
        var host=new ConfigurationRegistry(server);var capacity=MigrationAddon.register(host,Scope.SERVER);host.freezeSchema();host.persistDefaults();
        eq(capacity.get(),40);host.openWorld(world);eq(capacity.get(),70);host.closeWorld();eq(capacity.get(),40);
        eq(read(worldFile).get(MARKER),"2");eq(read(worldFile).get("other:world"),"4");
        host.openWorld(world);eq(capacity.get(),70);host.closeWorld();host.openWorld(root.resolve("new_world"));eq(capacity.get(),40);host.closeWorld();
        host.openLocalWorld();eq(capacity.get(),40);host.closeWorld();
        Path badWorld=root.resolve("bad_world"),badFile=badWorld.resolve("buildcraft/configuration.properties");
        put(badFile,Map.of("example:old","600"));before=Files.readAllBytes(badFile);
        try{host.openWorld(badWorld);throw new AssertionError("Invalid migrated world accepted");}catch(IllegalStateException expected){}
        eq(host.hasWorld(),false);eq(Arrays.equals(before,Files.readAllBytes(badFile)),true);
        // An absent addon must not copy a newer global marker onto old world data.
        Path absentWorld=root.resolve("absent_world"),absentFile=absentWorld.resolve("buildcraft/configuration.properties");
        put(absentFile,Map.of("example:old","44"));var absent=new ConfigurationRegistry(server);absent.freezeSchema();absent.openWorld(absentWorld);absent.closeWorld();
        eq(read(absentFile).containsKey(MARKER),false);eq(read(absentFile).containsKey("example:capacity"),false);
        host.openWorld(absentWorld);eq(capacity.get(),88);host.closeWorld();
        Path futureWorld=root.resolve("future_world"),futureFile=futureWorld.resolve("buildcraft/configuration.properties");
        put(futureFile,Map.of(MARKER,"3","example:capacity","60"));
        try{host.openWorld(futureWorld);throw new AssertionError("Future schema accepted");}catch(IllegalStateException expected){}
        eq(host.hasWorld(),false);eq(read(futureFile).get(MARKER),"3");
        System.out.println("Schema migration contracts passed");
    }
}
'''

class ConfigurationSchemaMigrationTests(unittest.TestCase):
    def test_external_addon_and_real_storage(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            api, addon, host = (work / x for x in ('api', 'addon', 'host'))
            for p in (api, addon, host): p.mkdir()
            def run(*args):
                out = subprocess.run(args, cwd=work, text=True, capture_output=True, timeout=60)
                self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
                return out.stdout
            import os
            src = work/'MigrationAddon.java'; src.write_text(ADDON, encoding='utf-8')
            probe = work/'Probe.java'; probe.write_text(PROBE, encoding='utf-8')
            run('javac','--release','17','-d',str(api),*map(str,(JAVA/'api/v2/config').glob('*.java')))
            run('javac','--release','17','-cp',str(api),'-d',str(addon),str(src))
            cp = os.pathsep.join(map(str,(api,addon,host)))
            run('javac','--release','17','-cp',cp,'-d',str(host),str(JAVA/'lib/internal/config/ConfigurationRegistry.java'),str(probe))
            self.assertIn('Schema migration contracts passed',run('java','-cp',cp,'Probe',str(work/'data')))

if __name__=='__main__': unittest.main()
