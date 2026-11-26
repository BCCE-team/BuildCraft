"""Exercise live presentation preferences and every effective renderer/config source."""
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
import buildcraft.api.v2.config.*;
import buildcraft.api.v2.config.ConfigDefinition.*;
import buildcraft.api.v2.config.DisplayFormat.*;
import buildcraft.lib.internal.config.*;
import buildcraft.lib.platform.config.*;
import java.math.BigDecimal;
import java.util.*;
public class PresentationProbe {
    static void eq(Object actual,Object expected) { if (!Objects.equals(actual,expected)) throw new AssertionError(actual+" != "+expected); }
    static void ok(boolean b) { if(!b)throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var plain=new DisplayFormat(NumberStyle.PLAIN,TemperatureUnit.KELVIN);
        var grouped=new DisplayFormat(NumberStyle.GROUPED,TemperatureUnit.CELSIUS);
        var compact=new DisplayFormat(NumberStyle.COMPACT,TemperatureUnit.FAHRENHEIT);
        eq(plain.formatNumber(new BigDecimal("1000000")),"1000000");
        eq(grouped.formatNumber(new BigDecimal("-1000000.125")),"-1 000 000.125");
        eq(compact.formatNumber(new BigDecimal("1000000")),"1M");
        eq(compact.formatNumber(new BigDecimal("999999")),"1M");
        eq(compact.formatNumber(new BigDecimal("0.000001")),"0.000001");
        eq(plain.formatTemperature(0),"273.15 K");
        eq(grouped.formatTemperature(-40),"-40 \u00b0C");
        eq(compact.formatTemperature(100),"212 \u00b0F");
        eq(compact.formatTemperature(-40),"-40 \u00b0F");
        eq(plain.formatTemperature(Double.NaN),"--");
        eq(grouped.formatFluid(DisplayUnits.DEFAULT,16000),"16 000 mB");
        eq(compact.formatRate(new DisplayUnits(DisplayUnits.RateUnit.SECONDS,DisplayUnits.FluidUnit.BUCKETS),new BigDecimal("2000"),"FE"),"40K FE/s");
        var builder=new BCConfigSpec.Builder();builder.push("display");
        builder.defineEnum("numberFormat",NumberStyle.GROUPED);
        builder.defineEnum("temperatureUnit",TemperatureUnit.CELSIUS);
        builder.define("rememberGuidePage",true);
        builder.push("pipes"); builder.define("fluidAnimation",true);
        builder.defineEnum("itemDisplay",ClientPresentation.PipeItems.ALL);
        builder.defineInRange("contentRenderDistance",64,16,128);
        builder.defineInRange("maxRenderedItems",64,1,512); builder.pop();
        builder.push("updates");builder.define("automaticCheck",true);builder.define("notifications",true);builder.pop();builder.pop();
        ConfigBinding.legacyClientValue("buildcraftcore:display/updates/automaticCheck","false");
        ConfigBinding.bind("buildcraftcore",builder.build(),m->{});
        var r=ConfigurationRegistry.INSTANCE;
        String base="buildcraftcore:display/";
        eq(r.value(base+"updates/automaticCheck").orElseThrow(),"false");
        for(var d:r.definitions()) { eq(d.scope(),Scope.CLIENT);eq(d.applyPolicy(),ApplyPolicy.LIVE); }
        eq(ClientPresentation.pipeItemLimit(),64);eq(ClientPresentation.pipeDistance(),64);
        ok(ClientPresentation.animatePipeFluids() && ClientPresentation.rememberGuidePage());
        r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of(base+"numberFormat","COMPACT",base+"temperatureUnit","KELVIN",
            base+"pipes/itemDisplay","SIMPLIFIED",base+"pipes/contentRenderDistance","128",base+"pipes/fluidAnimation","false",base+"rememberGuidePage","false"));
        eq(ClientPresentation.pipeItemLimit(),1);eq(ClientPresentation.pipeDistance(),128);
        ok(!ClientPresentation.animatePipeFluids() && !ClientPresentation.rememberGuidePage());
        eq(ClientPresentation.number(1000000),"1M");eq(ClientPresentation.temperature(0),"273.15 K");
        r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of(base+"pipes/itemDisplay","DISABLED"));eq(ClientPresentation.pipeItemLimit(),0);
        r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of(base+"pipes/itemDisplay","ALL",base+"pipes/maxRenderedItems","12"));eq(ClientPresentation.pipeItemLimit(),12);
        try { r.commit(Scope.CLIENT,r.snapshot(Scope.CLIENT).revision(),Map.of(base+"pipes/contentRenderDistance","129"));throw new AssertionError("Accepted invalid distance"); } catch(IllegalArgumentException expected){}
        eq(ClientPresentation.pipeDistance(),128);
        System.out.println("Presentation preferences passed");
    }
}
'''

class PresentationPreferencesTest(unittest.TestCase):
    def test_live_preferences(self):
        with tempfile.TemporaryDirectory() as temp:
            work=Path(temp); probe=work/'PresentationProbe.java'; probe.write_text(PROBE,encoding='utf-8')
            sources=list((JAVA/'api/v2/config').glob('*.java'))+[JAVA/p for p in (
                'lib/internal/config/ClientPresentation.java','lib/internal/config/ConfigurationRegistry.java',
                'lib/platform/config/ConfigBinding.java','lib/platform/config/BCConfigSpec.java','lib/platform/config/LegacyToml.java')]
            subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(work),*map(str,sources),str(probe)],check=True,timeout=60)
            out=subprocess.run(['java','-cp',str(work),'PresentationProbe'],cwd=work,check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('Presentation preferences passed',out.stdout)

    def test_effective_consumers(self):
        for target in TARGETS:
            with self.subTest(target=target):
                def read(name): return read_materialized_java(target,'src/main/java/buildcraft/'+name+'.java')
                items=read('transport/client/render/PipeFlowRendererItems')
                self.assertIn('ClientPresentation.pipeItemLimit()',items)
                self.assertIn('if (rendered++ >= limit) break;',items)
                fluid=read('transport/client/render/PipeFlowRendererFluids')
                self.assertIn('ClientPresentation.animatePipeFluids()',fluid)
                self.assertIn('new Vec3[7]',fluid)
                holder=read('transport/client/render/RenderPipeHolder')
                self.assertIn('PipeContentRendering.visible(',holder)
                self.assertIn('PipeContentRendering.viewDistance()',holder)
                guide=read('lib/client/guide/GuiGuide')
                self.assertGreaterEqual(guide.count('ClientPresentation.rememberGuidePage()'),2)
                self.assertIn('rememberPage ? (documentView ? documentSpread : contentsSpread) : 0',guide)
                self.assertIn('ClientPresentation.temperature(heat)',read('lib/misc/LocaleUtil'))
                self.assertIn('ClientPresentation.temperature(',read('compat/jade/BuildCraftJadePlugin'))
                engine=read('lib/engine/TileEngineBase_BC8')
                self.assertIn('BCEnergyConfig.engineExplosionBlockDamage',engine)
                self.assertIn('BlockInteraction.NONE' if target == '1.19.2-forge' else 'ExplosionInteraction.NONE',engine)
                generator=read('energy/generation/features/OilGenerator')
                self.assertIn('BCEnergyConfig.oilGenerationMultiplier',generator)
                self.assertIn('if (bonus <= 0) return ImmutableList.of();',generator)
                screen=read('lib/client/config/BuildCraftConfigurationScreen')
                self.assertIn('"CUSTOM".equals(edited.get(OIL_PROFILE))',screen)
                self.assertIn('Math.round(fraction*100)/100.0',screen)
                self.assertIn('UpdateNotificationManager.checkNow()',screen)
                self.assertIn('UpdatePreferences.clearIgnoredVersionsChecked()',screen)
                manager=read('lib/update/client/UpdateNotificationManager')
                self.assertIn('!UpdatePreferences.notificationsEnabled()',manager)
                self.assertIn('!previouslyEnabled',manager)

if __name__=='__main__': unittest.main()
