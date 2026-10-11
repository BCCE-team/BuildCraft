#!/usr/bin/env python3
"""Cross-target brush/facade resolver, world-data and network compatibility regressions."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_config import load_properties, target_layout
from source_layout import resolve_effective_source, _materialize_text_file

TARGETS = (
    '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
    '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
)


def effective(target: str, path: str) -> str:
    props = load_properties()
    layout = target_layout(target, props)
    logical = 'src/main/java/buildcraft/' + path + '.java'
    source = resolve_effective_source(layout, props, logical)
    if source is None:
        raise AssertionError(f'{target}: {path}: no effective source')
    with tempfile.TemporaryDirectory(prefix='bc-paint-source-') as td:
        out = Path(td) / 'Source.java'
        _materialize_text_file(source, out, logical_relative=logical,
                               minecraft=target.split('-')[0], family=layout.family,
                               platform=layout.platform, preprocess=True)
        return out.read_text(encoding='utf-8')


class PaintbrushAllTargetsTest(unittest.TestCase):
    def test_materialized_paint_handlers_and_bed_pair_are_present_in_all_seven(self):
        for target in TARGETS:
            with self.subTest(target=target):
                src = effective(target, 'lib/internal/block/CustomPaintHelper')
                for required in (
                    'BlockState recoloredState(', 'if (target == null || target == Blocks.AIR',
                    'result.hasProperty(property)', 'RECOLOR_MAP.put(BedBlock.class, "_bed")',
                    'recolorBed(world, pos, original, colored, paint)',
                    'partner.getValue(BedBlock.PART) == part',
                    'world.setBlock(partnerPos, coloredPartner, 2 | 16 | 32)',
                    'previous.saveWithFullMetadata(', 'reverted != null',
                    'replacement.setChanged();', 'world.sendBlockUpdated(pos, original, colored, 3);',
                    'world.isClientSide()',
                ):
                    self.assertIn(required, src, (target, required))
                self.assertNotIn('new ResourceLocation(paint.getName() + type)', src)
                self.assertNotIn('//?', src)
                if target.startswith(('26.', '1.21.11')):
                    self.assertIn('TagValueInput.create(', src)
                    self.assertIn('.map(net.minecraft.core.Holder.Reference::value).orElse(Blocks.AIR)', src)
                else:
                    self.assertNotIn('TagValueInput.create(', src)

    def test_brush_is_server_authoritative_and_only_consumes_durability_after_success(self):
        for target in TARGETS:
            with self.subTest(target=target):
                src = effective(target, 'core/item/ItemPaintbrush_BC8')
                self.assertIn('if (level.isClientSide()) return InteractionResult.SUCCESS;', src)
                self.assertIn('if (result != InteractionResult.SUCCESS)', src)
                self.assertIn('SoundUtil.playChangeColour(level, pos, type);', src)
                self.assertLess(src.index('if (level.isClientSide())'), src.index('stack.hurtAndBreak('))
                self.assertLess(src.index('if (result != InteractionResult.SUCCESS)'), src.index('stack.hurtAndBreak('))
                self.assertIn('ctx.getItemInHand()', src)

    def test_interaction_priority_prevents_bed_sleep_and_shulker_open(self):
        for target in TARGETS:
            with self.subTest(target=target):
                src = effective(target, 'core/item/ItemPaintbrush_BC8')
                core = effective(target, 'core/BCCore')
                self.assertIn('public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event)', src)
                self.assertIn('event.getItemStack().getItem() instanceof ItemPaintbrush_BC8 brush', src)
                self.assertIn('brush.type == null', src)
                for block in ('BedBlock', 'ShulkerBoxBlock', 'BannerBlock', 'WallBannerBlock'):
                    self.assertIn('instanceof ' + block, src)
                if target.endswith('forge') and not target.endswith('neoforge'):
                    self.assertIn('event.setUseBlock(Event.Result.DENY)', src)
                    self.assertIn('event.setUseItem(Event.Result.ALLOW)', src)
                    self.assertIn('MinecraftForge.EVENT_BUS.addListener(ItemPaintbrush_BC8::onRightClickBlock)', core)
                else:
                    self.assertIn('event.setUseBlock(TriState.FALSE)', src)
                    self.assertIn('event.setUseItem(TriState.TRUE)', src)
                    if target == '1.21.1-neoforge':
                        self.assertIn('net.neoforged.neoforge.common.util.TriState;', src)
                    else:
                        self.assertIn('net.minecraft.util.TriState;', src)
                    self.assertIn('NeoForge.EVENT_BUS.addListener(ItemPaintbrush_BC8::onRightClickBlock)', core)
                self.assertNotIn('//?', src)

    def test_facades_change_actual_material_and_sync_full_state(self):
        for target in TARGETS:
            with self.subTest(target=target):
                src = effective(target, 'silicon/plug/PluggableFacade')
                self.assertIn('CustomPaintHelper.recoloredState(', src)
                self.assertIn('FacadeStateManager.validFacadeStates.get(newMaterial)', src)
                self.assertIn('states = new FacadeInstance(', src)
                self.assertIn('states.writeToBuffer(buffer);', src)
                self.assertIn('states = FacadeInstance.readFromBuffer(buffer);', src)
                self.assertIn('isSideSolid = states.areAllStatesSolid(this.side);', src)
                sync=src[src.index('public void readSyncState('):src.index('// Networking')]
                self.assertIn('isSideSolid = states.areAllStatesSolid(this.side);', sync)
                self.assertNotIn('enableStainedGlassPhases()', src)
                holder = effective(target, 'transport/block/BlockPipeHolder')
                self.assertIn('PipeMessageReceiver.PLUGGABLES[', holder)
                self.assertIn('tile.setChanged();', holder)
                if not target.startswith('1.19.2'):
                    self.assertIn('getFacadeSideAt(tile, pos, hitPos, hitSide)', holder)
                    self.assertIn('.bounds().inflate(1.0E-4).contains(localHit)', holder)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK needed')
    def test_real_java_resolver_for_dyed_variants_and_compatible_properties(self):
        src = effective('1.21.1-neoforge', 'lib/internal/block/CustomPaintHelper')
        start = src.index('    @Nullable\n    public static BlockState recoloredState(')
        end = src.index('    /** Recolor world blocks', start)
        actual = src[start:end]
        fixture = '''import java.util.*;
public class PaintResolverProbe {
    @interface Nullable {}
    enum DyeColor { WHITE, RED, BLUE, LIGHT_BLUE;
        String getName() { return name().toLowerCase(Locale.ROOT); }
    }
    static class Property<T extends Comparable<T>> {
        final String name;
        Property(String name) { this.name = name; }
    }
    static final Property<String> FACING = new Property<>("facing");
    static final Property<String> PART = new Property<>("part");
    static final Property<Boolean> OCCUPIED = new Property<>("occupied");
    static final Property<Integer> ROTATION = new Property<>("rotation");
    static class Block {
        final String id;
        final Map<Property<?>, Comparable<?>> defaults = new HashMap<>();
        Block(String id) { this.id = id; }
        <T extends Comparable<T>> Block with(Property<T> p, T v) { defaults.put(p, v); return this; }
        BlockState defaultBlockState() { return new BlockState(this, defaults); }
    }
    static class BlockState {
        final Block block;
        final Map<Property<?>, Comparable<?>> values;
        BlockState(Block block, Map<Property<?>, Comparable<?>> values) {
            this.block = block; this.values = new HashMap<>(values);
        }
        Block getBlock() { return block; }
        Set<Property<?>> getProperties() { return values.keySet(); }
        boolean hasProperty(Property<?> p) { return values.containsKey(p); }
        @SuppressWarnings("unchecked")
        <T extends Comparable<T>> T getValue(Property<T> p) { return (T) values.get(p); }
        BlockState setValue(Property<?> p, Comparable<?> v) {
            var map = new HashMap<>(values); map.put(p, v);
            return new BlockState(block, map);
        }
    }
    static class ResourceLocation {
        final String ns, path;
        ResourceLocation(String ns, String path) { this.ns=ns;this.path=path; }
        String getNamespace() { return ns; }
        String getPath() { return path; }
        static ResourceLocation fromNamespaceAndPath(String ns, String path) {
            return new ResourceLocation(ns, path);
        }
    }
    static class Blocks { static final Block AIR = new Block("minecraft:air"); }
    static class Registry {
        final Map<String,Block> data = new HashMap<>();
        void add(Block b) { data.put(b.id, b); }
        ResourceLocation getKey(Block b) {
            String[] parts = b.id.split(":", 2);
            return new ResourceLocation(parts[0], parts[1]);
        }
        Block get(ResourceLocation id) { return data.get(id.ns + ":" + id.path); }
    }
    static class BuiltInRegistries { static final Registry BLOCK = new Registry(); }
    static final Map<Class<? extends Block>,String> RECOLOR_MAP = new HashMap<>();
''' + actual + '''
    static void assertTrue(boolean value, String caseName) {
        if (!value) throw new AssertionError(caseName);
    }
    static Block add(String id, Object... properties) {
        Block b = new Block(id);
        for (int i=0; i < properties.length; i+=2) {
            @SuppressWarnings("unchecked")
            Property prop = (Property) properties[i];
            b.with(prop, (Comparable) properties[i+1]);
        }
        BuiltInRegistries.BLOCK.add(b);
        return b;
    }
    public static void main(String[] args) {
        Block whiteWool = add("minecraft:white_wool");
        Block redWool = add("minecraft:red_wool");
        assertTrue(recoloredState(whiteWool.defaultBlockState(), DyeColor.RED).getBlock() == redWool, "wool");
        assertTrue(recoloredState(redWool.defaultBlockState(), DyeColor.RED) == null, "same color no-op");
        Block blueConcrete = add("minecraft:blue_concrete");
        assertTrue(recoloredState(blueConcrete.defaultBlockState(), DyeColor.RED) == null, "missing variant never air");
        Block glass = add("minecraft:glass");
        Block blueGlass = add("minecraft:blue_stained_glass");
        assertTrue(recoloredState(glass.defaultBlockState(), DyeColor.BLUE).getBlock() == blueGlass, "clear to stained");
        assertTrue(recoloredState(blueGlass.defaultBlockState(), null).getBlock() == glass, "clean brush clears glass");
        Block whiteBed = add("minecraft:white_bed", FACING, "west", PART, "foot", OCCUPIED, true);
        Block redBed = add("minecraft:red_bed", FACING, "north", PART, "head", OCCUPIED, false);
        BlockState b = recoloredState(whiteBed.defaultBlockState(), DyeColor.RED);
        assertTrue(b.getBlock() == redBed && b.getValue(FACING).equals("west")
                    && b.getValue(PART).equals("foot") && b.getValue(OCCUPIED), "bed properties");
        Block whiteBanner = add("minecraft:white_banner", ROTATION, 11);
        Block redBanner = add("minecraft:red_banner", ROTATION, 0);
        assertTrue(recoloredState(whiteBanner.defaultBlockState(), DyeColor.RED).getValue(ROTATION)==11, "banner rotation");
        Block plainShulker = add("minecraft:shulker_box", FACING, "east");
        Block redShulker = add("minecraft:red_shulker_box", FACING, "north");
        assertTrue(recoloredState(plainShulker.defaultBlockState(), DyeColor.RED).getValue(FACING).equals("east"), "shulker facing");
        Block modBlue = add("mod:blue_crystal");
        Block modRed = add("mod:red_crystal");
        assertTrue(recoloredState(modBlue.defaultBlockState(), DyeColor.RED).getBlock()==modRed, "mod namespace");
        System.out.println("9 Java recoloring behaviors passed");
    }
}
'''
        with tempfile.TemporaryDirectory(prefix='bc-paint-java-') as td:
            path = Path(td)
            (path / 'PaintResolverProbe.java').write_text(fixture)
            compile_result = subprocess.run(['javac', '-encoding', 'UTF-8', 'PaintResolverProbe.java'],
                                            cwd=path, capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            run = subprocess.run(['java', 'PaintResolverProbe'], cwd=path, capture_output=True,
                                 text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn('9 Java recoloring behaviors passed', run.stdout)


if __name__ == '__main__':
    unittest.main()
