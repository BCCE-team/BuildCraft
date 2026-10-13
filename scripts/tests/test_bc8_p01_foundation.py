#!/usr/bin/env python3
"""P01: compile a standalone addon against public API bytecode and exercise live backends.

Pure JVM stubs replace Minecraft and the mod loader only in this isolated test.
The full Gradle/GameTest matrix remains a separate release gate.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "source-shared/src/main/java"
ADDON = ROOT / "addon-fixture/src/main/java/dev/bcce/apifixture/FoundationReloadFixtureAddon.java"

API = [
    "buildcraft/api/v2/registry/ApiRegistry.java",
    "buildcraft/api/v2/registry/RegistrationContext.java",
    "buildcraft/api/v2/registry/RegistryEntry.java",
    "buildcraft/api/v2/module/ModuleInfo.java",
    "buildcraft/api/v2/module/ModuleService.java",
    "buildcraft/api/v2/reload/DefinitionDecoder.java",
    "buildcraft/api/v2/reload/DefinitionEntry.java",
    "buildcraft/api/v2/reload/DefinitionProvenance.java",
    "buildcraft/api/v2/reload/DefinitionReloadService.java",
    "buildcraft/api/v2/reload/DefinitionSnapshot.java",
    "buildcraft/api/v2/reload/ReloadChange.java",
    "buildcraft/api/v2/reload/ReloadContributor.java",
    "buildcraft/api/v2/reload/ReloadDiagnostic.java",
    "buildcraft/api/v2/reload/ReloadGeneration.java",
    "buildcraft/api/v2/reload/ReloadObserver.java",
    "buildcraft/api/v2/reload/ReloadPublishResult.java",
    "buildcraft/api/v2/reload/ResolvedDefinition.java",
    "buildcraft/api/v2/reload/ScriptableDefinitionType.java",
    "buildcraft/api/v2/reload/ScriptedDefinition.java",
    "buildcraft/api/v2/reload/ScriptedDefinitionSnapshot.java",
]
IMPL = [
    "buildcraft/lib/internal/api/v2/registry/ApiRegistryImpl.java",
    "buildcraft/lib/internal/api/v2/reload/DefinitionReloadServiceImpl.java",
    "buildcraft/lib/internal/api/v2/ModuleServiceImpl.java",
]
STUBS = {
    "net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {
    public static ResourceLocation tryParse(String value) {
        String[] a = value.split(":", 2);
        return a.length == 2 && !a[0].isBlank() && !a[1].isBlank()
            ? new ResourceLocation(a[0], a[1]) : null;
    }
    public String getNamespace() {return namespace;}
    public String getPath() {return path;}
    public String toString() {return namespace+":"+path;}
}""",
    "buildcraft/lib/internal/module/BCModules.java": """package buildcraft.lib.internal.module;
public enum BCModules {
    LIB("lib");
    public static final BCModules[] VALUES = values();
    public final String lowerCaseName;
    BCModules(String name) { lowerCaseName = name; }
    public boolean isLoaded() { return true; }
}""",
    "buildcraft/lib/internal/core/BuildCraftAPI.java": """package buildcraft.lib.internal.core;
public final class BuildCraftAPI { public static String getVersion() { return "2.1-test"; } }""",
}
HOST = """package buildcraft.lib.internal.api.v2;
import buildcraft.api.v2.reload.*;
import buildcraft.api.v2.registry.*;
import buildcraft.api.v2.module.*;
import buildcraft.lib.internal.api.v2.registry.ApiRegistryImpl;
import buildcraft.lib.internal.api.v2.reload.DefinitionReloadServiceImpl;
import dev.bcce.apifixture.FoundationReloadFixtureAddon;
import java.util.*;
import net.minecraft.resources.ResourceLocation;

public final class FoundationHostTest {
    static ResourceLocation id(String value) { return FoundationReloadFixtureAddon.id(value); }
    static DefinitionProvenance p(String owner, int priority) {
        return new DefinitionProvenance(owner, owner+".json", priority);
    }
    static ScriptedDefinition val(String id, String text, int priority) {
        return ScriptedDefinition.value(id(id), Map.of("value", text), p("foundation", priority));
    }
    static void check(boolean yes, String message) { if(!yes) throw new AssertionError(message); }
    static void denied(Runnable r) {
        try { r.run(); } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ex) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) {
        // Freeze preflight must preserve mutable state when a forward alias is unresolved.
        ApiRegistryImpl<String> registry = new ApiRegistryImpl<>();
        registry.registerAlias(id("foundation:forward"), id("foundation:missing"), ()->"foundation");
        denied(registry::freeze);
        check(!registry.frozen(), "A failed freeze mutated the registry");
        registry.register(id("foundation:missing"), "ok", ()->"foundation");
        registry.registerAlias(id("foundation:alias"), id("foundation:forward"), ()->"foundation");
        denied(() -> registry.registerAlias(id("foundation:missing"), id("foundation:alias"), ()->"foundation"));
        denied(() -> registry.registerAlias(id("foundation:forward"), id("foundation:missing"), ()->"foundation"));
        registry.validateFreeze();
        registry.freeze();
        check("ok".equals(registry.get(id("foundation:alias"))), "Alias not resolved");
        denied(() -> registry.register(id("foundation:late"), "bad"));
        check(registry.entries().size()==1, "Mutable data leak from entries");

        DefinitionReloadServiceImpl reloads = new DefinitionReloadServiceImpl();
        ModuleServiceImpl modules = new ModuleServiceImpl();
        FoundationReloadFixtureAddon.register(reloads, modules);
        modules.validateFreeze();
        check(modules.loaded(id("foundation:core")), "External module was not discoverable");
        ModuleServiceImpl missing = new ModuleServiceImpl();
        missing.registerExternal(new ModuleInfo(id("foundation:missing_dep"), "1", true),
            List.of(id("other:missing")), ()->"foundation");
        denied(missing::freeze);
        missing.registerExternal(new ModuleInfo(id("other:missing"), "1", true),
            List.of(), ()->"other");
        missing.freeze();
        denied(() -> modules.registerExternal(new ModuleInfo(id("buildcraft:lib"), "bad", true),
            List.of(), ()->"buildcraft"));
        modules.freeze();
        reloads.freeze();
        denied(() -> reloads.registerType(new ScriptableDefinitionType<>(id("foundation:late"), String.class,
            o -> "late"), ()->"foundation"));

        var type=FoundationReloadFixtureAddon.RULES;
        var first=reloads.reload(type,List.of(val("foundation:target","base",0),
            ScriptedDefinition.alias(id("foundation:alias"),id("foundation:target"),p("foundation",0)),
            ScriptedDefinition.disabled(id("foundation:off"), "removed",p("foundation",0))));
        check(first.published(), "Initial publication failed " + first.diagnostics());
        ScriptedDefinitionSnapshot<String> previous=reloads.snapshot(type);
        check(previous.get(id("foundation:injected")).isPresent(),"Code reload contributor missing");
        check("base".equals(previous.get(id("foundation:alias")).orElseThrow()),"Alias read");
        check(previous.get(id("foundation:off")).isEmpty(),"Disabled entry leaked");
        check(previous.disabled().get(id("foundation:off")).reason().equals("removed"), "Disabled reason lost");

        var changed=reloads.reload(type,List.of(val("foundation:target","low",0),
            val("foundation:target","winner",20)));
        check(changed.published(),"Priority reload failed");
        check("winner".equals(reloads.snapshot(type).get(id("foundation:target")).orElseThrow()),"Priority resolution");
        check(reloads.snapshot(type).definitions().resolved(id("foundation:target")).orElseThrow()
            .overridden().size()==1,"Lost override provenance");
        check("base".equals(previous.get(id("foundation:target")).orElseThrow()),"Old snapshot mutated");
        var committed=reloads.snapshot(type);
        check(FoundationReloadFixtureAddon.before==2 && FoundationReloadFixtureAddon.after==2,"Observer ordering");
        var scripted=reloads.reload(type,List.of(ScriptedDefinition.value(id("foundation:typed"),
            Map.of("type","foundation:uppercase","value","chosen"),p("foundation",0))));
        check(scripted.published(),"Named decoder did not publish");
        check("CHOSEN".equals(reloads.snapshot(type).get(id("foundation:typed")).orElseThrow()),"Named decoder wrong");
        var post=reloads.reload(type,List.of(val("foundation:after_fail","committed",0)));
        check(post.published() && !post.diagnostics().isEmpty(),"After publish warning must not roll back data");
        check("committed".equals(reloads.snapshot(type).get(id("foundation:after_fail")).orElseThrow()),"After publish lost data");
        committed=reloads.snapshot(type);

        check(!reloads.reload(type,List.of(val("foundation:target","left",2),
            val("foundation:target","right",2))).published(),"Conflicting priorities accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.alias(id("foundation:bad"),
            id("foundation:missing"),p("foundation",0)))).published(),"Dangling alias accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.alias(id("foundation:a"),
            id("foundation:b"),p("foundation",0)),ScriptedDefinition.alias(id("foundation:b"),
            id("foundation:a"),p("foundation",0)))).published(),"Alias cycle accepted");
        check(!reloads.reload(type,List.of(ScriptedDefinition.value(id("foundation:target"),
            Map.of("absent", "value"),p("foundation",0)))).published(),"Broken decoder accepted");
        check(!reloads.reload(type,List.of(val("foundation:veto","stop",0))).published(),"Veto was ignored");
        check(reloads.snapshot(type)==committed,"Failed reload changed published snapshot");
        check(FoundationReloadFixtureAddon.before==5 && FoundationReloadFixtureAddon.after==4,"Veto callback incorrect");

        var nested=new LinkedHashMap<String,Object>();
        var items=new ArrayList<Object>(List.of("old")); nested.put("list",items);
        var source=ScriptedDefinition.value(id("foundation:safe"),nested,p("foundation",0));
        items.set(0,"mutated");
        check(((List<?>)source.data().get("list")).get(0).equals("old"),"Nested input was not copied");
        denied(() -> source.data().put("hack","yes"));
        System.out.println("BC8-P01 PASS: api-only addon, modules, registry freeze, aliases, reload, veto, rollback");
    }
}
"""


