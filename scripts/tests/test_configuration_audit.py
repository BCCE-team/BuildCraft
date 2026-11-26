"""Regression checks for the active configuration schema and runtime paths."""
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'
sys.path.insert(0, str(ROOT / 'scripts'))
from source_lookup import read_materialized_java

TARGETS = ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge', '1.21.11-neoforge',
           '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge', '26.3-fabric')
SCHEMAS = {
    'core/BCCoreConfig': '''display.colorBlindMode display.useColouredLabels display.useHighContrastColouredLabels
        display.hidePowerValues display.hideFluidValues display.guideBookEnableDetail display.fluidVolumeUnit
        display.zonePlannerMode display.electronicLibraryMode display.useLongLocalizedName display.timeGap
        display.itemRenderRotation display.numberFormat display.temperatureUnit display.rememberGuidePage
        display.pipes.fluidAnimation display.pipes.itemDisplay display.pipes.contentRenderDistance display.pipes.maxRenderedItems
        display.updates.automaticCheck display.updates.notifications general.chunkLoading general.chunkLoadLevel general.itemLifespan
        general.useWrenchTag general.updateFactor general.maxGuideSearchResults power.mjPerFe power.powerMode
        debug.loggingProfile debug.verboseRobotLogging''',
    'energy/BCEnergyConfig': '''engines.stirlingExplosion engines.engineExplosionBlockDamage worldgen.oil.enable worldgen.oil.generationProfile worldgen.oil.customGenerationRate
        worldgen.oil.can_burn worldgen.oil.oilIsDense worldgen.oil.biomeBlacklist worldgen.oil.biomeWhitelist
        worldgen.oil.dimensionBlacklist worldgen.oil.dimensionWhitelist worldgen.oil.spouts.enable
        worldgen.oil.spouts.small_min_height worldgen.oil.spouts.small_max_height
        worldgen.oil.spouts.large_min_height worldgen.oil.spouts.large_max_height''',
    'transport/BCTransportConfig': '''general.pipes.mjPerMillibucket general.pipes.mjPerItem
        general.pipes.baseFluidRate general.pipes.baseFeRate display.pipes.fluidColourIsBorder''',
    'silicon/BCSiliconConfig': 'facades.enable',
}

PROBE = r'''
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.internal.config.ConfigurationRegistry;
import buildcraft.lib.balance.*;
import buildcraft.builders.QuarryRateLimit;
import java.nio.file.*;
import java.util.*;
public class AuditProbe {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of("config", "buildcraft"); Files.createDirectories(root);
        String retired = "buildcraft:balance/machine/buildcraftfactory/distiller/work_speed_multiplier";
        String chunk = "buildcraftcore:general/chunkLoadLevel";
        Properties old = new Properties(); old.setProperty(retired, "4.5"); old.setProperty(chunk, "ALL_TILES");
        try (var w=Files.newBufferedWriter(root.resolve("server-defaults.properties"))) { old.store(w, "legacy"); }
        var reg = ConfigurationRegistry.INSTANCE;
        var handle = reg.register(new ConfigDefinition(chunk, Section.WORLD, List.of("Performance"), Scope.SERVER,
            Kind.ENUM, "SELF_TILES", 0, 0, List.of("NONE","STRICT_TILES","SELF_TILES"), "n","d","t","N","D","T",ApplyPolicy.WORLD_RESTART), null);
        check(handle.get().equals("SELF_TILES"));
        BalanceConfig.initialize();
        var active = BalanceIni.runtimeParameters();
        check(BalanceIni.parameters().size()==78 && active.size()==56);
        check(active.stream().filter(p->p.file()==BalanceIni.FileKind.ROBOTS).count()==40);
        check(active.stream().filter(p->p.key().equals("work_speed_multiplier")).count()==1);
        check(active.stream().filter(p->p.file()==BalanceIni.FileKind.MACHINES && p.key().equals("energy_cost_multiplier")).count()==3);
        check(reg.definitions().stream().filter(d->d.id().startsWith("buildcraft:balance/")).count()==56);
        check(reg.value(retired).isEmpty());
        check(BalanceConfig.machine("buildcraftfactory:distiller", "work_speed_multiplier",1)==1);
        check(BalanceConfig.boardKey("buildcraft:boardRobotFluidCarrier").equals("fluid_carrier"));
        check(BalanceConfig.robot("example:picker", "energy_cost_multiplier",7)==7);
        reg.persistDefaults();
        Properties saved = new Properties();
        try (var r=Files.newBufferedReader(root.resolve("server-defaults.properties"))) { saved.load(r); }
        check(saved.getProperty(retired).equals("4.5") && saved.getProperty(chunk).equals("SELF_TILES"));
        reg.commitLocalDefaults(reg.snapshot(Scope.SERVER).revision(), Map.of("buildcraft:balance/machine/buildcraftbuilders/quarry/work_speed_multiplier","2", "buildcraft:balance/robot/picker/energy_cost_multiplier","3"));
        reg.openLocalWorld();
        check(BalanceConfig.machine("buildcraftbuilders:quarry","work_speed_multiplier",1)==2);
        check(BalanceConfig.robot("buildcraft:boardRobotPicker","energy_cost_multiplier",1)==3);
        reg.closeWorld();
        check(QuarryRateLimit.progress(1000000,1000000,1,0,0)==1000000);
        check(Math.abs(QuarryRateLimit.progress(1000000,1000000,1,0.01,0)-500)<=1);
        check(Math.abs(QuarryRateLimit.progress(100000000,100000000,5,2,0)-2000000)<=1);
        check(QuarryRateLimit.progress(1000000,1000000,1,2,0.1)==0);
        check(QuarryRateLimit.progress(1000000,1000000,1,1e-12,0)==1);
        System.out.println("Configuration audit passed");
    }
}
'''

