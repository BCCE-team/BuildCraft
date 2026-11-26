"""Regression tests for draft-based numerical explanations (no Minecraft client needed)."""
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'

PROBE = r'''package buildcraft.lib.client.config;
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import java.util.*;
public class CalculatedProbe {
    private static final String RATIO="buildcraftcore:power/mjPerFe";
    private static final String PIPE="buildcrafttransport:general/pipes/";
    private static final String PUMP="buildcraft:balance/machine/buildcraftfactory/pump/";
    private static final String QUARRY="buildcraft:balance/machine/buildcraftbuilders/quarry/";
    private static final Map<String, String> drafts=new HashMap<>();
    private static final DisplayUnits DEFAULT=DisplayUnits.DEFAULT;
    private static ConfigDefinition def(String id,Kind kind,String value,double min,double max) {
        return new ConfigDefinition(id,Section.BALANCE,List.of("Test"),Scope.SERVER,kind,value,min,max,List.of(),"n","d","t","N","D","T",ApplyPolicy.WORLD_RESTART);
    }
    private static ConfigDefinition decimal(String id) {return def(id,Kind.DECIMAL,"1",0,1000000);}
    private static void eq(Object actual,Object expected) {
        if(!Objects.equals(actual,expected))throw new AssertionError("expected "+expected+"; got "+actual);
    }
    private static void check(ConfigDefinition d,DisplayUnits units,String key,String... args) {
        var lines=ConfigurationCalculatedValues.preview(d,drafts::get,units);
        var line=new ConfigurationCalculatedValues.Line(key,List.of(args));
        if(!lines.contains(line))throw new AssertionError("Expected "+line+" in "+lines);
    }
    public static void main(String[] args) {
        var ratio=def(RATIO,Kind.DECIMAL,"0.1",0.0001,0.2);
        drafts.put(RATIO,"0.1");check(ratio,DEFAULT,"mj_to_fe","10");
        drafts.put(RATIO,"0.2");check(ratio,DEFAULT,"mj_to_fe","5");check(ratio,DEFAULT,"fe_to_mj","0.2");
        drafts.put(RATIO,"0.0001");check(ratio,DEFAULT,"mj_to_fe","10000");
        drafts.put(RATIO,"0.075");check(ratio,DEFAULT,"mj_to_fe_approx","13.333333");
        drafts.put(RATIO,"0.19999");check(ratio,DEFAULT,"mj_to_fe","5");check(ratio,DEFAULT,"ratio_rounded","0.2");
        drafts.remove(RATIO);check(ratio,DEFAULT,"invalid");
        drafts.put(RATIO,"0.2");drafts.put(PIPE+"baseFeRate","40");
        var perSecond=new DisplayUnits(DisplayUnits.RateUnit.SECONDS,DisplayUnits.FluidUnit.BUCKETS);
        check(decimal(PIPE+"baseFeRate"),DEFAULT,"base_fe_equivalent","8 MJ/t");
        check(decimal(PIPE+"baseFeRate"),perSecond,"base_fe_equivalent","160 MJ/s");
        drafts.put(PIPE+"mjPerItem","1000000");check(decimal(PIPE+"mjPerItem"),DEFAULT,"items_cost","64");
        drafts.put(PIPE+"mjPerMillibucket","1000");check(decimal(PIPE+"mjPerMillibucket"),DEFAULT,"bucket_cost","1");
        drafts.put(PIPE+"baseFluidRate","10");check(decimal(PIPE+"baseFluidRate"),DEFAULT,"base_fluid_energy","0.01 MJ/t");
        drafts.put(PIPE+"mjPerMillibucket","2000");check(decimal(PIPE+"baseFluidRate"),perSecond,"base_fluid_energy","0.4 MJ/s");
        drafts.remove(PIPE+"mjPerMillibucket");check(decimal(PIPE+"baseFluidRate"),DEFAULT,"invalid_dependency");
        drafts.put(PUMP+"energy_per_bucket_mj","10");drafts.put(PUMP+"energy_cost_multiplier","1.5");
        check(decimal(PUMP+"energy_cost_multiplier"),DEFAULT,"multiplier","150%","+50%");
        check(decimal(PUMP+"energy_per_bucket_mj"),DEFAULT,"pump_cycle","15");
        drafts.put(PUMP+"energy_cost_multiplier","0.5");check(decimal(PUMP+"energy_per_bucket_mj"),DEFAULT,"pump_cycle","5");
        drafts.put(QUARRY+"max_tasks_per_tick","4");drafts.put(QUARRY+"work_speed_multiplier","2");
        check(decimal(QUARRY+"max_tasks_per_tick"),DEFAULT,"quarry_attempts","8 tasks/t");
        check(decimal(QUARRY+"work_speed_multiplier"),perSecond,"quarry_attempts","160 tasks/s");
        drafts.put(QUARRY+"max_frame_speed","10");check(decimal(QUARRY+"max_frame_speed"),DEFAULT,"quarry_cap","1 blocks/t");
        drafts.put(QUARRY+"max_frame_speed","0");check(decimal(QUARRY+"max_frame_speed"),DEFAULT,"no_cap");
        drafts.put(QUARRY+"work_speed_multiplier","0.01");check(decimal(QUARRY+"max_tasks_per_tick"),DEFAULT,"quarry_attempts","1 tasks/t");
        drafts.put(QUARRY+"power_divisor","2");check(decimal(QUARRY+"power_divisor"),DEFAULT,"second_task","50%");
        drafts.put(QUARRY+"power_divisor","0");check(decimal(QUARRY+"power_divisor"),DEFAULT,"no_penalty");
        drafts.put("buildcraftcore:general/itemLifespan","60");check(decimal("buildcraftcore:general/itemLifespan"),DEFAULT,"lifespan","60","1200");
        drafts.put("buildcraftcore:general/updateFactor","10");check(decimal("buildcraftcore:general/updateFactor"),DEFAULT,"interval","10","0.5");
        var robot=decimal("buildcraft:balance/robot/picker/movement_speed_multiplier");drafts.put(robot.id(),"0.5");check(robot,DEFAULT,"multiplier","50%","-50%");
        eq(ConfigurationCalculatedValues.preview(decimal("addon:energy_cost_multiplier"),drafts::get,DEFAULT),List.of());
        eq(ConfigurationCalculatedValues.preview(decimal("buildcraft:balance/machine/buildcraftfactory/pump/tank_capacity_mb"),drafts::get,DEFAULT),List.of());
        System.out.println("Calculated values passed");
    }
}
'''