def run(*args: str) -> None:
    result = subprocess.run(args, capture_output=True, text=True, timeout=100)
    if result.returncode:
        raise AssertionError(" ".join(args[:5])+"\n"+result.stdout+"\n"+result.stderr[-16000:])


def write(root: Path, path: str, data: str) -> Path:
    target = root/path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(data, encoding="utf-8")
    return target


class FoundationConformanceTest(unittest.TestCase):
    def test_addon_jar_bootstrap_and_reload(self) -> None:
        if not all(shutil.which(it) for it in ("javac", "java", "jar")):
            self.skipTest("JDK required")
        with tempfile.TemporaryDirectory(prefix="bcce-foundation-") as td:
            temp=Path(td)
            stub={name:write(temp/"stub",name,src) for name,src in STUBS.items()}
            api_classes=temp/"api-classes"
            api_classes.mkdir()
            run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-d",str(api_classes),
                str(stub["net/minecraft/resources/ResourceLocation.java"]),
                *[str(JAVA/path) for path in API])
            api_jar=temp/"api.jar"
            run("jar","--create","--file",str(api_jar),"-C",str(api_classes),".")
            addon_classes=temp/"addon-classes"
            addon_classes.mkdir()
            run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-cp",str(api_jar),
                "-d",str(addon_classes), str(ADDON))
            addon_jar=temp/"addon.jar"
            run("jar","--create","--file",str(addon_jar),"-C",str(addon_classes),".")
            with __import__("zipfile").ZipFile(addon_jar) as zip_file:
                assert all(not member.startswith(("buildcraft/", "net/minecraft/")) for member in zip_file.namelist())
            host=write(temp,"host/buildcraft/lib/internal/api/v2/FoundationHostTest.java",HOST)
            host_classes=temp/"host-classes"
            host_classes.mkdir()
            run("javac","--release","17","-encoding","UTF-8","-sourcepath","", "-cp",os.pathsep.join((str(api_jar),str(addon_jar))),
                "-d",str(host_classes), *[str(JAVA/path) for path in IMPL],
                str(stub["buildcraft/lib/internal/module/BCModules.java"]),
                str(stub["buildcraft/lib/internal/core/BuildCraftAPI.java"]), str(host))
            run("java","-cp",os.pathsep.join((str(api_jar),str(addon_jar),str(host_classes))),
                "buildcraft.lib.internal.api.v2.FoundationHostTest")


    def test_all_seven_targets_select_registered_runtime(self) -> None:
        # Check the actual precedence of canonical, downport and native 26.X layers.
        import sys
        sys.path.insert(0, str(ROOT / "scripts"))
        from source_layout import load_properties, resolve_effective_source, target_layout

        props = load_properties()
        targets = (
            "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge", "1.21.11-neoforge",
            "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
        )
        paths = (
            "buildcraft/api/v2/reload/DefinitionReloadService.java",
            "buildcraft/lib/internal/api/v2/reload/DefinitionReloadServiceImpl.java",
            "buildcraft/lib/internal/api/v2/registry/ApiRegistryImpl.java",
            "buildcraft/lib/internal/api/v2/ModuleServiceImpl.java",
            "buildcraft/lib/internal/api/v2/BuildCraftApiRuntime.java",
        )
        for target in targets:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                resolved = {}
                for suffix in paths:
                    selected = resolve_effective_source(layout, props, "src/main/java/" + suffix)
                    self.assertIsNotNone(selected, f"{target} missing {suffix}")
                    resolved[suffix] = selected.read_text(encoding="utf-8")
                runtime = resolved[paths[-1]]
                self.assertIn("BuildCraftServices.DEFINITION_RELOAD, definitionReload", runtime)
                self.assertIn("definitionReload.freeze()", runtime)
                self.assertIn("registry.validateFreeze()", runtime)
                self.assertIn("modules.validateFreeze()", runtime)
                self.assertIn("registerExternal", resolved[paths[-2]])
                self.assertIn("validateFreeze()", resolved[paths[-3]])
                self.assertIn("registerContributor", resolved[paths[0]])


if __name__=="__main__":
    unittest.main()
