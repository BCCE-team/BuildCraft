"""Execute real 26.X lib boundaries with offline, deliberately minimal API doubles.

The fixture checks BCCE state/dispatch contracts, not Mojang binary compatibility.
It contains no game or NeoForge classes from a downloaded distribution.
"""
from __future__ import annotations
from pathlib import Path
import subprocess
import tempfile
from transfer_fixture import STUBS as TRANSFER_STUBS
from transfer_probes import PROBES as TRANSFER_PROBES


def execute(java: Path, actual: tuple[str, ...], doubles: dict[str, str], probes: dict[str, str]) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-lib-26-probes-') as temporary:
        root = Path(temporary)
        sources = dict(doubles)
        for relative in actual:
            sources[relative] = (java / relative).read_text(encoding='utf-8')
        sources.update(probes)
        for relative, text in sources.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
        args = root / 'sources.args'
        args.write_text('\n'.join(str(root / name) for name in sources))
        classes = root / 'classes'
        compiled = subprocess.run(['javac', '--release', '21', '-d', str(classes), '@' + str(args)],
                                  text=True, capture_output=True, timeout=45)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        output = []
        for path in probes:
            main = path.removesuffix('.java').replace('/', '.')
            tested = subprocess.run(['java', '-ea', '-cp', str(classes), main],
                                    text=True, capture_output=True, timeout=30)
            if tested.returncode:
                raise AssertionError(tested.stdout + tested.stderr)
            output.append(tested.stdout.strip())
        return '\n'.join(output)


