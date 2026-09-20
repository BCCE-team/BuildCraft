"""Executable probes for the internal Minecraft boundaries (not public API tests)."""
from __future__ import annotations


def probe(current: bool) -> str:
    be_io = '''
        CompoundTag save() { test.Inputs.Writer out = new test.Inputs.Writer(); saveAdditional(out); return out.tag; }
        void load(CompoundTag tag) { loadAdditional(new test.Inputs.Reader(tag, REG)); }
    ''' if current else '''
        CompoundTag save() { CompoundTag tag = new CompoundTag(); saveAdditional(tag, REG); return tag; }
        void load(CompoundTag tag) { loadAdditional(tag, REG); }
    '''
    listener = '''
        public boolean mouseClicked(MouseButtonEvent e, boolean twice) { x=e.x(); y=e.y(); button=e.button(); return !twice; }
        public boolean keyPressed(KeyEvent e) { key=e.key(); scan=e.scancode(); mods=e.modifiers(); return true; }
        public boolean charTyped(CharacterEvent e) { cp=e.codepoint(); mods=e.modifiers(); return true; }
    ''' if current else '''
        public boolean mouseClicked(double xx, double yy, int b) { x=xx;y=yy;button=b;return true; }
        public boolean keyPressed(int k,int s,int m) { key=k;scan=s;mods=m;return true; }
        public boolean charTyped(char c,int m) { cp=c;mods=m;return true; }
    '''
    screen_check = '''
        MouseButtonEvent click = new MouseButtonEvent(3,4,new MouseButtonInfo(1,1));
        check(screen.mouseClicked(click,true), "native click accepted");
        check(screen.legacyCalls==1 && screen.calls==0 && screen.seenShift, "legacy consumes before vanilla");
        check(!BCInputState.shiftDown(), "click restores modifier");
        screen.accept=false;
        check(screen.mouseClicked(click,true), "vanilla fallback accepted");
        check(screen.legacyCalls==2 && screen.calls==1 && screen.doubleClick && screen.shift, "fallback gets original native event");
        check(!BCInputState.shiftDown(), "fallback restores modifier");
        screen.explode=true;
        try { screen.mouseClicked(click,false); throw new AssertionError("missing callback error"); }
        catch (IllegalArgumentException expected) { check(!BCInputState.shiftDown(),"exception restores modifier"); }
        screen.explode=false;screen.accept=true;
        check(screen.mouseDragged(click,2,3) && screen.drag==5 && screen.seenShift,"drag dispatch");
        check(screen.mouseReleased(click) && screen.seenShift,"release dispatch");
        check(screen.keyPressed(new KeyEvent(65,7,1)) && screen.key==65 && screen.seenShift,"key dispatch");
        check(screen.charTyped(new CharacterEvent(65,2)) && screen.character==65,"character dispatch");
        int before=screen.calls;
        check(screen.charTyped(new CharacterEvent(0x1f600,2)) && screen.calls==before+1,"non-BMP goes to native vanilla");
        check(!BCInputState.shiftDown(),"all dispatches leave modifiers unchanged");
    ''' if current else '''
        check(screen.mouseClicked(3,4,1) && screen.legacyCalls==1 && screen.calls==0,"old callback unchanged");
        BCContainerScreen<AbstractContainerMenu> direct = new BCContainerScreen<>(new AbstractContainerMenu(),new Inventory(),new Component("test")) {};
        check(direct.mouseClicked(1,2,0) && direct.calls==1,"old vanilla fallback inherited directly");
    '''
    registry_check = '''
        ResourceKey<Item> red = new ResourceKey<>(Identifier.parse("buildcrafttransport:wire/red"));
        ResourceKey<Item> blue = new ResourceKey<>(Identifier.parse("buildcrafttransport:wire/blue"));
        Item.Properties reused = new Item.Properties();
        BCRegistrationScope.item(red, () -> {
            check(BCRegistrationScope.itemProperties(reused)==reused && reused.id==red,"outer item id");
            String redName=reused.name;check(redName!=null,"actual registered red wire translation");
            BCRegistrationScope.item(blue, () -> { BCRegistrationScope.itemProperties(reused);check(reused.id==blue && !Objects.equals(reused.name,redName),"nested item id and translated name");return new Item(); });
            BCRegistrationScope.itemProperties(reused);
            check(reused.id==red && Objects.equals(reused.name,redName),"nested id/name restored");
            try { BCRegistrationScope.item(blue, () -> { throw new IllegalArgumentException(); }); }
            catch (IllegalArgumentException expected) { BCRegistrationScope.itemProperties(reused);check(reused.id==red,"exception restores item scope"); }
            return new Item();
        });
        Item.Properties outside=new Item.Properties();BCRegistrationScope.itemProperties(outside);
        check(outside.id==null && outside.name==null,"no id leak outside factory");
        ResourceKey<Block> one=new ResourceKey<>(Identifier.parse("buildcraftcore:one"));
        ResourceKey<Block> two=new ResourceKey<>(Identifier.parse("buildcraftcore:two"));
        BCRegistrationScope.block(one, () -> {
            BCRegistrationScope.block(two, () -> {BlockBehaviour.Properties p=new BlockBehaviour.Properties();BCRegistrationScope.blockProperties(p);check(p.id==two,"nested block id");return new Block();});
            BlockBehaviour.Properties p=new BlockBehaviour.Properties();BCRegistrationScope.blockProperties(p);check(p.id==one,"outer block id restored");return new Block();
        });
        BlockBehaviour.Properties outsideBlock=new BlockBehaviour.Properties();BCRegistrationScope.blockProperties(outsideBlock);check(outsideBlock.id==null,"block scope cleared");
    ''' if current else '''
        Item.Properties item=new Item.Properties();BlockBehaviour.Properties block=new BlockBehaviour.Properties();
        check(BCRegistrationScope.itemProperties(item)==item,"old item properties pass through");
        check(BCRegistrationScope.blockProperties(block)==block,"old block properties pass through");
        BCRegistrationScope.item(new ResourceKey<>(ResourceLocation.parse("test:item")), () -> {check(BCRegistrationScope.itemProperties(item)==item,"old factory has no new API calls");return new Item();});
    '''
    native_persistence = '''
        check(saved.contains("bc_legacy") && !saved.contains("value"),"native envelope unchanged");
        RootMachine detachedRoot=new RootMachine();check(detachedRoot.save().getInt("value")==42,"root payload survives a detached context-free save");
        Machine noContext=new Machine();CompoundTag early=noContext.save();
        check(early.getString("owner").equals("owner") && !early.contains("bc_legacy"),"early save keeps common data but skips registry-dependent machine");
        noContext.load(saved);CompoundTag cached=noContext.save();
        check(cached.getCompound("bc_legacy").getInt("value")==42,"cached registry after detached load");
        noContext.value=76;noContext.load(new CompoundTag());
        check(noContext.value==76,"absent native envelope does not reset machine");
        Machine independent=new Machine(){protected boolean requiresPersistenceRegistries(){return false;}};
        check(independent.save().getCompound("bc_legacy").getInt("value")==42,"context-free machine persists without world");
    ''' if current else '''
        check(!saved.contains("bc_legacy") && saved.getInt("value")==42,"old flat schema unchanged");
        Machine old=new Machine();old.load(new CompoundTag());check(old.value==0,"old missing keys retain previous default semantics");
    '''
    identifier = 'Identifier' if current else 'ResourceLocation'
    return r'''
import java.util.*;
import java.util.concurrent.atomic.*;
import buildcraft.lib.compat.minecraft.persistence.*;
import buildcraft.lib.compat.minecraft.gui.*;
import buildcraft.lib.compat.minecraft.registry.*;
import buildcraft.lib.compat.minecraft.components.*;
import buildcraft.lib.compat.minecraft.world.*;
import buildcraft.lib.compat.minecraft.render.*;
import net.minecraft.nbt.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.item.*;
import net.minecraft.network.chat.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.client.input.*;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

public final class CompatProbe {
    private static int checks;
    private static final HolderLookup.Provider REG=new HolderLookup.Provider() {};
    private static void check(boolean condition,String label) {checks++;if(!condition)throw new AssertionError(label);}
    private static class Machine extends BCBlockEntity {
        int value=42;int reads;int writes;String owner="owner";
        Machine(){super(new BlockEntityType<>(),BlockPos.ZERO,new BlockState());}
        protected void readCommonData(BCValueInput in){owner=in.readString("owner");}
        protected void writeCommonData(BCValueOutput out){out.writeString("owner",owner);}
        protected void readData(BCValueInput in){reads++;value=in.readInt("value");}
        protected void writeData(BCValueOutput out){writes++;out.writeInt("value",value);}
@BE_IO@
    }
    private static final class Child extends Machine {
        int child=8;
        protected void readData(BCValueInput in){super.readData(in);child=in.readInt("child");}
        protected void writeData(BCValueOutput out){super.writeData(out);out.writeInt("child",child);}
    }
    private static final class RootMachine extends Machine {
        protected boolean storesMachineDataAtRoot(){return true;}
        protected boolean requiresPersistenceRegistries(){return false;}
    }
    private static final class Listener implements GuiEventListener {
        double x,y;int button,key,scan,mods,cp;
@LISTENER@
    }
    private static final class Panel implements BCWidgetInput {
        int clicked,typed,key;
        public boolean mouseClicked(double x,double y,int b){clicked=b;return true;}
        public boolean keyPressed(int k,int s,int m){key=k;return true;}
        public boolean charTyped(char c,int m){typed=c;return true;}
    }
    private static class Screen extends BCContainerScreen<AbstractContainerMenu> {
        int legacyCalls,key,character;double drag;boolean accept=true,explode,seenShift;
        Screen(){super(new AbstractContainerMenu(),new Inventory(),new Component("title"));}
        public boolean mouseClicked(double x,double y,int b){legacyCalls++;seenShift=BCInputState.shiftDown();if(explode)throw new IllegalArgumentException();return accept;}
        public boolean mouseDragged(double x,double y,int b,double dx,double dy){seenShift=BCInputState.shiftDown();drag=dx+dy;return accept;}
        public boolean mouseReleased(double x,double y,int b){seenShift=BCInputState.shiftDown();return accept;}
        public boolean keyPressed(int k,int s,int m){key=k;seenShift=BCInputState.shiftDown();return accept;}
        public boolean charTyped(char c,int m){character=c;return accept;}
    }
    private static void values() {
        CompoundTag tag=new CompoundTag();BCValueOutput out=new BCValueOutput(tag,REG);
        check(out.isEmpty() && out.hasRegistries() && out.registries()==REG,"writer context");
        out.writeBoolean("b",true);out.writeByte("byte",(byte)3);out.writeShort("short",(short)400);out.writeInt("i",1234);
        out.writeLong("l",Long.MAX_VALUE);out.writeFloat("f",1.25f);out.writeDouble("d",4.5d);out.writeString("s","name");
        out.writeByteArray("ba",new byte[]{2,3});out.writeIntArray("ia",new int[]{-32,64,99});out.writeLongArray("la",new long[]{Long.MIN_VALUE});
        UUID id=UUID.fromString("178a882c-54a3-4e52-a1db-223566778899");out.writeUUID("uuid",id);
        CompoundTag nested=new CompoundTag();nested.putInt("inside",7);out.put("nested",nested);
        ListTag list=new ListTag();list.add(nested);out.put("list",list);
        BCValueInput in=new BCValueInput(tag,REG);
        check(in.tag()==tag && in.registries()==REG && !out.isEmpty(),"reader shares exact internal storage/context");
        check(in.readBoolean("b") && in.readByte("byte")==3 && in.readShort("short")==400,"narrow primitive fields");
        check(in.readInt("i")==1234 && in.readLong("l")==Long.MAX_VALUE,"integer fields");
        check(in.readFloat("f")==1.25f && in.readDouble("d")==4.5,"floating fields");
        check(in.readString("s").equals("name"),"string field");
        check(Arrays.equals(in.readByteArray("ba"),new byte[]{2,3}),"byte array");
        check(Arrays.equals(in.readIntArray("ia"),new int[]{-32,64,99}),"int array");
        check(Arrays.equals(in.readLongArray("la"),new long[]{Long.MIN_VALUE}),"long array");
        check(in.hasUUID("uuid") && in.readUUID("uuid").equals(id),"UUID");
        check(in.has("i",99) && in.has("i",Tag.TAG_INT) && !in.has("i",Tag.TAG_LONG),"typed/numeric existence");
        check(!in.has("missing") && !in.has("missing",99),"absent fields");
        check(in.readInt("missing")==0 && !in.readBoolean("missing") && in.readString("missing").isEmpty(),"primitive defaults");
        check(in.readCompound("missing").isEmpty() && in.readIntArray("missing").length==0,"container defaults");
        check(in.findCompound("s").isEmpty() && in.findCompound("nested").orElseThrow()==nested,"compound type guard");
        check(in.child("nested").readInt("inside")==7 && in.child("nested").registries()==REG,"child context");
        check(in.readList("list",Tag.TAG_COMPOUND).size()==1 && in.readList("list",Tag.TAG_INT).isEmpty(),"list element type guard");
        check(in.decode("nested",CompoundTag.CODEC).orElseThrow()==nested && in.decode("missing",CompoundTag.CODEC).isEmpty(),"codec decode shares registry ops");
        BCValueOutput detached=new BCValueOutput(new CompoundTag(),null);check(!detached.hasRegistries(),"nullable output context");
        try{detached.registries();throw new AssertionError("expected missing registries");}catch(NullPointerException expected){checks++;}
    }
    private static void persistence() {
        Child machine=new Child();machine.setLevel(new Level(REG));CompoundTag saved=machine.save();
        check(saved.getInt("vanilla")==17 && saved.getString("owner").equals("owner"),"vanilla/common fields preserved");
        check(machine.saves==1 && machine.writes==1,"super/save hook exactly once");
        Child loaded=new Child();loaded.value=0;loaded.child=0;loaded.load(saved);
        check(loaded.value==42 && loaded.child==8 && loaded.owner.equals("owner"),"inheritance roundtrip");
        check(loaded.loads==1 && loaded.reads==1,"super/load hook exactly once");
        RootMachine root=new RootMachine();root.setLevel(new Level(REG));CompoundTag rootSaved=root.save();
        check(rootSaved.getInt("value")==42 && !rootSaved.contains("bc_legacy"),"root-format opt-in");
        RootMachine rootLoaded=new RootMachine();rootLoaded.value=0;rootLoaded.load(rootSaved);check(rootLoaded.value==42,"root read");
@NATIVE_PERSISTENCE@
    }
    private static void input() throws Exception {
        check(!BCInputState.shiftDown(),"initial shift false");
        try(BCInputState.Scope outer=BCInputState.pushShift(true)) {
            check(BCInputState.shiftDown(),"outer scope");
            try(BCInputState.Scope inner=BCInputState.pushShift(false)){check(!BCInputState.shiftDown(),"inner scope");}
            check(BCInputState.shiftDown(),"outer restored");
            AtomicBoolean threadValue=new AtomicBoolean(true);Thread thread=new Thread(()->threadValue.set(BCInputState.shiftDown()));thread.start();thread.join();check(!threadValue.get(),"modifiers thread-local");
        }
        check(!BCInputState.shiftDown(),"scope restores initial");
        BCInputState.Scope scope=BCInputState.pushShift(true);AtomicBoolean rejected=new AtomicBoolean();
        Thread thread=new Thread(()->{try{scope.close();}catch(IllegalStateException expected){rejected.set(true);}});thread.start();thread.join();
        check(rejected.get() && BCInputState.shiftDown(),"cross-thread scope close rejected");scope.close();scope.close();check(!BCInputState.shiftDown(),"double close harmless");
        Listener listener=new Listener();check(BCGuiInput.click(listener,7.5,8,2) && listener.x==7.5 && listener.button==2,"vanilla click signature");
        check(BCGuiInput.key(listener,65,19,3) && listener.key==65 && listener.scan==19 && listener.mods==3,"vanilla key signature");
        check(BCGuiInput.character(listener,90,4) && listener.cp==90 && listener.mods==4,"vanilla char signature");
        check(BCGuiInput.key(65,19).key()==65 && BCGuiInput.key(65,19).scan()==19,"InputConstants signature");
        check(!BCGuiInput.click(null,0,0,0) && !BCGuiInput.key(new Object(),0,0,0),"non-widget ignored");
        Panel panel=new Panel();check(BCGuiInput.click(panel,2,3,1) && panel.clicked==1,"internal recipe panel click");
        check(BCGuiInput.key(panel,82,0,1) && panel.key==82,"internal recipe panel key");
        check(BCGuiInput.character(panel,81,0) && panel.typed==81,"internal panel char");
        check(!BCGuiInput.character(panel,0x1f600,0),"legacy panel non-BMP not truncated");
        Screen screen=new Screen();
@SCREEN_CHECK@
    }
    private static void graphics() {
        GuiGraphics g=new GuiGraphics();Font font=new Font();test.Matrix m=@MATRIX@;
        BCGraphics.push(g);BCGraphics.translate(g,5,7);BCGraphics.scale(g,2,3);BCGraphics.translate(g,1,1);
        check(m.x==7 && m.y==10 && m.sx==2 && m.sy==3,"real GUI transform stack");BCGraphics.pop(g);
        check(m.x==0 && m.y==0 && m.sx==1 && m.sy==1 && m.balanced(),"GUI stack restored");
        BCGraphics.scissor(g,2,3,40,50);check(Arrays.equals(g.clip,new int[]{2,3,40,50}),"native clip bounds");BCGraphics.endScissor(g);check(g.clip==null,"clip cleared");
        BCGraphics.text(g,font,"text",1,2,0xff123456,true);check(g.color==0xff123456,"text ARGB unchanged");
        BCGraphics.text(g,font,new Component("component"),1,2,0x7f654321,false);check(g.color==0x7f654321,"component ARGB unchanged");
        BCGraphics.fill(g,0,0,4,4,0xaa111111);check(g.color==0xaa111111,"fill ARGB unchanged");
        BCGraphics.gradient(g,0,0,4,4,0xbb222222,0xcc333333);check(g.color==0xbb222222,"gradient start unchanged");
        @ID@ texture=@ID@.parse("test:texture");BCGraphics.blit(g,texture,1,2,3,4,10,12);
        check(g.draws==1 && g.draw[0]==texture && g.draw[7].equals(10) && g.draw[9].equals(256),"simple blit defaults");
        BCGraphics.blit(g,texture,1,2,3,4,10,12,8,9,64,128);
        check(g.draws==2 && g.draw[3].equals(3f) && g.draw[7].equals(8) && g.draw[8].equals(9) && g.draw[10].equals(128),"source/destination UV dimensions preserved");
        BCGraphics.blit(g,texture,1,2,3,4,0,12);BCGraphics.blit(g,null,1,2,3,4,10,12);check(g.draws==2,"empty blits ignored");
        BCGuiTooltip.text(g,font,new Component("tip"),1,2);check(g.tooltips==1,"single component tooltip submission");
        BCGuiTooltip.components(g,font,List.of(new Component("one"),new Component("two")),1,2);check(g.tooltips==2,"component-list tooltip submission");
        BCGuiTooltip.components(g,font,List.of(),1,2);BCGuiTooltip.text(g,font,null,1,2);BCGuiTooltip.item(null,font,ItemStack.EMPTY,1,2);check(g.tooltips==2,"empty tooltip list ignored");
        BCGuiTooltip.item(g,font,new ItemStack("test:item",1),1,2);check(g.tooltips==3,"item tooltip submission");
        BCGraphics.nextLayer(g);check(g.layers==@LAYERS@,"backend layer boundary");
    }
    private static void registry() {
@REGISTRY_CHECK@
    }
    private static void itemsAndWorld() {
        ItemStack stack=new ItemStack("test:tool",5);stack.data.putString("custom_name","Preserved");stack.data.putInt("damage",11);
        CompoundTag encoded=BCItemData.save(stack,REG);ItemStack loaded=BCItemData.load(REG,encoded);
        check(loaded.id.equals(stack.id) && loaded.count==5,"item id/count roundtrip");
        check(loaded.data.getString("custom_name").equals("Preserved") && loaded.data.getInt("damage")==11,"item components roundtrip");
        check(BCItemData.save(null,REG).isEmpty() && BCItemData.save(ItemStack.EMPTY,REG).isEmpty() && BCItemData.save(stack,null).isEmpty(),"empty/detached item saves");
        check(BCItemData.load(null,encoded).isEmpty() && BCItemData.load(REG,null).isEmpty(),"empty item loads");
        check(BCItemData.load(REG,new ValueTag(Tag.TAG_STRING,"bad")).isEmpty(),"invalid item tag");
        LevelHeightAccessor world=new LevelHeightAccessor(){public int @MIN@(){return -64;}public int getHeight(){return 384;}};
        check(BCWorldHeight.min(world)==-64 && BCWorldHeight.maxExclusive(world)==320,"height keeps exclusive upper bound");
        Vec3 position=new Vec3(17,-20,300);check(BCCamera.position(new Camera(position))==position,"camera actual position");
        check(BCCamera.position(null)==Vec3.ZERO,"camera null fallback");
    }
    public static void main(String[] args) throws Exception {
        values();persistence();input();graphics();registry();itemsAndWorld();@EXTRA@
        System.out.println("Minecraft compatibility @VERSION@: "+checks+" assertions");
    }
}
'''.replace('@EXTRA@', EXTRA_CURRENT if current else EXTRA_OLD).replace('@BE_IO@',be_io).replace('@LISTENER@',listener).replace('@NATIVE_PERSISTENCE@',native_persistence).replace('@SCREEN_CHECK@',screen_check).replace('@REGISTRY_CHECK@',registry_check).replace('@MATRIX@','g.matrix' if current else 'g.stack.state').replace('@ID@',identifier).replace('@LAYERS@','1' if current else '0').replace('@MIN@','getMinY' if current else 'getMinBuildHeight').replace('@VERSION@','1.21.11' if current else '1.21.1')


