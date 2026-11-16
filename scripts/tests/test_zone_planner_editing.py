#!/usr/bin/env python3
"""Zone Planner editing logic probes and source-family GUI wiring checks.

The Java probe compiles the production, Minecraft-independent edit logic with tiny
ZonePlan/ZoneChunk/ChunkPos test doubles. It checks behavior; full Minecraft client
compilation is a separate requirement.
"""
from __future__ import annotations
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
EDITOR = ROOT / 'source-shared/src/main/java/buildcraft/robotics/zone/ZonePlannerEditor.java'
GUI_PATHS = [
    'version-src/1.19.2-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'version-src/1.20.1-forge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-downports/1.21.X/1.21.1/family/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-families/1.21.X/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'version-src/26.1.2-neoforge/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
    'source-families/26.X/src/main/java/buildcraft/robotics/gui/GuiZonePlanner.java',
]

STUBS = {
    'net/minecraft/world/level/ChunkPos.java': '''package net.minecraft.world.level;
public record ChunkPos(int x, int z) { }
''',
    'buildcraft/robotics/zone/ZoneChunk.java': '''package buildcraft.robotics.zone;
public class ZoneChunk {
  private boolean[] cells = new boolean[256];
  public ZoneChunk() {}
  public ZoneChunk(ZoneChunk other) { cells = other.cells.clone(); }
  public boolean get(int x, int z) { return cells[x + z * 16]; }
  public void set(int x, int z, boolean value) { cells[x + z * 16] = value; }
  public boolean isEmpty() { for(boolean b: cells) if(b) return false; return true; }
}
''',
    'buildcraft/robotics/zone/ZonePlan.java': '''package buildcraft.robotics.zone;
import java.util.HashMap;
import net.minecraft.world.level.ChunkPos;
public class ZonePlan {
  private final HashMap<ChunkPos, ZoneChunk> chunks = new HashMap<>();
  public ZonePlan() {}
  public ZonePlan(ZonePlan other) {
    other.chunks.forEach((pos, chunk) -> chunks.put(pos, new ZoneChunk(chunk)));
  }
  public HashMap<ChunkPos, ZoneChunk> getChunkMapping() { return chunks; }
  public boolean get(int x, int z) {
    ZoneChunk chunk = chunks.get(new ChunkPos(x >> 4, z >> 4));
    return chunk != null && chunk.get(x & 15, z & 15);
  }
  public void set(int x, int z, boolean value) {
    ChunkPos key = new ChunkPos(x >> 4, z >> 4);
    ZoneChunk chunk = chunks.get(key);
    if(chunk == null && !value) return;
    if(chunk == null) { chunk = new ZoneChunk(); chunks.put(key, chunk); }
    chunk.set(x & 15, z & 15, value);
    if(chunk.isEmpty()) chunks.remove(key);
  }
}
''',
    'buildcraft/robotics/zone/EditorProbe.java': '''package buildcraft.robotics.zone;
import static buildcraft.robotics.zone.ZonePlannerEditor.*;
import net.minecraft.world.level.ChunkPos;
public class EditorProbe {
  static void check(boolean ok, String what) { if(!ok) throw new AssertionError(what); }
  public static void main(String[] args) {
    ZonePlan area = new ZonePlan();
    History history = new History();
    check(paint(area, Tool.RECTANGLE, 0, 0, 2, 2, true, key -> true, null), "rectangle painted");
    check(area.get(2, 2) && area.get(0, 0), "rectangle bounds");
    check(!paint(area, Tool.RECTANGLE, 0, 0, 2, 2, true, key -> true, null), "no-op rectangle");
    check(history.record(0, new ZonePlan(), area), "history initial action");
    check(history.canUndo(0) && !history.canUndo(1), "independent layer histories");
    ZonePlan undo = history.undo(0, area);
    check(undo != null && !undo.get(0, 0), "undo removes original region");
    ZonePlan redo = history.redo(0, undo);
    check(redo != null && redo.get(2, 2), "redo restores original region");
    ZonePlan second = new ZonePlan(redo);
    check(paint(second, Tool.LINE, -2, -2, 2, 2, true, key -> true, null), "line added");
    check(second.get(-2, -2) && second.get(-1, -1), "line includes negative coords");
    check(history.record(0, redo, second), "record second action");
    check(!history.canRedo(0), "new action discards redo");
    ZonePlan stale = new ZonePlan(second); stale.set(-1, -1, false);
    check(history.undo(0, stale) == null, "stale remote layer detected");
    check(!history.canUndo(0), "divergence drops stale undo chain");

    ZonePlan oval = new ZonePlan();
    check(paint(oval, Tool.ELLIPSE, 0, 0, 2, 2, true, key -> true, null), "ellipse nonempty");
    check(oval.get(1, 1) && !oval.get(0, 0), "ellipse not rectangular");
    ZonePlan two = new ZonePlan();
    check(paint(two, Tool.ELLIPSE, 0, 0, 1, 1, true, key -> true, null), "2x2 ellipse not empty");
    check(two.get(0, 0) && two.get(1, 1), "2x2 ellipse endpoints");

    ZonePlan brush = new ZonePlan();
    check(paint(brush, Tool.BRUSH, 0, 0, 3, 0, true, key -> true, null), "brush painted");
    check(brush.get(-1, -1) && brush.get(4, 1), "continuous 3x3 brush");
    check(!paint(brush, Tool.RECTANGLE, 100, 100, 110, 110, true, key -> false, null), "unavailable chunks excluded");

    ZonePlan island = new ZonePlan();
    paint(island, Tool.RECTANGLE, 0, 0, 4, 4, true, key -> true, null);
    paint(island, Tool.RECTANGLE, 2, 0, 2, 4, false, key -> true, null);
    check(flood(island, 0, 0, false, key -> true, new Bounds(0,0,4,4)), "flood erases region");
    check(!island.get(1, 0) && island.get(3, 0), "flood respects disconnected region");
    ZonePlan bounded = new ZonePlan();
    check(flood(bounded, 0, 0, true, key -> true, new Bounds(-2,-2,2,2)), "bounded positive flood");
    check(bounded.get(-2, -2) && !bounded.get(3, 0), "flood respects visible rectangle");
    ZonePlan denied = new ZonePlan();
    check(!flood(denied, 0, 0, true, key -> false, new Bounds(-10,-10,10,10)), "unavailable area denied");
    check(denied.getChunkMapping().isEmpty(), "denied no mutation");

    ZonePlan huge = new ZonePlan();
    check(!flood(huge, 0, 0, true, key -> true, new Bounds(0,0,300,300)), "oversize flood refused");
    check(huge.getChunkMapping().isEmpty(), "oversize flood atomic");

    ZonePlan layer1 = new ZonePlan(); layer1.set(100, 100, true);
    check(history.record(1, new ZonePlan(), layer1), "independent layer changed");
    check(history.canUndo(1) && !history.canUndo(0), "independent stacks preserved");
    System.out.println("ZonePlannerEditor Java probes passed");
  }
}
''',
}

