#!/usr/bin/env python3
"""Guards for entity placement and error-safe assembly recipe decoding on 26.x.

These source-level checks do not replace Gradle compileJava or Minecraft GameTests.
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / 'scripts'), str(ROOT / 'scripts/tests')]
from minecraft_compat_fixture import parse_sources
from source_config import load_properties, target_layout
from source_layout import effective_source_files
from source_lookup import read_materialized_java, resolve_target_source

TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')
BUILDER = 'src/main/java/buildcraft/builders/snapshot/BlueprintBuilder.java'
SCHEMATIC = 'src/main/java/buildcraft/builders/snapshot/SchematicEntityDefault.java'
ASSEMBLY = 'src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java'


class EntityAndAssembly26xTests(unittest.TestCase):
    def test_entity_work_no_longer_needs_a_full_battery(self):
        for target in TARGETS:
            with self.subTest(target=target):
                code = read_materialized_java(target, BUILDER)
                self.assertIn('ENTITY_ACTION_MICRO_MJ = 4L * MjAmount.MICRO_MJ_PER_MJ;', code)
                self.assertIn('return tile.getBattery().getStored() >= ENTITY_ACTION_MICRO_MJ;', code)
                self.assertIn('tile.getBattery().extractPower(ENTITY_ACTION_MICRO_MJ, ENTITY_ACTION_MICRO_MJ);', code)
                self.assertIn('if (!hasEntityActionPower()) {', code)
                self.assertNotIn('if (!tile.getBattery().isFull()) {', code)
                self.assertIn('if (entity.isRemoved()) chargeEntityAction();', code)
                self.assertIn('if (needsMoreEntityPower) {', code)
                self.assertIn('failedEntityPlacements.add(schematicEntity);', code)
                self.assertIn('cancelPlaceTask(new PlaceTask(tile.getBuilderPos(), extracted.get(), 0));', code)
                # Accounted material is not charged again for a verified existing entity.
                self.assertIn('noneMatch(existing -> matchesPlacedEntity(schematicEntity, existing, level))', code)
                self.assertIn('chargeEntityAction();', code.split('if (!tile.needMeterial()) {', 1)[1])
        for target in ('1.21.1-neoforge', '1.21.11-neoforge'):
            with self.subTest(unmodified_target=target):
                code = read_materialized_java(target, BUILDER)
                self.assertIn('if (!tile.getBattery().isFull()) {', code)
                self.assertNotIn('ENTITY_ACTION_MICRO_MJ', code)

    def test_hanging_frames_match_anchor_and_direction_and_armor_stands_by_position(self):
        for target in TARGETS:
            with self.subTest(target=target):
                source = resolve_target_source(target, SCHEMATIC).as_posix()
                if target == '26.1.2-neoforge':
                    self.assertIn('source-downports/26.X/26.1.2/', source)
                else:
                    self.assertIn('source-family-platforms/26.X/neoforge/', source)
                entity = read_materialized_java(target, SCHEMATIC)
                builder = read_materialized_java(target, BUILDER)
                self.assertIn('public boolean matchesPlacedEntity(Entity entity, BlockPos basePos, double maxDistanceSquared)', entity)
                self.assertIn('entity instanceof HangingEntity hanging', entity)
                self.assertIn('hanging.getPos().equals(basePos.offset(hangingPos))', entity)
                self.assertIn('hanging.getDirection() != hangingFacing', entity)
                self.assertIn('ItemStack.isSameItemSameComponents(captured, installed)', entity)
                self.assertIn('captured.getCount() == installed.getCount()', entity)
                self.assertIn('entity.distanceToSqr(pos.add(Vec3.atLowerCornerOf(basePos))) < maxDistanceSquared;', entity)
                self.assertIn('standard.matchesPlacedEntity(entity, getBuildingInfo().offsetPos, MAX_ENTITY_DISTANCE)', builder)
                self.assertIn('isSchematicEntityPresent(schematicEntity, level)', builder)
                self.assertIn('BlueprintEntityData.restoreEquipment(entity, nbt)', entity)

    def test_recipe_codec_reports_malformed_lengths_as_data_result(self):
        for target in TARGETS:
            with self.subTest(target=target):
                recipe = read_materialized_java(target, ASSEMBLY)
                self.assertIn('import com.mojang.serialization.DataResult;', recipe)
                self.assertIn('FIELDS_CODEC.flatXmap(', recipe)
                self.assertIn('fields.ingredients().size() != fields.counts().size()', recipe)
                self.assertIn('return DataResult.error(() -> "Assembly recipe " + fields.id() + ": "', recipe)
                self.assertIn('ingredient_counts (must match)', recipe)
                self.assertIn('return DataResult.success(fromCodec(', recipe)
                self.assertIn('fields.counts().stream().anyMatch(', recipe)
                self.assertNotIn('Codec.INT.listOf().fieldOf("ingredient_counts").forGetter(AssemblyRecipe::ingredientCountList)', recipe)

    def test_all_bundled_26x_assembly_recipes_have_matching_ingredient_counts(self):
        props = load_properties()
        for target in TARGETS:
            with self.subTest(target=target):
                files = effective_source_files(target_layout(target, props), props, 'src/main/resources/data')
                recipes = []
                for path in files.values():
                    if 'recipe/' not in path.as_posix() or path.suffix != '.json':
                        continue
                    value = json.loads(path.read_text(encoding='utf-8'))
                    if isinstance(value, dict) and value.get('type') == 'buildcraftsilicon:assembly':
                        recipes.append((path, value))
                self.assertEqual(84, len(recipes))
                for path, value in recipes:
                    with self.subTest(path=path.as_posix(), target=target):
                        self.assertEqual(len(value['ingredients']), len(value['ingredient_counts']))
                        self.assertTrue(all(isinstance(count, int) and count > 0 for count in value['ingredient_counts']))

    def test_selected_sources_are_valid_java_syntax_on_all_26x_targets(self):
        for target in TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory(prefix='bcce-26-entities-') as tmp:
                dest = Path(tmp)
                for file in (BUILDER, SCHEMATIC, ASSEMBLY):
                    path = dest / Path(file).name
                    path.write_text(read_materialized_java(target, file), encoding='utf-8')
                self.assertIn('0 errors', parse_sources([dest]))

    def test_hanging_frame_anchor_and_armorstand_matching_execute(self):
        source = read_materialized_java('26.1.2-neoforge', SCHEMATIC)
        method = source.split('    public boolean matchesPlacedEntity(', 1)[1].split('    @Nonnull', 1)[0].strip()
        method = 'public boolean matchesPlacedEntity(' + method
        code = '''public final class EntityPlacementProbe {
            enum Direction { NORTH, EAST, SOUTH, WEST }
            record BlockPos(int x,int y,int z) {
                BlockPos offset(BlockPos o){return new BlockPos(x+o.x,y+o.y,z+o.z);}
            }
            record Vec3(double x,double y,double z) {
                static Vec3 atLowerCornerOf(BlockPos pos){return new Vec3(pos.x(),pos.y(),pos.z());}
                Vec3 add(Vec3 o){return new Vec3(x+o.x,y+o.y,z+o.z);}
                double distanceToSqr(Vec3 o){return Math.pow(x-o.x,2)+Math.pow(y-o.y,2)+Math.pow(z-o.z,2);}
            }
            static class Entity {
                final String type;
                Vec3 location;
                Entity(String type,Vec3 location){this.type=type;this.location=location;}
                double distanceToSqr(Vec3 pos){return location.distanceToSqr(pos);}
                Object registryAccess(){return new Object();}
            }
            static class HangingEntity extends Entity {
                final BlockPos hanging;
                final Direction dir;
                HangingEntity(String type,Vec3 location,BlockPos hanging,Direction dir){
                    super(type,location);this.hanging=hanging;this.dir=dir;
                }
                BlockPos getPos(){return hanging;}
                Direction getDirection(){return dir;}
            }
            static final class ItemStack {
                final String item; final int count;
                ItemStack(String item,int count){this.item=item;this.count=count;}
                static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item.equals(b.item);}
                int getCount(){return count;}
            }
            static final class ItemStackUtil {
                static ItemStack parseOptional(Object context,String item){return new ItemStack(item,1);}
            }
            static final class NbtCompat {
                static String getCompound(java.util.Map<String,String> tag,String key){return tag.getOrDefault(key,"empty");}
            }
            static final class ItemFrame extends HangingEntity {
                final ItemStack item;
                ItemFrame(String type,Vec3 location,BlockPos pos,Direction facing,ItemStack item) {
                    super(type,location,pos,facing); this.item=item;
                }
                ItemStack getItem(){return item;}
            }
            private final java.util.Map<String,String> entityNbt = new java.util.HashMap<>();
            private final String type;
            private final BlockPos hangingPos;
            private final Direction hangingFacing;
            private final Vec3 pos;
            EntityPlacementProbe(String type, BlockPos anchor, Direction dir, Vec3 local) {
                this.type=type; this.hangingPos=anchor; this.hangingFacing=dir; this.pos=local;
            }
            boolean matchesEntityType(Entity entity){return entity != null && type.equals(entity.type);}
''' + method + '''
            public static void main(String[] args) {
                var source = new EntityPlacementProbe("frame",new BlockPos(2,1,3),Direction.WEST,new Vec3(2.5,1.5,3.5));
                var origin = new BlockPos(100,60,100);
                source.entityNbt.put("Item", "chest");
                var correct = new ItemFrame("frame",new Vec3(99.99,59.99,99.99),
                    new BlockPos(102,61,103),Direction.WEST,new ItemStack("chest",1));
                if (!source.matchesPlacedEntity(correct, origin, 0.1))
                    throw new AssertionError("attached frame lost due to position rounding");
                if (source.matchesPlacedEntity(new HangingEntity("frame", correct.location,
                    new BlockPos(102,61,102),Direction.WEST),origin,0.1))
                    throw new AssertionError("wrong frame anchor");
                if (source.matchesPlacedEntity(new HangingEntity("frame",correct.location,
                    new BlockPos(102,61,103),Direction.EAST),origin,0.1))
                    throw new AssertionError("wrong frame facing");
                if (source.matchesPlacedEntity(new HangingEntity("painting",correct.location,
                    new BlockPos(102,61,103),Direction.WEST),origin,0.1))
                    throw new AssertionError("wrong entity type");
                if (source.matchesPlacedEntity(new ItemFrame("frame",correct.location,
                    new BlockPos(102,61,103),Direction.WEST,new ItemStack("stone",1)),origin,0.1))
                    throw new AssertionError("wrong framed item accepted");
                var armor = new EntityPlacementProbe("armor_stand",new BlockPos(0,0,0),Direction.NORTH,
                    new Vec3(3.5,1,2.5));
                if (!armor.matchesPlacedEntity(new Entity("armor_stand",new Vec3(103.5,61,102.5)),origin,0.1))
                    throw new AssertionError("armor stand position lost");
                if (armor.matchesPlacedEntity(new Entity("armor_stand",new Vec3(104.5,61,102.5)),origin,0.1))
                    throw new AssertionError("distant armor stand accepted");
                System.out.println("entity anchors and positions PASS");
            }
        }
'''
        with tempfile.TemporaryDirectory(prefix='bcce-26-anchor-') as tmp:
            path = Path(tmp) / 'EntityPlacementProbe.java'
            path.write_text(code, encoding='utf-8')
            compile = subprocess.run(['javac', str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile.returncode, 0, compile.stderr)
            execute = subprocess.run(['java', '-cp', tmp, 'EntityPlacementProbe'], capture_output=True, text=True, timeout=30)
            self.assertEqual(execute.returncode, 0, execute.stderr)
            self.assertIn('entity anchors and positions PASS', execute.stdout)

    def test_entity_battery_cost_and_charge_methods_execute(self):
        source = read_materialized_java('26.1.2-neoforge', BUILDER)
        cost = 'private static final long ENTITY_ACTION_MICRO_MJ = 4L * MjAmount.MICRO_MJ_PER_MJ;'
        method1 = '''private boolean hasEntityActionPower() {
        return tile.getBattery().getStored() >= ENTITY_ACTION_MICRO_MJ;
    }'''
        method2 = '''private void chargeEntityAction() {
        tile.getBattery().extractPower(ENTITY_ACTION_MICRO_MJ, ENTITY_ACTION_MICRO_MJ);
    }'''
        for snippet in (cost, method1, method2):
            self.assertIn(snippet, source)
        code = '''public final class EnergyProbe {
            static final class MjAmount {static final long MICRO_MJ_PER_MJ = 1000000L;}
            static final class Battery {
                long stored;
                Battery(long power){stored=power;}
                long getStored(){return stored;}
                long extractPower(long min,long max) {
                    if (stored < min) return 0;
                    long result = Math.min(stored,max);
                    stored -= result;
                    return result;
                }
            }
            static final class Tile {
                final Battery battery;
                Tile(long power){battery=new Battery(power);}
                Battery getBattery(){return battery;}
            }
            private final Tile tile;
            EnergyProbe(long power){tile=new Tile(power);}
            ''' + cost + '\n' + method1 + '\n' + method2 + '''
            public static void main(String[] args) {
                EnergyProbe empty = new EnergyProbe(0L);
                if (empty.hasEntityActionPower()) throw new AssertionError("empty battery");
                EnergyProbe shortByOne = new EnergyProbe(3999999L);
                if (shortByOne.hasEntityActionPower()) throw new AssertionError("insufficient MJ");
                EnergyProbe enough = new EnergyProbe(8000000L);
                if (!enough.hasEntityActionPower()) throw new AssertionError("power missing");
                enough.chargeEntityAction();
                if (enough.tile.battery.getStored() != 4000000L || !enough.hasEntityActionPower())
                    throw new AssertionError("first placement charge");
                enough.chargeEntityAction();
                if (enough.tile.battery.getStored() != 0 || enough.hasEntityActionPower())
                    throw new AssertionError("second placement charge");
                System.out.println("entity power gates PASS");
            }
        }
'''
        with tempfile.TemporaryDirectory(prefix='bcce-26-energy-') as tmp:
            path = Path(tmp) / 'EnergyProbe.java'
            path.write_text(code)
            compile = subprocess.run(['javac', str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile.returncode, 0, compile.stderr)
            execute = subprocess.run(['java', '-cp', tmp, 'EnergyProbe'], capture_output=True, text=True, timeout=30)
            self.assertEqual(execute.returncode, 0, execute.stderr)
            self.assertIn('entity power gates PASS', execute.stdout)


if __name__ == '__main__':
    unittest.main()