EXTRA_OLD = r"""
        final int[] received=new int[3];
        buildcraft.lib.compat.minecraft.render.BCGeometryRenderer<BlockEntity> geometry = new buildcraft.lib.compat.minecraft.render.BCGeometryRenderer<>() {
            public boolean renderOffScreen(){return true;}
            public void renderContents(BlockEntity entity,float tick,com.mojang.blaze3d.vertex.PoseStack pose,net.minecraft.client.renderer.MultiBufferSource source,int light,int overlay){received[0]++;received[1]=light;received[2]=overlay;check(tick==0.5f,"old geometry partial tick");}
        };
        BlockEntity entity=new BlockEntity(new BlockEntityType<>(),BlockPos.ZERO,new BlockState());
        geometry.render(entity,.5f,new com.mojang.blaze3d.vertex.PoseStack(),null,123,45);
        check(Arrays.equals(received,new int[]{1,123,45}),"old geometry direct render dispatch");
        check(geometry.shouldRenderOffScreen(entity),"old offscreen policy delegates");
"""

EXTRA_CURRENT = r"""
        var slot=new net.minecraft.world.item.crafting.display.SlotDisplay() {};
        var shaped=new net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay(List.of(slot));
        var shapeless=new net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay(List.of(slot,slot));
        var first=new net.minecraft.world.item.crafting.display.RecipeDisplayEntry(1,shaped);
        var second=new net.minecraft.world.item.crafting.display.RecipeDisplayEntry(2,shapeless);
        var duplicate=new net.minecraft.world.item.crafting.display.RecipeDisplayEntry(1,shapeless);
        var unrelated=new net.minecraft.world.item.crafting.display.RecipeDisplayEntry(3,new net.minecraft.world.item.crafting.display.RecipeDisplay() {});
        var book=new net.minecraft.client.ClientRecipeBook(List.of(
            new net.minecraft.client.gui.screens.recipebook.RecipeCollection(List.of(first,unrelated)),
            new net.minecraft.client.gui.screens.recipebook.RecipeCollection(List.of(duplicate,second))));
        var recipes=buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays.unlockedCrafting(book);
        check(recipes.size()==2 && recipes.get(0)==first && recipes.get(1)==second,"recipe identity dedup preserves first entry and order");
        check(buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays.craftingInputs(first).equals(List.of(slot)),"shaped inputs");
        check(buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays.craftingInputs(second).equals(List.of(slot,slot)),"shapeless multiplicity");
        check(buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays.craftingInputs(unrelated).isEmpty(),"non-crafting display ignored");
"""
