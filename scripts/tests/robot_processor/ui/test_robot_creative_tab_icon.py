"""The cached tab icon cycles through charged professions; ordinary items never cycle."""
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

from scripts.tests.robot_processor.ui.test_modern_robot_preview import generated, BASE, OLD, MODERN

ROOT = Path(__file__).resolve().parents[4]
HELPER = BASE + 'item/RobotCreativeTabIcon.java'


class RobotCreativeTabIconTests(unittest.TestCase):
    def test_all_targets_register_cycle_and_preserve_battery_properties(self):
        for target in (*OLD, *MODERN):
            with self.subTest(target=target):
                source = generated(target, BASE + 'BCRobotics.java')
                factory = 'RobotCreativeTabIcon::create' if target.startswith('26.') else 'RobotCreativeTabIcon.create()'
                self.assertIn('TAB_ROBOTICS.setItem(' + factory + ');', source)
                self.assertNotIn('TAB_ROBOTICS.setItem(BCRoboticsItems.ROBOT.get());', source)
                registration = generated(target, BASE + 'client/FabricRoboticsClient.java') if target.endswith('fabric') else source
                self.assertIn('RobotCreativeTabIcon.modelValue(stack)', registration)
                self.assertIn('RobotBatteryVisuals.powerModel(stack)', registration)
                helper = generated(target, HELPER)
                self.assertIn('EntityRobotBase.MAX_ENERGY', helper)
                self.assertIn('return BCRoboticsBoards.getRobotModelValue(stack);', helper)
                self.assertIn('tag.contains(ICON_KEY)', helper)
                self.assertIn('filter(value -> value > 0.0F)', helper)
                if target.startswith(('1.19.', '1.20.')):
                    self.assertIn('stack.getOrCreateTag().putBoolean(ICON_KEY, true)', helper)
                    self.assertNotIn('ItemStackUtil', helper)
                else:
                    self.assertIn('ItemStackUtil.setCustomData(stack, tag)', helper)
                    self.assertNotIn('stack.getTag()', helper)
                boards = generated(target, BASE + 'BCRoboticsBoards.java')
                self.assertEqual(len(re.findall(r'^        board\("', boards, re.M)), 20)
                self.assertIn('return nbt instanceof ProgramBoardNBT ? 0.0F : modelIndex / 100.0F;', boards)

    def test_all_professions_have_charged_model_variants(self):
        path = ROOT / 'source-shared/src/main/resources/assets/buildcraftrobotics/models/item/robot.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        charged = [e['predicate']['buildcraftrobotics:robot'] for e in data['overrides']
                   if e['predicate'].get('buildcraftrobotics:robot_power') == 1 and 'buildcraftrobotics:robot' in e['predicate']]
        self.assertEqual(charged, [index / 100 for index in range(1, 21)])

    def test_java_cycle_and_icon_marker_both_nbt_paths(self):
        if not (shutil.which('javac') and shutil.which('java')):
            self.skipTest('JDK required for compiling the icon fixture')
        for target in ('1.19.2-forge', '26.3-fabric'):
            with self.subTest(target=target), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                sources = {
                    'buildcraft/robotics/item/RobotCreativeTabIcon.java': generated(target, HELPER),
                    'net/minecraft/nbt/CompoundTag.java': '''package net.minecraft.nbt;
                        import java.util.HashMap;
                        public class CompoundTag {
                            private final HashMap<String, Boolean> flags = new HashMap<>();
                            public void putBoolean(String key, boolean value) { flags.put(key, value); }
                            public boolean contains(String key) { return flags.containsKey(key); }
                        }''',
                    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
                        import net.minecraft.nbt.CompoundTag;
                        public class ItemStack {
                            public final float model;
                            public final int energy;
                            public CompoundTag tag;
                            public ItemStack(float model, int energy) { this.model=model; this.energy=energy; }
                            public CompoundTag getTag() { return tag; }
                            public CompoundTag getOrCreateTag() { if (tag==null) tag=new CompoundTag(); return tag; }
                        }''',
                    'buildcraft/lib/misc/ItemStackUtil.java': '''package buildcraft.lib.misc;
                        import net.minecraft.nbt.CompoundTag;
                        import net.minecraft.world.item.ItemStack;
                        public class ItemStackUtil {
                            public static CompoundTag getCustomData(ItemStack stack) { return stack.tag==null ? new CompoundTag() : stack.tag; }
                            public static void setCustomData(ItemStack stack, CompoundTag tag) { stack.tag=tag; }
                        }''',
                    'buildcraft/robotics/internal/legacy/robots/EntityRobotBase.java': '''package buildcraft.robotics.internal.legacy.robots;
                        public class EntityRobotBase { public static final int MAX_ENERGY=10000; }''',
                    'buildcraft/robotics/BCRoboticsBoards.java': '''package buildcraft.robotics;
                        import java.util.*;
                        import net.minecraft.world.item.ItemStack;
                        public class BCRoboticsBoards {
                            public record BoardEntry(float modelValue) {}
                            public static Collection<BoardEntry> robotEntries() {
                                List<BoardEntry> entries=new ArrayList<>();
                                for(int i=20;i>=1;i--) entries.add(new BoardEntry(i/100.0F));
                                entries.add(new BoardEntry(0)); entries.add(new BoardEntry(.01F));
                                return entries;
                            }
                            public static BoardEntry getByKey(String key) { return new BoardEntry(.01F); }
                            public static float getRobotModelValue(ItemStack stack) { return stack.model; }
                        }''',
                    'buildcraft/robotics/item/ItemRobot.java': '''package buildcraft.robotics.item;
                        import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
                        import net.minecraft.world.item.ItemStack;
                        public class ItemRobot { public static ItemStack createRobotStack(BoardEntry board, int energy) {
                            return new ItemStack(board.modelValue(), energy);
                        }}''',
                    'buildcraft/robotics/item/TestIcon.java': '''package buildcraft.robotics.item;
                        import java.util.*;
                        import net.minecraft.world.item.ItemStack;
                        public class TestIcon {
                            private static void check(boolean b) { if (!b) throw new AssertionError(); }
                            public static void main(String[] args) {
                                ItemStack icon=RobotCreativeTabIcon.create();
                                check(icon.energy==10000);
                                check(icon.tag.contains("bc_robot_creative_tab_icon"));
                                List<Float> models=new ArrayList<>();
                                for(int i=1;i<=20;i++) models.add(i/100.0F);
                                for(int i=0;i<60;i++) {
                                    float expected=(i%20+1)/100.0F;
                                    check(RobotCreativeTabIcon.modelAtMillis(models,i*1000L)==expected);
                                    check(RobotCreativeTabIcon.modelAtMillis(models,i*1000L+999)==expected);
                                }
                                check(RobotCreativeTabIcon.modelAtMillis(models,-1)==.20F);
                                check(models.contains(RobotCreativeTabIcon.modelValue(icon)));
                                check(RobotCreativeTabIcon.modelValue(new ItemStack(.12F,0))==.12F);
                                check(RobotCreativeTabIcon.modelValue(new ItemStack(.06F,10000))==.06F);
                                check(RobotCreativeTabIcon.modelValue(new ItemStack(0,0))==0);
                            }
                        }''',
                }
                for name, text in sources.items():
                    p=root / name
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.write_text(text, encoding='utf-8')
                subprocess.run(['javac', '--release', '17', '-d', str(root), *(str(root / name) for name in sources)], check=True)
                subprocess.run(['java', '-cp', str(root), 'buildcraft.robotics.item.TestIcon'], check=True)


if __name__ == '__main__':
    unittest.main()
