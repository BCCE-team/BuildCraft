"""Unsaved edit counts across the editor's hierarchy and all source families."""
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
import java.util.*;
public class DirtyPathsProbe {
    static ConfigDefinition def(String id, Section section, Kind kind, String value, String... path) {
        return new ConfigDefinition(id,section,List.of(path),section==Section.CLIENT?Scope.CLIENT:Scope.SERVER,
            kind,value,0,100,List.of(),"n","d","t","N","D","T",ApplyPolicy.RESTART);
    }
    static void eq(Object actual,Object expected) {
        if(!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        var rate=def("buildcraftcore:rate",Section.CLIENT,Kind.DECIMAL,"1.0","Interface","Units");
        var fluid=def("buildcraftcore:fluid",Section.CLIENT,Kind.INTEGER,"1","Interface","Units");
        var general=def("buildcraftcore:general",Section.CLIENT,Kind.BOOLEAN,"true","Interface");
        var guide=def("buildcraftcore:guide",Section.CLIENT,Kind.BOOLEAN,"true","Interface","Guide");
        var similar=def("buildcraftcore:similar",Section.CLIENT,Kind.INTEGER,"1","Interface/Units");
        var oil=def("buildcraftenergy:oil",Section.WORLD,Kind.DECIMAL,"0.3","World Generation","Oil");
        var balance=def("buildcraftenergy:balance",Section.BALANCE,Kind.INTEGER,"1","Interface","Units");
        var addon=def("example:rate",Section.CLIENT,Kind.DECIMAL,"1.0","example","Interface","Units");
        var defs=new ArrayList<>(List.of(rate,fluid,general,guide,similar,oil,balance,addon));
        Map<String,String> saved=new HashMap<>(), edited=new HashMap<>(), drafts=new HashMap<>();
        for(var d:defs) saved.put(d.id(),d.defaultValue());
        saved.put(fluid.id(),"2"); // saved but not factory-default
        edited.putAll(saved);
        var state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.view(false),0); eq(state.view(true),0); eq(state.contains(fluid.id()),false);
        edited.put(rate.id(),"1.00"); // canonical no-op
        edited.put(fluid.id(),"1"); // reset to default is an unsaved edit
        edited.put(general.id(),"false");
        edited.put(guide.id(),"false");
        edited.put(oil.id(),"0.5"); // counted even if custom slider is currently hidden
        edited.put(balance.id(),"3");
        edited.put(addon.id(),"2.0");
        state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.contains(rate.id()),false); eq(state.contains(fluid.id()),true);
        eq(state.section(false,Section.CLIENT),3); eq(state.section(false,Section.WORLD),1);
        eq(state.section(false,Section.BALANCE),1); eq(state.view(false),5); eq(state.view(true),1);
        eq(state.category(false,Section.CLIENT,List.of("Interface"),true),3);
        eq(state.category(false,Section.CLIENT,List.of("Interface"),false),1); // General child
        eq(state.category(false,Section.CLIENT,List.of("Interface","Units"),false),1);
        eq(state.category(false,Section.CLIENT,List.of("Interface/Units"),true),0);
        eq(state.category(false,Section.CLIENT,List.of("example"),true),0);
        eq(state.category(true,Section.CLIENT,List.of("example"),true),1);
        drafts.put(rate.id(),"-"); // invalid draft still counts once
        edited.put(guide.id(),saved.get(guide.id()));
        edited.put(fluid.id(),"002"); // canonical revert
        state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.contains(rate.id()),true); eq(state.contains(fluid.id()),false); eq(state.contains(guide.id()),false);
        eq(state.section(false,Section.CLIENT),2);
        drafts.clear(); edited.put(general.id(),"true");
        state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.category(false,Section.CLIENT,List.of("Interface"),true),0);
        edited.put(rate.id(),"invalid");
        state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.contains(rate.id()),true);
        edited.put(rate.id(),"1.0");
        saved.put(oil.id(),edited.get(oil.id())); // acknowledged server write / new baseline
        defs.remove(balance); // server denied / schema no longer accessible
        state=new ConfigurationDirtyPaths(defs,saved,edited,drafts);
        eq(state.view(false),0); eq(state.view(true),1);
        eq(state.section(false,Section.BALANCE),0);
        System.out.println("Dirty path counts passed");
    }
}
'''

class ConfigurationDirtyPathsTests(unittest.TestCase):
    def test_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp); probe=work/'DirtyPathsProbe.java'; probe.write_text(PROBE,encoding='utf-8')
            subprocess.run(['javac','--release','17','-d',str(work),str(JAVA/'api/v2/config/ConfigDefinition.java'),
                            str(JAVA/'lib/client/config/ConfigurationDirtyPaths.java'),str(probe)],check=True,timeout=60)
            result=subprocess.run(['java','-cp',str(work),'buildcraft.lib.client.config.DirtyPathsProbe'],
                                  check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Dirty path counts passed',result.stdout)

    def test_editor_integration_every_target(self):
        for target in TARGETS:
            with self.subTest(target=target):
                screen=read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java')
                self.assertIn('new ConfigurationDirtyPaths(definitions.values(),original,edited,numberDrafts)',screen)
                self.assertIn('dirty.view(!addonsView):dirty.section(addonsView,s)',screen)
                self.assertIn('dirty.category(addonsView,section,pathParts,node.branch())',screen)
                self.assertIn('dirty.contains(d.id())',screen)
                self.assertIn('treeRight()-3-suffixWidth',screen)
                self.assertIn('countedLabel(tab(s).getString(),count,w-8)',screen)
                # The three input responders refresh counts without rebuilding the edit box.
                self.assertIn('reset.active=nonDefault(d);\n                    invalidateDirtyPaths();',screen)
                self.assertEqual(screen.count('reset.active=nonDefault(d);\n                    invalidateDirtyPaths();'),2)
                self.assertIn('invalidateDirtyPaths();if(OIL_PROFILE.equals(d.id()))changed();',screen)
                self.assertIn('private void changed() { rebuild=true; invalidateDirtyPaths(); }',screen)
                self.assertIn('edited.put(d.id(),d.defaultValue()); invalidateDirtyPaths();',screen)
                self.assertIn('numberDrafts.remove(OIL_CUSTOM); changed();',screen)
                self.assertIn('numberDrafts.remove(d.id());\n        invalidateDirtyPaths();',screen)

if __name__ == '__main__':
    unittest.main()
