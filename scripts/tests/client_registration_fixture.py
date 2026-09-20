"""Compile real client adapters against small version-shaped native event doubles."""
from pathlib import Path
from actor_client_fixture import compile_probe


def stubs_for(target: str) -> dict[str, str]:
    forge, newest, oldest = target.endswith('-forge'), target.startswith('1.21.11'), target.startswith('1.19.2')
    native='net.minecraftforge' if forge else 'net.neoforged.neoforge'
    fml='net.minecraftforge.fml' if forge else 'net.neoforged.fml'
    identifier='Identifier' if newest else 'ResourceLocation'
    key='ResourceLocation' if forge else 'ModelResourceLocation'
    stubs={}
    def put(name,text):stubs[name.replace('.','/')+'.java']=text
    put('net.minecraft.resources.'+identifier,f'package net.minecraft.resources;public record {identifier}(String value){{}}')
    put('net.minecraft.client.resources.model.ModelResourceLocation','package net.minecraft.client.resources.model;public record ModelResourceLocation(String value){}')
    put('net.minecraft.world.inventory.AbstractContainerMenu','package net.minecraft.world.inventory;public class AbstractContainerMenu{}')
    put('net.minecraft.world.inventory.MenuType','package net.minecraft.world.inventory;public class MenuType<M extends AbstractContainerMenu>{}')
    put('net.minecraft.world.entity.player.Inventory','package net.minecraft.world.entity.player;public class Inventory{}')
    put('net.minecraft.network.chat.Component','package net.minecraft.network.chat;public record Component(String value){}')
    put('net.minecraft.client.gui.screens.Screen','package net.minecraft.client.gui.screens;public class Screen{}')
    put('net.minecraft.client.gui.screens.inventory.MenuAccess','package net.minecraft.client.gui.screens.inventory;import net.minecraft.world.inventory.AbstractContainerMenu;public interface MenuAccess<M extends AbstractContainerMenu>{M getMenu();}')
    menus='''package net.minecraft.client.gui.screens;import java.util.*;import net.minecraft.client.gui.screens.inventory.MenuAccess;import net.minecraft.world.inventory.*;import net.minecraft.world.entity.player.Inventory;import net.minecraft.network.chat.Component;
public class MenuScreens{public static final Map<MenuType<?>,ScreenConstructor<?,?>> registry=new IdentityHashMap<>();public static int calls;
public interface ScreenConstructor<M extends AbstractContainerMenu,S extends Screen & MenuAccess<M>>{S create(M m,Inventory i,Component t);}
public static <M extends AbstractContainerMenu,S extends Screen & MenuAccess<M>>void register(MenuType<? extends M> type,ScreenConstructor<M,S> factory){registry.put(type,factory);calls++;}}'''
    put('net.minecraft.client.gui.screens.MenuScreens',menus)
    put(fml+'.event.lifecycle.FMLClientSetupEvent',f'''package {fml}.event.lifecycle;import java.util.*;import java.util.concurrent.*;public class FMLClientSetupEvent{{public final List<Runnable> tasks=new ArrayList<>();public CompletableFuture<Void> enqueueWork(Runnable r){{tasks.add(r);return new CompletableFuture<>();}}public void drain(){{var pending=new ArrayList<>(tasks);tasks.clear();pending.forEach(Runnable::run);}}}}''')
    if not forge:
        put(native+'.client.event.RegisterMenuScreensEvent',f'''package {native}.client.event;import net.minecraft.client.gui.screens.*;import net.minecraft.client.gui.screens.inventory.*;import net.minecraft.world.inventory.*;
public class RegisterMenuScreensEvent{{public int calls;public <M extends AbstractContainerMenu,S extends Screen & MenuAccess<M>> void register(MenuType<? extends M> type,MenuScreens.ScreenConstructor<M,S> factory){{calls++;MenuScreens.register(type,factory);}}}}''')
    for class_name in ('net.minecraft.world.entity.Entity','net.minecraft.world.level.block.entity.BlockEntity','net.minecraft.world.level.block.Block','net.minecraft.world.level.block.state.BlockState'):
        pkg,cls=class_name.rsplit('.',1);put(class_name,f'package {pkg};public class {cls}{{}}')
    put('net.minecraft.world.entity.EntityType','package net.minecraft.world.entity;public class EntityType<T extends Entity>{}')
    put('net.minecraft.world.level.block.entity.BlockEntityType','package net.minecraft.world.level.block.entity;public class BlockEntityType<T extends BlockEntity>{}')
    # Keep the real versioned renderer-provider shape: 1.21.11 adds the render-state type parameter.
    if newest:
        put('net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState','package net.minecraft.client.renderer.blockentity.state;public class BlockEntityRenderState{}')
        put('net.minecraft.client.renderer.blockentity.BlockEntityRenderer','''package net.minecraft.client.renderer.blockentity;import net.minecraft.world.level.block.entity.BlockEntity;import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
public class BlockEntityRenderer<T extends BlockEntity,S extends BlockEntityRenderState>{}''')
        put('net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider','''package net.minecraft.client.renderer.blockentity;import net.minecraft.world.level.block.entity.BlockEntity;import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
public interface BlockEntityRendererProvider<T extends BlockEntity,S extends BlockEntityRenderState>{BlockEntityRenderer<T,S> create(Context c);class Context{}}''')
    else:
        put('net.minecraft.client.renderer.blockentity.BlockEntityRenderer','''package net.minecraft.client.renderer.blockentity;import net.minecraft.world.level.block.entity.BlockEntity;
public class BlockEntityRenderer<T extends BlockEntity>{}''')
        put('net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider','''package net.minecraft.client.renderer.blockentity;import net.minecraft.world.level.block.entity.BlockEntity;
public interface BlockEntityRendererProvider<T extends BlockEntity>{BlockEntityRenderer<T> create(Context c);class Context{}}''')
    put('net.minecraft.client.renderer.entity.EntityRendererProvider','''package net.minecraft.client.renderer.entity;import net.minecraft.world.entity.Entity;
public interface EntityRendererProvider<T extends Entity>{Object create(Context c);class Context{}}''')
    if newest:
        put(native+'.client.event.EntityRenderersEvent',f'''package {native}.client.event;import java.util.*;import net.minecraft.client.renderer.blockentity.*;import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;import net.minecraft.client.renderer.entity.*;import net.minecraft.world.entity.*;import net.minecraft.world.level.block.entity.*;
public class EntityRenderersEvent{{public static class RegisterRenderers{{public final List<Object> keys=new ArrayList<>(),factories=new ArrayList<>();
public <T extends BlockEntity,S extends BlockEntityRenderState> void registerBlockEntityRenderer(BlockEntityType<? extends T> type,BlockEntityRendererProvider<T,S> factory){{keys.add(type);factories.add(factory);}}
public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type,EntityRendererProvider<T> factory){{keys.add(type);factories.add(factory);}}}}}}''')
    else:
        put(native+'.client.event.EntityRenderersEvent',f'''package {native}.client.event;import java.util.*;import net.minecraft.client.renderer.blockentity.*;import net.minecraft.client.renderer.entity.*;import net.minecraft.world.entity.*;import net.minecraft.world.level.block.entity.*;
public class EntityRenderersEvent{{public static class RegisterRenderers{{public final List<Object> keys=new ArrayList<>(),factories=new ArrayList<>();
public <T extends BlockEntity> void registerBlockEntityRenderer(BlockEntityType<? extends T> type,BlockEntityRendererProvider<T> factory){{keys.add(type);factories.add(factory);}}
public <T extends Entity> void registerEntityRenderer(EntityType<? extends T> type,EntityRendererProvider<T> factory){{keys.add(type);factories.add(factory);}}}}}}''')
    put('net.minecraft.client.color.block.BlockColor','package net.minecraft.client.color.block;public interface BlockColor{int colour();}')
    put('net.minecraft.client.color.item.ItemColor','package net.minecraft.client.color.item;public interface ItemColor{int colour();}')
    put('net.minecraft.world.level.ItemLike','package net.minecraft.world.level;public interface ItemLike{}')
    colour_head=f'''package {native}.client.event;import net.minecraft.client.color.block.BlockColor;import net.minecraft.world.level.block.Block;
public class RegisterColorHandlersEvent{{public static class Block{{public BlockColor colour;public net.minecraft.world.level.block.Block[] blocks;public int calls;public void register(BlockColor c,net.minecraft.world.level.block.Block...b){{colour=c;blocks=b;calls++;}}}}
'''
    if newest:
        put('net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty','package net.minecraft.client.renderer.item.properties.numeric;public interface RangeSelectItemModelProperty{}')
        put('net.minecraft.client.color.item.ItemTintSource','package net.minecraft.client.color.item;public interface ItemTintSource{}')
        put('com.mojang.serialization.MapCodec','package com.mojang.serialization;public class MapCodec<T>{}')
        colour_tail='''public static class ItemTintSources{public int calls;public Object key,codec;public void register(net.minecraft.resources.Identifier k,com.mojang.serialization.MapCodec<? extends net.minecraft.client.color.item.ItemTintSource> c){calls++;key=k;codec=c;}}}'''
        put(native+'.client.event.RegisterRangeSelectItemModelPropertyEvent',f'''package {native}.client.event;import net.minecraft.resources.Identifier;import com.mojang.serialization.MapCodec;import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
public class RegisterRangeSelectItemModelPropertyEvent{{public int calls;public Identifier key;public MapCodec<? extends RangeSelectItemModelProperty> codec;public void register(Identifier k,MapCodec<? extends RangeSelectItemModelProperty> c){{calls++;key=k;codec=c;}}}}''')
    else:
        colour_tail='''public static class Item{public net.minecraft.client.color.item.ItemColor colour;public net.minecraft.world.level.ItemLike[] items;public int calls;public void register(net.minecraft.client.color.item.ItemColor c,net.minecraft.world.level.ItemLike...i){colour=c;items=i;calls++;}}}'''
    put(native+'.client.event.RegisterColorHandlersEvent',colour_head+colour_tail)
    put('net.minecraft.client.renderer.texture.TextureAtlas',f'package net.minecraft.client.renderer.texture;import net.minecraft.resources.{identifier};public class TextureAtlas{{public final {identifier} id;public TextureAtlas({identifier} i){{id=i;}}public {identifier} location(){{return id;}}}}')
    if forge:
        put(native+'.client.event.TextureStitchEvent',f'''package {native}.client.event;import java.util.*;import net.minecraft.resources.ResourceLocation;import net.minecraft.client.renderer.texture.TextureAtlas;
public class TextureStitchEvent{{public static class Post{{private final TextureAtlas atlas;public Post(TextureAtlas a){{atlas=a;}}public TextureAtlas getAtlas(){{return atlas;}}}}
public static class Pre{{public final Set<ResourceLocation> sprites=new LinkedHashSet<>();private final TextureAtlas atlas;public Pre(TextureAtlas a){{atlas=a;}}public TextureAtlas getAtlas(){{return atlas;}}public boolean addSprite(ResourceLocation i){{return sprites.add(i);}}}}}}''')
    else:
        put(native+'.client.event.TextureAtlasStitchedEvent',f'package {native}.client.event;import net.minecraft.client.renderer.texture.TextureAtlas;public record TextureAtlasStitchedEvent(TextureAtlas getAtlas){{}}')
    if not newest:
        put('net.minecraft.client.renderer.RenderType','package net.minecraft.client.renderer;public enum RenderType {CUTOUT,TRANSLUCENT;public static RenderType cutout(){return CUTOUT;}public static RenderType translucent(){return TRANSLUCENT;}}')
        put('net.minecraft.client.renderer.ItemBlockRenderTypes','package net.minecraft.client.renderer;import java.util.*;import net.minecraft.world.level.block.Block;public class ItemBlockRenderTypes{public static final Map<Block,RenderType> layers=new IdentityHashMap<>();public static int calls;public static void setRenderLayer(Block b,RenderType t){calls++;layers.put(b,t);}}')
    if newest:
        for name in ('net.minecraft.client.resources.model.QuadCollection','net.minecraft.client.renderer.block.model.BlockStateModel','net.minecraft.client.renderer.item.ItemModel'):
            pkg,cls=name.rsplit('.',1);put(name,f'package {pkg};public class {cls}{{}}')
        pack=native+'.client.model.standalone'
        put(pack+'.StandaloneModelKey',f'package {pack};import java.util.function.Supplier;public class StandaloneModelKey<T>{{public final Supplier<String> label;public StandaloneModelKey(Supplier<String> l){{label=l;}}}}')
        put(pack+'.SimpleUnbakedStandaloneModel',f'''package {pack};import net.minecraft.resources.Identifier;import net.minecraft.client.resources.model.QuadCollection;
public class SimpleUnbakedStandaloneModel<T>{{public final Identifier location;public SimpleUnbakedStandaloneModel(Identifier l){{location=l;}}public static SimpleUnbakedStandaloneModel<QuadCollection> quadCollection(Identifier i){{return new SimpleUnbakedStandaloneModel<>(i);}}}}''')
        put('net.minecraft.client.resources.model.ModelManager',f'''package net.minecraft.client.resources.model;import java.util.*;import {pack}.StandaloneModelKey;
public class ModelManager{{public final Map<StandaloneModelKey<?>,Object> models=new IdentityHashMap<>();@SuppressWarnings("unchecked")public <T>T getStandaloneModel(StandaloneModelKey<T> key){{return (T)models.get(key);}}}}''')
        put(native+'.client.event.ModelEvent',f'''package {native}.client.event;import java.util.*;import net.minecraft.client.resources.model.*;import net.minecraft.resources.Identifier;import net.minecraft.world.level.block.state.BlockState;import net.minecraft.client.renderer.block.model.BlockStateModel;import net.minecraft.client.renderer.item.ItemModel;import {pack}.*;
public class ModelEvent{{public static class RegisterStandalone{{public final List<StandaloneModelKey<?>> keys=new ArrayList<>();public final List<Object> values=new ArrayList<>();public <T> void register(StandaloneModelKey<T> key,SimpleUnbakedStandaloneModel<T> model){{keys.add(key);values.add(model);}}}}
public record BakingResult(Map<BlockState,BlockStateModel> blockStateModels,Map<Identifier,ItemModel> itemStackModels){{}}
public record ModifyBakingResult(BakingResult getBakingResult){{}}
public record BakingCompleted(ModelManager getModelManager){{}}}}''')
    else:
        put('net.minecraft.client.resources.model.BakedModel','package net.minecraft.client.resources.model;public class BakedModel{}')
        put(native+'.client.event.ModelEvent',f'''package {native}.client.event;import java.util.*;import net.minecraft.client.resources.model.*;import net.minecraft.resources.ResourceLocation;
public class ModelEvent{{public static class RegisterAdditional{{public final List<{key}> ids=new ArrayList<>();public void register({key} id){{ids.add(id);}}}}
public record ModifyBakingResult(Map<{key},BakedModel> getModels){{}}public record BakingCompleted(Map<{key},BakedModel> getModels){{}}}}''')
    return stubs


