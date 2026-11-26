"""Test structured config tooltip layout without a Minecraft client."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
JAVA = ROOT / 'source-shared/src/main/java/buildcraft/lib/client/config'
LANG = ROOT / 'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json'

PROBE = r'''package buildcraft.lib.client.config;
import java.util.*;
import java.util.function.ToIntFunction;
public class TooltipProbe {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static ToIntFunction<String> measure = s -> s.codePointCount(0,s.length()) * 6;
    static void bounds(List<ConfigurationTooltipLayout.Line> lines,int width) {
        for(var line:lines) {
            check(line.indent()+measure.applyAsInt(line.prefix())+measure.applyAsInt(line.text())<=width);
            check(!line.text().contains("\n"));
        }
    }
    public static void main(String[] args) {
        var layout=new ConfigurationTooltipLayout(60,measure);
        layout.paragraph("Hide the switch.",0,0,0);
        check(layout.lines().stream().map(ConfigurationTooltipLayout.Line::text).toList().equals(List.of("Hide the", "switch.")));
        var paragraphs=new ConfigurationTooltipLayout(120,measure);
        paragraphs.paragraph("First paragraph.\n\nSecond paragraph.",0,0,0);
        check(paragraphs.lines().get(1).gap()==4);
        var labels=new ConfigurationTooltipLayout(156,measure);
        labels.labelled("Legacy Only",1,"Always use the classic layout. The switch is hidden.",2,2);
        check(labels.lines().get(0).prefix().equals("Legacy Only: "));
        check(labels.lines().get(0).prefixColor()==1);
        check(labels.lines().get(0).color()==2);
        check(labels.lines().get(1).indent()==8);
        bounds(labels.lines(),156);
        var narrow=new ConfigurationTooltipLayout(36,measure);
        narrow.labelled("Very Long Mode Name",1,"Hidden\nMore text",2,2);
        narrow.paragraph("abcdefghijklmnopqrstuvwxyz",2,0,0);
        narrow.paragraph("\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00\uD83D\uDE00",2,0,0);
        bounds(narrow.lines(),36);
        check(narrow.lines().stream().noneMatch(l->l.text().endsWith("\uD83D")));
        var header=new ConfigurationTooltipLayout(200,measure);
        header.paragraph("Name",0,0,0);
        var body=new ConfigurationTooltipLayout(200,measure);
        body.paragraph("One\nTwo\nThree\nFour\nFive\nSix\nSeven\nEight",0,0,0);
        var footer=new ConfigurationTooltipLayout(200,measure);
        footer.paragraph("Default: Switch",0,0,6);
        footer.paragraph("Applies after saving",0,0,0);
        var fit=ConfigurationTooltipLayout.fit(header.lines(),body.lines(),footer.lines(),70);
        check(ConfigurationTooltipLayout.height(fit)<=70);
        check(fit.stream().anyMatch(l->l.text().equals("...")));
        check(fit.get(fit.size()-2).text().equals("Default: Switch"));
        check(fit.get(fit.size()-1).text().equals("Applies after saving"));
        var all=ConfigurationTooltipLayout.fit(header.lines(),body.lines(),footer.lines(),1000);
        check(all.size()==header.lines().size()+body.lines().size()+footer.lines().size());
        var fullWindow=ConfigurationTooltipLayout.window(body.lines(),5,1000);
        check(fullWindow.start()==0 && fullWindow.maxStart()==0 && fullWindow.lines().equals(body.lines()));
        var firstWindow=ConfigurationTooltipLayout.window(body.lines(),0,33);
        check(firstWindow.lines().size()==3 && firstWindow.maxStart()==5);
        var lastWindow=ConfigurationTooltipLayout.window(body.lines(),99,33);
        check(lastWindow.start()==5 && lastWindow.lines().get(2).text().equals("Eight"));
        for(int start=0;start<=lastWindow.maxStart();start++) {
            var window=ConfigurationTooltipLayout.window(body.lines(),start,33);
            check(ConfigurationTooltipLayout.height(window.lines())<=33);
            check(window.lines().get(0).equals(body.lines().get(start)));
        }
        System.out.println("Tooltip layout passed");
    }
}
'''

class ConfigurationTooltipTests(unittest.TestCase):
    def test_layout(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp); probe=work/'TooltipProbe.java'; probe.write_text(PROBE,encoding='utf-8')
            subprocess.run(['javac','--release','17','-d',str(work),str(JAVA/'ConfigurationTooltipLayout.java'),str(probe)],check=True,timeout=60)
            result=subprocess.run(['java','-cp',str(work),'buildcraft.lib.client.config.TooltipProbe'],check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Tooltip layout passed',result.stdout)

    def test_choice_descriptions(self):
        def unique(pairs):
            result={}
            for key,value in pairs:
                if key.startswith(('config.buildcraftcore.', 'config.buildcrafttransport.', 'config.buildcraftenergy.', 'gui.buildcraft.configuration.')):
                    self.assertNotIn(key,result)
                result[key]=value
            return result
        lang=json.loads(LANG.read_text(encoding='utf-8'),object_pairs_hook=unique)
        for key,choices in {
            'buildcraftcore.display.zonePlannerMode': ('switch','legacy_only','modern_only'),
            'buildcraftcore.display.electronicLibraryMode': ('switch','legacy_only','modern_only'),
            'buildcraftcore.display.timeGap': ('ticks','seconds'),
            'buildcraftcore.display.fluidVolumeUnit': ('buckets','millibuckets'),
            'buildcraftcore.power.powerMode': ('mj_only','mj_autoconvert_fe','display_fe'),
            'buildcraftcore.debug.loggingProfile': ('default','debug','hard_debugging','verbose'),
            'buildcraftcore.general.chunkLoading': ('on','auto','off'),
            'buildcraftcore.general.chunkLoadLevel': ('none','strict_tiles','self_tiles'),
            'buildcraftcore.display.itemRenderRotation': ('disabled','horizontals_only','enabled'),
            'buildcraftenergy.worldgen.oil.generationProfile': ('standard','high','very_high'),
        }.items():
            prefix='config.'+key
            self.assertNotEqual(lang[prefix+'.description'],lang[prefix+'.tooltip'])
            for choice in choices:
                self.assertTrue(lang[prefix+'.name.choice.'+choice+'.description'])
        self.assertIn('switch is hidden',lang['config.buildcraftcore.display.zonePlannerMode.name.choice.legacy_only.description'])
        self.assertIn('20 game ticks',lang['config.buildcraftcore.display.timeGap.name.choice.seconds.description'])

    def test_materialized_tooltips(self):
        from source_lookup import read_materialized_java
        for target in ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge','26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric'):
            with self.subTest(target=target):
                screen=read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java')
                helper=read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/ConfigurationTooltipLayout.java')
                self.assertIn('ConfigurationTooltipLayout.fit(header.lines(),body.lines(),footer.lines(),height-22)',screen)
                self.assertIn('font::width',screen)
                self.assertIn('choiceKey(d,choice)+".description"',screen)
                self.assertIn('line.prefixColor()',screen)
                self.assertIn('static int lineEnd(',helper)
                self.assertIn('lastBreak',helper)

if __name__=='__main__': unittest.main()