class ConfigurationAuditTest(unittest.TestCase):
    def test_schema_and_descriptions(self):
        lang = json.loads((ROOT / 'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
        for target in TARGETS:
            for file, expected in SCHEMAS.items():
                with self.subTest(target=target, file=file):
                    code = read_materialized_java(target, 'src/main/java/buildcraft/'+file+'.java')
                    stack, keys = [], []
                    for m in re.finditer(r'\bbuilder\.push\("([^"]+)"\)|\bbuilder\.pop\(\)|\.define(?:Enum|InRange)?\("([^"]+)"', code):
                        if m[1]: stack.append(m[1])
                        elif m[2] is None: stack.pop()
                        else: keys.append('.'.join(stack+[m[2]]))
                    self.assertFalse(stack)
                    self.assertCountEqual(keys, expected.split())
                    for key in keys:
                        prefix = 'config.buildcraft'+file.split('/')[0]+'.'+key
                        self.assertTrue(lang[prefix+'.name'])
                        self.assertTrue(lang[prefix+'.description'])
                        self.assertTrue(lang[prefix+'.tooltip'])
                        self.assertNotEqual(lang[prefix+'.name'], lang[prefix+'.description'])

    def test_effective_consumer_paths(self):
        for target in TARGETS:
            with self.subTest(target=target):
                def read(path): return read_materialized_java(target, 'src/main/java/buildcraft/'+path+'.java')
                renderer = read('transport/client/render/PipeFlowRendererItems')
                orient = read('transport/client/render/PipeItemOrientation')
                self.assertIn('PipeItemOrientation.apply(matrix, item.getRenderDirection(now, partialTicks))', renderer)
                self.assertIn('rotateTravelingItems.changeFacing', orient)
                self.assertEqual('import com.mojang.math.Vector3f;' in orient, target.startswith('1.19.2'))
                inventory = read('lib/misc/InventoryUtil')
                self.assertIn('BlockUtil.dropItem(server, pos, buildcraft.lib.BCLibConfig.itemLifespan * 20', inventory)
                quarry = read('builders/tile/TileQuarry')
                self.assertEqual(quarry.count('QuarryRateLimit.progress('), 2)
                if target.endswith('-fabric'):
                    self.assertIn('entityitem.age = 6000 - Math.max(1, Math.min(12000, forcedLifespan))', read('lib/misc/BlockUtil'))

    def test_registry_and_rate_limits(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp); probe = work/'AuditProbe.java'; probe.write_text(PROBE, encoding='utf-8')
            sources = list((JAVA/'api/v2/config').glob('*.java'))
            sources += [JAVA/p for p in ('lib/internal/config/ConfigurationRegistry.java', 'lib/platform/config/BCConfigSpec.java', 'lib/platform/config/ConfigBinding.java', 'lib/platform/config/LegacyToml.java', 'lib/balance/BalanceIni.java', 'lib/balance/BalanceConfig.java', 'builders/QuarryRateLimit.java')]
            subprocess.run(['javac','--release','17','-d',str(work),*map(str,sources),str(probe)],check=True,timeout=60)
            out = subprocess.run(['java','-cp',str(work),'AuditProbe'],cwd=work,check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Configuration audit passed',out.stdout)

if __name__ == '__main__': unittest.main()
