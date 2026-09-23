"""Offline typed probes for the real 1.21.11 client integration implementation.

External APIs are contract doubles, not a Minecraft runtime or a dependency build.
Recipe matching, display selection, model assembly, transfer handlers, sync and
Jade conversion run from maintained production sources, without text rewrites.
"""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from source_layout import materialize_target

SOURCES: dict[str, str] = {}


def source(name: str, body: str) -> None:
    package, _, simple = name.rpartition('.')
    SOURCES[name.replace('.', '/') + '.java'] = f'package {package};\n' + textwrap.dedent(body)


source('javax.annotation.Nullable', 'public @interface Nullable {}')
source('net.minecraft.core.Direction', '''
public enum Direction {
 DOWN(Axis.Y,0,-1,0),UP(Axis.Y,0,1,0),NORTH(Axis.Z,0,0,-1),SOUTH(Axis.Z,0,0,1),WEST(Axis.X,-1,0,0),EAST(Axis.X,1,0,0);
 public enum Axis {X,Y,Z}
 private final Axis axis; public final int x,y,z;
 Direction(Axis a,int x,int y,int z){axis=a;this.x=x;this.y=y;this.z=z;}
 public Axis getAxis(){return axis;}
}
''')
source('net.minecraft.resources.Identifier', '''
public record Identifier(String namespace,String path) {
 public static Identifier fromNamespaceAndPath(String n,String p){return new Identifier(n,p);}
}
''')
source('net.minecraft.resources.ResourceKey', '''
public record ResourceKey<T>(Identifier identifier) {}
''')
source('net.minecraft.nbt.CompoundTag', '''
public class CompoundTag {
 private final java.util.Map<String,Object> values=new java.util.HashMap<>();
 public int getIntOr(String key,int fallback){return values.get(key) instanceof Integer i?i:fallback;}
 public void putInt(String key,int value){values.put(key,value);}
 public CompoundTag copy(){CompoundTag copy=new CompoundTag();copy.values.putAll(values);return copy;}
}
''')
source('net.minecraft.world.item.Item', 'public class Item {}')
source('net.minecraft.world.item.Items', 'public class Items {public static final Item CRAFTING_TABLE=new Item();}')
source('net.minecraft.world.item.DyeColor', '''
public enum DyeColor {WHITE,ORANGE,MAGENTA,LIGHT_BLUE,YELLOW,LIME,PINK,GRAY,LIGHT_GRAY,CYAN,PURPLE,BLUE,BROWN,GREEN,RED,BLACK;
 public static DyeColor byId(int i){return values()[i];} public int getId(){return ordinal();}
}
''')
source('net.minecraft.world.item.ItemStack', '''
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack((Item)null,0);
 public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,ItemStack> STREAM_CODEC=
     net.minecraft.network.codec.StreamCodec.ofMember((stack,b)->b.writeObject(stack.copy()), b->(ItemStack)b.readObject());
 private final Item item; private int count; public net.minecraft.nbt.CompoundTag data=new net.minecraft.nbt.CompoundTag();
 public ItemStack(Item item){this(item,1);} public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public Item getItem(){return item;} public int getCount(){return count;} public void setCount(int c){count=c;}
 public boolean isEmpty(){return item==null||count<=0;}
 public ItemStack copy(){ItemStack copy=new ItemStack(item,count);copy.data=data.copy();return copy;}
}
''')
source('net.minecraft.world.item.BlockItem', '''
public class BlockItem extends Item {
 private final net.minecraft.world.level.block.Block block;
 public BlockItem(net.minecraft.world.level.block.Block b){block=b;}
 public net.minecraft.world.level.block.Block getBlock(){return block;}
}
''')
source('net.minecraft.world.level.block.Block', 'public class Block {}')
source('net.minecraft.world.level.block.StainedGlassBlock', '''
public class StainedGlassBlock extends Block {
 private final net.minecraft.world.item.DyeColor color;
 public StainedGlassBlock(net.minecraft.world.item.DyeColor c){color=c;}
 public net.minecraft.world.item.DyeColor getColor(){return color;}
}
''')
source('net.minecraft.world.level.Level', 'public class Level {}')
source('net.minecraft.client.multiplayer.ClientLevel', 'public class ClientLevel extends net.minecraft.world.level.Level {}')
source('net.minecraft.world.entity.ItemOwner', 'public interface ItemOwner {}')
source('net.minecraft.world.entity.player.Player', 'public class Player {}')
source('net.minecraft.world.item.ItemDisplayContext', '''
public enum ItemDisplayContext {NONE,HEAD,GUI,GROUND,FIXED,FIRST_PERSON_LEFT_HAND,FIRST_PERSON_RIGHT_HAND,THIRD_PERSON_LEFT_HAND,THIRD_PERSON_RIGHT_HAND}
''')
source('net.minecraft.core.HolderLookup', 'public class HolderLookup {public interface Provider {}}')
source('net.minecraft.core.NonNullList', '''
public class NonNullList<E> extends java.util.ArrayList<E> {public static <E> NonNullList<E> create(){return new NonNullList<>();}}
''')
source('com.mojang.serialization.Codec', '''
public class Codec<T> {
 public static final Codec<String> STRING=new Codec<>(); public static final Codec<Integer> INT=new Codec<>();
 public <R> Codec<R> xmap(java.util.function.Function<T,R> read,java.util.function.Function<R,T> write){return new Codec<>();}
 public MapCodec<T> fieldOf(String n){return new MapCodec<>();}
 public MapCodec<T> optionalFieldOf(String n,T v){return new MapCodec<>();}
}
''')
source('com.mojang.serialization.MapCodec', '''
public class MapCodec<T> {
 public <O> com.mojang.serialization.codecs.RecordCodecBuilder<O,T> forGetter(java.util.function.Function<O,T> fn){return new com.mojang.serialization.codecs.RecordCodecBuilder<>();}
}
''')
source('com.mojang.serialization.codecs.RecordCodecBuilder', '''
public class RecordCodecBuilder<O,F> {
 public static <O> com.mojang.serialization.Codec<O> create(java.util.function.Function<Instance<O>,RecordCodecBuilder<O,O>> f){return new com.mojang.serialization.Codec<>();}
 public static <O> com.mojang.serialization.MapCodec<O> mapCodec(java.util.function.Function<Instance<O>,RecordCodecBuilder<O,O>> f){return new com.mojang.serialization.MapCodec<>();}
 public static class Instance<O> {
  public <A,B> Group2<O,A,B> group(RecordCodecBuilder<O,A> a,RecordCodecBuilder<O,B> b){return new Group2<>();}
  public <A,B,C,D,E,F,G,H> Group8<O,A,B,C,D,E,F,G,H> group(RecordCodecBuilder<O,A>a,RecordCodecBuilder<O,B>b,RecordCodecBuilder<O,C>c,RecordCodecBuilder<O,D>d,RecordCodecBuilder<O,E>e,RecordCodecBuilder<O,F>f,RecordCodecBuilder<O,G>g,RecordCodecBuilder<O,H>h){return new Group8<>();}
 }
 public static class Group2<O,A,B> {public RecordCodecBuilder<O,O> apply(Instance<O> i,java.util.function.BiFunction<A,B,O> f){return new RecordCodecBuilder<>();}}
 public interface F8<A,B,C,D,E,F,G,H,O>{O apply(A a,B b,C c,D d,E e,F f,G g,H h);}
 public static class Group8<O,A,B,C,D,E,F,G,H>{public RecordCodecBuilder<O,O> apply(Instance<O> i,F8<A,B,C,D,E,F,G,H,O> f){return new RecordCodecBuilder<>();}}
}
''')
source('net.minecraft.core.registries.BuiltInRegistries', '''
public class BuiltInRegistries {
 public static final Registry<net.minecraft.world.item.Item> ITEM=new Registry<>();
 public static class Registry<T>{public com.mojang.serialization.Codec<T> byNameCodec(){return new com.mojang.serialization.Codec<>();}}
}
''')
source('net.minecraft.network.RegistryFriendlyByteBuf', '''
public class RegistryFriendlyByteBuf {
 private final java.util.ArrayDeque<Object> data=new java.util.ArrayDeque<>();
 public <E extends Enum<E>> void writeEnum(E value){data.add(value);}
 public <E extends Enum<E>> E readEnum(Class<E> type){return type.cast(data.remove());}
 public void writeUtf(String v){data.add(v);} public String readUtf(){return (String)data.remove();}
 public void writeObject(Object v){data.add(v);} public Object readObject(){return data.remove();}
 public boolean isEmpty(){return data.isEmpty();}
}
''')
source('net.minecraft.network.codec.StreamCodec', '''
public interface StreamCodec<B,T> {
 void encode(B b,T t);T decode(B b);
 static <B,T> StreamCodec<B,T> ofMember(java.util.function.BiConsumer<T,B> writer,java.util.function.Function<B,T> reader){return new StreamCodec<>(){
  public void encode(B b,T t){writer.accept(t,b);} public T decode(B b){return reader.apply(b);}
 };}
}
''')
source('net.minecraft.world.item.crafting.RecipeInput', '''
public interface RecipeInput {int size();net.minecraft.world.item.ItemStack getItem(int i);}
''')
source('net.minecraft.world.item.crafting.CraftingInput', '''
public record CraftingInput(int width,int height,java.util.List<net.minecraft.world.item.ItemStack> items) implements RecipeInput {
 public int size(){return items.size();}public net.minecraft.world.item.ItemStack getItem(int i){return items.get(i);}
}
''')
source('net.minecraft.world.item.crafting.Ingredient', '''
public class Ingredient {
 public static final com.mojang.serialization.Codec<Ingredient> CODEC=new com.mojang.serialization.Codec<>();
 public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,Ingredient> CONTENTS_STREAM_CODEC=
  net.minecraft.network.codec.StreamCodec.ofMember((v,b)->b.writeObject(v),b->(Ingredient)b.readObject());
 public final java.util.List<net.minecraft.world.item.Item> items;
 public Ingredient(net.minecraft.world.item.Item...items){this.items=java.util.List.of(items);}
 public boolean test(net.minecraft.world.item.ItemStack s){return !s.isEmpty() && items.contains(s.getItem());}
 public net.minecraft.world.item.crafting.display.SlotDisplay display(){return new net.minecraft.world.item.crafting.display.SlotDisplay.ItemSlotDisplay(items.getFirst());}
}
''')
source('net.minecraft.world.item.crafting.PlacementInfo', '''
public record PlacementInfo(java.util.List<Ingredient> ingredients) {
 public static int created;
 public static final PlacementInfo NOT_PLACEABLE=new PlacementInfo(java.util.List.of());
 public static PlacementInfo create(java.util.List<Ingredient> list){created++;return new PlacementInfo(java.util.List.copyOf(list));}
}
''')
source('net.minecraft.world.item.crafting.Recipe', 'public interface Recipe<T extends RecipeInput> {}')
source('net.minecraft.world.item.crafting.CraftingRecipe', '''
public interface CraftingRecipe extends Recipe<CraftingInput> {
 PlacementInfo placementInfo(); java.util.List<net.minecraft.world.item.crafting.display.RecipeDisplay> display();
 default String group(){return "";} default boolean isSpecial(){return false;}
}
''')
source('net.minecraft.world.item.crafting.RecipeSerializer', '''
public interface RecipeSerializer<T extends Recipe<?>> {
 com.mojang.serialization.MapCodec<T> codec();
 net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,T> streamCodec();
}
''')
source('net.minecraft.world.item.crafting.RecipeType', 'public class RecipeType<T extends Recipe<?>> {}')
source('net.minecraft.world.item.crafting.RecipeHolder', '''
public record RecipeHolder<T extends Recipe<?>>(net.minecraft.resources.ResourceKey<Recipe<?>> id,T value) {}
''')
source('net.minecraft.world.item.crafting.RecipeMap', '''
public class RecipeMap {
 public java.util.List<RecipeHolder<buildcraft.lib.recipe.AssemblyRecipeBasic>> recipes;
 public RecipeMap(java.util.List<RecipeHolder<buildcraft.lib.recipe.AssemblyRecipeBasic>> l){recipes=l;}
 public java.util.Collection<RecipeHolder<buildcraft.lib.recipe.AssemblyRecipeBasic>> byType(RecipeType<buildcraft.lib.recipe.AssemblyRecipeBasic> type){return recipes;}
}
''')
source('net.minecraft.world.item.crafting.CraftingBookCategory', 'public enum CraftingBookCategory {MISC}')
source('net.minecraft.world.item.crafting.display.SlotDisplay', '''
public interface SlotDisplay {
 record ItemStackSlotDisplay(net.minecraft.world.item.ItemStack stack) implements SlotDisplay {}
 record ItemSlotDisplay(net.minecraft.world.item.Item item) implements SlotDisplay {}
}
''')
source('net.minecraft.world.item.crafting.display.RecipeDisplay', '''
public interface RecipeDisplay {SlotDisplay result();SlotDisplay craftingStation();}
''')
source('net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay', '''
public record ShapedCraftingRecipeDisplay(int width,int height,java.util.List<SlotDisplay> ingredients,SlotDisplay result,SlotDisplay craftingStation) implements RecipeDisplay {}
''')
source('net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay', '''
public record ShapelessCraftingRecipeDisplay(java.util.List<SlotDisplay> ingredients,SlotDisplay result,SlotDisplay craftingStation) implements RecipeDisplay {}
''')
source('buildcraft.lib.compat.IngredientCompat', '''
public class IngredientCompat {
 public static net.minecraft.world.item.crafting.Ingredient empty(){return new net.minecraft.world.item.crafting.Ingredient();}
 public static boolean isEmpty(net.minecraft.world.item.crafting.Ingredient i){return i.items.isEmpty();}
}
''')
source('buildcraft.transport.item.ItemPipeHolder', '''
public class ItemPipeHolder {
 public static void setPipeColor(net.minecraft.world.item.ItemStack s,net.minecraft.world.item.DyeColor c){s.data.putInt("color",c==null?0:c.ordinal()+1);}
 public static void copyPipeColor(net.minecraft.world.item.ItemStack from,net.minecraft.world.item.ItemStack to){to.data.putInt("color",from.data.getIntOr("color",0));}
}
''')
source('buildcraft.transport.BCTransportRecipes', '''
public class BCTransportRecipes {public static final java.util.function.Supplier<buildcraft.transport.recipe.PipeRecipe.Serializer> PIPE=buildcraft.transport.recipe.PipeRecipe.Serializer::new;}
''')
source('org.joml.Vector3f', '''
public record Vector3f(float x,float y,float z) {}
''')
source('net.minecraft.client.renderer.texture.TextureAtlasSprite', '''
public record TextureAtlasSprite(String name) {}
''')
source('net.minecraft.client.renderer.rendertype.RenderType', '''
public record RenderType(String name) {}
''')
source('net.minecraft.client.renderer.Sheets', '''
public class Sheets {
 public static net.minecraft.client.renderer.rendertype.RenderType cutoutBlockSheet(){return new net.minecraft.client.renderer.rendertype.RenderType("cutout");}
 public static net.minecraft.client.renderer.rendertype.RenderType translucentBlockItemSheet(){return new net.minecraft.client.renderer.rendertype.RenderType("translucent");}
}
''')
source('net.minecraft.client.renderer.block.model.ItemTransforms', 'public class ItemTransforms {}')
source('net.minecraft.client.renderer.item.ItemModelResolver', 'public class ItemModelResolver {}')
source('net.minecraft.client.renderer.item.ItemStackRenderState', '''
public class ItemStackRenderState {
 public final java.util.List<BlockModelWrapper> layers=new java.util.ArrayList<>();
 public void appendModelIdentityElement(Object identity){}
}
''')
source('net.minecraft.client.renderer.item.ItemModel', '''
public interface ItemModel {
 void update(ItemStackRenderState state,net.minecraft.world.item.ItemStack stack,ItemModelResolver resolver,
 net.minecraft.world.item.ItemDisplayContext context,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.ItemOwner owner,int seed);
}
''')
source('net.minecraft.client.renderer.item.CompositeModel', '''
public record CompositeModel(java.util.List<ItemModel> models) implements ItemModel {
 public void update(ItemStackRenderState state,net.minecraft.world.item.ItemStack stack,ItemModelResolver resolver,
 net.minecraft.world.item.ItemDisplayContext context,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.ItemOwner owner,int seed){
  for(ItemModel m:models)m.update(state,stack,resolver,context,level,owner,seed);
 }
}
''')
source('net.minecraft.client.renderer.item.ModelRenderProperties', '''
public record ModelRenderProperties(boolean usesBlockLight,net.minecraft.client.renderer.texture.TextureAtlasSprite particle,net.minecraft.client.renderer.block.model.ItemTransforms transforms) {}
''')
source('net.minecraft.client.renderer.item.BlockModelWrapper', '''
public record BlockModelWrapper(java.util.List<Object> tints,java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> quads,
 ModelRenderProperties properties,java.util.function.Function<net.minecraft.world.item.ItemDisplayContext,net.minecraft.client.renderer.rendertype.RenderType> renderType) implements ItemModel {
 public void update(ItemStackRenderState state,net.minecraft.world.item.ItemStack stack,ItemModelResolver resolver,
 net.minecraft.world.item.ItemDisplayContext context,net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.ItemOwner owner,int seed){state.layers.add(this);}
}
''')
source('net.minecraft.client.model.geom.builders.UVPair', '''
public class UVPair {public static long pack(float u,float v){return ((long)Float.floatToIntBits(u)<<32)|(Float.floatToIntBits(v)&0xffffffffL);}}
''')
source('net.neoforged.neoforge.client.model.quad.BakedColors', '''
public record BakedColors(int a,int b,int c,int d){public static BakedColors of(int a,int b,int c,int d){return new BakedColors(a,b,c,d);}}
''')
source('net.neoforged.neoforge.client.model.quad.BakedNormals', '''
public record BakedNormals(int a,int b,int c,int d){
 public static int pack(float x,float y,float z){return ((int)(x*127)&255)|(((int)(y*127)&255)<<8)|(((int)(z*127)&255)<<16);}
 public static BakedNormals of(int a,int b,int c,int d){return new BakedNormals(a,b,c,d);}
}
''')
source('net.minecraft.client.renderer.block.model.BakedQuad', '''
public record BakedQuad(org.joml.Vector3f p0,org.joml.Vector3f p1,org.joml.Vector3f p2,org.joml.Vector3f p3,
 long uv0,long uv1,long uv2,long uv3,int tintIndex,net.minecraft.core.Direction face,net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
 boolean shade,int lightEmission,net.neoforged.neoforge.client.model.quad.BakedNormals normals,
 net.neoforged.neoforge.client.model.quad.BakedColors colors,boolean ambientOcclusion) {}
''')
source('buildcraft.lib.client.model.MutableVertex', '''
public class MutableVertex {
 public float position_x,position_y,position_z,tex_u,tex_v,normal_x,normal_y,normal_z;
 public int colour_a=255,colour_r=255,colour_g=255,colour_b=255,light_block=0;
 public MutableVertex(){}
 public MutableVertex(MutableVertex v){position_x=v.position_x;position_y=v.position_y;position_z=v.position_z;tex_u=v.tex_u;tex_v=v.tex_v;normal_x=v.normal_x;normal_y=v.normal_y;normal_z=v.normal_z;colour_a=v.colour_a;colour_r=v.colour_r;colour_g=v.colour_g;colour_b=v.colour_b;light_block=v.light_block;}
}
''')
source('buildcraft.lib.client.model.MutableQuad', '''
public class MutableQuad {
 public MutableVertex vertex_0=new MutableVertex(),vertex_1=new MutableVertex(),vertex_2=new MutableVertex(),vertex_3=new MutableVertex();
 public MutableVertex[] vertexs={vertex_0,vertex_1,vertex_2,vertex_3};
 public net.minecraft.core.Direction face;private net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;private int tint=-1;
 public MutableQuad(){}
 public MutableQuad(MutableQuad q){vertex_0=new MutableVertex(q.vertex_0);vertex_1=new MutableVertex(q.vertex_1);vertex_2=new MutableVertex(q.vertex_2);vertex_3=new MutableVertex(q.vertex_3);vertexs=new MutableVertex[]{vertex_0,vertex_1,vertex_2,vertex_3};face=q.face;sprite=q.sprite;tint=q.tint;}
 public void texFromSprite(net.minecraft.client.renderer.texture.TextureAtlasSprite s){sprite=s;}
 public void colouri(int c){for(var v:vertexs){v.colour_a=c>>>24;v.colour_r=c>>>16&255;v.colour_g=c>>>8&255;v.colour_b=c&255;}}
 public void setTint(int t){tint=t;}public int getTint(){return tint;}public boolean isShade(){return true;}
 public net.minecraft.core.Direction getFace(){return face;}public net.minecraft.client.renderer.texture.TextureAtlasSprite getSprite(){return sprite;}
}
''')
source('buildcraft.lib.client.model.ModelUtil', '''
public class ModelUtil {
 public record UvFaceData(float u0,float v0,float u1,float v1){public static UvFaceData from16(float a,float b,float c,float d){return new UvFaceData(a/16,b/16,c/16,d/16);}}
 public static MutableQuad createFace(net.minecraft.core.Direction face,org.joml.Vector3f c,org.joml.Vector3f r,UvFaceData uv){
  MutableQuad q=new MutableQuad();q.face=face;
  for(int i=0;i<4;i++){var v=q.vertexs[i];v.position_x=c.x()+r.x()*face.x;v.position_y=c.y()+r.y()*face.y;v.position_z=c.z()+r.z()*face.z;
   v.normal_x=face.x;v.normal_y=face.y;v.normal_z=face.z;v.tex_u=(i&1)==0?uv.u0():uv.u1();v.tex_v=i<2?uv.v0():uv.v1();}
  return q;
 }
}
''')
source('buildcraft.lib.client.model.ModelItemSimple', '''
public class ModelItemSimple {public static final net.minecraft.client.renderer.block.model.ItemTransforms TRANSFORM_BLOCK=new net.minecraft.client.renderer.block.model.ItemTransforms();}
''')
source('buildcraft.lib.misc.ItemStackUtil', '''
public class ItemStackUtil {public static net.minecraft.nbt.CompoundTag getCustomData(net.minecraft.world.item.ItemStack stack){return stack.data;}}
''')
source('buildcraft.lib.misc.ColourUtil', '''
public class ColourUtil {public static int getLightHex(net.minecraft.world.item.DyeColor d){return 0x123400+d.ordinal();}}
''')
source('buildcraft.lib.misc.SpriteUtil', '''
public class SpriteUtil {public static final net.minecraft.client.renderer.texture.TextureAtlasSprite MISSING=new net.minecraft.client.renderer.texture.TextureAtlasSprite("missing");public static net.minecraft.client.renderer.texture.TextureAtlasSprite missingSprite(){return MISSING;}}
''')
source('buildcraft.transport.BCTransportSprites', '''
public class BCTransportSprites {
 public record Sprite(String id){public net.minecraft.client.renderer.texture.TextureAtlasSprite getSprite(){return new net.minecraft.client.renderer.texture.TextureAtlasSprite(id);}}
 public static final Sprite PIPE_COLOUR=new Sprite("dye"),PIPE_COLOUR_BORDER_OUTER=new Sprite("outer"),PIPE_COLOUR_BORDER_INNER=new Sprite("inner");
}
''')
source('buildcraft.transport.internal.pipe.PipeDefinition', '''
public class PipeDefinition {
 public PipeFaceTex itemModelTop,itemModelCenter,itemModelBottom;
 public EnumPipeColourType colourType=EnumPipeColourType.TRANSLUCENT;
 public PipeDefinition(PipeFaceTex f){itemModelTop=f;itemModelCenter=f;itemModelBottom=f;}
 public EnumPipeColourType getColourType(){return colourType;}
}
''')
source('buildcraft.transport.client.model.PipeModelCacheBase', '''
public class PipeModelCacheBase {
 public static final Generator generator=new Generator();
 public static class Generator {public int calls;public net.minecraft.client.renderer.texture.TextureAtlasSprite[] sprites={
  new net.minecraft.client.renderer.texture.TextureAtlasSprite("zero"),new net.minecraft.client.renderer.texture.TextureAtlasSprite("one"),new net.minecraft.client.renderer.texture.TextureAtlasSprite("two")};
 public net.minecraft.client.renderer.texture.TextureAtlasSprite[] getItemSprites(buildcraft.transport.internal.pipe.PipeDefinition d){calls++;return sprites;}}
}
''')
source('net.minecraft.ChatFormatting', 'public enum ChatFormatting {WHITE}')
source('net.minecraft.network.chat.Component', '''
public record Component(String text){public static Component literal(String t){return new Component(t);}public Component withStyle(net.minecraft.ChatFormatting f){return this;}public String getString(){return text;}}
''')
source('net.minecraft.core.component.DataComponentPatch', 'public record DataComponentPatch(String value) {}')
source('net.minecraft.world.level.material.Fluid', 'public record Fluid(String name) {}')
source('net.neoforged.neoforge.fluids.FluidStack', '''
public record FluidStack(net.minecraft.world.level.material.Fluid fluid,int amount,net.minecraft.core.component.DataComponentPatch components) {
 public static final FluidStack EMPTY=new FluidStack(new net.minecraft.world.level.material.Fluid("empty"),0,new net.minecraft.core.component.DataComponentPatch(""));
 public net.minecraft.world.level.material.Fluid getFluid(){return fluid;}public int getAmount(){return amount;}public net.minecraft.core.component.DataComponentPatch getComponentsPatch(){return components;}
}
''')
source('snownee.jade.api.fluid.JadeFluidObject', '''
public record JadeFluidObject(net.minecraft.world.level.material.Fluid type,long amount,net.minecraft.core.component.DataComponentPatch components){
 public static JadeFluidObject of(net.minecraft.world.level.material.Fluid t,long a,net.minecraft.core.component.DataComponentPatch c){return new JadeFluidObject(t,a,c);}
}
''')
source('snownee.jade.api.view.FluidView', '''
public class FluidView {public record Data(java.util.List<snownee.jade.api.fluid.JadeFluidObject> fluids,long capacity){public Data(snownee.jade.api.fluid.JadeFluidObject f,long c){this(java.util.List.of(f),c);}}}
''')
source('snownee.jade.api.view.EnergyView', '''
public class EnergyView {
 public String current,max;public float ratio;public net.minecraft.network.chat.Component overrideText;
 public EnergyView(String current,String max){this.current=current;this.max=max;}
 public record Data(long current,long capacity){}
 public static EnergyView read(Data data,String unit){return data.capacity()<=0?null:new EnergyView(data.current()+" "+unit,data.capacity()+" "+unit);}
}
''')
source('buildcraft.lib.internal.mj.MjFormatting', '''
public class MjFormatting {public static String formatMicroMj(long v){return java.math.BigDecimal.valueOf(v,6).stripTrailingZeros().toPlainString();}}
''')
source('net.neoforged.api.distmarker.Dist', 'public enum Dist {CLIENT,DEDICATED_SERVER}')
source('net.neoforged.bus.api.EventPriority', 'public enum EventPriority {HIGHEST,NORMAL,LOWEST}')
source('net.neoforged.bus.api.SubscribeEvent', '''
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface SubscribeEvent {EventPriority priority() default EventPriority.NORMAL;}
''')
source('net.neoforged.fml.common.EventBusSubscriber', '''
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
public @interface EventBusSubscriber {String modid();net.neoforged.api.distmarker.Dist[] value() default {};}
''')
source('buildcraft.lib.recipe.AssemblyRecipeBasic', 'public class AssemblyRecipeBasic implements net.minecraft.world.item.crafting.Recipe<net.minecraft.world.item.crafting.RecipeInput> {}')
source('buildcraft.silicon.BCSilicon', 'public class BCSilicon {public static final String MODID="buildcraftsilicon";}')
source('buildcraft.silicon.BCSiliconRecipes', '''
public class BCSiliconRecipes {private static final net.minecraft.world.item.crafting.RecipeType<buildcraft.lib.recipe.AssemblyRecipeBasic> TYPE=new net.minecraft.world.item.crafting.RecipeType<>();public static final java.util.function.Supplier<net.minecraft.world.item.crafting.RecipeType<buildcraft.lib.recipe.AssemblyRecipeBasic>> ASSEMBLY_TYPE=()->TYPE;}
''')
source('net.neoforged.neoforge.event.OnDatapackSyncEvent', '''
public class OnDatapackSyncEvent {public java.util.List<net.minecraft.world.item.crafting.RecipeType<?>> requested=new java.util.ArrayList<>();public void sendRecipes(net.minecraft.world.item.crafting.RecipeType<?>... types){requested.addAll(java.util.List.of(types));}}
''')
source('net.neoforged.neoforge.client.event.RecipesReceivedEvent', '''
public record RecipesReceivedEvent(java.util.Set<net.minecraft.world.item.crafting.RecipeType<?>> types,net.minecraft.world.item.crafting.RecipeMap map){
 public java.util.Set<net.minecraft.world.item.crafting.RecipeType<?>> getRecipeTypes(){return types;}public net.minecraft.world.item.crafting.RecipeMap getRecipeMap(){return map;}
}
''')
source('net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent', 'public class ClientPlayerNetworkEvent {public static class LoggingOut {}}')
source('mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension', '''
public interface ICraftingCategoryExtension<R extends net.minecraft.world.item.crafting.CraftingRecipe> {
 java.util.List<net.minecraft.world.item.crafting.display.SlotDisplay> getIngredients(net.minecraft.world.item.crafting.RecipeHolder<R> holder);
 default int getWidth(net.minecraft.world.item.crafting.RecipeHolder<R> holder){return 0;}
 default int getHeight(net.minecraft.world.item.crafting.RecipeHolder<R> holder){return 0;}
}
''')
source('mezz.jei.api.recipe.types.IRecipeType', 'public interface IRecipeType<T> {}')
source('mezz.jei.api.constants.RecipeTypes', '''
public class RecipeTypes {public static final mezz.jei.api.recipe.types.IRecipeType<net.minecraft.world.item.crafting.RecipeHolder<net.minecraft.world.item.crafting.CraftingRecipe>> CRAFTING=new mezz.jei.api.recipe.types.IRecipeType<>(){};}
''')
source('mezz.jei.api.recipe.RecipeIngredientRole', 'public enum RecipeIngredientRole {INPUT,OUTPUT}')
source('mezz.jei.api.gui.ingredient.IRecipeSlotView', '''
public interface IRecipeSlotView {java.util.Optional<net.minecraft.world.item.ItemStack> getDisplayedItemStack();}
''')
source('mezz.jei.api.gui.ingredient.IRecipeSlotsView', '''
public interface IRecipeSlotsView {java.util.List<IRecipeSlotView> getSlotViews(mezz.jei.api.recipe.RecipeIngredientRole role);}
''')
source('mezz.jei.api.recipe.transfer.IRecipeTransferError', 'public interface IRecipeTransferError {}')
source('net.minecraft.world.inventory.AbstractContainerMenu', 'public class AbstractContainerMenu {}')
source('net.minecraft.world.inventory.MenuType', 'public class MenuType<T extends AbstractContainerMenu> {}')
source('mezz.jei.api.recipe.transfer.IRecipeTransferHandler', '''
public interface IRecipeTransferHandler<C extends net.minecraft.world.inventory.AbstractContainerMenu,R> {
 Class<? extends C> getContainerClass();java.util.Optional<net.minecraft.world.inventory.MenuType<C>> getMenuType();
 mezz.jei.api.recipe.types.IRecipeType<R> getRecipeType();
 IRecipeTransferError transferRecipe(C container,R recipe,mezz.jei.api.gui.ingredient.IRecipeSlotsView slots,net.minecraft.world.entity.player.Player player,boolean max,boolean transfer);
}
''')
for package, name, catalog, field in (
    ('buildcraft.factory.container', 'ContainerAutoCraftItems', 'buildcraft.factory.BCFactoryGuis', 'MENU_AUTOWORK_BENCH_ITEM'),
    ('buildcraft.silicon.container', 'ContainerAdvancedCraftingTable', 'buildcraft.silicon.BCSiliconGuis', 'MENU_AD_CRAFTING_TABLE'),
):
    source(package+'.'+name, f'''
public class {name} extends net.minecraft.world.inventory.AbstractContainerMenu {{
 public Object blueprintInv=new Object();public int sent;public java.util.List<net.minecraft.world.item.ItemStack> received;
 public void sendSetPhantomSlots(Object inv,java.util.List<net.minecraft.world.item.ItemStack> list){{if(inv!=blueprintInv)throw new AssertionError();sent++;received=list;}}
}}
''')
    source(catalog, f'''
public class {catalog.rsplit('.',1)[1]} {{public static final java.util.function.Supplier<net.minecraft.world.inventory.MenuType<{package}.{name}>> {field}=net.minecraft.world.inventory.MenuType::new;}}
''')

