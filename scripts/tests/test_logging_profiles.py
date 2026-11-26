"""Runtime verification of BCCE logging profile semantics without a Minecraft dependency.

A small Log4j API fixture checks the public ExtendedLoggerWrapper method contracts
and exercises the real production BCLog/BCLogProfile/BCDebugging sources with javac.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "source-shared/src/main/java/buildcraft/lib/internal/debug"

OVERLOADS = [
    "Message message, Throwable t", "CharSequence message, Throwable t",
    "Object message, Throwable t", "String message", "String message, Throwable t",
    "String message, Object... args",
] + ["String message, " + ", ".join(f"Object p{i}" for i in range(n)) for n in range(1, 11)]


def java_fixture(directory: Path) -> list[Path]:
    specs: dict[str, str] = {
        "org/apache/logging/log4j/Level.java": """package org.apache.logging.log4j;
public enum Level { OFF(-1), TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4), FATAL(5);
private final int weight; Level(int weight){this.weight=weight;}
public boolean isMoreSpecificThan(Level level){return weight>=level.weight;}}
""",
        "org/apache/logging/log4j/Marker.java": """package org.apache.logging.log4j;
public interface Marker {boolean isInstanceOf(Marker other);}
""",
        "org/apache/logging/log4j/MarkerManager.java": """package org.apache.logging.log4j;
import java.util.concurrent.ConcurrentHashMap;
public final class MarkerManager {
private static final ConcurrentHashMap<String, Marker> MARKERS=new ConcurrentHashMap<>();
public static Marker getMarker(String name){return MARKERS.computeIfAbsent(name,
 k -> new Marker(){public boolean isInstanceOf(Marker other){return this==other;}});}}
""",
        "org/apache/logging/log4j/message/Message.java": """package org.apache.logging.log4j.message;
public interface Message {String getFormattedMessage();}
""",
        "org/apache/logging/log4j/Logger.java": """package org.apache.logging.log4j;
public interface Logger {
 Object getMessageFactory();
 void info(String text, Object... args); void info(Marker marker, String text, Object... args);
 void warn(String text, Object... args); void error(String text, Object... args);
 void debug(String text, Object... args); void trace(String text, Object... args);
}
""",
        "org/apache/logging/log4j/spi/ExtendedLogger.java": """package org.apache.logging.log4j.spi;
import org.apache.logging.log4j.*; import org.apache.logging.log4j.message.Message;
public interface ExtendedLogger extends Logger {
 void logMessage(String fqcn, Level level, Marker marker, Message message, Throwable t);
}
""",
        "org/apache/logging/log4j/LogManager.java": """package org.apache.logging.log4j;
import java.util.*;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.message.Message;
public final class LogManager {
 public static final java.util.List<String> EVENTS=new java.util.ArrayList<>();
 private static final ExtendedLogger LOGGER = new Fake();
 public static Logger getLogger(String name){return LOGGER;}
 public static final class Fake implements ExtendedLogger {
  public void setLevel(Level l) { }
  private static String format(String t,Object[] a){return t+"|"+java.util.Arrays.toString(a);}
  public Object getMessageFactory(){return new Object();}
  public void logMessage(String f, Level l, Marker m, Message msg, Throwable t){EVENTS.add(l+":"+msg.getFormattedMessage());}
  public void info(String t,Object...a){ logMessage("",Level.INFO,null,()->format(t,a),null); }
  public void info(Marker m,String t,Object...a){ logMessage("",Level.INFO,m,()->format(t,a),null); }
  public void warn(String t,Object...a){ logMessage("",Level.WARN,null,()->format(t,a),null); }
  public void error(String t,Object...a){ logMessage("",Level.ERROR,null,()->format(t,a),null); }
  public void debug(String t,Object...a){ logMessage("",Level.DEBUG,null,()->format(t,a),null); }
  public void trace(String t,Object...a){ logMessage("",Level.TRACE,null,()->format(t,a),null); }
 }
}
""",
        "buildcraft/lib/internal/core/BuildCraftAPI.java": """package buildcraft.lib.internal.core;