def render(java: Path, sdl: bool) -> str:
    names = ('javax/annotation/Nullable.java', 'com/mojang/blaze3d/vertex/PoseStack.java',
             'com/mojang/blaze3d/vertex/VertexConsumer.java',
             'net/minecraft/world/level/block/entity/BlockEntity.java', 'net/minecraft/world/phys/Vec3.java')
    selected = {name: source for name, source in TRANSFER_STUBS.items()
                if name in names or name.startswith('net/minecraft/client/renderer/')}
    selected.pop('net/minecraft/client/renderer/MultiBufferSource.java')
    selected = {name.replace('state/CameraRenderState', 'state/level/CameraRenderState'):
                source.replace('renderer.state.CameraRenderState', 'renderer.state.level.CameraRenderState')
                .replace('package net.minecraft.client.renderer.state;', 'package net.minecraft.client.renderer.state.level;')
                for name, source in selected.items()}
    consumer = 'com/mojang/blaze3d/vertex/VertexConsumer.java'
    selected[consumer] = selected[consumer].replace('VertexConsumer addVertex(float', 'default VertexConsumer setColor(int c){return setColor((c>>>16)&255,(c>>>8)&255,c&255,(c>>>24)&255);}VertexConsumer addVertex(float')
    if sdl:
        selected[consumer] = selected[consumer].replace('VertexConsumer addVertex(float',
            'VertexConsumer setUv3(float u,float v);VertexConsumer addVertex(float')
    selected['buildcraft/lib/compat/RenderCompat.java'] = '''package buildcraft.lib.compat;
import net.minecraft.client.renderer.rendertype.RenderType;
public class RenderCompat {static final RenderType SOLID=new RenderType("solid"),CUTOUT=new RenderType("cutout");
 public static RenderType solid(){return SOLID;}public static RenderType cutout(){return CUTOUT;}}'''
    path = 'buildcraft/lib/client/render/compat/GeometryProbe.java'
    probe = TRANSFER_PROBES[path].replace('renderer.state.CameraRenderState','renderer.state.level.CameraRenderState')
    if sdl:
        probe = probe.replace('public VertexConsumer addVertex(float x,float y,float z)',
                              'public VertexConsumer setUv3(float u,float v){return this;}public VertexConsumer addVertex(float x,float y,float z)')
    scope = r'''package buildcraft.lib.client.render.compat;
import java.util.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.minecraft.render.BCVertexBuffers;
public class GeometryScopeProbe {
 static int checks;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static void rejects(Runnable call,String name){try{call.run();throw new AssertionError(name);}catch(IllegalStateException expected){checks++;}}
 public static void main(String[]args) throws Exception {
  RenderType a=new RenderType("first"), b=new RenderType("second");
  rejects(()->BCWorldGeometry.buffer(a),"draw outside extraction must fail");
  var first=new CapturedBlockEntityRenderer.RecordingBuffers();
  var second=new CapturedBlockEntityRenderer.RecordingBuffers();
  try(var outer=BCWorldGeometry.bind(first)) {
   BCWorldGeometry.buffer(a).addVertex(1,2,3).setColor(0x80334455);
   try(var inner=BCWorldGeometry.bind(second)) {
    rejects(outer::close,"cannot close outer scope first");
    BCWorldGeometry.buffer(b).addVertex(4,5,6);BCWorldGeometry.flush();BCWorldGeometry.flush();
   }
   check(BCWorldGeometry.buffer(a)==first.getBuffer(a),"restore parent capture");
   try {BCWorldGeometry.capture(()->{throw new IllegalArgumentException("test");});}
   catch(IllegalArgumentException expected){checks++;}
   check(BCWorldGeometry.buffer(a)==first.getBuffer(a),"exception restores parent capture");
   boolean[] caught={false};
   Thread t=new Thread(()->{try{outer.close();}catch(IllegalStateException expected){caught[0]=true;}});
   t.start();t.join();check(caught[0],"scope cannot migrate threads");
  }
  rejects(BCWorldGeometry::flush,"scope removed after close");
  check(first.finish().size()==1&&second.finish().size()==1,"nested captures isolated");
  check(second.finish().get(0).vertices().size()==1,"repeated flush does not duplicate final vertex");
  var snapshot=first.finish();var vertex=snapshot.get(0).vertices().get(0);
  check(vertex.a()==128&&vertex.r()==51&&vertex.g()==68&&vertex.b()==85,"ARGB channels exact");
  first.getBuffer(a).addVertex(7,8,9);
  check(snapshot.get(0).vertices().size()==1,"snapshot does not alias mutable recorder");
  try {snapshot.clear();throw new AssertionError("snapshot is mutable");} catch(UnsupportedOperationException expected){checks++;}
  try {snapshot.get(0).vertices().clear();throw new AssertionError("vertices are mutable");} catch(UnsupportedOperationException expected){checks++;}
  /* UV3 */
  int[] samples={0};
  class LegacyProbe implements LegacyBlockEntityRenderer<BlockEntity>, LegacyLaserBlockEntityRenderer<BlockEntity> {
   public void render(BlockEntity tile,float tick,PoseStack pose,BCVertexBuffers buffers,int light,int overlay){throw new AssertionError("full render would duplicate geometry");}
   public void renderLasers(BlockEntity tile,float tick,PoseStack pose,BCVertexBuffers buffers,int light,int overlay){
    samples[0]++;
    buffers.getBuffer(RenderCompat.cutout()).addVertex(tile.frame,0,0);
    buffers.getBuffer(new RenderType("ignored-translucent")).addVertex(999,0,0);
   }
  }
  var legacy=new LegacyProbe();
  BlockEntity tile=new BlockEntity();var state=legacy.createRenderState();legacy.extractRenderState(tile,state,0,new Vec3(0,0,0),null);
  tile.frame=999;int[] submits={0};
  SubmitNodeCollector collector=(pose,type,geometry)->{submits[0]++;check(type.equals(RenderCompat.cutout()),"laser-only layers");};
  legacy.submit(state,new PoseStack(),collector,new CameraRenderState());
  check(samples[0]==1&&submits[0]==1,"render sampled once in extraction, not submission");
  tile.removed=true;legacy.extractRenderState(tile,state,0,new Vec3(0,0,0),null);
  legacy.submit(state,new PoseStack(),collector,new CameraRenderState());check(submits[0]==1,"removed tile clears old laser geometry");
  System.out.println("Geometry scope probes: "+checks+" assertions (offline API doubles)");
 }
}
'''
    if sdl:
        scope = scope.replace('/* UV3 */', '''var decals=new CapturedBlockEntityRenderer.RecordingBuffers();
  decals.getBuffer(a).addVertex(0,0,0).setUv3(.125f,.75f);
  decals.getBuffer(a).addVertex(1,0,0);
  var ds=decals.finish().get(0).vertices();
  check(ds.get(0).decalU()==.125f&&ds.get(0).decalV()==.75f,"decal UVs captured");
  check(ds.get(1).decalU()==0&&ds.get(1).decalV()==0,"decal UVs reset per vertex");''')
    actual = tuple('buildcraft/lib/' + name + '.java' for name in (
        'client/render/compat/CapturedBlockEntityRenderer', 'client/render/compat/BCWorldGeometry',
        'client/render/compat/LegacyBlockEntityRenderer', 'client/render/laser/LegacyLaserBlockEntityRenderer',
        'compat/minecraft/render/BCGeometryRenderer', 'compat/minecraft/render/BCVertexBuffers'))
    return execute(java, actual, selected, {path: probe, 'buildcraft/lib/client/render/compat/GeometryScopeProbe.java': scope})


