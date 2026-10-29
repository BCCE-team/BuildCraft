#!/usr/bin/env python3
"""26.X silicon source selection and native 26.3 recipe/menu/render paths."""
from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'scripts'),str(ROOT/'scripts/tests')]
from source_config import load_properties,target_layout
from source_layout import effective_source_files,_materialize_text_file,resolve_effective_source
from source_lookup import read_materialized_java
from minecraft_compat_fixture import parse_sources
from silicon_26_fixture import execute

SILICON='src/main/java/buildcraft/silicon'

class Silicon26Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='bc-silicon-26-test-')
        cls.roots={}
        props=load_properties()
        for version in ('26.1.2','26.2','26.3'):
            target=version+'-neoforge'
            layout=target_layout(target,props)
            downports=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
            dest=Path(cls.temp.name)/version
            for logical,source in sorted(effective_source_files(layout,props,SILICON).items()):
                _materialize_text_file(source,dest/logical,logical_relative=logical,
                    minecraft=props[f'target.{target}.deps.minecraft'],family=layout.family,
                    platform=layout.platform,preprocess=True,
                    native_source=any(source.is_relative_to(root) for root in downports))
            cls.roots[version]=dest/SILICON

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def java(self,version,relative):
        return (self.roots[version]/relative).read_text(encoding='utf-8')

    def files(self, version):
        root = self.roots[version]
        return {(Path(SILICON) / p.relative_to(root)).as_posix() for p in root.rglob("*.java")}

    def test_lens_cutout_frames_and_translucent_glass_across_targets(self):
        """Execute the real Stonecutter-selected lens baker against shading/alpha stubs."""
        from lens_render_fixture import execute_target
        all_targets = (
            '1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge',
            '1.21.11-neoforge', '26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge',
        )
        logical = SILICON + '/client/model/plug/PlugBakerLens.java'
        props = load_properties()
        for target in all_targets:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                source = resolve_effective_source(layout, props, logical)
                self.assertIsNotNone(source)
                with tempfile.TemporaryDirectory(prefix='bcce-lens-selected-') as tmp:
                    dest = Path(tmp) / logical
                    downports = tuple(
                        root for root in (layout.family_downport_root, layout.family_platform_downport_root) if root
                    )
                    _materialize_text_file(
                        source, dest, logical_relative=logical,
                        minecraft=props[f'target.{target}.deps.minecraft'],
                        family=layout.family, platform=layout.platform, preprocess=True,
                        native_source=any(source.is_relative_to(root) for root in downports),
                    )
                    code = dest.read_text(encoding='utf-8')
                    modern = target not in ('1.19.2-forge', '1.20.1-forge', '1.21.1-neoforge')
                    self.assertNotIn('//? if ', code)
                    if modern:
                        self.assertNotIn('colour_a = 64;', code)
                        self.assertIn('c.vertex_0.colour_a = q.vertex_0.colour_a;', code)
                    elif target == '1.21.1-neoforge':
                        # This target must keep cutout frames opaque, glass translucent,
                        # and shared source quads unchanged when using Iris.
                        self.assertIn('boolean glass = key.layer == RenderType.translucent();', code)
                        self.assertIn('MutableQuad baked = new MutableQuad(quad);', code)
                        self.assertIn('short alpha = (short) (glass ? 64 : 255);', code)
                        self.assertIn('vertex.colour_a = alpha;', code)
                    else:
                        self.assertIn('colour_a = 64;', code)
                    print(execute_target(target, dest, modern=modern), flush=True)

    def test_2612_silicon_sources_materialize(self):
        for relative in ("BCSilicon.java", "container/ContainerAssemblyTable.java"):
            with self.subTest(source=relative):
                self.assertTrue((self.roots["26.1.2"] / relative).is_file())

    def test_262_silicon_sources_materialize(self):
        for relative in ("BCSilicon.java", "container/ContainerAssemblyTable.java"):
            with self.subTest(source=relative):
                self.assertTrue((self.roots["26.2"] / relative).is_file())

    def test_native_263_file_selection(self):
        actual=self.files('26.3')
        self.assertTrue(actual)
        self.assertIn(SILICON+'/compat/SiliconDisplayContainer263.java',actual)
        self.assertIn(SILICON+'/client/render/SiliconDebugGeometry263.java',actual)
        for version in ('26.1.2','26.2'):
            self.assertNotIn(SILICON+'/compat/SiliconDisplayContainer263.java',self.files(version))
        self.assertNotIn(SILICON+'/client/render/SiliconDebugGeometry263.java',self.files('26.1.2'))
        self.assertIn(SILICON+'/client/render/SiliconDebugGeometry263.java',self.files('26.2'))

    def test_262_remains_on_original_recipe_pipeline(self):
        self.assertNotIn('RecipeProvider.asBootstrap',self.java('26.2','BCSilicon.java'))
        self.assertIn('buildRecipes(RecipeOutput writer)',self.java('26.2','BCSiliconRecipesProvider.java'))

    def test_preview_slots_use_vanilla_readonly_container(self):
        menus=('ContainerAdvancedCraftingTable','ContainerAssemblyTable',
               'ContainerIntegrationTable','ContainerProgrammingTable')
        for menu in menus:
            file=self.java('26.3','container/'+menu+'.java')
            self.assertIn('SiliconDisplayContainer263',file,menu)
            self.assertNotIn('new SlotDisplay(',file,menu)
            self.assertNotIn('net.neoforged.neoforge.items.IItemHandler',file,menu)
        adapter=self.java('26.3','compat/SiliconDisplayContainer263.java')
        self.assertIn('implements Container',adapter)
        self.assertIn('new Slot(this, index, x, y)',adapter)
        self.assertIn('mayPlace(ItemStack stack) { return false; }',adapter)
        self.assertIn('mayPickup(Player player) { return false; }',adapter)
        self.assertIn('item.copy()',adapter)

    def test_native_display_java_probe(self):
        result=execute(self.roots['26.3']/'compat/SiliconDisplayContainer263.java')
        self.assertIn('assertions PASS',result)
        print(result,flush=True)

    def test_debug_target_public_access_by_target(self):
        relative='src/main/java/buildcraft/lib/debug/BCAdvDebugging.java'
        props=load_properties()
        for version in ('26.1.2','26.2','26.3'):
            with self.subTest(target=version):
                layout=target_layout(version+'-neoforge',props)
                source=resolve_effective_source(layout,props,relative)
                self.assertIsNotNone(source)
                downports=tuple(root for root in (layout.family_downport_root,layout.family_platform_downport_root) if root)
                with tempfile.TemporaryDirectory(prefix='bc-debug-target-') as td:
                    root=Path(td)
                    dest=root/relative
                    _materialize_text_file(source,dest,logical_relative=relative,
                        minecraft=props[f'target.{version}-neoforge.deps.minecraft'],
                        family=layout.family,platform=layout.platform,preprocess=True,
                        native_source=any(source.is_relative_to(d) for d in downports))
                    text=dest.read_text(encoding='utf-8')
                    if version=='26.1.2':
                        self.assertNotIn('getClientDebugTarget(',text)
                        continue
                    self.assertIn('public static IAdvDebugTarget getClientDebugTarget()',text)
                    laser=self.java(version,'client/render/SiliconDebugGeometry263.java')
                    self.assertIn('BCAdvDebugging.getClientDebugTarget()',laser)
                    self.assertNotIn('BCAdvDebugging.INSTANCE.targetClient',laser)
                    interface=root/'buildcraft/lib/debug/IAdvDebugTarget.java'
                    interface.parent.mkdir(parents=True,exist_ok=True)
                    interface.write_text('package buildcraft.lib.debug; public interface IAdvDebugTarget { void disableDebugging(); boolean doesExistInWorld(); void sendDebugState(); }')
                    probe=root/'buildcraft/silicon/client/render/DebugAccessProbe.java'
                    probe.parent.mkdir(parents=True,exist_ok=True)
                    probe.write_text("""package buildcraft.silicon.client.render;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.debug.IAdvDebugTarget;
public final class DebugAccessProbe {
    public static void main(String[] args) {
        IAdvDebugTarget target = new IAdvDebugTarget() {
            public void disableDebugging() {}
            public boolean doesExistInWorld() {return true;}
            public void sendDebugState() {}
        };
        BCAdvDebugging.setClientDebugTarget(target);
        if (BCAdvDebugging.getClientDebugTarget() != target) throw new AssertionError();
        BCAdvDebugging.setClientDebugTarget(null);
        if (BCAdvDebugging.getClientDebugTarget() != null) throw new AssertionError();
        System.out.println("debug target public accessor PASS");
    }
}
""")
                    classes=root/'classes'
                    compiled=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(classes),
                        str(dest),str(interface),str(probe)],capture_output=True,text=True,timeout=30)
                    self.assertEqual(0,compiled.returncode,compiled.stderr)
                    executed=subprocess.run(['java','-cp',str(classes),
                        'buildcraft.silicon.client.render.DebugAccessProbe'],capture_output=True,text=True,timeout=30)
                    self.assertEqual(0,executed.returncode,executed.stderr)
                    self.assertIn('debug target public accessor PASS',executed.stdout)

    def test_debugger_geometry_extraction_and_submission(self):
        laser=self.java('26.3','client/render/AdvDebuggerLaser.java')
        event=self.java('26.3','client/render/SiliconDebugGeometry263.java')
        self.assertIn('SiliconDebugGeometry263.isCapturing()',laser)
        self.assertIn('BCWorldGeometry.buffer(RenderCompat.solid())',laser)
        self.assertNotIn('renderBuffers().bufferSource()',laser)
        self.assertIn('BCWorldGeometry.capture(',event)
        self.assertIn('event.getRenderState().setRenderData(',event)
        self.assertIn('event.getLevelRenderState().getRenderData(',event)
        self.assertIn('BCWorldGeometry.submit(',event)
        self.assertIn('pose.translate(-camera.x, -camera.y, -camera.z)',event)

    def test_silicon_gameplay_components_available_in_both_native_targets(self):
        for version in ("26.2", "26.3"):
            for relative in ("tile/TileAssemblyTable.java", "tile/TileIntegrationTable.java",
                             "tile/TileLaser.java", "tile/TileProgrammingTable_Neptune.java",
                             "gate/GateVariant.java", "recipe/FacadeAssemblyRecipes.java"):
                with self.subTest(version=version, source=relative):
                    self.assertTrue((self.roots[version] / relative).is_file())

    def test_java_syntax_all_targets(self):
        result=parse_sources([self.roots[version] for version in ('26.1.2','26.2','26.3')])
        print(result,flush=True)
        self.assertGreaterEqual(result.count('0 errors'), 3, result)



