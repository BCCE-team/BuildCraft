"""Regression coverage for restart classification and in-place confirmation input/rendering."""
import json
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
PROBE = r'''
package buildcraft.lib.client.config;
import buildcraft.api.v2.config.ConfigDefinition;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.lib.client.config.ConfigurationRestartNotice.Requirement;
import java.util.*;
public class ConfirmationProbe {
    static ConfigDefinition def(String id, Scope scope, ApplyPolicy policy) {
        return new ConfigDefinition(id,scope==Scope.CLIENT?Section.CLIENT:Section.WORLD,
            List.of("Test"),scope,Kind.INTEGER,"1",1,10,List.of(),"n","d","t","N","D","T",policy);
    }
    static void eq(Object actual,Object expected) {
        if(!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        var live=def("buildcraftcore:live",Scope.CLIENT,ApplyPolicy.LIVE);
        var client=def("buildcraftcore:client",Scope.CLIENT,ApplyPolicy.RESTART);
        var world=def("buildcraftcore:world",Scope.SERVER,ApplyPolicy.WORLD_RESTART);
        var addon=def("testaddon:world",Scope.SERVER,ApplyPolicy.WORLD_RESTART);
        var server=def("testaddon:server",Scope.SERVER,ApplyPolicy.RESTART);
        var clientWorld=def("testaddon:client",Scope.CLIENT,ApplyPolicy.WORLD_RESTART);
        var defs=List.of(live,client,world,addon,server,clientWorld);
        var active=new HashMap<String,String>();
        for(var d:defs)active.put(d.id(),"1");
        var c=Map.of(client.id(),"2",live.id(),"2");
        var s=Map.of(world.id(),"2",addon.id(),"2",server.id(),"2");
        eq(ConfigurationRestartNotice.requirements(defs,c,s,active,true,true),Map.of(Requirement.GAME,2,Requirement.WORLD,2));
        eq(ConfigurationRestartNotice.requirements(defs,c,s,active,true,false),Map.of(Requirement.GAME,1,Requirement.SERVER,3));
        eq(ConfigurationRestartNotice.requirements(defs,c,s,active,false,true),Map.of(Requirement.GAME,2,Requirement.NEXT_WORLD,2));
        eq(ConfigurationRestartNotice.requirements(defs,Map.of(),Map.of(),active,true,true),Map.of());
        eq(ConfigurationRestartNotice.requirements(defs,Map.of(live.id(),"2"),Map.of(),active,true,true),Map.of());
        eq(ConfigurationRestartNotice.requirements(defs,Map.of(client.id(),"1"),Map.of(world.id(),"1"),active,true,true),Map.of());
        eq(ConfigurationRestartNotice.requirements(defs,Map.of(clientWorld.id(),"2"),Map.of(),active,true,true),Map.of(Requirement.WORLD,1));
        eq(ConfigurationRestartNotice.requirements(defs,Map.of(),Map.of(addon.id(),"2"),active,false,true),Map.of(Requirement.NEXT_WORLD,1));
        System.out.println("Restart classification passed");
    }
}
'''

class ConfirmationTests(unittest.TestCase):
    def test_restart_classification(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp); probe=work/'ConfirmationProbe.java'; probe.write_text(PROBE,encoding='utf-8')
            subprocess.run(['javac','--release','17','-d',str(work),str(JAVA/'api/v2/config/ConfigDefinition.java'),
                            str(JAVA/'lib/client/config/ConfigurationRestartNotice.java'),str(probe)],check=True,timeout=60)
            out=subprocess.run(['java','-cp',str(work),'buildcraft.lib.client.config.ConfirmationProbe'],check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Restart classification passed',out.stdout)

    def test_modal_save_input_and_render_for_every_target(self):
        for target in TARGETS:
            with self.subTest(target=target):
                screen=read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java')
                self.assertNotIn('ConfirmScreen',screen)
                save=screen.split('private void save(boolean confirmed)',1)[1].split('private void saveClient()',1)[0]
                warning=save.index('if(!confirmed && showRestartWarning(client,server)) return;')
                for preflight in ('changes(Scope.CLIENT)','changes(Scope.SERVER)','if(!allowed)','bytes>ConfigurationWire.MAX_REQUEST_BYTES'):
                    self.assertLess(save.index(preflight),warning)
                for write in ('registry.commitLocalDefaults(', 'request(true,server)', 'saveClient();'):
                    self.assertLess(warning,save.index(write))
                self.assertIn('()->save(true)',screen)
                self.assertIn('if(accept) current.action().run();',screen)
                self.assertIn('if(confirmation!=null) return clickConfirmation(mx,my,button);',screen)
                for name in ('dragList','releaseList'):
                    method=screen.split('private boolean '+name+'(',1)[1].split('\n    }',1)[0]
                    self.assertIn('if(confirmation!=null) return true;',method)
                self.assertIn('if(confirmation!=null) {\n            var content=dialogBounds().content();',screen)
                self.assertIn('ConfigurationTooltipLayout.window(lines,dialogOffset,contentHeight)',screen)
                if target.startswith(('1.21.11-', '26.')):
                    self.assertIn('confirmationKey(event.key()) || super.keyPressed(event)',screen)
                    self.assertIn('confirmation!=null || super.charTyped(event)',screen)
                    self.assertIn('drawConfirmation(g::fill,t,g::nextStratum,mx,my)',screen)
                else:
                    self.assertIn('confirmationKey(keyCode) || super.keyPressed(keyCode,scanCode,modifiers)',screen)
                    self.assertIn('confirmation!=null || super.charTyped(codePoint,modifiers)',screen)
                    self.assertIn('translate(0,0,400)',screen)
                self.assertIn('else if(key==9)' if target.startswith('26.3-') else 'else if(key==258)',screen)
                self.assertIn('if(confirmation!=null) { closeConfirmation(false); return; }',screen)
                draw=screen.split('private void drawConfirmation(',1)[1].split('private void tooltip(',1)[0]
                self.assertEqual(draw.count('nextLayer.run();'),3)
                self.assertLess(draw.index('0xFF161616'),draw.rindex('nextLayer.run();'))
                self.assertLess(draw.rindex('nextLayer.run();'),draw.index('t.draw('))
                self.assertIn('else tooltip(',screen)
                # Immediate reset is executed only by the accepted callback, not when opening the dialog.
                update=screen.split('button(tr("update_reset_ignored")',1)[1].split('int bw=',1)[0]
                self.assertLess(update.index('()->{'),update.index('clearIgnoredVersionsChecked()'))
                self.assertIn('tr("dialog.immediate")',update)

    def test_localization(self):
        lang=json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
        for key in ('reset','save','immediate','restart.title','next_world.title','restart.intro',
                    'restart.game','restart.world','restart.server','restart.next_world','restart.footer','personal_hint','editor_hint'):
            self.assertTrue(lang['gui.buildcraft.configuration.dialog.'+key])

if __name__=='__main__': unittest.main()