# Probe harnesses share one checked assertion counter.
source('probe.Checks', '''
public class Checks {
 public static int assertions;
 public static void that(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}
}
''')
source('buildcraft.compat.jei.IntegrationProbe', '''
import static probe.Checks.that;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import buildcraft.transport.recipe.PipeRecipe;
import java.util.*;
public class IntegrationProbe {
 static PipeRecipe recipe(PipeRecipe.Mode mode,Ingredient left,Ingredient middle,Ingredient right,Ingredient from,Ingredient additional,ItemStack result)throws Exception{
  var ctor=PipeRecipe.class.getDeclaredConstructor(String.class,PipeRecipe.Mode.class,Ingredient.class,Ingredient.class,Ingredient.class,Ingredient.class,Ingredient.class,ItemStack.class);
  ctor.setAccessible(true);return ctor.newInstance("pipes",mode,left,middle,right,from,additional,result);
 }
 static RecipeHolder<PipeRecipe> holder(PipeRecipe r){return new RecipeHolder<>(new net.minecraft.resources.ResourceKey<>(new net.minecraft.resources.Identifier("test","pipe")),r);}
 public static void run()throws Exception{
  Item left=new Item(),right=new Item(),base=new Item(),upgraded=new Item(),waterproof=new Item();
  var empty=new Ingredient();var result=new ItemStack(base,8);
  for(DyeColor dye:DyeColor.values()){
   var glass=new BlockItem(new net.minecraft.world.level.block.StainedGlassBlock(dye));
   var r=recipe(PipeRecipe.Mode.BASE,new Ingredient(left),new Ingredient(glass),new Ingredient(right),empty,empty,result);
   that(PlacementInfo.created==dye.ordinal(),"placement must be lazy");
   var info=r.placementInfo();that(info==r.placementInfo(),"placement cache");that(info.ingredients().size()==3,"three inputs");
   var display=(ShapedCraftingRecipeDisplay)r.display().getFirst();that(display.width()==3&&display.height()==1,"native 3x1");
   that(display.ingredients().size()==3,"display inputs");that(((SlotDisplay.ItemStackSlotDisplay)display.result()).stack().getCount()==8,"result count");
   that(((SlotDisplay.ItemSlotDisplay)display.craftingStation()).item()==Items.CRAFTING_TABLE,"vanilla station");
   that(!r.isSpecial(),"recipe book visible");that(r.group().equals("pipes"),"group");
   var ext=PipeCraftingCategoryExtension.INSTANCE;that(ext.getWidth(holder(r))==3&&ext.getHeight(holder(r))==1,"JEI matches vanilla");that(ext.getIngredients(holder(r)).size()==3,"JEI slots");
   for(int row=0;row<3;row++)for(boolean mirror:new boolean[]{false,true}){
    var stacks=new ArrayList<ItemStack>(Collections.nCopies(9,ItemStack.EMPTY));
    stacks.set(row*3,new ItemStack(mirror?right:left));stacks.set(row*3+1,new ItemStack(glass));stacks.set(row*3+2,new ItemStack(mirror?left:right));
    var input=new CraftingInput(3,3,stacks);that(r.matches(input,null),"row/mirror matching");var out=r.assemble(input,null);
    that(out.getCount()==8&&out.data.getIntOr("color",0)==dye.ordinal()+1,"color preserved");that(result.data.getIntOr("color",0)==0,"result template unchanged");
    stacks.set(((row+1)%3)*3,new ItemStack(waterproof));that(!r.matches(new CraftingInput(3,3,stacks),null),"reject extra input");
   }
   var source=new ItemStack(base);source.data.putInt("color",dye.ordinal()+1);
   for(var mode:new PipeRecipe.Mode[]{PipeRecipe.Mode.UPGRADE,PipeRecipe.Mode.DOWNGRADE}){
    var recipe=recipe(mode,empty,empty,empty,new Ingredient(base),new Ingredient(waterproof),new ItemStack(upgraded));
    var stacks=mode==PipeRecipe.Mode.UPGRADE?List.of(new ItemStack(waterproof),source):List.of(source);
    var out=recipe.assemble(new CraftingInput(stacks.size(),1,stacks),null);
    that(out.data.getIntOr("color",0)==dye.ordinal()+1,"conversion color");
    that(recipe.display().getFirst() instanceof ShapelessCraftingRecipeDisplay,"native shapeless");
    that(ext.getWidth(holder(recipe))==0&&ext.getHeight(holder(recipe))==0,"JEI shapeless");
    that(((ShapelessCraftingRecipeDisplay)recipe.display().getFirst()).ingredients().size()==stacks.size(),"shapeless ingredients");
    var buf=new net.minecraft.network.RegistryFriendlyByteBuf();var codec=new PipeRecipe.Serializer().streamCodec();codec.encode(buf,recipe);var copy=codec.decode(buf);
    that(buf.isEmpty()&&copy.assemble(new CraftingInput(stacks.size(),1,stacks),null).data.getIntOr("color",0)==dye.ordinal()+1,"network field order round trip");
   }
   // Upgrade/downport recipes above deliberately do not request their PlacementInfo.
  }
  var r=recipe(PipeRecipe.Mode.BASE,new Ingredient(left),new Ingredient(waterproof),new Ingredient(right),empty,empty,result);
  that(!r.matches(new CraftingInput(2,2,List.of(new ItemStack(left),new ItemStack(waterproof),new ItemStack(right),ItemStack.EMPTY)),null),"2x2 not craftable");
  var stack=new ItemStack(base,5);List<mezz.jei.api.gui.ingredient.IRecipeSlotView> views=new ArrayList<>();
  for(int i=0;i<9;i++){final int index=i;views.add(()->index==4?Optional.of(stack):Optional.empty());}
  mezz.jei.api.gui.ingredient.IRecipeSlotsView slots=role->views;
  var player=new net.minecraft.world.entity.player.Player();
  var auto=new buildcraft.factory.container.ContainerAutoCraftItems();var a=new AutoWorkbenchRecipeTransferHandler();
  that(a.getRecipeType()==mezz.jei.api.constants.RecipeTypes.CRAFTING&&a.getMenuType().isPresent(),"auto registration");
  that(a.transferRecipe(auto,null,slots,player,false,false)==null&&auto.sent==0,"auto dry run");
  a.transferRecipe(auto,null,slots,player,true,true);that(auto.sent==1&&auto.received.size()==9,"auto transfer");
  that(auto.received.get(4)!=stack&&auto.received.get(4).getCount()==5&&auto.received.get(0).isEmpty(),"copy and grid holes");
  auto.received.get(4).setCount(1);that(stack.getCount()==5,"no input mutation");
  var advanced=new buildcraft.silicon.container.ContainerAdvancedCraftingTable();var b=new AdvancedCraftingRecipeTransferHandler();
  that(b.getRecipeType()==mezz.jei.api.constants.RecipeTypes.CRAFTING&&b.getMenuType().isPresent(),"advanced registration");
  b.transferRecipe(advanced,null,slots,player,false,false);that(advanced.sent==0,"advanced dry run");
  b.transferRecipe(advanced,null,slots,player,false,true);that(advanced.sent==1&&advanced.received.get(4)!=stack,"advanced transfer");
  that(CraftingPhantomTransfer.getCraftingGrid(role->List.of()).stream().allMatch(ItemStack::isEmpty),"empty ghost recipe");
 }
}
''')
source('buildcraft.compat.jade.JadeDataProbe', '''
import static probe.Checks.that;
public class JadeDataProbe {public static void run(){
 for(long capacity:new long[]{0,1,999999,1000000,Integer.MAX_VALUE,Long.MAX_VALUE}){
  for(long current:new long[]{-1,0,1,1500000,Long.MAX_VALUE}){
   var data=JadeViewData.energy(current,capacity);that(data.capacity()==capacity,"capacity precision");that(data.current()==Math.max(0,Math.min(capacity,current)),"bounded energy");
   var view=JadeViewData.readEnergy(data,"MJ");that((view==null)==(capacity==0),"zero capacity");
   if(view!=null){that(Float.isFinite(view.ratio)&&view.ratio>=0&&view.ratio<=1,"finite ratio");that(view.current.endsWith(" MJ"),"MJ units");}
  }
 }
 that(JadeViewData.readEnergy(JadeViewData.energy(1500000,2000000),"MJ").current.equals("1.5 MJ"),"fractional MJ not truncated");
 that(JadeViewData.readEnergy(JadeViewData.energy(1500000,2000000),"FE").current.equals("1500000 FE"),"FE not scaled");
 that(JadeViewData.energy(1,-1).capacity()==0,"negative capacity");
 var fluid=new net.minecraft.world.level.material.Fluid("oil");var components=new net.minecraft.core.component.DataComponentPatch("grade=hot");
 var data=JadeViewData.fluid(new net.neoforged.neoforge.fluids.FluidStack(fluid,250,components),4000);
 that(data.capacity()==4000&&data.fluids().getFirst().amount()==250,"fluid millibuckets");that(data.fluids().getFirst().components()==components,"fluid components preserved");
 that(data.fluids().getFirst().type()==fluid,"fluid identity");that(JadeViewData.fluid(null,-1).capacity()==0,"empty malformed tank");
}}
''')
source('probe.SyncProbe', '''
import static probe.Checks.that;
import buildcraft.silicon.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.client.event.*;
import java.util.*;
public class SyncProbe {public static void run() throws Exception {
 var request=new net.neoforged.neoforge.event.OnDatapackSyncEvent();BCSiliconRecipeSync.onDatapackSync(request);
 that(request.requested.equals(List.of(BCSiliconRecipes.ASSEMBLY_TYPE.get())),"server requests correct recipe type");
 var entry=new RecipeHolder<>(new net.minecraft.resources.ResourceKey<Recipe<?>>(new net.minecraft.resources.Identifier("test","assembly")),new buildcraft.lib.recipe.AssemblyRecipeBasic());
 var input=new ArrayList<>(List.of(entry));var event=new RecipesReceivedEvent(Set.of(BCSiliconRecipes.ASSEMBLY_TYPE.get()),new RecipeMap(input));
 BCSiliconRecipeSync.Client.onRecipes(event);that(BCSiliconRecipeSync.Client.assemblyRecipes().size()==1,"received recipes");input.clear();that(BCSiliconRecipeSync.Client.assemblyRecipes().size()==1,"detached immutable cache");
 boolean immutable=false;try{BCSiliconRecipeSync.Client.assemblyRecipes().clear();}catch(UnsupportedOperationException e){immutable=true;}that(immutable,"immutable accessor");
 BCSiliconRecipeSync.Client.onRecipes(new RecipesReceivedEvent(Set.of(),new RecipeMap(List.of())));that(BCSiliconRecipeSync.Client.assemblyRecipes().size()==1,"unrelated types do not clear cache");
 BCSiliconRecipeSync.Client.onRecipes(new RecipesReceivedEvent(Set.of(BCSiliconRecipes.ASSEMBLY_TYPE.get()),new RecipeMap(List.of())));that(BCSiliconRecipeSync.Client.assemblyRecipes().isEmpty(),"reload removals replace old recipes");
 BCSiliconRecipeSync.Client.onRecipes(event);BCSiliconRecipeSync.Client.onLogout(new ClientPlayerNetworkEvent.LoggingOut());that(BCSiliconRecipeSync.Client.assemblyRecipes().isEmpty(),"logout clears recipes");
 that(BCSiliconRecipeSync.Client.class.getMethod("onRecipes",RecipesReceivedEvent.class).getAnnotation(net.neoforged.bus.api.SubscribeEvent.class).priority()==net.neoforged.bus.api.EventPriority.HIGHEST,"cache before JEI listener");
 that(BCSiliconRecipeSync.Client.class.getAnnotation(net.neoforged.fml.common.EventBusSubscriber.class).value()[0]==net.neoforged.api.distmarker.Dist.CLIENT,"client side registration");
}}
''')
source('probe.ModelProbe', '''
import static probe.Checks.that;
import buildcraft.transport.client.model.*;
import buildcraft.transport.internal.pipe.*;
import net.minecraft.world.item.*;
import net.minecraft.client.renderer.item.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.util.*;
public class ModelProbe {
 static final class Base implements ItemModel {
  int calls;
  final BlockModelWrapper layer=new BlockModelWrapper(List.of(),List.of(),
   new ModelRenderProperties(true,new net.minecraft.client.renderer.texture.TextureAtlasSprite("base"),buildcraft.lib.client.model.ModelItemSimple.TRANSFORM_BLOCK),
   c->new RenderType("base"));
  public void update(ItemStackRenderState state,ItemStack stack,ItemModelResolver resolver,ItemDisplayContext context,
   net.minecraft.client.multiplayer.ClientLevel level,net.minecraft.world.entity.ItemOwner owner,int seed){calls++;state.layers.add(layer);}
 }
 static ItemStackRenderState render(ModelPipeItem m,int color,ItemDisplayContext context){var state=new ItemStackRenderState();var stack=new ItemStack(new Item());stack.data.putInt("color",color);m.update(state,stack,null,context,null,null,42);return state;}
 public static void run(){
  var d=new PipeDefinition(PipeFaceTex.get(0));var base=new Base();var model=new ModelPipeItem(d,base);
  for(var context:ItemDisplayContext.values()){
   var plain=render(model,0,context);that(plain.layers.size()==1&&plain.layers.getFirst()==base.layer,"plain pipe delegates baked JSON body");
   var dyed=render(model,1,context);that(dyed.layers.size()==2&&dyed.layers.getFirst()==base.layer,"dyed pipe keeps baked JSON body");
   var overlay=dyed.layers.get(1);that(overlay.quads().size()==6,"dye sleeve faces");that(overlay.renderType().apply(context).name().equals("translucent"),"translucent dye layer");
   that(overlay.quads().getFirst().colors().a()==0xff123400,"ARGB overlay colour");
  }
  that(render(model,-1,ItemDisplayContext.GUI).layers.size()==1&&render(model,100,ItemDisplayContext.GUI).layers.size()==1,"invalid color safe");
  for(var kind:new EnumPipeColourType[]{EnumPipeColourType.BORDER_INNER,EnumPipeColourType.BORDER_OUTER}){
   var def=new PipeDefinition(PipeFaceTex.get(0));def.colourType=kind;var b=new Base();var layers=render(new ModelPipeItem(def,b),1,ItemDisplayContext.GUI).layers;
   that(layers.size()==2&&layers.getFirst()==b.layer&&layers.get(1).quads().size()==6,"border overlay preserves base");
   that(layers.get(1).renderType().apply(ItemDisplayContext.GUI).name().equals("cutout"),"border cutout layer");
  }
  var custom=new PipeDefinition(PipeFaceTex.get(0));custom.colourType=EnumPipeColourType.CUSTOM;var customBase=new Base();
  that(render(new ModelPipeItem(custom,customBase),1,ItemDisplayContext.GUI).layers.size()==1,"custom colouring remains owned by baked base model");
  that(base.calls>0,"base model invoked");
 }
}
''')
source('probe.Main', '''
public class Main {public static void main(String[] args)throws Exception {
 buildcraft.compat.jei.IntegrationProbe.run();probe.ModelProbe.run();buildcraft.compat.jade.JadeDataProbe.run();probe.SyncProbe.run();
 System.out.println("Client integration assertions: "+Checks.assertions);
}}
''')