# ---- 26.3 assembly menu previews and synchronized result slots ----

from source_layout import load_properties, resolve_effective_source, target_layout
from silicon_26_fixture import STUBS as DISPLAY_STUBS

ASSEMBLY_PREVIEW_LOGICAL = 'src/main/java/buildcraft/silicon/container/ContainerAssemblyTable.java'
ASSEMBLY_PREVIEW_DISPLAY = 'src/main/java/buildcraft/silicon/compat/SiliconDisplayContainer263.java'

ASSEMBLY_PREVIEW_STUBS = {
    k: value for k, value in DISPLAY_STUBS.items()
    if k != 'buildcraft/silicon/compat/SiliconDisplayProbe'
}
ASSEMBLY_PREVIEW_STUBS.update({
    'buildcraft/lib/tile/item/IItemHandlerAdv': '''package buildcraft.lib.tile.item;
public interface IItemHandlerAdv {}''',
    'buildcraft/lib/tile/item/ItemHandlerSimple': '''package buildcraft.lib.tile.item;
import net.minecraft.world.item.ItemStack;
public class ItemHandlerSimple implements IItemHandlerAdv {
 private final ItemStack[] contents;
 public ItemHandlerSimple(int size){contents=new ItemStack[size];
   for(int i=0;i<size;i++) contents[i]=ItemStack.EMPTY;}
 public int getSlots(){return contents.length;}
 public ItemStack getStackInSlot(int index){return contents[index];}
 public void setStackInSlot(int index,ItemStack item){contents[index]=item;}
}''',
    'buildcraft/lib/gui/slot/SlotBase': '''package buildcraft.lib.gui.slot;
import buildcraft.lib.tile.item.IItemHandlerAdv;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.compat.SiliconDisplayContainer263;
import net.minecraft.world.inventory.Slot;
public class SlotBase extends Slot {
 public SlotBase(IItemHandlerAdv handler,int index,int x,int y){
  super(new SiliconDisplayContainer263(new ItemHandlerSimple(12)),index,x,y);
 }
}''',
    'net/minecraft/world/entity/player/Inventory': '''package net.minecraft.world.entity.player;
public final class Inventory {}''',
    'net/minecraft/network/FriendlyByteBuf': '''package net.minecraft.network;
import net.minecraft.world.inventory.ContainerLevelAccess;
public final class FriendlyByteBuf {
 public final ContainerLevelAccess access;
 public FriendlyByteBuf(ContainerLevelAccess access){this.access=access;}
}''',
    'net/minecraft/world/inventory/ContainerLevelAccess': '''package net.minecraft.world.inventory;
import buildcraft.silicon.tile.TileAssemblyTable;
public final class ContainerLevelAccess {
 public final TileAssemblyTable tile;
 public ContainerLevelAccess(TileAssemblyTable tile){this.tile=tile;}
}''',
    'buildcraft/lib/gui/ContainerBCTile': '''package buildcraft.lib.gui;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.silicon.tile.TileAssemblyTable;
public abstract class ContainerBCTile<T extends TileAssemblyTable> {
 public final T tile;
 public final List<Slot> slots = new ArrayList<>();
 @SuppressWarnings("unchecked")
 protected ContainerBCTile(Object menuType, Inventory inv, int id, ContainerLevelAccess access){
  this.tile=(T)access.tile;
 }
 protected static ContainerLevelAccess createLevelAccess(Inventory inv, FriendlyByteBuf buf){return buf.access;}
 protected void addFullPlayerInventory(int y) {}
 protected void addSlot(Slot slot){slots.add(slot);}
}''',
    'buildcraft/silicon/BCSiliconGuis': '''package buildcraft.silicon;
public final class BCSiliconGuis {
 public static final Entry MENU_ASSEMBLY_TABLE = new Entry();
 public static final class Entry {public Object get(){return new Object();}}
}''',
    'buildcraft/silicon/tile/TileAssemblyTable': '''package buildcraft.silicon.tile;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.gui.ItemProvider;
public final class TileAssemblyTable {
 public final ItemStack[] previews = new ItemStack[12];
 public final ItemProvider display = new ItemProvider(i -> previews[i],12);
 public TileAssemblyTable(){for(int i=0;i<12;i++)previews[i]=ItemStack.EMPTY;}
}''',
    'buildcraft/silicon/container/AssemblyPreviewProbe': '''package buildcraft.silicon.container;
import buildcraft.silicon.tile.TileAssemblyTable;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
public final class AssemblyPreviewProbe {
 private static int checks=0;
 private static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  TileAssemblyTable tile = new TileAssemblyTable();
  ContainerLevelAccess access=new ContainerLevelAccess(tile);
  ContainerAssemblyTable client=new ContainerAssemblyTable(10,new Inventory(),new FriendlyByteBuf(access));
  ok(client.tile==tile);ok(client.slots.size()==24);
  Slot result=client.slots.get(12);
  ok(result.getItem().isEmpty());
  // Simulate NET_GUI_DATA arriving after the client menu has been constructed.
  tile.previews[0]=new ItemStack("purple_lens",1);
  ok(result.getItem().id.equals("purple_lens"));
  ok(result.getItem().getCount()==1);
  ok(!result.mayPickup(new Player()));
  ok(!result.mayPlace(new ItemStack("stone",1)));
  tile.previews[0]=new ItemStack("filter_blue_lens",2);
  ok(result.getItem().id.equals("filter_blue_lens"));
  ok(result.getItem().getCount()==2);
  ItemStack copy=result.getItem();copy.setCount(0);
  ok(result.getItem().getCount()==2);
  tile.previews[0]=ItemStack.EMPTY;
  ok(result.getItem().isEmpty());
  // The server constructor uses the same provider as the tile.
  tile.previews[0]=new ItemStack("gate_and",1);
  ContainerAssemblyTable server=new ContainerAssemblyTable(11,new Inventory(),
   new ItemHandlerSimple(12),tile.display,access);
  ok(server.slots.get(12).getItem().id.equals("gate_and"));
  System.out.println(checks+" assembly-preview assertions PASS");
 }
}''',
})