public final class BuildCraftAPI {public static String getVersion(){return "test";}}
""",
        "Probe.java": """import buildcraft.lib.internal.debug.*;
import org.apache.logging.log4j.LogManager;
public final class Probe {
 public static void main(String[] args) {
  String core=System.getProperty("test.core.profile");
  if (core != null) BCLogProfile.applyCoreProfile(BCLogProfile.parse(core));
  BCLog.logger.error("real-error"); BCLog.logger.warn("real-warning");
  BCLog.logger.info("routine-info"); BCLog.logger.debug("ordinary-debug");
  BCLog.logger.trace("network-trace");
  BCLog.diagnostic("fallback-case"); BCLog.event("task-started");
  BCLog.explicitEvent("explicit-robot-trace");
  for (int i=0;i<130;i++) BCLog.diagnosticSampled("tile-race", "tile-race-occurrence");
  for (int i=0;i<130;i++) BCLog.warningSampled("invalid-packet", "invalid-packet-occurrence", new RuntimeException("bad"));
  System.out.println("PROFILE="+BCLogProfile.current());
  System.out.println("HOOKS="+BCDebugging.shouldDebugLog("lib.marker.full")+":"+BCDebugging.shouldDebugComplex("lib.marker.full"));
  for (String entry:LogManager.EVENTS) System.out.println("LOG="+entry);
 }
}
""",
    }
    methods = "\n".join("public boolean isEnabled(Level l,Marker m," + decl + "){return true;}"
                        for decl in OVERLOADS)
    specs["org/apache/logging/log4j/spi/ExtendedLoggerWrapper.java"] = """package org.apache.logging.log4j.spi;