PRODUCTION = [
    'buildcraft/transport/recipe/PipeRecipe.java',
    'buildcraft/transport/client/model/ModelPipeItem.java',
    'buildcraft/transport/internal/pipe/PipeFaceTex.java',
    'buildcraft/transport/internal/pipe/EnumPipeColourType.java',
    'buildcraft/lib/compat/minecraft/model/NativeItemModelBuilder.java',
    'buildcraft/compat/jei/PipeCraftingCategoryExtension.java',
    'buildcraft/compat/jei/CraftingPhantomTransfer.java',
    'buildcraft/compat/jei/AutoWorkbenchRecipeTransferHandler.java',
    'buildcraft/compat/jei/AdvancedCraftingRecipeTransferHandler.java',
    'buildcraft/compat/jade/JadeViewData.java',
    'buildcraft/silicon/BCSiliconRecipeSync.java',
]


def run_probe(effective: Path) -> str:
    with tempfile.TemporaryDirectory(prefix='bc-client-integrations-') as temp:
        root = Path(temp)
        for name, text in SOURCES.items():
            file = root / 'src' / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(text, encoding='utf-8')
        for name in PRODUCTION:
            file = root / 'src' / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes((effective / 'src/main/java' / name).read_bytes())
        args = root / 'sources.txt'
        args.write_text('\n'.join(str(p) for p in sorted((root/'src').rglob('*.java'))), encoding='utf-8')
        compile_result = subprocess.run(['javac','--release','21','-d',str(root/'classes'),'@'+str(args)], capture_output=True, text=True, timeout=120)
        if compile_result.returncode:
            raise AssertionError(compile_result.stdout + compile_result.stderr)
        run = subprocess.run(['java','-cp',str(root/'classes'),'probe.Main'], capture_output=True, text=True, timeout=120)
        if run.returncode:
            raise AssertionError(run.stdout + run.stderr)
        return run.stdout.strip()
