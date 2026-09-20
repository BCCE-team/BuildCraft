"""Offline API doubles for the maintained Minecraft compatibility boundaries.

This compiles actual BCCE Java for both supported modern API shapes. It is not
Minecraft, a loader integration test, or a substitute for Gradle type checking.
The minimal codec implementation exercises map flattening and typed NBT values;
it does not implement DataFixerUpper or a real dynamic registry.
"""
from __future__ import annotations
from pathlib import Path
import shutil
import subprocess
import tempfile

ACTUAL = (
    'persistence/BCValueInput', 'persistence/BCValueOutput', 'persistence/TagAccess',
    'persistence/BCBlockEntity', 'gui/BCInputState', 'gui/BCWidgetInput',
    'gui/BCGuiInput', 'gui/BCGuiTooltip', 'gui/BCGraphics', 'gui/BCContainerScreen',
    'registry/BCRegistrationScope', 'components/BCItemData', 'world/BCWorldHeight', 'render/BCCamera',
)


def stubs(current: bool) -> dict[str, str]:
    result: dict[str, str] = {}

    def put(path: str, text: str) -> None:
        result[path + '.java'] = text

    put('javax/annotation/Nullable', 'package javax.annotation; public @interface Nullable {}')
    put('com/mojang/datafixers/util/Pair', '''package com.mojang.datafixers.util;
public record Pair<A,B>(A first,B second) {public static <A,B> Pair<A,B> of(A a,B b){return new Pair<>(a,b);}public A getFirst(){return first;}public B getSecond(){return second;}}''')
    put('com/mojang/serialization/DataResult', '''package com.mojang.serialization;
import java.util.*;import java.util.function.*;
public final class DataResult<A> {private final A value;private DataResult(A a){value=a;}
 public static <A> DataResult<A> success(A a){return new DataResult<>(a);}
 public static <A> DataResult<A> error(Supplier<String> e){return new DataResult<>(null);}
 public Optional<A> result(){return Optional.ofNullable(value);}
 public <B> DataResult<B> map(Function<? super A,? extends B> f){return value==null?new DataResult<>(null):success(f.apply(value));}
 public <B> DataResult<B> flatMap(Function<? super A,? extends DataResult<B>> f){return value==null?new DataResult<>(null):f.apply(value);}
}''')
    put('com/mojang/serialization/DynamicOps', '''package com.mojang.serialization;
import java.util.stream.Stream;import com.mojang.datafixers.util.Pair;
public interface DynamicOps<T> {T createMap(Stream<Pair<T,T>> values);DataResult<MapLike<T>> getMap(T value);}''')
    put('com/mojang/serialization/MapLike', '''package com.mojang.serialization;
import java.util.stream.Stream;import com.mojang.datafixers.util.Pair;
public interface MapLike<T> {Stream<Pair<T,T>> entries();}''')
    put('com/mojang/serialization/RecordBuilder', '''package com.mojang.serialization;
public interface RecordBuilder<T> {RecordBuilder<T> add(T key,T value);RecordBuilder<T> withErrorsFrom(DataResult<?> result);}''')
    put('com/mojang/serialization/MapCodec', '''package com.mojang.serialization;
import java.util.stream.Stream;
public abstract class MapCodec<A> {public abstract <T> DataResult<A> decode(DynamicOps<T> o,MapLike<T> input);
 public abstract <T> RecordBuilder<T> encode(A a,DynamicOps<T> o,RecordBuilder<T> b);public abstract <T> Stream<T> keys(DynamicOps<T> o);}''')
    put('com/mojang/serialization/Codec', '''package com.mojang.serialization;
public abstract class Codec<A> {public abstract <T> DataResult<A> parse(DynamicOps<T> o,T v);public abstract <T> DataResult<T> encodeStart(DynamicOps<T> o,A a);}''')
    put('net/minecraft/nbt/Tag', '''package net.minecraft.nbt;
public abstract class Tag {public static final int TAG_BYTE=1,TAG_SHORT=2,TAG_INT=3,TAG_LONG=4,TAG_FLOAT=5,TAG_DOUBLE=6,TAG_BYTE_ARRAY=7,TAG_STRING=8,TAG_LIST=9,TAG_COMPOUND=10,TAG_INT_ARRAY=11,TAG_LONG_ARRAY=12;public abstract int getId();}''')
    put('net/minecraft/nbt/NumericTag', '''package net.minecraft.nbt;
public class NumericTag extends Tag {public final Number value;private final int type;public NumericTag(int t,Number n){type=t;value=n;}public int getId(){return type;}}''')
    put('net/minecraft/nbt/ValueTag', '''package net.minecraft.nbt;
public class ValueTag extends Tag {public final Object value;private final int type;public ValueTag(int t,Object v){type=t;value=v;}public int getId(){return type;}}''')
    put('net/minecraft/nbt/ListTag', '''package net.minecraft.nbt;
import java.util.*;
public class ListTag extends Tag implements Iterable<Tag> {private final List<Tag> values=new ArrayList<>();public int getId(){return TAG_LIST;}
 public boolean add(Tag t){return values.add(t);}public Tag get(int i){return values.get(i);}public int size(){return values.size();}public boolean isEmpty(){return values.isEmpty();}public Iterator<Tag> iterator(){return values.iterator();}}''')
    put('net/minecraft/nbt/CompoundTag', '''package net.minecraft.nbt;
import java.util.*;import com.mojang.serialization.*;
public class CompoundTag extends Tag {
 public final Map<String,Tag> values=new LinkedHashMap<>();public int getId(){return TAG_COMPOUND;}
 public static final Codec<CompoundTag> CODEC=new Codec<>(){
  public <T> DataResult<CompoundTag> parse(DynamicOps<T> o,T v){return v instanceof CompoundTag c?DataResult.success(c):DataResult.error(()->"Not compound");}
  @SuppressWarnings("unchecked") public <T> DataResult<T> encodeStart(DynamicOps<T> o,CompoundTag c){return DataResult.success((T)c);}};
 public boolean contains(String k){return values.containsKey(k);}public boolean contains(String k,int t){Tag v=get(k);return v!=null&&(t==99?v instanceof NumericTag:v.getId()==t);}
 public Tag get(String k){return values.get(k);}public void put(String k,Tag v){values.put(k,v);}public boolean isEmpty(){return values.isEmpty();}
 private Number number(String k){Tag v=get(k);return v instanceof NumericTag n?n.value:0;}
 private Object object(String k,Object empty){Tag v=get(k);return v instanceof ValueTag t?t.value:empty;}
 public boolean getBoolean(String k){return number(k).byteValue()!=0;}public byte getByte(String k){return number(k).byteValue();}
 public short getShort(String k){return number(k).shortValue();}public int getInt(String k){return number(k).intValue();}public long getLong(String k){return number(k).longValue();}
 public float getFloat(String k){return number(k).floatValue();}public double getDouble(String k){return number(k).doubleValue();}
 public String getString(String k){Object v=object(k,"");return v instanceof String s?s:"";}
 public byte[] getByteArray(String k){Object v=object(k,new byte[0]);return v instanceof byte[] a?a:new byte[0];}
 public int[] getIntArray(String k){Object v=object(k,new int[0]);return v instanceof int[] a?a:new int[0];}
 public long[] getLongArray(String k){Object v=object(k,new long[0]);return v instanceof long[] a?a:new long[0];}
 public CompoundTag getCompound(String k){return get(k) instanceof CompoundTag c?c:new CompoundTag();}
 public ListTag getList(String k,int t){if(!(get(k) instanceof ListTag l))return new ListTag();return l.isEmpty()||l.get(0).getId()==t?l:new ListTag();}
 public boolean hasUUID(String k){return getIntArray(k).length==4;}public UUID getUUID(String k){int[] a=getIntArray(k);return a.length!=4?null:new UUID(((long)a[0]<<32)|(a[1]&0xffffffffL),((long)a[2]<<32)|(a[3]&0xffffffffL));}
 public void putBoolean(String k,boolean v){putByte(k,(byte)(v?1:0));}public void putByte(String k,byte v){put(k,new NumericTag(TAG_BYTE,v));}
 public void putShort(String k,short v){put(k,new NumericTag(TAG_SHORT,v));}public void putInt(String k,int v){put(k,new NumericTag(TAG_INT,v));}
 public void putLong(String k,long v){put(k,new NumericTag(TAG_LONG,v));}public void putFloat(String k,float v){put(k,new NumericTag(TAG_FLOAT,v));}public void putDouble(String k,double v){put(k,new NumericTag(TAG_DOUBLE,v));}
 public void putString(String k,String v){put(k,new ValueTag(TAG_STRING,v));}public void putByteArray(String k,byte[] v){put(k,new ValueTag(TAG_BYTE_ARRAY,v));}
 public void putIntArray(String k,int[] v){put(k,new ValueTag(TAG_INT_ARRAY,v));}public void putLongArray(String k,long[] v){put(k,new ValueTag(TAG_LONG_ARRAY,v));}
 public void putUUID(String k,UUID v){long m=v.getMostSignificantBits(),l=v.getLeastSignificantBits();putIntArray(k,new int[]{(int)(m>>32),(int)m,(int)(l>>32),(int)l});}
}''')
    put('net/minecraft/nbt/NbtOps', '''package net.minecraft.nbt;
import com.mojang.serialization.*;import com.mojang.datafixers.util.Pair;import java.util.stream.Stream;
public final class NbtOps implements DynamicOps<Tag> {public static final NbtOps INSTANCE=new NbtOps();
 public Tag createMap(Stream<Pair<Tag,Tag>> s){CompoundTag c=new CompoundTag();s.forEach(p->c.put((String)((ValueTag)p.getFirst()).value,p.getSecond()));return c;}
 public DataResult<MapLike<Tag>> getMap(Tag v){if(!(v instanceof CompoundTag c))return DataResult.error(()->"Not compound");return DataResult.success(()->c.values.entrySet().stream().map(e->Pair.of(new ValueTag(Tag.TAG_STRING,e.getKey()),e.getValue())));}
}''')
    # Existing NbtCompat is outside the changed boundary. Its contract is represented here,
    # while the actual new TagAccess/BCValueInput/BCValueOutput classes are compiled below.
    methods = []
    for typ, name in [('boolean','Boolean'),('byte','Byte'),('short','Short'),('int','Int'),('long','Long'),('float','Float'),('double','Double'),('String','String'),('byte[]','ByteArray'),('int[]','IntArray'),('long[]','LongArray'),('CompoundTag','Compound')]:
        methods.append(f'public static {typ} get{name}(CompoundTag t,String k){{return t.get{name}(k);}}')
    put('buildcraft/lib/compat/NbtCompat', 'package buildcraft.lib.compat;import net.minecraft.nbt.*;import java.util.UUID;public class NbtCompat {'+'\n'.join(methods)+'''
 public static ListTag getList(CompoundTag t,String k){return t.get(k) instanceof ListTag l?l:new ListTag();}
 public static UUID getUUID(CompoundTag t,String k){return t.getUUID(k);}public static boolean hasUUID(CompoundTag t,String k){return t.hasUUID(k);}public static void putUUID(CompoundTag t,String k,UUID v){t.putUUID(k,v);}}''')
    put('net/minecraft/core/HolderLookup', '''package net.minecraft.core;import net.minecraft.nbt.*;import com.mojang.serialization.DynamicOps;
public class HolderLookup {public interface Provider {default DynamicOps<Tag> createSerializationContext(NbtOps ops){return ops;}}}''')
    put('net/minecraft/core/BlockPos', 'package net.minecraft.core;public record BlockPos(int x,int y,int z){public static final BlockPos ZERO=new BlockPos(0,0,0);}')
    put('net/minecraft/world/level/block/state/BlockState', 'package net.minecraft.world.level.block.state;public class BlockState {}')
    put('net/minecraft/world/level/block/entity/BlockEntityType', 'package net.minecraft.world.level.block.entity;public class BlockEntityType<T> {}')
    put('net/minecraft/world/level/Level', '''package net.minecraft.world.level;import net.minecraft.core.HolderLookup;
public class Level {private final HolderLookup.Provider provider;public Level(HolderLookup.Provider p){provider=p;}public HolderLookup.Provider registryAccess(){return provider;}}''')
    put('net/minecraft/world/level/storage/ValueInput', '''package net.minecraft.world.level.storage;
import java.util.*;import com.mojang.serialization.*;import net.minecraft.core.HolderLookup;
public interface ValueInput {<T> Optional<T> read(MapCodec<T> c);HolderLookup.Provider lookup();}''')
    put('net/minecraft/world/level/storage/ValueOutput', '''package net.minecraft.world.level.storage;
import com.mojang.serialization.MapCodec;public interface ValueOutput {<T> void store(MapCodec<T> c,T v);void putInt(String k,int v);}''')
    put('test/Inputs', '''package test;import java.util.*;import com.mojang.serialization.*;import net.minecraft.nbt.*;import net.minecraft.core.HolderLookup;import net.minecraft.world.level.storage.*;
public class Inputs {
 public record Reader(CompoundTag tag,HolderLookup.Provider provider) implements ValueInput {
  public HolderLookup.Provider lookup(){return provider;}public <T> Optional<T> read(MapCodec<T> c){return c.decode(NbtOps.INSTANCE,NbtOps.INSTANCE.getMap(tag).result().orElseThrow()).result();}}
 public static final class Writer implements ValueOutput {public final CompoundTag tag=new CompoundTag();public void putInt(String k,int v){tag.putInt(k,v);}
  public <T> void store(MapCodec<T> c,T value){c.encode(value,NbtOps.INSTANCE,new RecordBuilder<Tag>(){
   public RecordBuilder<Tag> add(Tag key,Tag val){tag.put((String)((ValueTag)key).value,val);return this;}
   public RecordBuilder<Tag> withErrorsFrom(DataResult<?> result){if(result.result().isEmpty())throw new AssertionError("Codec error");return this;}});}}
}''')
    methods = '''protected void loadAdditional(ValueInput i) {loads++;}
 protected void saveAdditional(ValueOutput o) {saves++;o.putInt("vanilla",17);}''' if current else '''protected void loadAdditional(CompoundTag t,HolderLookup.Provider p) {loads++;}
 protected void saveAdditional(CompoundTag t,HolderLookup.Provider p) {saves++;t.putInt("vanilla",17);}'''
    put('net/minecraft/world/level/block/entity/BlockEntity', '''package net.minecraft.world.level.block.entity;
import net.minecraft.core.*;import net.minecraft.nbt.*;import net.minecraft.world.level.*;import net.minecraft.world.level.block.state.*;import net.minecraft.world.level.storage.*;
public class BlockEntity {protected Level level;public int loads,saves;public BlockEntity(BlockEntityType<?> t,BlockPos p,BlockState s){}public void setLevel(Level l){level=l;}'''+methods+'}')
    identifier = 'Identifier' if current else 'ResourceLocation'
    put('net/minecraft/resources/'+identifier, '''package net.minecraft.resources;
public record '''+identifier+'''(String value) {public static '''+identifier+''' parse(String s){return new '''+identifier+'''(s);}public String toString(){return value;}public String getNamespace(){return value.split(":",2)[0];}public String getPath(){return value.split(":",2)[1];}}''')
    accessor = 'identifier' if current else 'location'
    put('net/minecraft/resources/ResourceKey', f'package net.minecraft.resources;public record ResourceKey<T>({identifier} {accessor}) {{}}')
    props = f'''public net.minecraft.resources.ResourceKey<Item> id;public String name;public Properties setId(net.minecraft.resources.ResourceKey<Item> k){{id=k;return this;}}public Properties overrideDescription(String n){{name=n;return this;}}''' if current else ''
    put('net/minecraft/world/item/Item', 'package net.minecraft.world.item;public class Item {public static class Properties {'+props+'}}')
    put('net/minecraft/world/level/block/Block', 'package net.minecraft.world.level.block;public class Block {}')
    props = 'public net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> id;public Properties setId(net.minecraft.resources.ResourceKey<net.minecraft.world.level.block.Block> k){id=k;return this;}' if current else ''
    put('net/minecraft/world/level/block/state/BlockBehaviour', 'package net.minecraft.world.level.block.state;public class BlockBehaviour {public static class Properties {'+props+'}}')
    put('net/minecraft/world/item/ItemStack', '''package net.minecraft.world.item;
import net.minecraft.nbt.*;import net.minecraft.core.HolderLookup;import com.mojang.serialization.*;
public class ItemStack {public static final ItemStack EMPTY=new ItemStack("",0);public final String id;public final int count;public final CompoundTag data=new CompoundTag();
 public ItemStack(String id,int count){this.id=id;this.count=count;}public boolean isEmpty(){return id.isEmpty()||count<=0;}
 public static final Codec<ItemStack> OPTIONAL_CODEC=new Codec<>(){
  public <T> DataResult<ItemStack> parse(DynamicOps<T> ops,T v){if(!(v instanceof CompoundTag c))return DataResult.error(()->"Bad item");return DataResult.success(parseOptional(null,c));}
  @SuppressWarnings("unchecked") public <T> DataResult<T> encodeStart(DynamicOps<T> ops,ItemStack s){return DataResult.success((T)s.saveOptional(null));}};
 public Tag saveOptional(HolderLookup.Provider p){CompoundTag c=new CompoundTag();if(!isEmpty()){c.putString("id",id);c.putInt("count",count);c.put("components",data);}return c;}
 public static ItemStack parseOptional(HolderLookup.Provider p,CompoundTag c){if(c.isEmpty()||c.getString("id").isEmpty())return EMPTY;ItemStack s=new ItemStack(c.getString("id"),c.getInt("count"));s.data.values.putAll(c.getCompound("components").values);return s;}}
''')
    put('net/minecraft/client/gui/Font', 'package net.minecraft.client.gui;public class Font {}')
    put('net/minecraft/network/chat/Component', 'package net.minecraft.network.chat;public record Component(String text) {}')
    put('net/minecraft/util/FormattedCharSequence', 'package net.minecraft.util;public interface FormattedCharSequence {}')
    put('test/Matrix', '''package test;import java.util.*;
public class Matrix {public float x,y,sx=1,sy=1;private final Deque<float[]> stack=new ArrayDeque<>();
 public void pushMatrix(){stack.push(new float[]{x,y,sx,sy});}public void popMatrix(){float[] v=stack.pop();x=v[0];y=v[1];sx=v[2];sy=v[3];}
 public void translate(float x,float y){this.x+=sx*x;this.y+=sy*y;}public void scale(float x,float y){sx*=x;sy*=y;}public boolean balanced(){return stack.isEmpty();}}''')
    put('com/mojang/blaze3d/vertex/PoseStack', '''package com.mojang.blaze3d.vertex;import test.Matrix;
public class PoseStack {public final Matrix state=new Matrix();public void pushPose(){state.pushMatrix();}public void popPose(){state.popMatrix();}public void translate(double x,double y,double z){state.translate((float)x,(float)y);}public void scale(float x,float y,float z){state.scale(x,y);}}''')
    put('net/minecraft/client/renderer/RenderPipelines', 'package net.minecraft.client.renderer;public class RenderPipelines {public static final Object GUI_TEXTURED=new Object();}')
    native_gui = '''public test.Matrix pose(){return matrix;}public void nextStratum(){layers++;}
 public void blit(Object pipeline,Identifier texture,int x,int y,float u,float v,int w,int h,int sw,int sh,int tw,int th){draw=new Object[]{texture,x,y,u,v,w,h,sw,sh,tw,th};draws++;}
 public void setTooltipForNextFrame(Font f,ItemStack s,int x,int y){tooltip=s;tooltips++;}
 public void setTooltipForNextFrame(Font f,Component s,int x,int y){tooltip=s;tooltips++;}
 public void setComponentTooltipForNextFrame(Font f,List<Component> s,int x,int y){tooltip=s;tooltips++;}''' if current else '''public final com.mojang.blaze3d.vertex.PoseStack stack=new com.mojang.blaze3d.vertex.PoseStack();
 public com.mojang.blaze3d.vertex.PoseStack pose(){return stack;}
 public void blit(ResourceLocation texture,int x,int y,int w,int h,float u,float v,int sw,int sh,int tw,int th){draw=new Object[]{texture,x,y,u,v,w,h,sw,sh,tw,th};draws++;}
 public void renderTooltip(Font f,ItemStack s,int x,int y){tooltip=s;tooltips++;}
 public void renderTooltip(Font f,Component s,int x,int y){tooltip=s;tooltips++;}
 public void renderComponentTooltip(Font f,List<Component> s,int x,int y){tooltip=s;tooltips++;}'''
    put('net/minecraft/client/gui/GuiGraphics', '''package net.minecraft.client.gui;
import java.util.*;import net.minecraft.resources.*;import net.minecraft.network.chat.Component;import net.minecraft.world.item.ItemStack;import net.minecraft.util.FormattedCharSequence;
public class GuiGraphics {public final test.Matrix matrix=new test.Matrix();public int layers,draws,tooltips,color;public Object[] draw;public Object tooltip;public int[] clip;
 public void drawString(Font f,String s,int x,int y,int c,boolean shadow){color=c;}public void drawString(Font f,Component s,int x,int y,int c,boolean shadow){color=c;}public void drawString(Font f,FormattedCharSequence s,int x,int y,int c,boolean shadow){color=c;}
 public void enableScissor(int l,int t,int r,int b){clip=new int[]{l,t,r,b};}public void disableScissor(){clip=null;}
 public void fill(int l,int t,int r,int b,int color){this.color=color;}public void fillGradient(int l,int t,int r,int b,int a,int z){this.color=a;}
'''+native_gui+'}')
    put('net/minecraft/client/input/KeyEvent', 'package net.minecraft.client.input;public record KeyEvent(int key,int scancode,int modifiers){public boolean hasShiftDown(){return (modifiers&1)!=0;}}')
    put('net/minecraft/client/input/MouseButtonInfo', 'package net.minecraft.client.input;public record MouseButtonInfo(int button,int modifiers) {}')
    put('net/minecraft/client/input/MouseButtonEvent', 'package net.minecraft.client.input;public record MouseButtonEvent(double x,double y,MouseButtonInfo info){public int button(){return info.button();}public boolean hasShiftDown(){return (info.modifiers()&1)!=0;}}')
    put('net/minecraft/client/input/CharacterEvent', 'package net.minecraft.client.input;public record CharacterEvent(int codepoint,int modifiers) {}')
    key = 'public static Key getKey(net.minecraft.client.input.KeyEvent e){return new Key(e.key(),e.scancode());}' if current else 'public static Key getKey(int k,int s){return new Key(k,s);}'
    put('com/mojang/blaze3d/platform/InputConstants', 'package com.mojang.blaze3d.platform;public class InputConstants {public record Key(int key,int scan){}'+key+'}')
    listener = '''default boolean mouseClicked(MouseButtonEvent e,boolean twice){return false;}
 default boolean mouseDragged(MouseButtonEvent e,double x,double y){return false;}default boolean mouseReleased(MouseButtonEvent e){return false;}
 default boolean keyPressed(KeyEvent e){return false;}default boolean charTyped(CharacterEvent e){return false;}''' if current else '''default boolean mouseClicked(double x,double y,int b){return false;}default boolean mouseDragged(double x,double y,int b,double dx,double dy){return false;}
 default boolean mouseReleased(double x,double y,int b){return false;}default boolean keyPressed(int k,int s,int m){return false;}default boolean charTyped(char c,int m){return false;}'''
    put('net/minecraft/client/gui/components/events/GuiEventListener', 'package net.minecraft.client.gui.components.events;import net.minecraft.client.input.*;public interface GuiEventListener {'+listener+'}')
    put('net/minecraft/world/entity/player/Inventory', 'package net.minecraft.world.entity.player;public class Inventory {}')
    put('net/minecraft/world/inventory/AbstractContainerMenu', 'package net.minecraft.world.inventory;public class AbstractContainerMenu {}')
    fallback = '''public boolean mouseClicked(MouseButtonEvent e,boolean twice){calls++;doubleClick=twice;shift=buildcraft.lib.compat.minecraft.gui.BCInputState.shiftDown();return true;}
 public boolean mouseDragged(MouseButtonEvent e,double x,double y){calls++;return true;}public boolean mouseReleased(MouseButtonEvent e){calls++;return true;}
 public boolean keyPressed(KeyEvent e){calls++;return true;}public boolean charTyped(CharacterEvent e){calls++;return true;}''' if current else '''public boolean mouseClicked(double x,double y,int b){calls++;return true;}public boolean mouseDragged(double x,double y,int b,double dx,double dy){calls++;return true;}
 public boolean mouseReleased(double x,double y,int b){calls++;return true;}public boolean keyPressed(int k,int s,int m){calls++;return true;}public boolean charTyped(char c,int m){calls++;return true;}'''
    put('net/minecraft/client/gui/screens/inventory/AbstractContainerScreen', '''package net.minecraft.client.gui.screens.inventory;
import net.minecraft.client.gui.components.events.GuiEventListener;import net.minecraft.client.input.*;import net.minecraft.world.inventory.AbstractContainerMenu;import net.minecraft.world.entity.player.Inventory;import net.minecraft.network.chat.Component;
public abstract class AbstractContainerScreen<T extends AbstractContainerMenu> implements GuiEventListener {public int calls;public boolean doubleClick,shift;protected AbstractContainerScreen(T t,Inventory i,Component title){}'''+fallback+'}')
    height = 'getMinY' if current else 'getMinBuildHeight'
    put('net/minecraft/world/level/LevelHeightAccessor', f'package net.minecraft.world.level;public interface LevelHeightAccessor {{int {height}();int getHeight();}}')
    put('net/minecraft/world/phys/Vec3', 'package net.minecraft.world.phys;public record Vec3(double x,double y,double z){public static final Vec3 ZERO=new Vec3(0,0,0);}')
    camera = 'position' if current else 'getPosition'
    put('net/minecraft/client/Camera', f'package net.minecraft.client;import net.minecraft.world.phys.Vec3;public class Camera {{private final Vec3 value;public Camera(Vec3 v){{value=v;}}public Vec3 {camera}(){{return value;}}}}')
    if current:
        put('net/minecraft/world/item/crafting/display/SlotDisplay', 'package net.minecraft.world.item.crafting.display;public interface SlotDisplay {}')
        put('net/minecraft/world/item/crafting/display/RecipeDisplay', 'package net.minecraft.world.item.crafting.display;public interface RecipeDisplay {}')
        for name in ('ShapedCraftingRecipeDisplay', 'ShapelessCraftingRecipeDisplay'):
            put('net/minecraft/world/item/crafting/display/'+name, 'package net.minecraft.world.item.crafting.display;import java.util.List;public record '+name+'(List<SlotDisplay> ingredients) implements RecipeDisplay {}')
        put('net/minecraft/world/item/crafting/display/RecipeDisplayEntry', 'package net.minecraft.world.item.crafting.display;public record RecipeDisplayEntry(int id,RecipeDisplay display) {}')
        put('net/minecraft/client/gui/screens/recipebook/RecipeCollection', 'package net.minecraft.client.gui.screens.recipebook;import java.util.List;import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;public record RecipeCollection(List<RecipeDisplayEntry> getRecipes) {}')
        put('net/minecraft/client/ClientRecipeBook', 'package net.minecraft.client;import java.util.List;import net.minecraft.client.gui.screens.recipebook.RecipeCollection;public record ClientRecipeBook(List<RecipeCollection> getCollections) {}')
    else:
        put('net/minecraft/client/renderer/MultiBufferSource', 'package net.minecraft.client.renderer;public interface MultiBufferSource {}')
        put('net/minecraft/client/renderer/blockentity/BlockEntityRenderer', '''package net.minecraft.client.renderer.blockentity;
import net.minecraft.world.level.block.entity.BlockEntity;import com.mojang.blaze3d.vertex.PoseStack;import net.minecraft.client.renderer.MultiBufferSource;
public interface BlockEntityRenderer<T extends BlockEntity> {default boolean shouldRenderOffScreen(T tile){return false;}void render(T tile,float tick,PoseStack pose,MultiBufferSource buffers,int light,int overlay);}''')
    return result