PROBE=r'''package probe;import java.util.*;import buildcraft.lib.platform.client.*;
import net.minecraft.client.gui.screens.*;import net.minecraft.client.gui.screens.inventory.MenuAccess;import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;import net.minecraft.world.inventory.*;import net.minecraft.world.entity.*;import net.minecraft.world.level.block.entity.*;import net.minecraft.world.level.block.Block;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;import net.minecraft.resources.*;import net.minecraft.client.resources.model.*;
public class Probe{static int checks,constructed;static void ok(boolean v,String n){checks++;if(!v)throw new AssertionError(n);}
static class Menu extends AbstractContainerMenu{}static class TestScreen extends Screen implements MenuAccess<Menu>{final Menu menu;final Inventory inventory;final Component title;TestScreen(Menu m,Inventory i,Component t){constructed++;menu=m;inventory=i;title=t;}public Menu getMenu(){return menu;}}
static class BaseTile extends BlockEntity{}static class SubTile extends BaseTile{}static class BaseEntity extends Entity{}static class SubEntity extends BaseEntity{}
@SuppressWarnings("unchecked")public static void main(String[]args){
var setup=new __FML__.event.lifecycle.FMLClientSetupEvent();int[] runs={0};PlatformClientRegistration.setup(setup).enqueueWork(()->runs[0]++);ok(runs[0]==0&&setup.tasks.size()==1,"setup remains deferred");setup.drain();ok(runs[0]==1,"setup once");
MenuType<Menu> type=new MenuType<>();ClientRegistration.ScreenFactory<Menu,TestScreen> factory=TestScreen::new;__SCREENS__
ok(constructed==0,"screen factory not evaluated during registration");ok(MenuScreens.registry.containsKey(type)&&MenuScreens.calls==1,"same menu registered once");
var ctor=(MenuScreens.ScreenConstructor<Menu,TestScreen>)MenuScreens.registry.get(type);Menu menu=new Menu();Inventory inventory=new Inventory();Component title=new Component("machine");TestScreen screen=ctor.create(menu,inventory,title);
ok(constructed==1&&screen.getMenu()==menu&&screen.inventory==inventory&&screen.title==title,"constructor arguments and lazy screen identity");
var nativeRenderers=new __NATIVE__.client.event.EntityRenderersEvent.RegisterRenderers();var renderers=PlatformClientRegistration.renderers(nativeRenderers);
BlockEntityType<SubTile> blockType=new BlockEntityType<>();EntityType<SubEntity> entityType=new EntityType<>();int[] renders={0};
__BLOCK_FACTORY__EntityRendererProvider<BaseEntity> entityFactory=c->{renders[0]++;return new Object();};
renderers.registerBlockEntityRenderer(blockType,blockFactory);renderers.registerEntityRenderer(entityType,entityFactory);
ok(nativeRenderers.keys.equals(List.of(blockType,entityType)),"renderer key and declaration order");ok(nativeRenderers.factories.get(0)==blockFactory&&nativeRenderers.factories.get(1)==entityFactory,"renderer factory identity");ok(renders[0]==0,"renderer factories remain lazy");
var blockEvent=new __NATIVE__.client.event.RegisterColorHandlersEvent.Block();net.minecraft.client.color.block.BlockColor colour=()->0x123456;Block a=new Block(),b=new Block();
PlatformClientRegistration.blockColours(blockEvent).register(colour,a,b);ok(blockEvent.calls==1&&blockEvent.colour==colour,"block tint callback once");ok(blockEvent.blocks[0]==a&&blockEvent.blocks[1]==b,"block tint order");
__COLOURS__
TextureAtlas atlas=new TextureAtlas(new __ID__("test:atlas"));__ATLAS__
__MODELS__
System.out.println("Client registration/model boundary: "+checks+" assertions (native event doubles)");}}
'''


