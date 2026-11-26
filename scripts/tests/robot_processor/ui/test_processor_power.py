"""Power budget, persistence/wiring, and cross-family gauge contracts."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'


class ProcessorPowerTests(unittest.TestCase):
    def test_exact_long_power_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            check = Path(tmp) / 'CheckProcessorPower.java'
            check.write_text('''
import buildcraft.robotics.scheduler.RobotProcessorPower;
class CheckProcessorPower {
  static void eq(long expected,long actual) {
    if(expected!=actual) throw new AssertionError(expected+" != "+actual);
  }
  public static void main(String[] args) {
    eq(5_000_000_000L, RobotProcessorPower.CAPACITY);
    eq(16, RobotProcessorPower.MAX_ROBOTS);
    eq(0,RobotProcessorPower.requiredPerTick(0));
    eq(0,RobotProcessorPower.requiredPerTick(-1));
    eq(10_000_000L,RobotProcessorPower.requiredPerTick(1));
    eq(24_000_000L,RobotProcessorPower.requiredPerTick(8));
    eq(40_000_000L,RobotProcessorPower.requiredPerTick(16));
    eq(40_000_000L,RobotProcessorPower.requiredPerTick(64));
  }
}
''')
            subprocess.run(['javac','--release','17','-d',tmp,
                str(JAVA/'api/v2/energy/MjAmount.java'),
                str(JAVA/'robotics/scheduler/RobotProcessorPower.java'),str(check)],check=True)
            subprocess.run(['java','-cp',tmp,'CheckProcessorPower'],check=True)

    def test_server_energy_gate_persistence_and_network(self):
        tile = (JAVA/'robotics/tile/TileRobotProcessor.java').read_text()
        menu = (JAVA/'robotics/container/ContainerRobotProcessor.java').read_text()
        for fragment in ('new MjBattery(ENERGY_CAPACITY)',
                         'new MjRedstoneBatteryReceiver(processorBattery)',
                         'processorBattery.extractPower(requestedPower)',
                         'workScheduler.onPowerPaused()',
                         'RobotProcessorPower.requiredPerTick(loadedLinkedRobotCount())',
                         'linkedRobotIds.size() < MAX_LINKED_ROBOTS',
                         'linkedRobotIds.size() >= MAX_LINKED_ROBOTS',
                         'data.writeLong("processorEnergy", getStoredEnergy())',
                         'tag.putLong("processorEnergy", getStoredEnergy())',
                         'restoreEnergy(data.readLong("processorEnergy"))',
                         'restoreEnergy(tag.getLong("processorEnergy"))'):
            self.assertIn(fragment,tile)
        self.assertIn('out.writeLong(power.stored())',menu)
        self.assertIn('stored > TileRobotProcessor.ENERGY_CAPACITY',menu)
        self.assertIn('0, STATION_LIMIT, "station snapshots"',menu)
        self.assertIn('0, ROBOT_LIMIT, "robot snapshots"',menu)
        self.assertIn('SCAN_WAITING, SCAN_NO_POWER',menu)

    def test_all_modern_platforms_expose_mj(self):
        files = [p for p in ROOT.rglob('BCRobotics.java') if 'neoforge' in str(p) or 'fabric' in str(p)]
        self.assertEqual(len(files),5)
        for path in files:
            with self.subTest(path=path):
                text=path.read_text()
                for cap in ('CAP_CONNECTOR','CAP_RECEIVER','CAP_REDSTONE_RECEIVER','CAP_READABLE'):
                    self.assertIn('MjCapabilities.'+cap+', processor)',text)
                self.assertNotIn('event, buildcraft.lib.misc.CapUtil.CAP_FE, processor',text)

    def test_map_title_and_power_on_all_gui_variants(self):
        files=list(ROOT.rglob('GuiRobotProcessor.java'))
        self.assertEqual(len(files),5)
        for path in files:
            with self.subTest(path=path):
                text=path.read_text()
                self.assertIn('157 - font.width(machineTitle) / 2',text)
                self.assertIn('6, 202, 0xFF_40_40_40',text)
                self.assertIn('renderScanProgress(pose);',text)
                self.assertIn('ContainerRobotProcessor.SCAN_NO_POWER',text)
                self.assertIn('RobotProcessorPowerGauge.draw(state.stored(), processorMenu.getEnergyCapacity()',text)
                self.assertIn('RobotProcessorPowerGauge.contains(x, y)',text)
                self.assertIn('RobotProcessorUiText.energyTooltip(processorMenu)',text)
                self.assertIn('RobotProcessorUiText.energyConsumption(state)',text)
                self.assertNotIn('ENERGY_FILL',text)
                self.assertNotIn('ENERGY_BAR_X',text)
                self.assertNotIn('RobotProcessorPowerOverlay',text)
                self.assertIn('renderCargo(pose);\n        renderEnergyGauge(pose);',text)
                self.assertIn('RobotProcessorPowerGauge.ATLAS_SIZE, RobotProcessorPowerGauge.ATLAS_SIZE',text)
                if 'source-families' in path.parts:
                    self.assertIn('BCGraphics.nextLayer(getActiveGraphics());',text)
                    self.assertIn('RenderCompat.blit(getActiveGraphics(), BACKGROUND,',text)
                elif '1.19.2-forge' in str(path):
                    self.assertIn('RenderSystem.setShaderTexture(0, BACKGROUND);',text)
                    self.assertIn('blit(pose, leftPos + x, topPos + y, u, v, w, h,',text)
                else:
                    self.assertIn('getActiveGraphics().blit(BACKGROUND,',text)

    def test_atlas_gauge_geometry_and_bottom_up_fill(self):
        from PIL import Image
        atlas = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png'
        with Image.open(atlas) as image:
            rgba = image.convert('RGBA')
            self.assertEqual(rgba.size, (512, 512))
            # All 6x88 inner pixels belong to the existing recessed rail.
            self.assertEqual(set(rgba.crop((320, 177, 326, 265)).getdata()), {(139, 139, 139, 255)})
            self.assertEqual(rgba.getpixel((319, 180)), (55, 55, 55, 255))
            self.assertEqual(rgba.getpixel((326, 180)), (255, 255, 255, 255))
            self.assertTrue(all(r > g + 100 and r > b + 100 and a == 255
                                for r,g,b,a in rgba.crop((476, 0, 492, 60)).getdata()))
        with tempfile.TemporaryDirectory() as tmp:
            check = Path(tmp) / 'CheckGauge.java'
            check.write_text('''
package buildcraft.robotics.gui;
class CheckGauge {
  static void eq(int expected, int actual) {
    if (expected != actual) throw new AssertionError(expected + " != " + actual);
  }
  public static void main(String[] args) {
    long cap = 5_000_000_000L;
    eq(0, RobotProcessorPowerGauge.filledHeight(0, cap));
    eq(1, RobotProcessorPowerGauge.filledHeight(1, cap));
    eq(16, RobotProcessorPowerGauge.filledHeight(890_000_000L, cap));
    eq(44, RobotProcessorPowerGauge.filledHeight(cap / 2, cap));
    eq(87, RobotProcessorPowerGauge.filledHeight(cap - 1, cap));
    eq(88, RobotProcessorPowerGauge.filledHeight(cap, cap));
    eq(88, RobotProcessorPowerGauge.filledHeight(Long.MAX_VALUE, cap));
    eq(0, RobotProcessorPowerGauge.filledHeight(cap, 0));
    eq(0, RobotProcessorPowerGauge.filledHeight(-1, cap));
    for (int level = 0; level <= 100; level++) {
      long stored = cap * level / 100;
      int[] bottom = {265}, pixels = {0};
      RobotProcessorPowerGauge.draw(stored, cap, (x,y,u,v,w,h) -> {
        eq(320, x); eq(6, w); eq(bottom[0], y+h);
        eq(476, u); eq(60, v+h);
        if (y < 177 || h <= 0 || h > 60) throw new AssertionError("Out of bounds");
        bottom[0] = y; pixels[0] += h;
      });
      eq(RobotProcessorPowerGauge.filledHeight(stored, cap), pixels[0]);
    }
    if (!RobotProcessorPowerGauge.contains(319, 176)
      || !RobotProcessorPowerGauge.contains(326, 265)
      || RobotProcessorPowerGauge.contains(318, 176)
      || RobotProcessorPowerGauge.contains(327, 176)
      || RobotProcessorPowerGauge.contains(320, 175)
      || RobotProcessorPowerGauge.contains(320, 266)) throw new AssertionError("Hitbox");
  }
}
''')
            subprocess.run(['javac','--release','17','-d',tmp,
                str(JAVA/'robotics/gui/RobotProcessorPowerGauge.java'),str(check)],check=True)
            subprocess.run(['java','-cp',tmp,'buildcraft.robotics.gui.CheckGauge'],check=True)

    def test_actual_screen_blit_arguments_against_atlas(self):
        """Compile the actual screen's gauge method with recording graphics, then composite its blits.

        This exercises the renderer entry point and pixel coordinates, not just the height formula.
        It does not replace an in-game GPU test.
        """
        from PIL import Image
        with Image.open(ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/textures/gui/robot_processor.png') as source:
            atlas = source.convert('RGBA')
        screens = sorted(ROOT.rglob('GuiRobotProcessor.java'))
        self.assertEqual(len(screens), 5)
        with tempfile.TemporaryDirectory() as tmp:
            for path in screens:
                with self.subTest(path=path):
                    text = path.read_text()
                    method = text.split('    private void renderEnergyGauge(PoseStack pose) {', 1)[1].split('\n    }', 1)[0]
                    fixture = Path(tmp) / 'CheckGaugeBlit.java'
                    fixture.write_text('''
package buildcraft.robotics.gui;
class CheckGaugeBlit {
  static final String BACKGROUND = "processor";
  static final int leftPos = 17, topPos = 23;
  static final long CAP = 5_000_000_000L;
  record State(long stored) {}
  static class Menu {
    long stored;
    State getPowerState() { return new State(stored); }
    long getEnergyCapacity() { return CAP; }
  }
  final Menu processorMenu = new Menu();
  static class PoseStack {}
  static class RenderSystem {
    static String texture;
    static void setShaderTexture(int slot, String name) { texture = name; }
    static void setShaderColor(float r,float g,float b,float a) {}
  }
  static void record(String texture,int x,int y,int u,int v,int w,int h,int tw,int th) {
    if (!BACKGROUND.equals(texture) || tw != 512 || th != 512)
      throw new AssertionError("Wrong texture or atlas dimensions");
    System.out.printf("%d %d %d %d %d %d%n",x-leftPos,y-topPos,u,v,w,h);
  }
  static class Graphics {
    void blit(String texture,int x,int y,int u,int v,int w,int h,int tw,int th) {
      record(texture,x,y,u,v,w,h,tw,th);
    }
  }
  static class BCGraphics {
    static void nextLayer(Graphics graphics) { System.out.println("layer"); }
  }
  static class RenderCompat {
    static void blit(Graphics g,String texture,int x,int y,int u,int v,int w,int h,int tw,int th) {
      g.blit(texture,x,y,u,v,w,h,tw,th);
    }
  }
  static void blit(PoseStack pose,int x,int y,int u,int v,int w,int h,int tw,int th) {
    record(RenderSystem.texture,x,y,u,v,w,h,tw,th);
  }
  Graphics getActiveGraphics() { return new Graphics(); }
  private void renderEnergyGauge(PoseStack pose) {''' + method + '''
  }
  public static void main(String[] args) {
    var screen = new CheckGaugeBlit();
    for (long stored : new long[] {0L,1L,890_000_000L,2_500_000_000L,CAP-1,CAP}) {
      System.out.println("energy " + stored);
      screen.processorMenu.stored = stored;
      screen.renderEnergyGauge(new PoseStack());
    }
  }
}
''')
                    subprocess.run(['javac','--release','17','-d',tmp,
                        str(JAVA/'robotics/gui/RobotProcessorPowerGauge.java'),str(fixture)],check=True)
                    output = subprocess.check_output(['java','-cp',tmp,'buildcraft.robotics.gui.CheckGaugeBlit'],text=True)
                    cases = {}
                    for line in output.splitlines():
                        if line.startswith('energy '):
                            stored = int(line.split()[1]); cases[stored] = []
                        else:
                            cases[stored].append(line)
                    for stored, height in ((0,0),(1,1),(890_000_000,16),(2_500_000_000,44),
                                            (4_999_999_999,87),(5_000_000_000,88)):
                        calls = cases[stored]
                        if height and 'source-families' in path.parts:
                            self.assertEqual(calls.pop(0),'layer')
                        composed = atlas.crop((0,0,331,272))
                        for line in calls:
                            x,y,u,v,w,h = map(int,line.split())
                            self.assertEqual((x,u,w),(320,476,6))
                            self.assertEqual(v+h,60)
                            self.assertGreaterEqual(y,177)
                            self.assertLessEqual(y+h,265)
                            composed.paste(atlas.crop((u,v,u+w,v+h)),(x,y))
                        for y in range(177,265):
                            for x in range(320,326):
                                r,g,b,a = composed.getpixel((x,y))
                                if y >= 265-height:
                                    self.assertTrue(r>g+100 and r>b+100 and a==255)
                                else:
                                    self.assertEqual((r,g,b,a),(139,139,139,255))
                        self.assertEqual(composed.crop((319,177,320,265)).tobytes(),atlas.crop((319,177,320,265)).tobytes())
                        self.assertEqual(composed.crop((326,177,327,265)).tobytes(),atlas.crop((326,177,327,265)).tobytes())