class Assembly263PreviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.properties = load_properties()

    def selected(self, target: str, logical: str) -> Path | None:
        return resolve_effective_source(target_layout(target, self.properties), self.properties, logical)

    def test_fix_is_specific_to_26_3(self):
        selected_263 = self.selected('26.3-neoforge', ASSEMBLY_PREVIEW_LOGICAL)
        selected_262 = self.selected('26.2-neoforge', ASSEMBLY_PREVIEW_LOGICAL)
        selected_2612 = self.selected('26.1.2-neoforge', ASSEMBLY_PREVIEW_LOGICAL)
        self.assertIsNotNone(selected_263)
        self.assertIsNotNone(selected_262)
        self.assertIsNotNone(selected_2612)
        self.assertIn('source-platforms/neoforge/', selected_263.as_posix())
        # Consolidated sources intentionally share one physical Java file, but
        # Stonecutter must still emit distinct 26.3 and earlier menu semantics.
        self.assertEqual(selected_263, selected_262)
        self.assertEqual(selected_263, selected_2612)
        java = read_materialized_java('26.3-neoforge', ASSEMBLY_PREVIEW_LOGICAL)
        self.assertIn('tile != null ? tile.display : display', java)
        for target in ('26.2-neoforge', '26.1.2-neoforge'):
            self.assertNotIn('tile != null ? tile.display : display',
                             read_materialized_java(target, ASSEMBLY_PREVIEW_LOGICAL))

    def test_actual_menu_tracks_post_open_synced_previews(self):
        menu = self.selected('26.3-neoforge', ASSEMBLY_PREVIEW_LOGICAL)
        display = self.selected('26.3-neoforge', ASSEMBLY_PREVIEW_DISPLAY)
        self.assertIsNotNone(menu)
        self.assertIsNotNone(display)
        with tempfile.TemporaryDirectory(prefix='bcce-assembly-263-menu-') as td:
            root = Path(td) / 'src'
            classes = Path(td) / 'classes'
            sources = []
            for path, text in ASSEMBLY_PREVIEW_STUBS.items():
                target = root / (path + '.java')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(text, encoding='utf-8')
                sources.append(str(target))
            for logical, java in ((ASSEMBLY_PREVIEW_LOGICAL, menu), (ASSEMBLY_PREVIEW_DISPLAY, display)):
                target = root / logical.removeprefix('src/main/java/')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(read_materialized_java('26.3-neoforge', logical), encoding='utf-8')
                sources.append(str(target))
            compiled = subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8',
                '-d', str(classes), *sources], capture_output=True, text=True, timeout=50)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            launched = subprocess.run(['java', '-ea', '-cp', str(classes),
                'buildcraft.silicon.container.AssemblyPreviewProbe'],
                capture_output=True, text=True, timeout=15)
            self.assertEqual(launched.returncode, 0, launched.stdout + launched.stderr)
            self.assertIn('assembly-preview assertions PASS', launched.stdout)