class ConfigurationCalculatedValuesTests(unittest.TestCase):
    def test_calculated_drafts(self):
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            probe = work/'CalculatedProbe.java'
            probe.write_text(PROBE, encoding='utf-8')
            sources = [JAVA/p for p in ('api/v2/config/ConfigDefinition.java', 'api/v2/config/DisplayUnits.java', 'api/v2/energy/MjAmount.java', 'lib/internal/mj/MjFeConversion.java', 'lib/client/config/ConfigurationCalculatedValues.java')]
            subprocess.run(['javac','--release','17','-d',str(work),*map(str,sources),str(probe)],check=True,timeout=60)
            result = subprocess.run(['java','-cp',str(work),'buildcraft.lib.client.config.CalculatedProbe'],check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Calculated values passed',result.stdout)

    def test_localization(self):
        import re
        helper = (JAVA/'lib/client/config/ConfigurationCalculatedValues.java').read_text(encoding='utf-8')
        lang = json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text(encoding='utf-8'))
        for key in re.findall(r'line\("([a-z_]+)"',helper)+['heading']:
            self.assertTrue(lang['gui.buildcraft.configuration.calculated.'+key])

    def test_all_materialized_targets(self):
        from source_lookup import read_materialized_java
        for target in ('1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge','26.1.2-neoforge','26.2-neoforge','26.3-neoforge','26.3-fabric'):
            with self.subTest(target=target):
                screen = read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/BuildCraftConfigurationScreen.java')
                calc = read_materialized_java(target,'src/main/java/buildcraft/lib/client/config/ConfigurationCalculatedValues.java')
                self.assertIn('ConfigurationCalculatedValues.preview(d,this::previewValue,registry.displayUnits())',screen)
                self.assertIn('numberDrafts.containsKey(id)',screen)
                self.assertIn('definition.validate(edited.get(id))',screen)
                self.assertIn('String summary=calculated.isEmpty()?description(d):calculated.get(0)',screen)
                self.assertIn('MjFeConversion.createParsed(value.doubleValue())',calc)

if __name__ == '__main__': unittest.main()
