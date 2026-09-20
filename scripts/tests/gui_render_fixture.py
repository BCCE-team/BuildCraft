"""Small headless API-contract fixtures; these are NOT a replacement for Minecraft/NeoForge integration tests.
The regression test compiles the real target sprite classes against these fixtures, then executes their
submission/vertex code after changing the live GUI state. No pixels or Minecraft worlds are rendered.
"""
STUBS = {
'javax/annotation/Nullable.java': 'package javax.annotation; public @interface Nullable {}',
'org/joml/Matrix3x2f.java': '''package org.joml;
public class Matrix3x2f {
 public float sx=1,sy=1,tx=0,ty=0;
 public Matrix3x2f() {} public Matrix3x2f(Matrix3x2f p) {sx=p.sx;sy=p.sy;tx=p.tx;ty=p.ty;}
 public Matrix3x2f translate(float x,float y){tx+=sx*x;ty+=sy*y;return this;}
 public Matrix3x2f scale(float x,float y){sx*=x;sy*=y;return this;}
 public Matrix3x2f identity(){sx=sy=1;tx=ty=0;return this;}
}''',
'com/mojang/blaze3d/pipeline/RenderPipeline.java': 'package com.mojang.blaze3d.pipeline; public class RenderPipeline {}',
'com/mojang/blaze3d/vertex/VertexConsumer.java': '''package com.mojang.blaze3d.vertex;
import java.util.*; import org.joml.Matrix3x2f;
public class VertexConsumer {
 public static class V { public float x,y,u,v; public int c; V(float x,float y){this.x=x;this.y=y;} }
 public final List<V> vertices=new ArrayList<>();
 public VertexConsumer addVertexWith2DPose(Matrix3x2f p,float x,float y){vertices.add(new V(p.tx+p.sx*x,p.ty+p.sy*y));return this;}
 public VertexConsumer setUv(float u,float v){V x=vertices.get(vertices.size()-1);x.u=u;x.v=v;return this;}
 public VertexConsumer setColor(int c){vertices.get(vertices.size()-1).c=c;return this;}
}''',
'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources;
public record Identifier(String value) {
 public static Identifier parse(String s){return new Identifier(s);} public String toString(){return value;}
}''',
'net/minecraft/client/renderer/RenderPipelines.java': '''package net.minecraft.client.renderer;
import com.mojang.blaze3d.pipeline.RenderPipeline; public class RenderPipelines {
 public static final RenderPipeline GUI_TEXTURED=new RenderPipeline();
}''',
'net/minecraft/client/gui/navigation/ScreenRectangle.java': '''package net.minecraft.client.gui.navigation;
import org.joml.Matrix3x2f;
public record ScreenRectangle(int x,int y,int width,int height) {
 public ScreenRectangle transformMaxBounds(Matrix3x2f p){
  int l=(int)Math.floor(p.tx+x*p.sx),t=(int)Math.floor(p.ty+y*p.sy);
  return new ScreenRectangle(l,t,(int)Math.ceil(p.tx+(x+width)*p.sx)-l,(int)Math.ceil(p.ty+(y+height)*p.sy)-t);
 }
 public ScreenRectangle intersection(ScreenRectangle b){int l=Math.max(x,b.x),t=Math.max(y,b.y),r=Math.min(x+width,b.x+b.width),d=Math.min(y+height,b.y+b.height);return r<=l||d<=t?null:new ScreenRectangle(l,t,r-l,d-t);}
}''',
'net/minecraft/client/gui/render/TextureSetup.java': '''package net.minecraft.client.gui.render;
public record TextureSetup(Object view,Object sampler) {public static TextureSetup singleTexture(Object v,Object s){return new TextureSetup(v,s);}}''',
'net/minecraft/client/gui/render/state/GuiElementRenderState.java': '''package net.minecraft.client.gui.render.state;
import net.minecraft.client.gui.render.TextureSetup; import net.minecraft.client.gui.navigation.ScreenRectangle;
import com.mojang.blaze3d.pipeline.RenderPipeline; import com.mojang.blaze3d.vertex.VertexConsumer;
public interface GuiElementRenderState { ScreenRectangle bounds(); ScreenRectangle scissorArea(); RenderPipeline pipeline(); TextureSetup textureSetup(); void buildVertices(VertexConsumer c); }
''',
'net/minecraft/client/gui/GuiGraphics.java': '''package net.minecraft.client.gui;
import org.joml.Matrix3x2f; import java.util.*; import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.navigation.ScreenRectangle;
public class GuiGraphics {
 public final Matrix3x2f livePose=new Matrix3x2f(); public ScreenRectangle clip;
 public final List<GuiElementRenderState> states=new ArrayList<>();
 public Matrix3x2f pose(){return livePose;} public ScreenRectangle peekScissorStack(){return clip;}
 public void submitGuiElementRenderState(GuiElementRenderState s){states.add(s);}
}''',
'net/minecraft/client/renderer/texture/AbstractTexture.java': '''package net.minecraft.client.renderer.texture;
public class AbstractTexture { private final Object view; private final Object sampler=new Object(); public AbstractTexture(Object id){view=id;}
 public Object getTextureView(){return view;} public Object getSampler(){return sampler;}}
''',
'net/minecraft/client/renderer/texture/TextureAtlas.java': '''package net.minecraft.client.renderer.texture;
import net.minecraft.resources.Identifier; public class TextureAtlas {public static final Identifier LOCATION_BLOCKS=Identifier.parse("minecraft:atlas/blocks");}''',
'net/minecraft/client/renderer/texture/TextureManager.java': '''package net.minecraft.client.renderer.texture;
import net.minecraft.resources.Identifier; public class TextureManager {public AbstractTexture getTexture(Identifier id){return new AbstractTexture(id);}}''',
'net/minecraft/client/Minecraft.java': '''package net.minecraft.client;
import net.minecraft.client.renderer.texture.TextureManager;
public class Minecraft {private static final Minecraft MC=new Minecraft(); public static Minecraft getInstance(){return MC;}
 public TextureManager getTextureManager(){return new TextureManager();}}
''',
'buildcraft/lib/compat/RenderCompat.java': '''package buildcraft.lib.compat;
import net.minecraft.resources.Identifier; public class RenderCompat { public static Identifier texture; public static Identifier getShaderTexture(){return texture;}}
''',
'buildcraft/lib/internal/core/render/ISprite.java': '''package buildcraft.lib.internal.core.render;
public interface ISprite {void bindTexture();float getInterpU(double u);float getInterpV(double v);}
''',
'buildcraft/lib/gui/pos/IGuiArea.java': '''package buildcraft.lib.gui.pos;
public interface IGuiArea {double getX();double getY();double getWidth();double getHeight();}
''',
}

PROBE = r'''
import java.util.*;
import buildcraft.lib.client.sprite.*;
import buildcraft.lib.compat.ItemNameKeys121111;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.core.render.ISprite;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.vertex.VertexConsumer;

public class GuiPortProbe {
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 static void eq(float a,float b,String label){check(Math.abs(a-b)<.0001,label+" "+a+" != "+b);}
 static class Sprite implements ISprite {
  String id; float u0,v0,du,dv; Sprite(String id,float u0,float v0,float du,float dv){this.id=id;this.u0=u0;this.v0=v0;this.du=du;this.dv=dv;}
  public void bindTexture(){RenderCompat.texture=Identifier.parse(id);}
  public float getInterpU(double u){return u0+(float)u*du;}public float getInterpV(double v){return v0+(float)v*dv;}
 }
 public static void main(String[] args) {
  Sprite sprite=new Sprite("buildcraftlib:ledger",0,0,1,1);
  GuiGraphics g=new GuiGraphics();g.pose().translate(10,20).scale(2,2);g.clip=new ScreenRectangle(0,0,1000,1000);
  SpriteNineSliced nine=new SpriteNineSliced(sprite,4,4,12,12,16);
  nine.draw(g,5,7,80,30,0xFFCC99FF);
  check(g.states.size()==9,"nine nonempty submitted regions");
  g.pose().identity();g.clip=null; RenderCompat.texture=Identifier.parse("other:texture");
  VertexConsumer out=new VertexConsumer();for(var state:g.states){state.buildVertices(out);check(state.bounds()!=null,"valid bounds");check(state.scissorArea()!=null,"captured scissor");check(state.textureSetup().view().equals(Identifier.parse(sprite.id)),"captured texture");}
  check(out.vertices.size()==36,"quad vertex count");
  for(var vertex:out.vertices)check(vertex.c==0xFFCC99FF,"captured ledger ARGB tint");
  eq(out.vertices.get(0).x,20,"captured transform x");eq(out.vertices.get(0).y,34,"captured transform y");
  eq(out.vertices.get(2).x,28,"four pixel border x");eq(out.vertices.get(2).u,.25f,"quarter border UV");
  check(GuiSpriteRender121111.opaqueRgb(0)==0xFF000000,"black help text opaque");
  check(GuiSpriteRender121111.opaqueRgb(0x30251D)==0xFF30251D,"old RGB opaque");
  check(GuiSpriteRender121111.opaqueRgb(0x80112233)==0x80112233,"explicit alpha preserved");
  int count=g.states.size();nine.draw(g,0,0,80,30,0);check(g.states.size()==count,"explicit transparent sprite not forced opaque");
  g.states.clear();g.clip=new ScreenRectangle(500,500,20,20);nine.draw(g,0,0,30,30,0xFFFFFFFF);check(g.states.isEmpty(),"fully clipped quad not submitted");
  g.clip=null;g.states.clear();nine.draw(g,0,0,3,2,0xFFFFFFFF);check(g.states.size()==1,"small target never inverts center/edges");
  g.states.clear();new SpriteNineSliced(sprite,8,8,24,24,24,32).draw(g,0,0,100,40,0xFFFFFFFF);check(g.states.size()==6,"open right tab cap");
  g.states.clear();GuiSpriteRender121111.draw(g,new Sprite("skin:owner",.125f,.125f,.125f,.125f),0,0,16,16,0,0,1,1,0xFFFFFFFF);
  out=new VertexConsumer();g.states.get(0).buildVertices(out);eq(out.vertices.get(0).u,.125f,"skin face U origin");eq(out.vertices.get(2).v,.25f,"skin face V end");
  GuideProbe guide=new GuideProbe();check(guide.keyPressed(new KeyEvent(256,99,7)),"ESC consumed");check(guide.closed&&!guide.legacyCalled,"ESC precedes text field that consumes keys");
  guide=new GuideProbe();check(guide.keyPressed(new KeyEvent(65,44,0)),"ordinary key delegated");check(!guide.closed&&guide.legacyCalled,"ordinary key does not close");
  OwnerProbe owner=new OwnerProbe();GameProfile alice=new GameProfile(UUID.fromString("dd97812c-1d0c-417f-87aa-5238ca06a09c"),"Alice");Player a=new Player(alice);
  check(owner.getOwner()==FakePlayerProvider.NULL_PROFILE,"unknown permission fallback");check(owner.getKnownOwner()==null,"reading fallback does not claim ownership");
  owner.onPlacedBy(a,new ItemStack());check(owner.dirty==1,"placement marks chunk changed");check(owner.getKnownOwner().equals(alice),"placement owner");
  CompoundTag saved=OwnerProbe.writeGameProfile(alice);OwnerProbe restored=new OwnerProbe();restored.owner=OwnerProbe.readGameProfile(saved);check(restored.getKnownOwner().equals(alice),"owner UUID/name NBT round trip");
  Player b=new Player(new GameProfile(UUID.fromString("24938ae6-2b9d-4d55-8cb8-0d1ce189df3d"),"Bob"));restored.onPlayerOpen(b);check(restored.owner.equals(alice),"another viewer cannot overwrite recorded owner");
  OwnerProbe legacy=new OwnerProbe();legacy.getOwner();legacy.onPlayerOpen(a);check(legacy.owner.equals(alice)&&legacy.dirty==1,"legacy owner claim is persisted after a passive read");
  OwnerProbe client=new OwnerProbe();client.level.client=true;client.onPlacedBy(a,new ItemStack());client.onPlayerOpen(a);check(client.owner==null&&client.dirty==0,"client cannot author ownership");
  check(ItemNameKeys121111.key(Identifier.parse("buildcrafttransport:wire/light_gray")).equals("item.pipewire.light_gray"),"wire canonical key");
  check(ItemNameKeys121111.key(Identifier.parse("buildcraftsilicon:redstone_chipset/gold")).equals("item.buildcraftsilicon.redstone_gold_chipset"),"chipset key");
  check(ItemNameKeys121111.key(Identifier.parse("addon:unknown"))==null,"addon naming unchanged");
  System.out.println("Headless actual-code probes passed: "+checks+" assertions (no game launched)");
 }
 static class InputConstants {static final int KEY_ESCAPE=256;}
 record KeyEvent(int key,int scancode,int modifiers) {}
 static class ScreenProbe {public boolean keyPressed(KeyEvent event){return false;}}
 static class GuideProbe extends ScreenProbe {
  boolean closed,legacyCalled; void onClose(){closed=true;}
  public boolean keyPressed(int key,int scan,int mods){legacyCalled=true;return true;}
  /* GUIDE_METHOD */
 }
 @interface Nullable {}
 record GameProfile(UUID id,String name){}
 static class GameProfileCompat {static UUID id(GameProfile p){return p.id();}static String name(GameProfile p){return p.name();}}
 static class FakePlayerProvider {static final GameProfile NULL_PROFILE=new GameProfile(new UUID(0,0),"[BuildCraft]");}
 static class LivingEntity {} static class Player extends LivingEntity {GameProfile profile;Player(GameProfile p){profile=p;}GameProfile getGameProfile(){return profile;}}
 static class ItemStack {} static class Level {boolean client;boolean isClientSide(){return client;}}
 static class BCLog {static Logger logger=new Logger();} static class Logger {void warn(String s){}}
 static class Tag {static final int TAG_STRING=8;}
 static class CompoundTag {Map<String,Object> values=new HashMap<>();void putString(String k,String v){values.put(k,v);}}
 static class NbtCompat {static boolean hasUUID(CompoundTag t,String k){return t.values.get(k) instanceof UUID;}
 static UUID getUUID(CompoundTag t,String k){return (UUID)t.values.get(k);}static void putUUID(CompoundTag t,String k,UUID v){t.values.put(k,v);}
 static boolean contains(CompoundTag t,String k,int v){return t.values.get(k) instanceof String;}static String getString(CompoundTag t,String k){return (String)t.values.get(k);}}
 static class OwnerProbe {
  Level level=new Level();GameProfile owner;int dirty;Set<Player> usingPlayers=new HashSet<>();final int NET_GUI_DATA=1;
  void setChanged(){dirty++;}Object getBlockPos(){return "pos";}void sendNetworkUpdate(int id,Player p){}
  /* OWNER_METHODS */
 }
}
'''