# ---- 26.X recipe decode before component registry binding ----




from source_layout import load_properties, resolve_effective_source, target_layout

ASSEMBLY_BOOTSTRAP_RECIPE_PATH = 'src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java'
ASSEMBLY_BOOTSTRAP_TARGETS = ('26.1.2-neoforge', '26.2-neoforge', '26.3-neoforge')


def ASSEMBLY_BOOTSTRAP_method_body(source: str, marker: str) -> str:
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class AssemblyRegistryBootstrap(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        props = load_properties()
        cls.sources = {}
        for target in ASSEMBLY_BOOTSTRAP_TARGETS:
            cls.sources[target] = read_materialized_java(target, ASSEMBLY_BOOTSTRAP_RECIPE_PATH)

    def test_all_26x_targets_defer_itemstack_creation_until_after_registry_load(self):
        for target, source in self.sources.items():
            with self.subTest(target=target):
                decoder = ASSEMBLY_BOOTSTRAP_method_body(source, 'private static AssemblyRecipe fromCodec(')
                self.assertNotIn('result.template()', decoder)
                self.assertIn('null, result, group', decoder)
                self.assertIn('private volatile ItemStackTemplate materializedOutput;', source)
                self.assertIn('private ItemStackTemplate resultTemplate()', source)
                # Every result consumer must resolve the deferred template rather than
                # bypassing it, including network, JEI previews and recipe displays.
                self.assertNotIn('output.create()', source)
                self.assertIn('LegacyResult.fromStack(recipe.resultTemplate().create())', source)
                self.assertIn('new SlotDisplay.ItemStackSlotDisplay(resultTemplate())', source)
                self.assertIn('ItemStack.STREAM_CODEC.encode(buffer, resultTemplate().create())', source)

    def test_real_java_template_does_not_access_unbound_components_during_decode(self):
        source = self.sources['26.3-neoforge']
        template = ASSEMBLY_BOOTSTRAP_method_body(source, '        private ItemStackTemplate template() {')
        deferred = ASSEMBLY_BOOTSTRAP_method_body(source, '    private ItemStackTemplate resultTemplate() {')
        fixture = '''import java.util.*;
public class AssemblyBootstrapProbe {
    static class Item { final boolean damageable; Item(boolean damaged){damageable=damaged;} }
    static class ItemStack {
        static boolean componentsBound;
        ItemStack(Item item) {
            if (!componentsBound) throw new NullPointerException("Components not bound yet");
            this.item=item;
        }
        final Item item;
        boolean isDamageableItem(){return item.damageable;}
    }
    static class CompoundTag {
        final Map<String,Integer> values = new HashMap<>();
        boolean isEmpty(){return values.isEmpty();}
        void remove(String key){values.remove(key);}
        int getInt(String key){return values.getOrDefault(key,0);}
    }
    static class NbtCompat {
        static boolean contains(CompoundTag tag,String key,int type){return tag.values.containsKey(key);}
        static int getInt(CompoundTag tag,String key){return tag.getInt(key);}
    }
    static class DataComponents {static final String DAMAGE="durability";static final String CUSTOM_DATA="custom";}
    static class CustomData {static CompoundTag of(CompoundTag tag){return tag;}}
    static class DataComponentPatch {
        static Builder builder(){return new Builder();}
        static class Builder {
            final Map<String,Object> components = new HashMap<>();
            void set(String key,Object value){components.put(key,value);}
            Map<String,Object> build(){return components;}
        }
    }
    static class ItemStackTemplate {
        final Map<String,Object> components;
        ItemStackTemplate(Item item,int count,Map<String,Object> components){this.components=components;}
    }
    static class LegacyResult {
        final Item item;
        final int count=1;
        final int damage;
        LegacyResult(Item item,int damage){this.item=item;this.damage=damage;}
        CompoundTag parseLegacyTag(){CompoundTag tag=new CompoundTag();tag.values.put("Damage",damage);tag.values.put("Other",42);return tag;}
TEMPLATE
    }
    static class Recipe {
        final LegacyResult deferredResult;
        final ItemStackTemplate eagerOutput;
        volatile ItemStackTemplate materializedOutput;
        Recipe(LegacyResult result){this.deferredResult=result;this.eagerOutput=null;}
DEFERRED
    }
    public static void main(String[] args) {
        Recipe[] recipes = new Recipe[34];
        for (int variant=0;variant<34;variant++) {
            // Simulates fromCodec / registry reload: no ItemStack may be created here.
            recipes[variant] = new Recipe(new LegacyResult(new Item(false),variant));
            if (recipes[variant].materializedOutput != null) throw new AssertionError("early result");
        }
        ItemStack.componentsBound=true;
        for (int variant=0;variant<34;variant++) {
            ItemStackTemplate output=recipes[variant].resultTemplate();
            if (output!=recipes[variant].resultTemplate()) throw new AssertionError("no memoization");
            if (output.components.containsKey(DataComponents.DAMAGE)) throw new AssertionError("lens durability");
            CompoundTag tag=(CompoundTag)output.components.get(DataComponents.CUSTOM_DATA);
            if (tag == null || tag.getInt("Damage")!=variant || tag.getInt("Other")!=42)
                throw new AssertionError("lost legacy lens variant " + variant);
        }
        ItemStackTemplate damageable=new Recipe(new LegacyResult(new Item(true),12)).resultTemplate();
        if (!Integer.valueOf(12).equals(damageable.components.get(DataComponents.DAMAGE)))
            throw new AssertionError("lost real durability");
        CompoundTag other=(CompoundTag)damageable.components.get(DataComponents.CUSTOM_DATA);
        if (other==null || other.values.containsKey("Damage") || other.getInt("Other")!=42)
            throw new AssertionError("incorrect durability custom data");
        System.out.println("deferred decode, 34 lens variants, durability and memoization PASS");
    }
}
'''.replace('TEMPLATE', template).replace('DEFERRED', deferred)
        with tempfile.TemporaryDirectory(prefix='bcce-assembly-bootstrap-') as tmp:
            work = Path(tmp)
            (work / 'AssemblyBootstrapProbe.java').write_text(fixture, encoding='utf-8')
            compile_result = subprocess.run(
                ['javac', '--release', '21', 'AssemblyBootstrapProbe.java'],
                cwd=work, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, compile_result.returncode, compile_result.stderr)
            run = subprocess.run(['java', 'AssemblyBootstrapProbe'], cwd=work,
                                 capture_output=True, text=True, timeout=20)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn('PASS', run.stdout)


if __name__ == '__main__':
    unittest.main()
