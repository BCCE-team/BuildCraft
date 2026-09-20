"""Assertions for the offline Java transfer probes; see transfer_fixture for boundaries."""
PROBES = {}
PROBES['TransferProbe.java'] = r'''
import java.util.*;
import buildcraft.lib.compat.transfer.*;
import buildcraft.lib.platform.storage.*;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.core.item.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.transfer.*;
import net.neoforged.neoforge.transfer.item.*;
import net.neoforged.neoforge.transfer.fluid.*;
import net.neoforged.neoforge.transfer.energy.*;
import net.neoforged.neoforge.transfer.access.*;
import net.neoforged.neoforge.transfer.transaction.*;

public class TransferProbe {
 static int checks;
 static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static void eq(long actual,long expected,String name){check(actual==expected,name+": "+actual+" != "+expected);}
 static void rejects(Runnable r,String name){boolean rejected=false;try{r.run();}catch(IllegalArgumentException|IndexOutOfBoundsException e){rejected=true;}check(rejected,name);}
 static Item IRON=new Item("iron"),GOLD=new Item("gold");
 static ItemResource iron=ItemResource.of(new ItemStack(IRON)), gold=ItemResource.of(new ItemStack(GOLD));
 static FluidResource water=new FluidResource("water"), lava=new FluidResource("lava");

 public static void main(String[] args){
  int[] changed={0},dirty={0};
  ItemHandlerSimple inventory=new ItemHandlerSimple(2,(h,s,b,a)->changed[0]++);
  inventory.setStackInSlot(0,new ItemStack(IRON,20));changed[0]=0;
  var faceA=TransferInterop.exportItems(()->inventory,()->dirty[0]++);
  var faceB=TransferInterop.exportItems(()->inventory,()->dirty[0]++);
  eq(faceA.getAmountAsLong(0),20,"initial inventory");
  try(Transaction outer=Transaction.openRoot()){
   eq(faceA.insert(0,iron,10,outer),10,"insert through face A");
   eq(faceB.getAmountAsLong(0),30,"face B sees speculative insert");
   try(Transaction nested=Transaction.open(outer)){
    eq(faceB.extract(0,iron,7,nested),7,"nested extract");
    nested.commit();
   }
   eq(inventory.getStackInSlot(0).getCount(),23,"nested commit visible to parent");
   eq(changed[0],0,"no speculative callbacks");eq(dirty[0],0,"no speculative dirty marks");
  }
  eq(inventory.getStackInSlot(0).getCount(),20,"parent abort rolls back both aliases");
  eq(changed[0],0,"aborted callbacks discarded");eq(dirty[0],0,"aborted dirty marks discarded");
  try(Transaction outer=Transaction.openRoot()){
   faceA.insert(0,iron,10,outer);
   try(Transaction nested=Transaction.open(outer)){faceB.extract(0,iron,4,nested);}
   eq(faceA.getAmountAsLong(0),30,"child abort retains outer change");
   outer.commit();
  }
  eq(changed[0],1,"single changed-slot callback on root commit");eq(dirty[0],1,"only committed operation dirties owner");
  eq(faceB.getAmountAsLong(0),30,"committed contents");
  try(Transaction tx=Transaction.openRoot()){
   eq(faceA.insert(0,gold,4,tx),0,"different item rejected");
   eq(faceA.insert(0,iron,100,tx),34,"partial capacity");
   eq(faceA.getAmountAsLong(0),64,"full inventory speculative state");
   eq(faceA.extract(0,gold,5,tx),0,"wrong resource cannot extract");
  }
  eq(faceA.getAmountAsLong(0),30,"partial insert aborted");
  IItemHandler legacy=TransferInterop.importItems(faceA);
  eq(legacy.insertItem(0,new ItemStack(IRON,100),true).getCount(),66,"legacy simulate remainder");
  eq(faceA.getAmountAsLong(0),30,"legacy simulate no mutation");
  try(Transaction tx=Transaction.openRoot()){
   eq(legacy.insertItem(1,new ItemStack(GOLD,5),false).getCount(),0,"nested legacy execute opens child, not root");
   eq(legacy.extractItem(1,2,true).getCount(),2,"nested legacy simulation");
   eq(faceA.getAmountAsLong(1),5,"nested simulation restored item");
  }
  eq(faceA.getAmountAsLong(1),0,"native parent abort restores legacy child");
  ItemStack decorated=new ItemStack(IRON,3);decorated.data.put("variant","custom");
  try(Transaction tx=Transaction.openRoot()){
   eq(faceA.insert(1,ItemResource.of(decorated),3,tx),3,"component-bearing item accepted");
   check(faceA.getResource(1).equals(ItemResource.of(decorated)),"components preserved");
   eq(faceA.extract(1,iron,3,tx),0,"different components rejected on extract");
  }
  var sink=TransferInterop.exportItemSink(()->(IItemTransactor.IItemInsertable)(stack,all,simulate)->inventory.insert(stack,all,simulate),()->dirty[0]++);
  try(Transaction tx=Transaction.openRoot()){
   eq(sink.insert(0,gold,6,tx),6,"pipe insertion port accepts resource");
   eq(sink.getAmountAsLong(0),0,"pipe insertion port exposes no fabricated inventory");
   eq(sink.extract(0,gold,6,tx),0,"cannot steal in-flight items through port");
  }
  eq(faceA.getAmountAsLong(1),0,"pipe insertion participates in rollback");
  try(Transaction tx=Transaction.openRoot()){
   rejects(()->faceA.insert(-1,iron,2,tx),"negative item index");
   rejects(()->faceA.insert(0,ItemResource.EMPTY,2,tx),"empty item rejected");
   rejects(()->faceA.insert(0,iron,-2,tx),"negative item amount");
  }
  var holder=new IItemHandler[]{inventory};var dynamic=TransferInterop.exportItems(()->holder[0]);holder[0]=null;
  eq(dynamic.size(),0,"stale capability re-resolves removed/blocked provider");

  FluidStore fluids=new FluidStore();var fluidA=TransferInterop.exportFluids(()->fluids);var fluidB=TransferInterop.exportFluids(()->fluids);
  try(Transaction tx=Transaction.openRoot()){
   eq(fluidA.insert(1,water,100,tx),0,"output tank refuses indexed insertion");
   eq(fluidA.getAmountAsLong(0),0,"rejected tank insertion did not reroute into tank zero");
   eq(fluidA.insert(0,water,1500,tx),1000,"fluid capacity bounded");
   eq(fluidB.getAmountAsLong(0),1000,"fluid aliases see speculative state");
   eq(fluidB.extract(0,water,500,tx),0,"input-only tank refuses extraction");
   eq(fluidB.extract(1,water,50,tx),0,"wrong fluid rejected");
   eq(fluidB.extract(1,lava,200,tx),200,"exact output tank extracted");
   eq(fluids.commits,0,"no fluid callbacks before commit");
  }
  eq(fluidA.getAmountAsLong(0),0,"fluid input rollback");eq(fluidA.getAmountAsLong(1),600,"fluid output rollback");
  IFluidHandler oldFluid=TransferInterop.importFluids(fluidA);
  try(Transaction tx=Transaction.openRoot()){
   eq(oldFluid.fill(water.toStack(300),FluidAction.EXECUTE),300,"fluid reverse bridge inside native tx");
   eq(oldFluid.drain(lava.toStack(120),FluidAction.SIMULATE).getAmount(),120,"fluid reverse simulate");
   eq(fluidA.getAmountAsLong(1),600,"simulated fluid drain reverted");
   tx.commit();
  }
  eq(fluids.commits,1,"single underlying fluid journal root callback");eq(fluidA.getAmountAsLong(0),300,"fluid root commit");
  try(Transaction outer=Transaction.openRoot()){
   fluidA.insert(water,200,outer);
   try(Transaction child=Transaction.open(outer)){fluidB.extract(lava,60,child);child.commit();}
  }
  eq(fluidA.getAmountAsLong(0),300,"global fluid insert abort");eq(fluidA.getAmountAsLong(1),600,"nested global fluid drain abort");

  Power power=new Power();var energyA=TransferInterop.exportEnergy(()->power);var energyB=TransferInterop.exportEnergy(()->power);
  try(Transaction outer=Transaction.openRoot()){
   eq(energyA.insert(40,outer),40,"energy insert");eq(energyB.getAmountAsLong(),90,"energy alias visibility");
   try(Transaction child=Transaction.open(outer)){eq(energyB.extract(15,child),15,"nested energy extract");child.commit();}
   var old=TransferInterop.importEnergy(energyA);
   check(old.canReceive(),"nested energy metadata simulation");eq(energyA.getAmountAsLong(),75,"metadata simulation reverted");
   eq(old.receiveEnergy(10,false),10,"reverse energy execute in active tx");
   eq(energyA.getAmountAsLong(),85,"reverse energy write visible");
  }
  eq(power.amount,50,"energy parent abort");eq(power.commits,0,"energy aborted callback");
  power.input=false;
  try(Transaction tx=Transaction.openRoot()){eq(energyA.insert(10,tx),0,"output-only energy port");eq(energyA.extract(10,tx),10,"output-only extraction");tx.commit();}
  eq(power.amount,40,"committed energy extraction");power.output=false;
  try(Transaction tx=Transaction.openRoot()){eq(energyA.extract(10,tx),0,"blocked output rechecked");rejects(()->energyA.insert(-1,tx),"negative energy rejected");}

  ItemFragileFluidContainer shardItem=new ItemFragileFluidContainer();ItemStack shard=new ItemStack(shardItem);shard.data.put("label","keep");ItemFragileFluidContainer.setFluid(shard,water.toStack(500));
  Access access=new Access(shard);var shardHandler=new FragileFluidResourceHandler(access);
  eq(shardHandler.getAmountAsLong(0),500,"filled shard");check(!shardHandler.isValid(0,water),"shard is drain-only");
  try(Transaction tx=Transaction.openRoot()){
   eq(shardHandler.insert(0,water,100,tx),0,"shard rejects filling");
   eq(shardHandler.extract(0,lava,100,tx),0,"shard rejects wrong fluid");
   eq(shardHandler.extract(0,water,125,tx),125,"partial shard drain");eq(shardHandler.getAmountAsLong(0),375,"remaining shard fluid");
   check(access.stack.data.get("label").equals("keep"),"unrelated shard components kept");
  }
  eq(shardHandler.getAmountAsLong(0),500,"aborted shard replacement");eq(access.commits,0,"aborted item-access callback");
  try(Transaction outer=Transaction.openRoot()){
   try(Transaction child=Transaction.open(outer)){eq(shardHandler.extract(0,water,1000,child),500,"full shard drain capped");child.commit();}
   eq(access.getAmount(),0,"fully drained shard consumed speculatively");eq(shardHandler.getAmountAsLong(0),0,"same handler sees empty item location");
  }
  eq(access.getAmount(),1,"parent abort restores consumed shard");eq(shardHandler.getAmountAsLong(0),500,"parent abort restores fluid");
  try(Transaction tx=Transaction.openRoot()){eq(shardHandler.extract(0,water,100,tx),100,"partial committed shard drain");tx.commit();}
  eq(shardHandler.getAmountAsLong(0),400,"partial commit persists replacement");eq(access.commits,1,"item access committed once");
  try(Transaction tx=Transaction.openRoot()){eq(shardHandler.extract(0,water,400,tx),400,"final shard drain");tx.commit();}
  eq(access.getAmount(),0,"shard has no reusable empty shell");
  access.stack=new ItemStack(GOLD);eq(shardHandler.getAmountAsLong(0),0,"stale shard capability rejects other item");
  Access creative=new Access(shard.copy());creative.creative=true;var creativeShard=new FragileFluidResourceHandler(creative);
  try(Transaction tx=Transaction.openRoot()){eq(creativeShard.extract(0,water,500,tx),500,"creative ItemAccess extraction");tx.commit();}
  eq(creative.getAmount(),1,"creative context owns item retention");eq(creativeShard.getAmountAsLong(0),500,"creative context retains original fluid");
  // Operation views participate in the same native transaction and retain identity.
  ItemStorage itemView=StorageAdapters.fromNativeItems(legacy);
  check(StorageAdapters.toNativeItems(itemView)==legacy,"internal item view unwraps original native import");
  try(Transaction outer=Transaction.openRoot()){
   eq(itemView.insertItem(1,new ItemStack(GOLD,6),false).getCount(),0,"internal item execute uses current transaction");
   eq(itemView.getStackInSlot(1).getCount(),6,"internal view sees speculative data");
   eq(itemView.extractItem(1,3,true).getCount(),3,"internal item simulate extracts exact request");
   eq(itemView.getStackInSlot(1).getCount(),6,"internal item simulation rolls back");
  }
  eq(faceA.getAmountAsLong(1),0,"root abort restores internal item insert");
  FluidStorage<FluidStack> fluidView=StorageAdapters.fromNativeFluids(oldFluid);
  check(StorageAdapters.toNativeFluids(fluidView)==oldFluid,"internal fluid view unwraps exact native import");
  int committedFluids=fluids.commits;
  try(Transaction outer=Transaction.openRoot()){
   eq(fluidView.fill(water.toStack(100),false),100,"internal fluid execute");
   eq(fluidView.drain(lava.toStack(80),true).getAmount(),80,"internal fluid simulation");
   eq(fluidA.getAmountAsLong(0),400,"internal fluid insert visible");
   eq(fluidA.getAmountAsLong(1),600,"internal fluid simulation restored");
  }
  eq(fluidA.getAmountAsLong(0),300,"native abort rolls back internal fluid wrapper");
  eq(fluids.commits,committedFluids,"internal wrapper did not commit or dirty parent transaction");
  var importedEnergy=TransferInterop.importEnergy(energyA);var energyView=StorageAdapters.fromNativeEnergy(importedEnergy);
  check(StorageAdapters.toNativeEnergy(energyView)==importedEnergy,"energy unwrap identity");
  power.input=true;power.output=true;int committedEnergy=power.commits;
  try(Transaction outer=Transaction.openRoot()){
   eq(energyView.receiveEnergy(20,false),20,"internal FE execute in native transaction");
   eq(energyView.extractEnergy(5,true),5,"internal FE simulate");
   eq(power.amount,60,"internal FE simulation does not mutate");
  }
  eq(power.amount,40,"FE parent abort restores operation view");
  eq(power.commits,committedEnergy,"FE operation view did not independently commit");
  check(TransferJournal.current()==null,"no leaked transaction context");
  System.out.println("Transfer Java probes: "+checks+" assertions (offline API doubles)");
 }

 static class FluidStore implements IFluidHandler,IndexedFluidHandler {
  FluidStack[] stacks={FluidStack.EMPTY,lava.toStack(600)};int commits;
  final TransferJournal<FluidStack[]> journal=new TransferJournal<>(()->new FluidStack[]{stacks[0].copy(),stacks[1].copy()},s->stacks=s,s->commits++);
  public int getTanks(){return 2;}public FluidStack getFluidInTank(int t){return stacks[t];}public int getTankCapacity(int t){return 1000;}
  public boolean isFluidValid(int t,FluidStack f){return t==0&&f.fluid.equals("water");}
  public int fillTank(int t,FluidStack f,FluidAction a){if(!isFluidValid(t,f))return 0;int n=Math.min(f.getAmount(),1000-stacks[t].getAmount());if(a.execute()&&n>0){journal.record();stacks[t]=f.copyWithAmount(stacks[t].getAmount()+n);}return n;}
  public FluidStack drainTank(int t,FluidStack f,FluidAction a){if(t!=1||!stacks[t].fluid.equals(f.fluid))return FluidStack.EMPTY;int n=Math.min(f.getAmount(),stacks[t].getAmount());if(a.execute()&&n>0){journal.record();stacks[t]=stacks[t].copyWithAmount(stacks[t].getAmount()-n);}return f.copyWithAmount(n);}
  public int fill(FluidStack f,FluidAction a){return fillTank(0,f,a);}public FluidStack drain(FluidStack f,FluidAction a){return drainTank(1,f,a);}public FluidStack drain(int n,FluidAction a){return drain(stacks[1].copyWithAmount(n),a);}
 }
 static class Power implements IEnergyStorage {
  int amount=50,commits;boolean input=true,output=true;
  final TransferJournal<Integer> journal=new TransferJournal<>(()->amount,n->amount=n,n->commits++);
  public int getEnergyStored(){return amount;}public int getMaxEnergyStored(){return 100;}public boolean canReceive(){return input;}public boolean canExtract(){return output;}
  public int receiveEnergy(int n,boolean simulate){int moved=input?Math.min(n,100-amount):0;if(!simulate&&moved>0){journal.record();amount+=moved;}return moved;}
  public int extractEnergy(int n,boolean simulate){int moved=output?Math.min(n,amount):0;if(!simulate&&moved>0){journal.record();amount-=moved;}return moved;}
 }
 static class Access implements ItemAccess {
  ItemStack stack;int commits;boolean creative;
  final TransferJournal<ItemStack> journal=new TransferJournal<>(()->stack.copy(),s->stack=s,s->commits++);
  Access(ItemStack stack){this.stack=stack.copy();}public ItemResource getResource(){return ItemResource.of(stack);}public int getAmount(){return stack.getCount();}
  public int extract(ItemResource resource,int n,TransactionContext tx){if(!getResource().equals(resource))return 0;int moved=Math.min(n,getAmount());if(!creative&&moved>0){journal.record();stack=stack.copyWithCount(getAmount()-moved);}return moved;}
  public int insert(ItemResource resource,int n,TransactionContext tx){if(creative)return n;if(!stack.isEmpty())return 0;if(n!=1)return 0;journal.record();stack=resource.toStack(n);return n;}
 }
}
'''
PROBES['buildcraft/builders/snapshot/BlueprintProbe.java'] = r'''
package buildcraft.builders.snapshot;
import net.minecraft.world.entity.*;import net.minecraft.world.entity.decoration.ArmorStand;import net.minecraft.world.item.*;import net.minecraft.nbt.*;import buildcraft.lib.compat.NbtCompat;import buildcraft.lib.misc.ItemStackUtil;
public class BlueprintProbe {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 public static void main(String[]args){
  Entity generic=new Entity();CompoundTag saved=BlueprintEntityData.save(generic);
  check(generic.saved,"native entity.save executed");check("native".equals(saved.get("payload")),"native entity payload retained");
  ArmorStand source=new ArmorStand();Item[] items=new Item[6];int i=0;
  for(EquipmentSlot slot:EquipmentSlot.values()){items[i]=new Item("slot-"+i);ItemStack s=new ItemStack(items[i],i+1);s.data.put("variant","custom-"+i);source.setItemSlot(slot,s);i++;}
  saved=BlueprintEntityData.save(source);check(!saved.contains("equipment"),"no second unaccounted native gear copy");
  ListTag hands=NbtCompat.getList(saved,"HandItems"),armor=NbtCompat.getList(saved,"ArmorItems");
  check(hands.size()==2&&armor.size()==4,"stable rule schema contains all six equipment slots");
  ArmorStand restored=new ArmorStand();saved.put("equipment",new CompoundTag());BlueprintEntityData.prepareLoad(saved);
  check(!saved.contains("equipment"),"native alias stripped on load");BlueprintEntityData.restoreEquipment(restored,saved);
  for(EquipmentSlot slot:EquipmentSlot.values())check(ItemStack.matches(source.getItemBySlot(slot),restored.getItemBySlot(slot)),"count/components preserved "+slot);
  check("native".equals(saved.get("payload")),"pose/other data not replaced by equipment normalization");
  ArmorStand empty=new ArmorStand();CompoundTag old=BlueprintEntityData.save(empty);BlueprintEntityData.restoreEquipment(restored,old);
  for(EquipmentSlot slot:EquipmentSlot.values())check(restored.getItemBySlot(slot).isEmpty(),"empty slot clears existing gear "+slot);
  System.out.println("Blueprint equipment Java probes: "+checks+" assertions (offline API doubles)");
 }
}
'''
PROBES['buildcraft/lib/client/render/compat/GeometryProbe.java'] = r'''
package buildcraft.lib.client.render.compat;
import java.util.*;import com.mojang.blaze3d.vertex.*;import net.minecraft.client.renderer.*;import net.minecraft.client.renderer.rendertype.RenderType;import net.minecraft.client.renderer.state.CameraRenderState;import net.minecraft.world.level.block.entity.BlockEntity;import net.minecraft.world.phys.Vec3;
public class GeometryProbe {
 static int checks;static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}static void eq(float a,float b,String n){check(Math.abs(a-b)<0.0001,n+": "+a+" != "+b);}
 public static void main(String[]args){
  var solid=new RenderType("solid");var translucent=new RenderType("translucent");
  buildcraft.lib.compat.minecraft.render.BCGeometryRenderer<BlockEntity> renderer=(tile,partial,pose,buffers,light,overlay)->{
   pose.translate(3,4,5);
   for(RenderType type:List.of(solid,translucent)){
    VertexConsumer c=buffers.getBuffer(type);float offset=type==solid?0:100;
    for(int i=0;i<4;i++)c.addVertex(pose.last().pose(),tile.frame+partial+i+offset,0,0).setColor(17+i,34,51,128).setUv(.25f,.75f).setUv1(4,8).setUv2(light&65535,light>>>16).setNormal(0,1,0).setLineWidth(2);
   }
  };
  check(!renderer.renderOffScreen() && !renderer.shouldRenderOffScreen(),"default offscreen policy preserved by geometry boundary");
  BlockEntity tile=new BlockEntity();var state=renderer.createRenderState();renderer.extractRenderState(tile,state,.5f,new Vec3(0,0,0),null);
  tile.frame=900;List<Runnable> queued=new ArrayList<>();List<RenderType> types=new ArrayList<>();List<Sink> sinks=new ArrayList<>();PoseStack world=new PoseStack();world.translate(10,20,30);
  SubmitNodeCollector collector=(pose,type,geometry)->{types.add(type);Sink sink=new Sink();sinks.add(sink);queued.add(()->geometry.draw(pose.last(),sink));};
  renderer.submit(state,world,collector,new CameraRenderState());check(queued.size()==2,"all layers submitted");check(types.contains(solid)&&types.contains(translucent),"translucent layer not discarded");
  for(Runnable callback:queued)callback.run();for(Sink sink:sinks){check(sink.vertices.size()==4,"captured final vertex included");
   for(float[] v:sink.vertices){eq(v[1],24,"captured local plus submit transform Y");eq(v[2],35,"captured local plus submit transform Z");eq(v[6],128,"alpha retained");eq(v[7],.25f,"U retained");eq(v[8],.75f,"V retained");eq(v[9],4,"overlay U retained");eq(v[10],8,"overlay V retained");eq(v[11],160,"block light retained");eq(v[12],240,"sky light retained");eq(v[14],1,"normal retained");eq(v[16],2,"line width retained");}
  }
  eq(sinks.get(0).vertices.get(0)[0],14.5f,"tile sampled during extraction, not submit");eq(sinks.get(1).vertices.get(0)[0],114.5f,"independent layer vertex data");
  tile.removed=true;renderer.extractRenderState(tile,state,0,new Vec3(0,0,0),null);queued.clear();renderer.submit(state,world,collector,new CameraRenderState());check(queued.isEmpty(),"reused removed-tile state clears geometry");
  System.out.println("Captured renderer Java probes: "+checks+" assertions (offline API doubles)");
 }
 static class Sink implements VertexConsumer {
  List<float[]> vertices=new ArrayList<>();float[] p;
  public VertexConsumer addVertex(float x,float y,float z){p=new float[17];p[0]=x;p[1]=y;p[2]=z;vertices.add(p);return this;}
  public VertexConsumer setColor(int r,int g,int b,int a){p[3]=r;p[4]=g;p[5]=b;p[6]=a;return this;}public VertexConsumer setUv(float u,float v){p[7]=u;p[8]=v;return this;}
  public VertexConsumer setUv1(int u,int v){p[9]=u;p[10]=v;return this;}public VertexConsumer setUv2(int u,int v){p[11]=u;p[12]=v;return this;}
  public VertexConsumer setNormal(float x,float y,float z){p[13]=x;p[14]=y;p[15]=z;return this;}public VertexConsumer setLineWidth(float w){p[16]=w;return this;}
 }
}
'''
