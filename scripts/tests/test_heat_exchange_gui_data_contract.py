"""Regression: BCU heat-exchanger GUI needs 4 x TankContainerData.LEN slots.

The regression this catches is subtle: the common TankComponent reserves only 10
words for dynamic capacity, but the rich-fluid network payload contains 14.
ContainerScreenBase.setup() silently disables all four gauges if lengths differ.
"""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
    "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge"
)


class HeatExchangeGuiDataContract(unittest.TestCase):
    def test_all_targets_have_14_words_per_tank_and_fresh_components(self):
        props = load_properties()
        helper = ROOT / "source-shared/src/main/java/buildcraft/factory/client/gui/HeatExchangeTankComponent.java"
        helper_src = helper.read_text(encoding="utf-8")
        self.assertIn("return TankContainerData.LEN;", helper_src)
        self.assertIn("boolean isHovering(int mouseX, int mouseY)", helper_src)
        self.assertIn("buildcraft.tank.empty", helper_src)

        for target in TARGETS:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                sources = {}
                for label, relative in (
                    ("screen", "src/main/java/buildcraft/factory/client/gui/ScreenHeatExchange.java"),
                    ("menu", "src/main/java/buildcraft/factory/client/gui/MenuHeatExchange.java"),
                    ("data", "src/main/java/buildcraft/lib/gui/TankContainerData.java"),
                    ("base", "src/main/java/buildcraft/lib/gui/ContainerScreenBase.java"),
                ):
                    source = resolve_effective_source(layout, props, relative)
                    self.assertIsNotNone(source, (target, label))
                    sources[label] = source.read_text(encoding="utf-8")
                self.assertIn("LEN = 14;", sources["data"])
                self.assertIn("TANK_COUNT * TankContainerData.LEN", sources["menu"])
                self.assertIn("data.getCount() != offset", sources["base"])
                self.assertIn("setup(menu.data);", sources["screen"])
                self.assertEqual(sources["screen"].count("add(new HeatExchangeTankComponent("), 4)
                self.assertNotIn("static final TankComponent[]", sources["screen"])
                if target.startswith("26."):
                    self.assertIn("super(menu, inventory, name, 4, TEXTURE_BASE, 176, 171)", sources["screen"])
                    self.assertNotIn("this.imageHeight = 171", sources["screen"])
                    self.assertIn("super(menu, inventory, name, imageWidth, imageHeight);", sources["base"])
                self.assertLess(sources["screen"].index("setup(menu.data);"),
                                sources["screen"].index("GuiHelpUtil.addRoot(mainGui"))

    def test_empty_tooltip_translation(self):
        lang = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"
        self.assertEqual(json.loads(lang.read_text(encoding="utf-8"))["buildcraft.tank.empty"], "Empty %s Tank")

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "requires JDK")
    def test_compiled_component_consumes_exactly_56_words(self):
        # Compile the ACTUAL widget with a minimal representation of its inherited
        # contract, then exercise the stride and BCU hover geometry in Java.
        src = ROOT / "source-shared/src/main/java/buildcraft/factory/client/gui/HeatExchangeTankComponent.java"
        stubs = {
            "buildcraft/lib/gui/TankContainerData.java": "package buildcraft.lib.gui; public final class TankContainerData { public static final int LEN=14; }",
            "buildcraft/lib/misc/LocaleUtil.java": "package buildcraft.lib.misc; public final class LocaleUtil { public static net.minecraft.network.chat.Component localizeFluidStaticAmount(int amount){return net.minecraft.network.chat.Component.literal(Integer.toString(amount));}}",
            "net/minecraft/world/level/material/Fluid.java": "package net.minecraft.world.level.material; public class Fluid {}",
            "net/minecraft/network/chat/Component.java": "package net.minecraft.network.chat; public class Component { public static Component literal(String s){return new Component();} public static Component translatable(String s, Object... args){return new Component();}}",
            "buildcraft/lib/gui/component/TankComponent.java": """package buildcraft.lib.gui.component;
import java.util.*;import net.minecraft.network.chat.Component;import net.minecraft.world.level.material.Fluid;
public class TankComponent {
 protected int x,y,xs,ys; protected int capacityOffset;
 public TankComponent(int x,int y,int w,int h,int cap,int tx,int ty,int co){this.x=x;this.y=y;xs=w;ys=h;capacityOffset=co;}
 public int getNeedDataSize(){return capacityOffset>=0?Math.max(4,capacityOffset+2):4;}
 public boolean isHovering(int mx,int my){return mx>x&&mx<x+xs&&my>y&&my<y+ys;}
 protected List<Component> getToolTip(Fluid f,int amount,int cap){return List.of();}
} """,
            "Probe.java": """import buildcraft.factory.client.gui.HeatExchangeTankComponent;
public class Probe {
 public static void main(String[] args){
  int[][] positions={{44,64,34,17},{116,43,16,38},{44,12,16,38},{98,12,34,17}};
  int offset=0;for(int[] p:positions){
   HeatExchangeTankComponent c=new HeatExchangeTankComponent(p[0],p[1],p[2],p[3]);
   if(c.getNeedDataSize()!=14||!c.isHovering(p[0],p[1])||c.isHovering(p[0]+p[2],p[1]))throw new AssertionError();
   offset+=c.getNeedDataSize();
  }
  if(offset!=56)throw new AssertionError("got "+offset);
 }
}""",
        }
        with tempfile.TemporaryDirectory(prefix="heat-gui-contract-") as td:
            work = Path(td)
            sources = []
            for name, content in stubs.items():
                f = work / name
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(content, encoding="utf-8")
                sources.append(str(f))
            subprocess.run(["javac", "-d", str(work / "out"), *sources, str(src)],
                           capture_output=True, text=True, check=True)
            subprocess.run(["java", "-cp", str(work / "out"), "Probe"],
                           capture_output=True, text=True, check=True)


if __name__ == "__main__":
    unittest.main()