def run(java_root: Path, *, current: bool, probe: str) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java 21 is required; executable compatibility probes were NOT run')
    with tempfile.TemporaryDirectory(prefix='bc-minecraft-compat-') as temporary:
        root = Path(temporary)
        sources = stubs(current)
        for name in ACTUAL:
            relative = 'buildcraft/lib/compat/minecraft/' + name + '.java'
            sources[relative] = (java_root / relative).read_text(encoding='utf-8')
        if current:
            for relative in ('buildcraft/lib/compat/minecraft/persistence/CompoundValueCodec.java',
                             'buildcraft/lib/compat/ItemNameKeys121111.java',
                             'buildcraft/lib/compat/minecraft/recipe/BCRecipeDisplays.java'):
                sources[relative] = (java_root / relative).read_text(encoding='utf-8')
        else:
            relative = 'buildcraft/lib/compat/minecraft/render/BCGeometryRenderer.java'
            sources[relative] = (java_root / relative).read_text(encoding='utf-8')
        sources['CompatProbe.java'] = probe
        for relative, source in sources.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding='utf-8')
        argument_file = root / 'sources.txt'
        argument_file.write_text('\n'.join(str(p) for p in root.rglob('*.java')))
        classes = root / 'classes'
        compiled = subprocess.run(['javac', '--release', '21', '-d', str(classes), '@' + str(argument_file)],
                                  text=True, capture_output=True, timeout=45)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        executed = subprocess.run(['java', '-ea', '-cp', str(classes), 'CompatProbe'],
                                 text=True, capture_output=True, timeout=30)
        if executed.returncode:
            raise AssertionError(executed.stdout + executed.stderr)
        return executed.stdout.strip()