import org.apache.logging.log4j.*; import org.apache.logging.log4j.message.Message;
public class ExtendedLoggerWrapper implements ExtendedLogger {
 protected final ExtendedLogger logger;
 private static String format(String t,Object[] a){return t+"|"+java.util.Arrays.toString(a);}
 public ExtendedLoggerWrapper(ExtendedLogger l,String n,Object factory){logger=l;}
 public Level getLevel(){return Level.INFO;}
 public Object getMessageFactory(){return logger.getMessageFactory();}
 public void logMessage(String f,Level l,Marker m,Message msg,Throwable t){logger.logMessage(f,l,m,msg,t);}
 public void info(String t,Object...a){if(isEnabled(Level.INFO,null,t,a))logMessage("",Level.INFO,null,()->format(t,a),null);}
 public void info(Marker m,String t,Object...a){if(isEnabled(Level.INFO,m,t,a))logMessage("",Level.INFO,m,()->format(t,a),null);}
 public void warn(String t,Object...a){if(isEnabled(Level.WARN,null,t,a))logMessage("",Level.WARN,null,()->format(t,a),null);}
 public void error(String t,Object...a){if(isEnabled(Level.ERROR,null,t,a))logMessage("",Level.ERROR,null,()->format(t,a),null);}
 public void debug(String t,Object...a){if(isEnabled(Level.DEBUG,null,t,a))logMessage("",Level.DEBUG,null,()->format(t,a),null);}
 public void trace(String t,Object...a){if(isEnabled(Level.TRACE,null,t,a))logMessage("",Level.TRACE,null,()->format(t,a),null);}
""" + methods + "\n}\n"
    paths=[]
    for name,data in specs.items():
        path=directory/name; path.parent.mkdir(parents=True,exist_ok=True); path.write_text(data,encoding='utf8');paths.append(path)
    return paths


class BuildCraftLoggingProfilesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if not (shutil.which('javac') and shutil.which('java')):
            raise unittest.SkipTest('Java toolchain is not available')
        cls.work=tempfile.TemporaryDirectory(prefix='bcce_log_profiles_')
        cls.temp=Path(cls.work.name)
        cls.classes=cls.temp/'classes';cls.classes.mkdir()
        sources=java_fixture(cls.temp/'fixture')
        sources += [SOURCES/x for x in ('BCLogProfile.java','BCProfileLogger.java','BCLog.java','BCDebugging.java')]
        sources.append(ROOT/'source-shared/src/main/java/buildcraft/lib/client/config/CoreDebugSettingsFile.java')
        cmd=['javac','--release','17','-encoding','UTF-8','-d',str(cls.classes),*map(str,sources)]
        result=subprocess.run(cmd,capture_output=True,text=True,timeout=60)
        if result.returncode:
            raise AssertionError('Production logger failed to compile:\n'+result.stderr)

    @classmethod
    def tearDownClass(cls) -> None:
        if hasattr(cls,'work'):cls.work.cleanup()

    def launch(self, setting: str | None, core_profile: str | None = None):
        game_dir=self.temp/('launch-'+(setting or 'core')+'-'+(core_profile or 'default')).replace(' ', '-')
        game_dir.mkdir(parents=True,exist_ok=True)
        cmd=['java']
        if setting is not None: cmd.append('-Dbuildcraft.log.profile='+setting)
        if core_profile is not None: cmd.append('-Dtest.core.profile='+core_profile)
        cmd += ['-cp',str(self.classes),'Probe']
        env=os.environ.copy(); env.pop('BUILDCRAFT_LOG_PROFILE',None)
        run=subprocess.run(cmd,cwd=game_dir,capture_output=True,text=True,timeout=20,env=env)
        self.assertEqual(run.returncode,0,run.stderr)
        lines=run.stdout.splitlines()
        return [line[4:] for line in lines if line.startswith('LOG=')], lines,game_dir

    def test_default_keeps_errors_and_actionable_warnings_without_ordinary_events(self):
        logs, lines, game_dir=self.launch(None)
        text='\n'.join(logs)
        self.assertIn('real-error',text); self.assertIn('real-warning',text)
        self.assertIn('explicit-robot-trace',text)
        for excluded in ('routine-info','ordinary-debug','fallback-case','task-started','network-trace','tile-race-occurrence'):
            self.assertNotIn(excluded,text)
        self.assertEqual(text.count('invalid-packet-occurrence'),4)
        self.assertFalse((game_dir/'config/buildcraft-logging.properties').exists())
        self.assertIn('HOOKS=false:false',lines)

    def test_debug_has_fallbacks_but_not_routine_info(self):
        logs,lines,_=self.launch('debug')
        text='\n'.join(logs)
        for included in ('ordinary-debug','fallback-case','real-error','real-warning'):
            self.assertIn(included,text)
        self.assertEqual(text.count('tile-race-occurrence'),4)
        self.assertNotIn('routine-info',text);self.assertNotIn('task-started',text)
        self.assertIn('HOOKS=false:false',lines)

    def test_hard_debugging_has_lifecycle_and_log_hooks(self):
        logs,lines,_=self.launch('hard debugging')
        text='\n'.join(logs)
        self.assertIn('routine-info',text);self.assertIn('task-started',text)
        self.assertNotIn('network-trace',text)
        self.assertIn('HOOKS=true:false',lines)

    def test_verbose_keeps_all_trace_and_unsampled_details(self):
        logs,lines,_=self.launch('verbose')
        text='\n'.join(logs)
        self.assertIn('network-trace',text)
        self.assertEqual(text.count('tile-race-occurrence'),130)
        self.assertEqual(text.count('invalid-packet-occurrence'),130)
        self.assertIn('HOOKS=true:true',lines)

    def test_core_selection_and_system_property_override(self):
        logs,lines,_=self.launch(None,'hard_debugging')
        self.assertIn('PROFILE=HARD_DEBUGGING',lines)
        self.assertIn('HOOKS=true:false',lines)
        logs,lines,_=self.launch('default','verbose')
        self.assertIn('PROFILE=DEFAULT',lines)
        logs,lines,_=self.launch(None,'verbose')
        self.assertIn('PROFILE=VERBOSE',lines)
        self.assertIn('HOOKS=true:true',lines)

    def test_invalid_profile_falls_back_to_default_with_warning(self):
        logs,lines,_=self.launch('not-a-profile')
        self.assertIn('PROFILE=DEFAULT',lines)
        self.assertIn('Invalid BuildCraft logging profile', '\n'.join(logs))

    def test_all_network_message_owners_use_profiled_diagnostics(self):
        owners=[ROOT/x for x in (
            'source-platforms/forge/src/main/java/buildcraft/lib/net/MessageUpdateTile.java',
            'source-families/1.21.X/src/main/java/buildcraft/lib/net/MessageUpdateTile.java',
            'source-families/26.X/src/main/java/buildcraft/lib/net/MessageUpdateTile.java',
        )]
        for owner in owners:
            with self.subTest(owner=owner):
                code=owner.read_text(encoding='utf-8')
                self.assertIn('diagnosticSampled("tile-update-missing"',code)
                self.assertIn('warningSampled("invalid-tile-update"',code)
                self.assertNotIn('BCLog.logger.warn("Dropped BuildCraft tile update for missing',code)

    def test_core_debug_is_the_owner_and_robot_trace_is_independent(self):
        core = (ROOT/'source-shared/src/main/java/buildcraft/core/BCCoreConfig.java').read_text()
        self.assertIn('builder.push("debug")',core)
        self.assertIn('defineEnum("loggingProfile", BCLogProfile.Profile.DEFAULT',core)
        self.assertIn('define("verboseRobotLogging", false)',core)
        self.assertIn('BCLogProfile.applyCoreProfile(chosenLoggingProfile == null',core)
        robot = (ROOT/'source-shared/src/main/java/buildcraft/robotics/RobotTrace.java').read_text()
        self.assertIn('if (ai == null || !BCCoreConfig.verboseRobotLogging) return;',robot)
        self.assertNotIn('BCLogProfile.current() == BCLogProfile.Profile.VERBOSE',robot)
        self.assertIn('if (!BCCoreConfig.verboseRobotLogging',robot)
        profile = (ROOT/'source-shared/src/main/java/buildcraft/lib/internal/debug/BCLogProfile.java').read_text()
        self.assertNotIn('buildcraft-logging.properties',profile)
        self.assertIn('coreProfile',profile)
        logger = (ROOT/'source-shared/src/main/java/buildcraft/lib/internal/debug/BCProfileLogger.java').read_text()
        self.assertIn('invoke(base, Level.TRACE)', logger)

    def test_native_neoforge_core_editor_is_not_replaced_by_logging_screen(self):
        forge=(ROOT/'source-platforms/forge/src/main/java/buildcraft/lib/platform/config/ConfigScreenRegistration.java').read_text()
        self.assertNotIn('registerExtensionPoint(',forge)
        self.assertNotIn('LoggingSettingsScreen(parent)',forge)
        for version in ('1.19.2-forge','1.20.1-forge'):
            lib=(ROOT/f'version-src/{version}/src/main/java/buildcraft/lib/BCLib.java').read_text()
            core=(ROOT/f'version-src/{version}/src/main/java/buildcraft/core/BCCore.java').read_text()
            self.assertNotIn('registerLogging(',lib)
            self.assertNotIn('ConfigScreenRegistration.registerCoreDebug()',core)
        for relative in ('source-platforms/neoforge/src/main/java/buildcraft/lib/platform/config/ConfigScreenRegistration.java',
                         'source-families/26.X/src/main/java/buildcraft/lib/platform/config/ConfigScreenRegistration.java'):
            code=(ROOT/relative).read_text()
            self.assertIn('new buildcraft.lib.client.config.BuildCraftConfigurationScreen(parent)', code)
            self.assertNotIn('new buildcraft.lib.client.config.LoggingSettingsScreen', code)
            self.assertNotIn('LoggingSettingsScreen',code)
            self.assertNotIn('registerLogging(',code)
        for relative in ('source-platforms/neoforge/src/main/java/buildcraft/lib/BCLib.java',
                         'source-families/26.X/src/main/java/buildcraft/lib/BCLib.java'):
            self.assertNotIn('registerLogging(', (ROOT/relative).read_text())
        fabric_manifest=(ROOT/'source-family-platforms/26.X/fabric/src/main/resources/fabric.mod.json').read_text()
        import json
        parsed = json.loads(fabric_manifest)
        self.assertNotIn('modmenu', parsed['entrypoints'])
        self.assertNotIn('BuildCraftModMenu', fabric_manifest)
        # The config values remain in Core for all loaders, even where there is no
        # native complete in-game editor.
        core = (ROOT/'source-shared/src/main/java/buildcraft/core/BCCoreConfig.java').read_text()
        for section in ('display', 'general', 'power', 'debug'):
            self.assertIn('builder.push("' + section + '")', core)
        self.assertIn('defineEnum("loggingProfile", BCLogProfile.Profile.DEFAULT', core)
        self.assertIn('define("verboseRobotLogging", false)', core)

    def test_core_toml_editor_is_atomic_preserves_other_sections_and_flags(self):
        source=self.temp/'ChangeCoreDebug.java'
        source.write_text(r'''import buildcraft.lib.client.config.CoreDebugSettingsFile;
import buildcraft.lib.internal.debug.BCLogProfile;
import java.nio.file.*;
public class ChangeCoreDebug {
 public static void main(String[] ignored) throws Exception {
  Path path=Path.of("config", "buildcraftcore-common.toml");
  Files.createDirectories(path.getParent());
  Files.writeString(path, "# custom\n[general]\nfoo = 12\n\n[debug]\nverboseRobotLogging = false # robot\n[power]\nbar = 1\n");
  CoreDebugSettingsFile.save(path, BCLogProfile.Profile.HARD_DEBUGGING, true);
  CoreDebugSettingsFile.Settings state=CoreDebugSettingsFile.read(path);
  if (state.profile()!=BCLogProfile.Profile.HARD_DEBUGGING || !state.verboseRobotLogging()) throw new AssertionError("read-back");
  String value=Files.readString(path);
  if (!value.contains("# custom") || !value.contains("foo = 12") || !value.contains("bar = 1")
      || !value.contains("verboseRobotLogging = true # robot")
      || !value.contains("loggingProfile = \"HARD_DEBUGGING\"")) throw new AssertionError(value);
  if (BCLogProfile.current()!=BCLogProfile.Profile.DEFAULT) throw new AssertionError("changed mid-run");
  CoreDebugSettingsFile.save(path, BCLogProfile.Profile.VERBOSE, false);
  if (CoreDebugSettingsFile.read(path).verboseRobotLogging()) throw new AssertionError("robot flag not independent");
  if (Files.readString(path).split("loggingProfile =", -1).length!=2) throw new AssertionError("duplicate key");
  System.out.println("PASS");
 }
}
''', encoding='utf-8')
        result=subprocess.run(['javac','-encoding','UTF-8','-cp',str(self.classes),'-d',str(self.classes),str(source)],
                              capture_output=True,text=True,timeout=30)
        self.assertEqual(result.returncode,0,result.stderr)
        game=self.temp/'change-core-debug'
        game.mkdir(exist_ok=True)
        env=os.environ.copy();env.pop('BUILDCRAFT_LOG_PROFILE',None)
        result=subprocess.run(['java','-cp',str(self.classes),'ChangeCoreDebug'],cwd=game,
                              capture_output=True,text=True,env=env,timeout=20)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('PASS',result.stdout)


if __name__=='__main__':unittest.main()