def run_client(java: Path, work: Path, target: str) -> str:
    forge, newest, oldest=target.endswith('-forge'),target.startswith('1.21.11'),target.startswith('1.19.2')
    native='net.minecraftforge' if forge else 'net.neoforged.neoforge'
    ident='Identifier' if newest else 'ResourceLocation'
    screens='''PlatformClientRegistration.screens(setup,view->view.register(type,factory));ok(MenuScreens.registry.isEmpty()&&setup.tasks.size()==1,"Forge screen registration is enqueued exactly once");setup.drain();''' if forge else '''var screenEvent=new __NATIVE__.client.event.RegisterMenuScreensEvent();PlatformClientRegistration.screens(screenEvent).register(type,factory);ok(screenEvent.calls==1&&setup.tasks.isEmpty(),"NeoForge screen event is not delayed again");'''
    block_factory=('''BlockEntityRendererProvider<BaseTile,net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState> blockFactory=c->{renders[0]++;return new net.minecraft.client.renderer.blockentity.BlockEntityRenderer<>();};''' if newest else '''BlockEntityRendererProvider<BaseTile> blockFactory=c->{renders[0]++;return new net.minecraft.client.renderer.blockentity.BlockEntityRenderer<>();};''')
    if newest:
        colours='''var ranges=new __NATIVE__.client.event.RegisterRangeSelectItemModelPropertyEvent();Identifier id=new Identifier("test:property");var codec=new com.mojang.serialization.MapCodec<net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty>();
PlatformClientRegistration.rangeProperties(ranges).register(id,codec);ok(ranges.calls==1&&ranges.key==id&&ranges.codec==codec,"property codec/ID identity");
var tints=new __NATIVE__.client.event.RegisterColorHandlersEvent.ItemTintSources();var tint=new com.mojang.serialization.MapCodec<net.minecraft.client.color.item.ItemTintSource>();PlatformClientRegistration.tintSources(tints).register(id,tint);ok(tints.calls==1&&tints.key==id&&tints.codec==tint,"tint codec identity");'''
    else:
        colours='''var itemEvent=new __NATIVE__.client.event.RegisterColorHandlersEvent.Item();net.minecraft.client.color.item.ItemColor tint=()->0xabcdef;net.minecraft.world.level.ItemLike first=new net.minecraft.world.level.ItemLike(){},second=new net.minecraft.world.level.ItemLike(){};
PlatformClientRegistration.itemColours(itemEvent).register(tint,first,second);ok(itemEvent.calls==1&&itemEvent.colour==tint,"legacy item colour once");ok(itemEvent.items[0]==first&&itemEvent.items[1]==second,"legacy item order");'''
    atlas='''var after=new __NATIVE__.client.event.TextureStitchEvent.Post(atlas);ok(PlatformClientRegistration.atlas(after).getAtlas()==atlas,"same post-stitch atlas instance");''' if forge else '''var after=new __NATIVE__.client.event.TextureAtlasStitchedEvent(atlas);ok(PlatformClientRegistration.atlas(after).getAtlas()==atlas,"same stitched atlas instance");'''
    if oldest:
        atlas+='''var before=new __NATIVE__.client.event.TextureStitchEvent.Pre(atlas);var view=PlatformClientRegistration.atlas(before);ResourceLocation sprite=new ResourceLocation("test:sprite");view.addSprite(sprite);ok(view.getAtlas()==atlas&&before.sprites.contains(sprite),"pre-stitch registration reaches original atlas phase");'''
    if newest:
        models='''ClientStandaloneModel descriptor=new ClientStandaloneModel(new Identifier("test:static"));ModelManager manager=new ModelManager();ok(descriptor.get(manager)==null,"unbaked descriptor has no stale result");
var reg1=new __NATIVE__.client.event.ModelEvent.RegisterStandalone();PlatformClientModels.additional(reg1).register(descriptor);ok(reg1.keys.size()==1,"one standalone per catalogue entry");
var key1=reg1.keys.get(0);ok(key1.label.get().contains("BuildCraft static model"),"model diagnostic label retained");QuadCollection quads1=new QuadCollection();manager.models.put(key1,quads1);ok(descriptor.get(manager)==quads1,"model key bound to current manager");ok(PlatformClientModels.completed(new __NATIVE__.client.event.ModelEvent.BakingCompleted(manager)).getModelManager()==manager,"current bake manager forwarded");
var reg2=new __NATIVE__.client.event.ModelEvent.RegisterStandalone();PlatformClientModels.additional(reg2).register(descriptor);QuadCollection quads2=new QuadCollection();ModelManager next=new ModelManager();next.models.put(reg2.keys.get(0),quads2);
ok(reg2.keys.get(0)!=key1&&descriptor.get(next)==quads2,"reload rebinds descriptor instead of retaining old native key");ok(descriptor.get(manager)==null,"old generation not read after rebind");
Map<net.minecraft.world.level.block.state.BlockState,net.minecraft.client.renderer.block.model.BlockStateModel> blockModels=new HashMap<>();Map<Identifier,net.minecraft.client.renderer.item.ItemModel> itemModels=new HashMap<>();
var baking=new __NATIVE__.client.event.ModelEvent.ModifyBakingResult(new __NATIVE__.client.event.ModelEvent.BakingResult(blockModels,itemModels));var models=PlatformClientModels.models(baking);
var blockState=new net.minecraft.world.level.block.state.BlockState();var blockModel=new net.minecraft.client.renderer.block.model.BlockStateModel();Identifier itemId=new Identifier("test:item");var itemModel=new net.minecraft.client.renderer.item.ItemModel();
models.blockStateModels().put(blockState,blockModel);models.itemStackModels().put(itemId,itemModel);ok(blockModels.get(blockState)==blockModel&&itemModels.get(itemId)==itemModel,"baking callbacks mutate actual result maps");'''
    else:
        key='ResourceLocation' if forge else 'ModelResourceLocation'
        models=f'''{key} id=new {key}("test:model");var register=new __NATIVE__.client.event.ModelEvent.RegisterAdditional();PlatformClientModels.additional(register).register(id);ok(register.ids.equals(List.of(id)),"additional model ID unchanged");
Map<{key},BakedModel> raw=new HashMap<>();BakedModel model=new BakedModel();var completed=PlatformClientModels.completed(new __NATIVE__.client.event.ModelEvent.BakingCompleted(raw));completed.getModels().put(id,model);ok(raw.get(id)==model,"bake completed retains native map identity");'''
        if not oldest:
            models+='''var result=PlatformClientModels.models(new __NATIVE__.client.event.ModelEvent.ModifyBakingResult(raw));ok(result.getModels()==raw,"modify event map is not a detached copy");'''
    if not newest:
        colours+='PlatformClientRegistration.layers().set(a,ClientRegistration.BlockLayer.CUTOUT);PlatformClientRegistration.layers().set(b,ClientRegistration.BlockLayer.TRANSLUCENT);ok(net.minecraft.client.renderer.ItemBlockRenderTypes.layers.get(a)==net.minecraft.client.renderer.RenderType.CUTOUT&&net.minecraft.client.renderer.ItemBlockRenderTypes.layers.get(b)==net.minecraft.client.renderer.RenderType.TRANSLUCENT,"layer mapping preserves native values");ok(net.minecraft.client.renderer.ItemBlockRenderTypes.calls==2,"each layer registration once");'
    probe=PROBE
    for a,b in {'__SCREENS__':screens,'__BLOCK_FACTORY__':block_factory,'__COLOURS__':colours,'__ATLAS__':atlas,'__MODELS__':models}.items():probe=probe.replace(a,b)
    for a,b in {'__ID__':ident,'__NATIVE__':native,'__FML__':'net.minecraftforge.fml' if forge else 'net.neoforged.fml'}.items():probe=probe.replace(a,b)
    names=['ClientRegistration','PlatformClientRegistration','ClientAtlas','ClientModelBaking','PlatformClientModels']
    names+=['ClientModelProperties','ClientStandaloneModel'] if newest else ['ClientItemColours']
    return compile_probe(java,work,['buildcraft/lib/platform/client/'+name+'.java' for name in names],stubs_for(target),probe)