def parse_sources(java_roots: list[Path]) -> str:
    """Use javac's parser only; deliberately do not claim dependency/type resolution."""
    program = r'''
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
public final class SyntaxProbe {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler is required");
        for (String directory : args) {
            List<Path> paths;
            try (var files = Files.walk(Path.of(directory))) {
                paths = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                var task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-proc:none"), null, manager.getJavaFileObjectsFromPaths(paths));
                int units = 0;
                for (var unit : task.parse()) units++;
                long errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
                System.out.println("Java syntax: " + units + " units, " + errors + " errors (parse only)");
                if (errors != 0) throw new AssertionError(diagnostics.getDiagnostics().toString());
            }
        }
    }
}
'''
    with tempfile.TemporaryDirectory(prefix='bc-java-syntax-') as temporary:
        root = Path(temporary)
        source = root / 'SyntaxProbe.java'
        source.write_text(program, encoding='utf-8')
        built = subprocess.run(['javac', '--release', '21', '-d', str(root), str(source)],
                               text=True, capture_output=True, timeout=30)
        if built.returncode:
            raise AssertionError(built.stdout + built.stderr)
        parsed = subprocess.run(['java', '-Xmx1g', '-cp', str(root), 'SyntaxProbe', *map(str, java_roots)],
                                text=True, capture_output=True, timeout=60)
        if parsed.returncode:
            raise AssertionError(parsed.stdout + parsed.stderr)
        return parsed.stdout.strip()