def input_and_text(java: Path, sdl: bool) -> str:
    from minecraft_compat_fixture import stubs
    all_stubs = stubs(True)
    keep = ('javax/annotation/Nullable.java', 'net/minecraft/client/input/KeyEvent.java',
            'net/minecraft/client/input/MouseButtonEvent.java', 'net/minecraft/client/input/MouseButtonInfo.java',
            'net/minecraft/client/input/CharacterEvent.java', 'com/mojang/blaze3d/platform/InputConstants.java',
            'net/minecraft/client/gui/components/events/GuiEventListener.java',
            'net/minecraft/world/inventory/AbstractContainerMenu.java', 'net/minecraft/world/entity/player/Inventory.java',
            'net/minecraft/network/chat/Component.java')
    doubles = {name: all_stubs[name] for name in keep}
    doubles['net/minecraft/client/input/CharacterEvent.java'] = 'package net.minecraft.client.input;public record CharacterEvent(int codepoint){}'
    doubles['net/minecraft/client/input/MouseButtonEvent.java'] = '''package net.minecraft.client.input;
public record MouseButtonEvent(double x,double y,MouseButtonInfo info){public int button(){return info.button();}public boolean hasShiftDown(){return (info.modifiers()&1)!=0;}}'''
    if sdl:
        doubles['net/minecraft/client/input/KeyEvent.java']=doubles['net/minecraft/client/input/KeyEvent.java'].replace('scancode','keycode')
        doubles['com/mojang/blaze3d/platform/InputConstants.java']=doubles['com/mojang/blaze3d/platform/InputConstants.java'].replace('scancode()','keycode()')
        doubles['org/lwjgl/sdl/SDLMouse.java']='package org.lwjgl.sdl;public class SDLMouse {public static final int SDL_BUTTON_LEFT=1,SDL_BUTTON_MIDDLE=2,SDL_BUTTON_RIGHT=3;}'
    doubles['net/minecraft/client/gui/components/events/GuiEventListener.java'] = '''package net.minecraft.client.gui.components.events;
import net.minecraft.client.input.*;
public interface GuiEventListener {default boolean mouseClicked(MouseButtonEvent e,boolean d){return false;}default boolean keyPressed(KeyEvent e){return false;}default boolean charTyped(CharacterEvent e){return false;}}'''
    doubles['net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.java'] = '''package net.minecraft.client.gui.screens.inventory;
import net.minecraft.client.gui.components.events.GuiEventListener;import net.minecraft.client.input.*;
import net.minecraft.world.inventory.AbstractContainerMenu;import net.minecraft.world.entity.player.Inventory;import net.minecraft.network.chat.Component;
public abstract class AbstractContainerScreen<T extends AbstractContainerMenu> implements GuiEventListener {
 public int nativeClicks,nativeKeys,nativeChars,nativeReleases,nativeDrags;
 protected AbstractContainerScreen(T m,Inventory i,Component t){}protected AbstractContainerScreen(T m,Inventory i,Component t,int w,int h){}
 public boolean mouseClicked(MouseButtonEvent e,boolean d){nativeClicks++;return true;}public boolean mouseDragged(MouseButtonEvent e,double x,double y){nativeDrags++;return true;}
 public boolean mouseReleased(MouseButtonEvent e){nativeReleases++;return true;}public boolean keyPressed(KeyEvent e){nativeKeys++;return true;}public boolean charTyped(CharacterEvent e){nativeChars++;return true;}
}'''
    doubles['net/minecraft/network/chat/Style.java'] = '''package net.minecraft.network.chat;
public record Style(Integer color,boolean bold,boolean italic,boolean underlined,boolean strikethrough,boolean obfuscated){
 public static final Style EMPTY=new Style(null,false,false,false,false,false);
 public Style withColor(int x){return new Style(x,bold,italic,underlined,strikethrough,obfuscated);}
 public Style withBold(boolean x){return new Style(color,x,italic,underlined,strikethrough,obfuscated);}
 public Style withItalic(boolean x){return new Style(color,bold,x,underlined,strikethrough,obfuscated);}
 public Style withUnderlined(boolean x){return new Style(color,bold,italic,x,strikethrough,obfuscated);}
 public Style withStrikethrough(boolean x){return new Style(color,bold,italic,underlined,x,obfuscated);}
 public Style withObfuscated(boolean x){return new Style(color,bold,italic,underlined,strikethrough,x);}
}'''
    probe = r'''import buildcraft.lib.compat.minecraft.gui.*;import buildcraft.lib.compat.minecraft.text.BCTextFormat;
import net.minecraft.client.input.*;import net.minecraft.network.chat.Style;import net.minecraft.world.inventory.AbstractContainerMenu;
public class InputTextProbe {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static class Screen extends BCContainerScreen<AbstractContainerMenu> {
  int button,key,scan;boolean consume,shift;
  Screen(){super(null,null,null);}public boolean mouseClicked(double x,double y,int b){button=b;shift=BCInputState.shiftDown();return consume;}
  public boolean mouseDragged(double x,double y,int b,double dx,double dy){button=b;return consume;}
  public boolean mouseReleased(double x,double y,int b){button=b;return consume;}
  public boolean keyPressed(int k,int s,int m){key=k;scan=s;return consume;}
 }
 public static void main(String[]args){
  for(int i=0;i<8;i++)check(BCGuiInput.legacyButton(BCGuiInput.nativeButton(i))==i,"button round trip "+i);
  Screen screen=new Screen();var click=new MouseButtonEvent(1,2,new MouseButtonInfo(BCGuiInput.nativeButton(1),1));
  check(screen.mouseClicked(click,false),"vanilla fallback consumes click");check(screen.button==1&&screen.nativeClicks==1&&screen.shift,"right click mapping + one fallback + modifiers");
  check(!BCInputState.shiftDown(),"modifiers restored after callback");screen.consume=true;screen.mouseClicked(click,false);check(screen.nativeClicks==1,"no duplicate vanilla click after BC consumes it");
  screen.consume=false;screen.mouseDragged(click,2,3);screen.mouseReleased(click);check(screen.button==1&&screen.nativeDrags==1&&screen.nativeReleases==1,"drag and release mapping");
  screen.keyPressed(new KeyEvent(42,73,0));check(screen.key==42&&screen.scan==73&&screen.nativeKeys==1,"native physical and logical keys preserved");
  screen.charTyped(new CharacterEvent(0x1F600));check(screen.nativeChars==1,"supplementary input reaches native widget, no truncation");
  try(var shift=BCInputState.pushShift(true)){screen.mouseClicked(new MouseButtonEvent(0,0,new MouseButtonInfo(BCGuiInput.nativeButton(0),0)),false);check(BCInputState.shiftDown(),"outer modifier scope restored");}
  for(int i=0;i<16;i++){BCTextFormat f=BCTextFormat.getById(i);check(f!=null&&f.isColor()&&f.getId()==i,"colour identity "+i);check(f==BCTextFormat.getByCode(f.getChar()),"legacy code "+i);check(f.toString().equals("\u00a7"+f.getChar()),"section-sign token "+i);}
  check(BCTextFormat.getById(-1)==BCTextFormat.RESET&&BCTextFormat.getById(16)==null,"non-colour ids");
  check(BCTextFormat.getByCode('Z')==null&&BCTextFormat.getByName(null)==null,"unknown tokens");
  check(BCTextFormat.getByName("DARK_RED")==BCTextFormat.DARK_RED&&BCTextFormat.getByCode('A')==BCTextFormat.GREEN,"case-insensitive names and codes");
  Style style=BCTextFormat.BLUE.apply(Style.EMPTY.withBold(true));check(style.bold()&&style.color()==0x5555FF,"colour preserves parent formatting");
  style=BCTextFormat.ITALIC.apply(BCTextFormat.UNDERLINE.apply(style));check(style.italic()&&style.underlined()&&style.bold(),"additive formatting");
  check(BCTextFormat.RESET.apply(style)==Style.EMPTY,"reset clears legacy formatting");
  System.out.println("Input/text probes: "+checks+" assertions (offline API doubles)");
 }
}'''
    actual=tuple('buildcraft/lib/compat/minecraft/'+p+'.java' for p in ('gui/BCGuiInput','gui/BCContainerScreen','gui/BCWidgetInput','gui/BCInputState','text/BCTextFormat'))
    return execute(java,actual,doubles,{'InputTextProbe.java':probe})


