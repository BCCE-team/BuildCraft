#!/usr/bin/env python3
"""Regression coverage for the legacy/modern client slot geometry.

The Java probe compiles the actual menu-layout methods against small Slot API
fixtures. It checks menu indexes, slot types, inventory indices, and geometry
in both directions. This is not a substitute for Minecraft Gradle compilation.
"""
from __future__ import annotations

from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'source-shared/src/main/java/buildcraft'


def extract_method(source: str, signature: str) -> str:
    start = source.index(signature)
    brace = source.index('{', start)
    i, depth = brace + 1, 1
    while depth:
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
        i += 1
    return source[start:i]


class LegacyModernSlotGeometryTests(unittest.TestCase):
    def test_screen_constructors_apply_matching_layout_and_buttons_overlap_gui(self):
        paths = []
        for group in ['source-families', 'source-downports', 'version-src']:
            paths += list((ROOT / group).rglob('GuiElectronicLibrary*.java'))
            paths += list((ROOT / group).rglob('GuiZonePlanner*.java'))
        self.assertEqual(20, len(paths), 'All modern and legacy screens must be covered')
        for path in paths:
            with self.subTest(path=path.relative_to(ROOT)):
                content = path.read_text()
                legacy = path.stem.endswith('Legacy')
                expected = f'container.applyClientGuiLayout({str(legacy).lower()});'
                self.assertIn(expected, content)
                self.assertIn('int y = topPos - 16;', content)
                self.assertNotIn('int y = topPos - 19;', content)

    def test_legacy_resources_maintain_original_backgrounds(self):
        for module, texture in [('buildcraftbuilders', 'electronic_library'),
                                ('buildcraftrobotics', 'zone_planner')]:
            path = ROOT / 'source-shared/src/main/resources/assets' / module / 'textures/gui' / f'{texture}_legacy.png'
            self.assertTrue(path.is_file(), path)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'Java JDK not available')
    def test_real_layout_methods_preserve_menu_index_and_item_handlers(self):
        library = (JAVA / 'builders/menu/ContainerElectronicLibrary.java').read_text()
        planner = (JAVA / 'robotics/container/ContainerZonePlanner.java').read_text()
        lib_apply = extract_method(library, 'public void applyClientGuiLayout(boolean legacy)')
        lib_replace = extract_method(library, 'private void replaceClientSlot(int menuIndex, Slot replacement)')
        zone_apply = extract_method(planner, 'public void applyClientGuiLayout(boolean legacy)')
        zone_replace = extract_method(planner, 'private void replaceClientSlot(int menuIndex, Slot replacement)')
        java_source = r'''
import java.util.ArrayList;
import java.util.List;

class GuiLayoutProbe {
    static class Inventory { }
    interface IItemHandlerAdv { }
    static class ItemHandlerSimple implements IItemHandlerAdv { }
    static class Slot {
        public int index;
        public final int containerSlot, x, y;
        Slot(Inventory inventory, int slotIndex, int x, int y) {
            this.containerSlot=slotIndex;this.x=x;this.y=y;
        }
        Slot(int slotIndex,int x,int y) {
            this.containerSlot=slotIndex;this.x=x;this.y=y;
        }
    }
    static class SlotBase extends Slot {
        final IItemHandlerAdv itemHandler;
        final int handlerIndex;
        SlotBase(IItemHandlerAdv handler,int index,int x,int y) {
            super(index,x,y);this.itemHandler=handler;this.handlerIndex=index;
        }
    }
    static class SlotOutput extends SlotBase {
        SlotOutput(IItemHandlerAdv handler,int index,int x,int y) {super(handler,index,x,y);}
    }
    static class SlotDownloadInput extends SlotBase {
        SlotDownloadInput(ItemHandlerSimple handler,int index,int x,int y) {super(handler,index,x,y);}
    }
    static class SlotUploadInput extends SlotBase {
        SlotUploadInput(ItemHandlerSimple handler,int index,int x,int y) {super(handler,index,x,y);}
    }
    static class Base {
        final Inventory playerInventory = new Inventory();
        final List<Slot> slots = new ArrayList<>();
        void add(Slot slot) {slot.index=slots.size();slots.add(slot);}
    }
    static class Library extends Base {
__LIB_APPLY__
__LIB_REPLACE__
        Library(ItemHandlerSimple handler) {
            for(int i=0;i<36;i++) add(new Slot(playerInventory,i<27?i+9:i-27,8+(i%9)*18,i<27?148+i/9*18:206));
            add(new SlotOutput(handler,0,175,67));
            add(new SlotDownloadInput(handler,0,219,67));
            add(new SlotUploadInput(handler,0,175,89));
            add(new SlotOutput(handler,0,219,89));
        }
    }
    static class Planner extends Base {
__ZONE_APPLY__
__ZONE_REPLACE__
        Planner(ItemHandlerSimple handler) {
            add(new SlotBase(handler,0,233,8));
            add(new SlotOutput(handler,1,233,56));
            add(new SlotBase(handler,2,88,128));
            for(int i=0;i<36;i++) add(new Slot(playerInventory,i<27?i+9:i-27,88+(i%9)*18,i<27?146+i/9*18:204));
        }
    }
    static void check(boolean condition,String message) {
        if(!condition)throw new AssertionError(message);
    }
    static void testLibrary(Library menu,ItemHandlerSimple handler,boolean legacy) {
        int y=legacy?138:148;
        for(int i=0;i<36;i++) {
            Slot s=menu.slots.get(i);
            check(s.index==i,"library player menu index "+i);
            check(s.containerSlot==(i<27?i+9:i-27),"library inventory slot index "+i);
            check(s.x==8+i%9*18,"library player x "+i);
            check(s.y==(i<27?y+i/9*18:y+58),"library player y "+i);
        }
        int[] xs={175,219,175,219};
        int[] ys={legacy?57:67,legacy?57:67,legacy?79:89,legacy?79:89};
        for(int i=0;i<4;i++) {
            Slot s=menu.slots.get(36+i);
            check(s.index==36+i && s.x==xs[i] && s.y==ys[i],"library machine slot "+i);
            check(((SlotBase)s).itemHandler==handler,"library handler identity "+i);
        }
        check(menu.slots.get(36) instanceof SlotOutput,"download output type");
        check(menu.slots.get(37) instanceof SlotDownloadInput,"download input type");
        check(menu.slots.get(38) instanceof SlotUploadInput,"upload input type");
        check(menu.slots.get(39) instanceof SlotOutput,"upload output type");
    }
    static void testPlanner(Planner menu,ItemHandlerSimple handler,boolean legacy) {
        int[][] c=legacy?new int[][]{{233,9},{233,57},{8,125}}:new int[][]{{233,8},{233,56},{88,128}};
        for(int i=0;i<3;i++) {
            Slot s=menu.slots.get(i);
            check(s.index==i && s.x==c[i][0] && s.y==c[i][1],"planner slot "+i);
            check(((SlotBase)s).itemHandler==handler && ((SlotBase)s).handlerIndex==i,"planner handler "+i);
        }
        check(menu.slots.get(1) instanceof SlotOutput,"planner output must stay output-only");
        for(int i=3;i<39;i++) check(menu.slots.get(i).index==i,"planner player index "+i);
    }
    public static void main(String[] args) {
        ItemHandlerSimple handler=new ItemHandlerSimple();
        Library library=new Library(handler);
        Planner planner=new Planner(handler);
        for(boolean legacy:new boolean[]{true,false,true,false}) {
            library.applyClientGuiLayout(legacy);
            planner.applyClientGuiLayout(legacy);
            testLibrary(library,handler,legacy);
            testPlanner(planner,handler,legacy);
        }
        System.out.println("GUI slot layouts OK");
    }
}
'''
        for key, method in [('__LIB_APPLY__', lib_apply), ('__LIB_REPLACE__', lib_replace),
                            ('__ZONE_APPLY__', zone_apply), ('__ZONE_REPLACE__', zone_replace)]:
            java_source = java_source.replace(key, method)
        with tempfile.TemporaryDirectory(prefix='bc-gui-slot-layout-') as temp:
            source = Path(temp) / 'GuiLayoutProbe.java'
            source.write_text(java_source)
            subprocess.run(['javac', '--release', '17', str(source)], check=True, capture_output=True, text=True)
            result = subprocess.run(['java', '-cp', temp, 'GuiLayoutProbe'], check=True,
                                    capture_output=True, text=True)
            self.assertIn('GUI slot layouts OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