class ZonePlannerEditingTests(unittest.TestCase):
    def test_zone_planner_editor_java_behavior(self):
        self.assertTrue(shutil.which('javac'), 'JDK required for Java behavioral probe')
        with tempfile.TemporaryDirectory(prefix='bc-zone-editor-') as temp:
            base = Path(temp)
            for rel, code in STUBS.items():
                path = base/rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
            targets = [str(base/p) for p in STUBS] + [str(EDITOR)]
            subprocess.run(['javac', '--release', '17', '-d', str(base/'classes'), *targets],
                           check=True, capture_output=True, text=True)
            proc = subprocess.run(['java', '-cp', str(base/'classes'),
                                   'buildcraft.robotics.zone.EditorProbe'],
                                  check=True, capture_output=True, text=True)
            self.assertIn('probes passed', proc.stdout)

    def test_all_gui_target_versions_wired(self):
        for rel in GUI_PATHS:
            with self.subTest(rel=rel):
                src = (ROOT/rel).read_text()
                self.assertIn('ZonePlannerEditor.History', src)
                self.assertIn('ZonePlannerEditor.paint(', src)
                self.assertIn('container.saveArea(selectedLayer, modified)', src)
                self.assertIn('undoEdit();', src)
                self.assertIn('redoEdit();', src)
                self.assertIn('commitEdit(new ZonePlan());', src)
                self.assertIn('ZonePlannerEditor.Tool.RECTANGLE', src)
                self.assertIn('ZonePlannerEditor.Tool.ELLIPSE', src)
                self.assertIn('ZonePlannerEditor.Tool.BRUSH', src)
                self.assertNotIn('private void applySelection(', src)

    def test_redesigned_slot_coordinates_and_name_field(self):
        menu = (ROOT/'source-shared/src/main/java/buildcraft/robotics/container/ContainerZonePlanner.java').read_text()
        self.assertIn('new SlotBase(inv, 2, 88, 128)', menu)
        self.assertIn('new SlotBase(inv, 0, 233, 8)', menu)
        self.assertIn('new SlotOutput(inv, 1, 233, 56)', menu)
        self.assertIn('addFullPlayerInventory(88, 146)', menu)
        for rel in GUI_PATHS:
            with self.subTest(rel=rel):
                src = (ROOT/rel).read_text()
                self.assertIn('private static final int NAME_X = 108;', src)
                self.assertIn('private static final int NAME_Y = 129;', src)
                self.assertIn('private static final int NAME_W = 136;', src)
                self.assertIn('new GuiRectangle(87, 127, 20, 20)', src)
                self.assertIn('private static final int MAP_Y = 8;', src)
                self.assertIn('new GuiRectangle(236, 26, 8, 28)', src)
                self.assertIn('new GuiRectangle(232, 7, 20, 68)', src)

    def test_new_toolbar_slots_independent_eraser_and_buttons(self):
        for rel in GUI_PATHS:
            with self.subTest(rel=rel):
                src = (ROOT/rel).read_text()
                self.assertIn('private static final int ERASER_X = 8;', src)
                self.assertIn('private static final int SHAPE_Y = 128;', src)
                self.assertIn('private static final int[] SHAPE_X = {26, 44, 62};', src)
                self.assertIn('ZonePlannerEditor.Tool.RECTANGLE, ZonePlannerEditor.Tool.ELLIPSE, ZonePlannerEditor.Tool.BRUSH', src)
                self.assertIn('private boolean addMode = true;', src)
                self.assertIn('addMode = !addMode;', src)
                self.assertIn('selectedTool = SHAPE_TOOLS[index];', src)
                self.assertIn('drawEditorSlots(', src)
                self.assertIn('drawHistoryButtons(', src)
                self.assertNotIn('FS_X', src)
                self.assertNotIn('FS_Y', src)
                self.assertNotIn('FS_W', src)
                self.assertNotIn('FS_H', src)
                self.assertNotIn('help.zone_planner.fullscreen.title', src)
                self.assertNotIn('fullscreen', src.lower())
                self.assertNotIn('GLFW.GLFW_KEY_M)', src)
                self.assertIn('return WINDOWED_MAP_WIDTH;', src)
                self.assertIn('return WINDOWED_MAP_HEIGHT;', src)
                self.assertNotIn('\"FS\"', src)
                self.assertIn('private static final int UNDO_X = 8;', src)
                self.assertIn('private static final int REDO_X = 26;', src)
                self.assertIn('private static final int HISTORY_Y = 110;', src)
                self.assertIn('insideRelativeButton(mouseX, mouseY, UNDO_X, HISTORY_Y', src)
                self.assertIn('insideRelativeButton(mouseX, mouseY, REDO_X, HISTORY_Y', src)
                self.assertIn('TEXTURE_ERASER', src)
                self.assertIn('TEXTURE_PEN', src)
                self.assertIn('TEXTURE_RECTANGLE', src)
                self.assertIn('TEXTURE_OVAL', src)
                self.assertIn('TEXTURE_HISTORY', src)
                self.assertNotIn('copiedLayer', src)
                self.assertNotIn('GLFW.GLFW_KEY_C)', src)
                self.assertNotIn('GLFW.GLFW_KEY_V)', src)
                self.assertNotIn('EDIT_X = {66, 104, 142, 180}', src)
                self.assertNotIn('selectedTool.next()', src)

    def test_clear_button_uses_third_history_sprite_above_pen(self):
        for rel in GUI_PATHS:
            with self.subTest(rel=rel):
                src = (ROOT/rel).read_text()
                self.assertIn('private static final int CLEAR_X = 62;', src)
                self.assertIn('private static final int HISTORY_Y = 110;', src)
                self.assertIn('new GuiRectangle(CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE)', src)
                self.assertIn('insideRelativeButton(mouseX, mouseY, CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE)', src)
                self.assertIn('32, insideRelativeButton(mouseX, mouseY, CLEAR_X, HISTORY_Y, HISTORY_SIZE, HISTORY_SIZE) ? 16 : 0, 64);', src)
                self.assertIn('commitEdit(new ZonePlan());', src)
                self.assertNotIn('private void drawButton(', src)
                self.assertNotIn('CLEAR_Y', src)
                self.assertNotIn('CLEAR_W', src)
                self.assertNotIn('CLEAR_H', src)
                self.assertNotIn('"Clear"', src)

    def test_cancel_selection_does_not_override_a_superclass_method(self):
        # cancelSelection is a local private helper, not a Minecraft GUI override.
        # Incorrect @Override broke compilation on 1.19.2, 1.20.1 and 1.21.1.
        import re
        for rel in GUI_PATHS:
            with self.subTest(rel=rel):
                src = (ROOT/rel).read_text()
                self.assertRegex(src, r'private\s+void\s+cancelSelection\s*\(\s*\)')
                self.assertNotRegex(src, r'@Override\s+private\s+void\s+cancelSelection\s*\(\s*\)')

    def test_help_translation_has_tool_descriptions(self):
        contents = json.loads((ROOT/'source-shared/src/main/resources/assets/buildcraft/lang/en_us.json').read_text())
        for key in ['undo', 'redo', 'clear', 'eraser', 'rectangle', 'oval', 'pen']:
            self.assertIn(f'buildcraftrobotics.help.zone_planner.{key}.title', contents)
            self.assertIn(f'buildcraftrobotics.help.zone_planner.{key}.desc', contents)
        for key in ['copy', 'paste']:
            self.assertNotIn(f'buildcraftrobotics.help.zone_planner.{key}.title', contents)
            self.assertNotIn(f'buildcraftrobotics.help.zone_planner.{key}.desc', contents)
        self.assertNotIn('buildcraftrobotics.help.zone_planner.fullscreen.title', contents)
        self.assertNotIn('buildcraftrobotics.help.zone_planner.fullscreen.desc', contents)
        self.assertIn('buildcraft.help.library.search.title', contents)

if __name__ == '__main__':
    unittest.main()