def fuel(java: Path) -> str:
    doubles: dict[str, str] = {}
    def put(path: str, body: str) -> None:
        doubles[path + '.java'] = body
    for name in ('Nonnull', 'Nullable'):
        put('javax/annotation/' + name, 'package javax.annotation;public @interface ' + name + '{}')
    put('net/minecraft/core/HolderLookup', 'package net.minecraft.core;public interface HolderLookup {interface Provider{}}')
    put('net/minecraft/core/BlockPos', 'package net.minecraft.core;public record BlockPos(int x,int y,int z){}')
    put('net/minecraft/world/phys/Vec3', 'package net.minecraft.world.phys;import net.minecraft.core.BlockPos;public record Vec3(double x,double y,double z){public static Vec3 atCenterOf(BlockPos p){return new Vec3(p.x()+.5,p.y()+.5,p.z()+.5);}}')
    put('net/minecraft/core/component/DataComponentType', 'package net.minecraft.core.component;public record DataComponentType<T>(String id){}')
    put('test/Fuel', 'package test;import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;public record Fuel(ResolvableInt burnTime){}')
    put('net/minecraft/core/component/DataComponents', '''package net.minecraft.core.component;
import net.minecraft.world.item.component.ItemAttributeModifiers;import net.minecraft.world.item.equipment.Equippable;
public class DataComponents {
 public static final DataComponentType<ItemAttributeModifiers> ATTRIBUTE_MODIFIERS=new DataComponentType<>("armor");
 public static final DataComponentType<Equippable> EQUIPPABLE=new DataComponentType<>("equippable");
 public static final DataComponentType<test.Fuel> COOKING_FUEL=new DataComponentType<>("cooking_fuel");
}''')
    put('net/minecraft/world/entity/EquipmentSlot', 'package net.minecraft.world.entity;public enum EquipmentSlot {HEAD,CHEST,LEGS,FEET,MAINHAND,OFFHAND}')
    put('net/minecraft/world/entity/ai/attributes/Attributes', 'package net.minecraft.world.entity.ai.attributes;public class Attributes {public static final Object ARMOR=new Object();}')
    put('net/minecraft/world/item/component/ItemAttributeModifiers', '''package net.minecraft.world.item.component;
import net.minecraft.world.entity.EquipmentSlot;public class ItemAttributeModifiers {public static final ItemAttributeModifiers EMPTY=new ItemAttributeModifiers();public double compute(Object a,double d,EquipmentSlot s){return 0;}}''')
    put('net/minecraft/world/item/equipment/Equippable', 'package net.minecraft.world.item.equipment;import net.minecraft.world.entity.EquipmentSlot;public record Equippable(EquipmentSlot slot){}')
    put('net/minecraft/tags/ItemTags', 'package net.minecraft.tags;public class ItemTags {' + ''.join('public static final String '+name+'="'+name+'";' for name in ('HEAD_ARMOR','CHEST_ARMOR','LEG_ARMOR','FOOT_ARMOR','SWORDS','AXES','PICKAXES','SHOVELS')) + '}')
    put('net/neoforged/neoforge/common/ItemAbility', 'package net.neoforged.neoforge.common;public record ItemAbility(String id){public static ItemAbility get(String id){return new ItemAbility(id);}}')
    put('net/minecraft/world/item/ItemStack', '''package net.minecraft.world.item;
import java.util.*;import net.minecraft.core.component.DataComponentType;import net.neoforged.neoforge.common.ItemAbility;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack("");public final String id;public final Map<DataComponentType<?>,Object> data=new HashMap<>();public final Set<String> tags=new HashSet<>();public ItemStackTemplate remainder;
 public ItemStack(String id){this.id=id;}public boolean isEmpty(){return id.isEmpty();}public boolean is(String tag){return tags.contains(tag);}
 public boolean canPerformAction(ItemAbility ability){return tags.contains(ability.id());}public boolean has(DataComponentType<?> t){return data.containsKey(t);}
 @SuppressWarnings("unchecked") public <T>T get(DataComponentType<T> t){return (T)data.get(t);}public <T>T getOrDefault(DataComponentType<T> t,T fallback){T v=get(t);return v==null?fallback:v;}
 public ItemStackTemplate getCraftingRemainder(){return remainder;}
}''')
    put('net/minecraft/world/item/ItemStackTemplate', 'package net.minecraft.world.item;public record ItemStackTemplate(String id){public ItemStack create(){return new ItemStack(id);}}')
    for path in ('Tag','NbtOps'):
        put('net/minecraft/nbt/'+path,'package net.minecraft.nbt;public class '+path+'{}')
    put('net/minecraft/nbt/CompoundTag','package net.minecraft.nbt;public class CompoundTag extends Tag{}')
    put('buildcraft/lib/misc/ItemStackUtil','package buildcraft.lib.misc;public class ItemStackUtil{}')
    put('buildcraft/lib/compat/minecraft/components/BCItemData','''package buildcraft.lib.compat.minecraft.components;import net.minecraft.world.item.ItemStack;import net.minecraft.nbt.*;import net.minecraft.core.HolderLookup;
public class BCItemData {public static CompoundTag save(ItemStack s,HolderLookup.Provider p){return new CompoundTag();}public static ItemStack load(HolderLookup.Provider p,Tag t){return ItemStack.EMPTY;}}''')
    put('net/minecraft/world/Container','package net.minecraft.world;public interface Container{}')
    put('net/minecraft/world/level/Level','package net.minecraft.world.level;public class Level{}')
    put('net/minecraft/server/level/ServerLevel','package net.minecraft.server.level;public class ServerLevel extends net.minecraft.world.level.Level{}')
    put('net/minecraft/world/level/block/state/BlockState','package net.minecraft.world.level.block.state;public class BlockState{}')
    put('net/minecraft/world/level/block/entity/BlockEntity','''package net.minecraft.world.level.block.entity;
import net.minecraft.world.level.Level;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.core.BlockPos;
public record BlockEntity(Level getLevel,BlockState getBlockState,BlockPos getBlockPos){}''')
    put('net/minecraft/world/level/storage/loot/parameters/LootContextParams','package net.minecraft.world.level.storage.loot.parameters;public class LootContextParams {' + ''.join('public static final Object '+name+'=new Object();' for name in ('BLOCK_STATE','BLOCK_ENTITY','ORIGIN','CONTAINER'))+'}')
    put('net/minecraft/world/level/storage/loot/parameters/LootContextParamSets','package net.minecraft.world.level.storage.loot.parameters;public class LootContextParamSets {public static final Object CONTAINER_PROCESS=new Object();}')
    put('net/neoforged/neoforge/common/loot/NeoForgeLootContextParams','package net.neoforged.neoforge.common.loot;public class NeoForgeLootContextParams{public static final Object QUERIED_STACK=new Object();}')
    put('net/minecraft/world/level/storage/loot/LootParams','''package net.minecraft.world.level.storage.loot;
import java.util.*;import net.minecraft.server.level.ServerLevel;
public record LootParams(ServerLevel level,Map<Object,Object> values,Object set) {
 public static class Builder {private final ServerLevel level;private final Map<Object,Object> values=new HashMap<>();
 public Builder(ServerLevel level){this.level=level;}public Builder withParameter(Object key,Object value){values.put(key,value);return this;}
 public Builder withOptionalParameter(Object key,Object value){if(value!=null)values.put(key,value);return this;}
 public LootParams create(Object set){return new LootParams(level,Map.copyOf(values),set);}}
}''')
    put('net/minecraft/world/level/storage/loot/LootContext','''package net.minecraft.world.level.storage.loot;
import java.util.Optional;public record LootContext(LootParams params){public static class Builder{private final LootParams p;public Builder(LootParams p){this.p=p;}public LootContext create(Optional<?> random){return new LootContext(p);}}}''')
    put('net/minecraft/world/level/storage/loot/providers/number/ints/ResolvableInt','''package net.minecraft.world.level.storage.loot.providers.number.ints;
import java.util.function.Function;import net.minecraft.core.component.DataComponentType;import net.minecraft.world.item.ItemStack;import net.minecraft.world.level.storage.loot.LootContext;
public interface ResolvableInt {
 int resolve(LootContext context);
 static <T> int getFromItem(ItemStack stack,DataComponentType<T> type,Function<T,ResolvableInt> getter,LootContext context,int fallback){T value=stack.get(type);return value==null?fallback:getter.apply(value).resolve(context);}
}''')
    probe = r'''import buildcraft.lib.compat.ItemCompat;import net.minecraft.world.item.*;import net.minecraft.core.component.DataComponents;
import net.minecraft.core.BlockPos;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;import net.minecraft.world.Container;import net.minecraft.world.level.storage.loot.parameters.*;
import net.neoforged.neoforge.common.loot.NeoForgeLootContextParams;
public class FuelProbe {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args){
  check(!ItemCompat.isFuel(null)&&!ItemCompat.isFuel(ItemStack.EMPTY),"empty classification");
  ItemStack coal=new ItemStack("coal");int[] sampled={0};var level=new ServerLevel();var block=new BlockState();var pos=new BlockPos(13,42,-7);
  var machine=new BlockEntity(level,block,pos);Container inventory=new Container(){};
  coal.data.put(DataComponents.COOKING_FUEL,new test.Fuel(context->{sampled[0]++;check(context.params().level()==level,"actual dimension");
   var p=context.params().values();check(p.get(LootContextParams.BLOCK_ENTITY)==machine,"actual machine");check(p.get(LootContextParams.BLOCK_STATE)==block,"actual block state");
   check(p.get(LootContextParams.CONTAINER)==inventory,"actual inventory snapshot");check(p.get(NeoForgeLootContextParams.QUERIED_STACK)==coal,"queried stack");
   check(p.get(LootContextParams.ORIGIN).equals(net.minecraft.world.phys.Vec3.atCenterOf(pos)),"machine position");
   check(context.params().set()==LootContextParamSets.CONTAINER_PROCESS,"container process context");return 1600;}));
  check(ItemCompat.isFuel(coal)&&sampled[0]==0,"classification never samples contextual fuel");
  check(ItemCompat.getBurnTime(coal,machine,inventory)==1600&&sampled[0]==1,"provider sampled exactly once at burn");
  check(ItemCompat.getBurnTime(coal,new BlockEntity(new net.minecraft.world.level.Level(),block,pos),inventory)==0,"client cannot evaluate server loot providers");
  check(ItemCompat.getCraftingRemainingItem(coal)==ItemStack.EMPTY,"coal remainder empty");
  check(ItemCompat.getCraftingRemainingItem(null)==ItemStack.EMPTY&&ItemCompat.getCraftingRemainingItem(ItemStack.EMPTY)==ItemStack.EMPTY,"empty remainder safe");
  var lava=new ItemStack("lava_bucket");lava.remainder=new ItemStackTemplate("bucket");
  var first=ItemCompat.getCraftingRemainingItem(lava);var second=ItemCompat.getCraftingRemainingItem(lava);check(first.id.equals("bucket")&&second.id.equals("bucket")&&first!=second,"remainder instantiated per operation");
  var tool=new ItemStack("modded_tool");tool.tags.add("axe_dig");check(ItemCompat.isAxe(tool),"modded ability axe");
  tool.tags.clear();tool.tags.add(net.minecraft.tags.ItemTags.SHOVELS);check(ItemCompat.isShovel(tool),"component-era shovel tag");
  coal.data.put(DataComponents.COOKING_FUEL,new test.Fuel(context->-2));check(ItemCompat.getBurnTime(coal,machine,inventory)==0,"negative fuel provider clamped");
  coal.data.remove(DataComponents.COOKING_FUEL);check(!ItemCompat.isFuel(coal)&&ItemCompat.getBurnTime(coal,machine,inventory)==0,"component removal respected");
  System.out.println("Fuel context probes: "+checks+" assertions (offline API doubles)");
 }
}'''
    return execute(java,('buildcraft/lib/compat/ItemCompat.java',),doubles,{'FuelProbe.java':probe})
