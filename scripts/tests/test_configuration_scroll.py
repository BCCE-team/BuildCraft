"""Exercise row scrolling, bounds and scrollbar geometry without Minecraft."""
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "source-shared/src/main/java/buildcraft/lib/client/config/ConfigurationScroll.java"
PROBE = r'''package buildcraft.lib.client.config;
public class ScrollProbe {
    static void eq(int actual,int expected) {
        if(actual!=expected) throw new AssertionError(actual+" != "+expected);
    }
    public static void main(String[] args) {
        eq(ConfigurationScroll.maxOffset(5,8),0);
        eq(ConfigurationScroll.clamp(100,0,5),0);
        eq(ConfigurationScroll.clamp(-1,20,5),0);
        eq(ConfigurationScroll.clamp(20,20,5),15);
        eq(ConfigurationScroll.wheel(0,-1,20,5),1);
        eq(ConfigurationScroll.wheel(7,2,20,5),5);
        eq(ConfigurationScroll.wheel(7,-0.3,20,5),8);
        eq(ConfigurationScroll.wheel(15,-1,20,5),15);
        eq(ConfigurationScroll.wheel(0,1,20,5),0);
        eq(ConfigurationScroll.wheel(4,Double.NaN,20,5),4);
        eq(ConfigurationScroll.clamp(14,3,5),0);
        eq(ConfigurationScroll.clamp(14,20,12),8);
        eq(ConfigurationScroll.thumb(10,100,0,20,5).top(),10);
        eq(ConfigurationScroll.thumb(10,100,0,20,5).height(),25);
        eq(ConfigurationScroll.thumb(10,100,15,20,5).top(),85);
        eq(ConfigurationScroll.drag(-200,10,100,20,5),0);
        eq(ConfigurationScroll.drag(200,10,100,20,5),15);
        eq(ConfigurationScroll.drag(35,10,100,20,5),5);
        eq(ConfigurationScroll.thumb(10,100,0,10000,5).height(),12);
        eq(ConfigurationScroll.thumb(10,100,0,3,5).height(),100);
        eq(ConfigurationScroll.drag(100,10,100,3,5),0);
        eq(ConfigurationScroll.thumb(10,0,0,20,5).height(),0);
        eq(ConfigurationScroll.drag(100,10,0,20,5),0);
        for(int size=0;size<=100;size++) for(int visible=1;visible<=20;visible++) {
            int max=ConfigurationScroll.maxOffset(size,visible);
            int previous=-1;
            for(int offset=0;offset<=max;offset++) {
                var thumb=ConfigurationScroll.thumb(12,140,offset,size,visible);
                if(thumb.top()<12 || thumb.top()+thumb.height()>152 || thumb.top()<previous) throw new AssertionError();
                previous=thumb.top();
                int roundTrip=ConfigurationScroll.drag(thumb.top(),12,140,size,visible);
                if(Math.abs(roundTrip-offset)>1) throw new AssertionError();
            }
        }
        System.out.println("Configuration scroll tests passed");
    }
}
'''

class ConfigurationScrollTest(unittest.TestCase):
    def test_scroll(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            probe = work / "ScrollProbe.java"
            probe.write_text(PROBE, encoding="utf-8")
            subprocess.run(["javac", "--release", "17", "-d", str(work), str(MODEL), str(probe)], check=True, timeout=60)
            result = subprocess.run(["java", "-cp", str(work), "buildcraft.lib.client.config.ScrollProbe"], capture_output=True, text=True, check=True, timeout=30)
            self.assertIn("Configuration scroll tests passed", result.stdout)

if __name__ == "__main__":
    unittest.main()
