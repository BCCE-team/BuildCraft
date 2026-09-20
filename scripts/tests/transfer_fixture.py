"""Compile and execute maintained 1.21.11 Java against small offline API test doubles.

These are deterministic unit probes, NOT a Minecraft/NeoForge integration test or
Gradle type check. In particular the fixture transaction models nested commit/abort;
the real NeoForge transaction contract must also be exercised by the runtime checklist.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ACTUAL = [
 'buildcraft/lib/platform/storage/ItemStorage.java',
 'buildcraft/lib/platform/storage/MutableItemStorage.java',
 'buildcraft/lib/platform/storage/FilteredItemStorage.java',
 'buildcraft/lib/platform/storage/FluidStorage.java',
 'buildcraft/lib/platform/storage/FilteredFluidStorage.java',
 'buildcraft/lib/platform/storage/EnergyStorage.java',
 'buildcraft/lib/platform/storage/StorageAdapters.java',
 'buildcraft/lib/internal/core/IFluidFilter.java',
 'buildcraft/lib/internal/core/IFluidHandlerAdv.java',
 'buildcraft/lib/internal/inventory/IItemHandlerFiltered.java',
 'buildcraft/lib/compat/transfer/TransferJournal.java',
 'buildcraft/lib/compat/transfer/TransferInterop.java',
 'buildcraft/lib/compat/transfer/IndexedFluidHandler.java',
 'buildcraft/core/item/FragileFluidResourceHandler.java',
 'buildcraft/lib/tile/item/ItemHandlerSimple.java',
 'buildcraft/lib/tile/item/StackInsertionChecker.java',
 'buildcraft/lib/tile/item/StackInsertionFunction.java',
 'buildcraft/lib/tile/item/StackChangeCallback.java',
 'buildcraft/lib/tile/item/IItemHandlerAdv.java',
 'buildcraft/lib/inventory/AbstractInvItemTransactor.java',
 'buildcraft/lib/internal/inventory/IItemTransactor.java',
 'buildcraft/builders/snapshot/BlueprintEntityData.java',
 'buildcraft/lib/client/render/compat/CapturedBlockEntityRenderer.java',
 'buildcraft/lib/compat/minecraft/render/BCGeometryRenderer.java',
]
STUBS = {}
def stub(path, body): STUBS[path + '.java'] = body
for annotation in ['javax/annotation/Nonnull','javax/annotation/Nullable','org/jetbrains/annotations/NotNull']:
 package, name = annotation.rsplit('/', 1)
 stub(annotation, f'package {package.replace("/", ".")}; public @interface {name} {{}}')
stub('net/neoforged/neoforge/transfer/transaction/TransactionContext', '''package net.neoforged.neoforge.transfer.transaction;
public interface TransactionContext {}''')
stub('net/neoforged/neoforge/transfer/transaction/SnapshotJournal', '''package net.neoforged.neoforge.transfer.transaction;
public abstract class SnapshotJournal<S> {
 protected abstract S createSnapshot(); protected abstract void revertToSnapshot(S s); protected void onRootCommit(S s) {}
 public final void updateSnapshots(TransactionContext context){Transaction.record(context,this);}
 Object capture(){return createSnapshot();}
 @SuppressWarnings("unchecked") void revert(Object s){revertToSnapshot((S)s);}
 @SuppressWarnings("unchecked") void committed(Object s){onRootCommit((S)s);}
}''')
stub('net/neoforged/neoforge/transfer/transaction/Transaction', '''package net.neoforged.neoforge.transfer.transaction;
import java.util.*;
public final class Transaction implements TransactionContext,AutoCloseable {
 private static final ThreadLocal<Transaction> CURRENT=new ThreadLocal<>();
 private final Transaction parent; private boolean closed;
 private final Map<SnapshotJournal<?>,Object> snapshots=new LinkedHashMap<>();
 private Transaction(Transaction p){parent=p;CURRENT.set(this);}
 public static TransactionContext getCurrentOpenedTransaction(){return CURRENT.get();}
 public static Transaction openRoot(){return open(null);}
 public static Transaction open(TransactionContext parent){
  if(CURRENT.get()!=parent)throw new IllegalStateException("Parent is not the current transaction");
  return new Transaction((Transaction)parent);
 }
 static void record(TransactionContext c,SnapshotJournal<?> j){
  Transaction t=(Transaction)c;if(t.closed||CURRENT.get()!=t)throw new IllegalStateException("Closed transaction");
  if(!t.snapshots.containsKey(j))t.snapshots.put(j,j.capture());
 }
 public void commit(){
  if(closed||CURRENT.get()!=this)throw new IllegalStateException("Transaction is not current");
  closed=true;CURRENT.set(parent);
  if(parent!=null)snapshots.forEach(parent.snapshots::putIfAbsent);
  else snapshots.forEach((j,s)->j.committed(s));
 }
 public void close(){if(!closed){
  if(CURRENT.get()!=this)throw new IllegalStateException("Transaction is not current");
  var entries=new ArrayList<>(snapshots.entrySet()); // NeoForge closes journals in registration order.
  entries.forEach(e->e.getKey().revert(e.getValue()));closed=true;CURRENT.set(parent);
 }}
}''')
stub('net/neoforged/neoforge/transfer/ResourceHandler', '''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public interface ResourceHandler<T> {
 int size(); T getResource(int index); long getAmountAsLong(int index); long getCapacityAsLong(int index,T resource);
 boolean isValid(int index,T resource); int insert(int index,T resource,int amount,TransactionContext transaction);
 int extract(int index,T resource,int amount,TransactionContext transaction);
 default int insert(T resource,int amount,TransactionContext tx){int moved=0;for(int i=0;i<size()&&moved<amount;i++)moved+=insert(i,resource,amount-moved,tx);return moved;}
 default int extract(T resource,int amount,TransactionContext tx){int moved=0;for(int i=0;i<size()&&moved<amount;i++)moved+=extract(i,resource,amount-moved,tx);return moved;}
}''')
stub('net/neoforged/neoforge/transfer/TransferPreconditions','''package net.neoforged.neoforge.transfer;
import net.neoforged.neoforge.transfer.item.ItemResource;import net.neoforged.neoforge.transfer.fluid.FluidResource;
public class TransferPreconditions {public static void checkNonEmptyNonNegative(Object resource,int amount){
 if(resource==null||amount<0||resource instanceof ItemResource i&&i.isEmpty()||resource instanceof FluidResource f&&f.isEmpty())throw new IllegalArgumentException();}}''')
stub('net/minecraft/world/item/Item', '''package net.minecraft.world.item;
public class Item {public final String id;public Item(String id){this.id=id;}public int max(){return 64;}}''')
stub('net/minecraft/world/item/ItemStack', '''package net.minecraft.world.item;
import java.util.*;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(null,0);private final Item item;private int count;public final Map<String,String> data=new HashMap<>();
 public ItemStack(Item item){this(item,1);}public ItemStack(Item item,int count){this.item=item;this.count=count;}
 public Item getItem(){return item;}public int getCount(){return isEmpty()?0:count;}public boolean isEmpty(){return item==null||count<=0;}
 public int getMaxStackSize(){return item==null?64:item.max();} public void setCount(int n){count=n;}public void grow(int n){count+=n;}public void shrink(int n){count-=n;}
 public ItemStack copy(){return copyWithCount(getCount());}public ItemStack copyWithCount(int n){if(n<=0||item==null)return EMPTY;ItemStack c=new ItemStack(item,n);c.data.putAll(data);return c;}
 public ItemStack split(int n){int k=Math.min(n,getCount());ItemStack r=copyWithCount(k);shrink(k);return r;}
 public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item==b.item&&a.data.equals(b.data);}
 public static boolean matches(ItemStack a,ItemStack b){return a.isEmpty()&&b.isEmpty()||a.getCount()==b.getCount()&&isSameItemSameComponents(a,b);}
}''')
stub('net/neoforged/neoforge/transfer/item/ItemResource', '''package net.neoforged.neoforge.transfer.item;
import java.util.*;import net.minecraft.world.item.*;
public record ItemResource(Item item,Map<String,String> data) {
 public static final ItemResource EMPTY=new ItemResource(null,Map.of());
 public static ItemResource of(ItemStack stack){return stack.isEmpty()?EMPTY:new ItemResource(stack.getItem(),Map.copyOf(stack.data));}
 public boolean isEmpty(){return item==null;}public ItemStack toStack(){return toStack(1);}
 public ItemStack toStack(int n){if(n<=0||isEmpty())return ItemStack.EMPTY;ItemStack stack=new ItemStack(item,n);stack.data.putAll(data);return stack;}
}''')
stub('net/neoforged/neoforge/fluids/FluidStack', '''package net.neoforged.neoforge.fluids;
public class FluidStack {public static final FluidStack EMPTY=new FluidStack("",0);public final String fluid;private int amount;
 public FluidStack(String fluid,int amount){this.fluid=fluid;this.amount=amount;}public boolean isEmpty(){return fluid.isEmpty()||amount<=0;}
 public int getAmount(){return isEmpty()?0:amount;}public FluidStack copy(){return copyWithAmount(amount);}
 public FluidStack copyWithAmount(int n){return n<=0?EMPTY:new FluidStack(fluid,n);}public void shrink(int n){amount-=n;}
}''')
stub('net/neoforged/neoforge/transfer/fluid/FluidResource', '''package net.neoforged.neoforge.transfer.fluid;
import net.neoforged.neoforge.fluids.FluidStack;
public record FluidResource(String fluid){public static final FluidResource EMPTY=new FluidResource("");
 public static FluidResource of(FluidStack stack){return stack.isEmpty()?EMPTY:new FluidResource(stack.fluid);}
 public boolean isEmpty(){return fluid.isEmpty();}public FluidStack toStack(int n){return n<=0||isEmpty()?FluidStack.EMPTY:new FluidStack(fluid,n);}}''')
stub('net/neoforged/neoforge/items/IItemHandler','''package net.neoforged.neoforge.items;import net.minecraft.world.item.ItemStack;
public interface IItemHandler {int getSlots();ItemStack getStackInSlot(int slot);int getSlotLimit(int slot);boolean isItemValid(int slot,ItemStack stack);
 ItemStack insertItem(int slot,ItemStack stack,boolean simulate);ItemStack extractItem(int slot,int amount,boolean simulate);}''')
stub('net/neoforged/neoforge/items/IItemHandlerModifiable','''package net.neoforged.neoforge.items;import net.minecraft.world.item.ItemStack;
public interface IItemHandlerModifiable extends IItemHandler {void setStackInSlot(int slot,ItemStack stack);}''')
stub('net/neoforged/neoforge/fluids/capability/IFluidHandler','''package net.neoforged.neoforge.fluids.capability;
import net.neoforged.neoforge.fluids.FluidStack;
public interface IFluidHandler {enum FluidAction {EXECUTE,SIMULATE;public boolean execute(){return this==EXECUTE;}public boolean simulate(){return this==SIMULATE;}}
 int getTanks();FluidStack getFluidInTank(int tank);int getTankCapacity(int tank);boolean isFluidValid(int tank,FluidStack stack);
 int fill(FluidStack stack,FluidAction action);FluidStack drain(FluidStack stack,FluidAction action);FluidStack drain(int amount,FluidAction action);}''')
stub('net/neoforged/neoforge/energy/IEnergyStorage','''package net.neoforged.neoforge.energy;
public interface IEnergyStorage {int getEnergyStored();int getMaxEnergyStored();boolean canReceive();boolean canExtract();int receiveEnergy(int amount,boolean simulate);int extractEnergy(int amount,boolean simulate);}''')
stub('net/neoforged/neoforge/transfer/energy/EnergyHandler','''package net.neoforged.neoforge.transfer.energy;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
public interface EnergyHandler {long getAmountAsLong();long getCapacityAsLong();int insert(int amount,TransactionContext tx);int extract(int amount,TransactionContext tx);}''')
stub('net/neoforged/neoforge/transfer/access/ItemAccess', '''package net.neoforged.neoforge.transfer.access;
import net.neoforged.neoforge.transfer.item.ItemResource;import net.neoforged.neoforge.transfer.transaction.*;
public interface ItemAccess {ItemResource getResource();int getAmount();int insert(ItemResource r,int n,TransactionContext t);int extract(ItemResource r,int n,TransactionContext t);
 default int exchange(ItemResource resource,int amount,TransactionContext transaction){
  try(Transaction child=Transaction.open(transaction)){int taken=extract(getResource(),amount,child);if(taken>0&&insert(resource,taken,child)==taken){child.commit();return taken;}}return 0;
 }}''')
stub('buildcraft/core/item/ItemFragileFluidContainer', '''package buildcraft.core.item;
import net.minecraft.world.item.*;import net.neoforged.neoforge.fluids.FluidStack;
public class ItemFragileFluidContainer extends Item {public static final int MAX_FLUID_HELD=500;public ItemFragileFluidContainer(){super("buildcraft:shard");}public int max(){return 1;}
 public static FluidStack getFluid(ItemStack s){return s.isEmpty()?FluidStack.EMPTY:new FluidStack(s.data.getOrDefault("fluid",""),Integer.parseInt(s.data.getOrDefault("amount","0")));}
 public static void setFluid(ItemStack s,FluidStack f){s.data.put("fluid",f.fluid);s.data.put("amount",Integer.toString(f.getAmount()));}}''')
stub('buildcraft/lib/internal/core/IStackFilter','''package buildcraft.lib.internal.core;import net.minecraft.world.item.ItemStack;public interface IStackFilter {boolean matches(ItemStack stack);}''')
stub('buildcraft/lib/inventory/filter/StackFilter','''package buildcraft.lib.inventory.filter;import buildcraft.lib.internal.core.IStackFilter;public class StackFilter {public static final IStackFilter ALL=s->true;}''')
stub('buildcraft/lib/misc/StackUtil','''package buildcraft.lib.misc;import net.minecraft.world.item.ItemStack;public class StackUtil {public static final ItemStack EMPTY=ItemStack.EMPTY;public static boolean canMerge(ItemStack a,ItemStack b){return ItemStack.isSameItemSameComponents(a,b);}}''')
stub('it/unimi/dsi/fastutil/ints/IntList','''package it.unimi.dsi.fastutil.ints;public interface IntList extends Iterable<Integer>{boolean add(Integer n);}''')
stub('it/unimi/dsi/fastutil/ints/IntArrayList','''package it.unimi.dsi.fastutil.ints;public class IntArrayList extends java.util.ArrayList<Integer> implements IntList {public IntArrayList(){}public IntArrayList(int n){super(n);}}''')
stub('net/minecraft/core/NonNullList','''package net.minecraft.core;public class NonNullList<T> extends java.util.ArrayList<T>{public static <T> NonNullList<T> create(){return new NonNullList<>();}public static <T> NonNullList<T> withSize(int n,T e){NonNullList<T> r=create();for(int i=0;i<n;i++)r.add(e);return r;}}''')
stub('net/minecraft/CrashReport','''package net.minecraft;public class CrashReport {public CrashReport(String m,Throwable t){}public CrashReportCategory addCategory(String s){return new CrashReportCategory();}}''')
stub('net/minecraft/CrashReportCategory','''package net.minecraft;public class CrashReportCategory {public void setDetail(String s,Object o){}}''')
stub('net/minecraft/ReportedException','''package net.minecraft;public class ReportedException extends RuntimeException {public ReportedException(CrashReport r){}}''')
stub('net/minecraft/core/HolderLookup','''package net.minecraft.core;public class HolderLookup {public interface Provider {}}''')
stub('buildcraft/lib/compat/neoforge121111/common/util/INBTSerializable','''package buildcraft.lib.compat.neoforge121111.common.util;import net.minecraft.core.HolderLookup;public interface INBTSerializable<T>{T serializeNBT(HolderLookup.Provider p);void deserializeNBT(HolderLookup.Provider p,T tag);}''')
stub('net/minecraft/nbt/Tag','''package net.minecraft.nbt;public abstract class Tag {public static final int TAG_COMPOUND=10;}''')
stub('net/minecraft/nbt/CompoundTag','''package net.minecraft.nbt;import java.util.*;public class CompoundTag extends Tag {
 public final Map<String,Object> values=new HashMap<>();public void put(String k,Tag v){values.put(k,v);}public void putString(String k,String v){values.put(k,v);}
 public void putInt(String k,int v){values.put(k,v);}public Object get(String k){return values.get(k);}public void remove(String k){values.remove(k);}public boolean contains(String k){return values.containsKey(k);}
}''')
stub('net/minecraft/nbt/ListTag','''package net.minecraft.nbt;import java.util.*;public class ListTag extends Tag {private final List<Tag> list=new ArrayList<>();public void add(Tag t){list.add(t);}public Tag get(int n){return list.get(n);}public int size(){return list.size();}}''')
stub('buildcraft/lib/compat/NbtCompat','''package buildcraft.lib.compat;import net.minecraft.nbt.*;
public class NbtCompat {public static ListTag getList(CompoundTag t,String k){return t.get(k) instanceof ListTag l?l:new ListTag();}
 public static CompoundTag getCompound(CompoundTag t,String k){return t.get(k) instanceof CompoundTag c?c:new CompoundTag();}
 public static CompoundTag getCompound(ListTag l,int i){return (CompoundTag)l.get(i);}public static String getString(CompoundTag t,String k){return t.get(k) instanceof String s?s:"";}}''')
stub('buildcraft/lib/misc/ItemStackUtil','''package buildcraft.lib.misc;import net.minecraft.world.item.*;import net.minecraft.nbt.*;import net.minecraft.core.HolderLookup;
public class ItemStackUtil {public static CompoundTag saveOptional(ItemStack s,HolderLookup.Provider p){CompoundTag t=new CompoundTag();t.values.put("stack",s.copy());return t;}
 public static ItemStack parseOptional(HolderLookup.Provider p,CompoundTag t){return t.get("stack") instanceof ItemStack s?s.copy():ItemStack.EMPTY;}}''')
stub('net/minecraft/util/ProblemReporter','''package net.minecraft.util;public class ProblemReporter {public static final ProblemReporter DISCARDING=new ProblemReporter();}''')
stub('net/minecraft/world/level/storage/TagValueOutput','''package net.minecraft.world.level.storage;import net.minecraft.nbt.CompoundTag;import net.minecraft.util.ProblemReporter;import net.minecraft.core.HolderLookup;
public class TagValueOutput {public final CompoundTag tag=new CompoundTag();public static TagValueOutput createWithContext(ProblemReporter p,HolderLookup.Provider r){return new TagValueOutput();}public CompoundTag buildResult(){return tag;}}''')
stub('net/minecraft/world/entity/Entity','''package net.minecraft.world.entity;import net.minecraft.core.HolderLookup;import net.minecraft.world.level.storage.TagValueOutput;
public class Entity {public boolean saved;public HolderLookup.Provider registryAccess(){return new HolderLookup.Provider(){};}public boolean save(TagValueOutput o){saved=true;o.tag.putString("id","test:entity");o.tag.putString("payload","native");return true;}}''')
stub('net/minecraft/world/entity/EquipmentSlot','''package net.minecraft.world.entity;public enum EquipmentSlot {MAINHAND,OFFHAND,FEET,LEGS,CHEST,HEAD}''')
stub('net/minecraft/world/entity/decoration/ArmorStand','''package net.minecraft.world.entity.decoration;import net.minecraft.world.entity.*;import net.minecraft.world.item.*;import java.util.*;import net.minecraft.world.level.storage.TagValueOutput;import net.minecraft.nbt.CompoundTag;
public class ArmorStand extends Entity {public final Map<EquipmentSlot,ItemStack> items=new EnumMap<>(EquipmentSlot.class);
 public ItemStack getItemBySlot(EquipmentSlot s){return items.getOrDefault(s,ItemStack.EMPTY);}public void setItemSlot(EquipmentSlot s,ItemStack item){items.put(s,item);}
 public boolean save(TagValueOutput o){super.save(o);o.tag.putString("id","minecraft:armor_stand");o.tag.put("equipment",new CompoundTag());return true;}}''')
# Renderer API doubles: translations are sufficient to detect double-applied/lost transforms.
stub('com/mojang/blaze3d/vertex/PoseStack','''package com.mojang.blaze3d.vertex;
public class PoseStack {public static class Matrix {public float x,y,z;}
 public static class Pose {private final Matrix m=new Matrix();public Matrix pose(){return m;}}
 private final Pose p=new Pose();public Pose last(){return p;}public void translate(float x,float y,float z){p.m.x+=x;p.m.y+=y;p.m.z+=z;}}''')
stub('com/mojang/blaze3d/vertex/VertexConsumer','''package com.mojang.blaze3d.vertex;
public interface VertexConsumer {VertexConsumer addVertex(float x,float y,float z);VertexConsumer setColor(int r,int g,int b,int a);VertexConsumer setUv(float u,float v);VertexConsumer setUv1(int u,int v);VertexConsumer setUv2(int u,int v);VertexConsumer setNormal(float x,float y,float z);VertexConsumer setLineWidth(float w);
 default VertexConsumer addVertex(PoseStack.Matrix p,float x,float y,float z){return addVertex(p.x+x,p.y+y,p.z+z);}default VertexConsumer setNormal(PoseStack.Pose p,float x,float y,float z){return setNormal(x,y,z);}
}''')
stub('net/minecraft/client/renderer/rendertype/RenderType','''package net.minecraft.client.renderer.rendertype;public record RenderType(String name){}''')
stub('net/minecraft/client/renderer/MultiBufferSource','''package net.minecraft.client.renderer;import net.minecraft.client.renderer.rendertype.RenderType;import com.mojang.blaze3d.vertex.VertexConsumer;public interface MultiBufferSource {VertexConsumer getBuffer(RenderType t);}''')
stub('net/minecraft/client/renderer/SubmitNodeCollector','''package net.minecraft.client.renderer;import net.minecraft.client.renderer.rendertype.RenderType;import com.mojang.blaze3d.vertex.*;public interface SubmitNodeCollector {interface Geometry {void draw(PoseStack.Pose p,VertexConsumer c);}void submitCustomGeometry(PoseStack p,RenderType t,Geometry g);}''')
stub('net/minecraft/client/renderer/feature/ModelFeatureRenderer','''package net.minecraft.client.renderer.feature;public class ModelFeatureRenderer {public static class CrumblingOverlay{}}''')
stub('net/minecraft/client/renderer/state/CameraRenderState','''package net.minecraft.client.renderer.state;public class CameraRenderState {}''')
stub('net/minecraft/client/renderer/texture/OverlayTexture','''package net.minecraft.client.renderer.texture;public class OverlayTexture {public static final int NO_OVERLAY=655360;}''')
stub('net/minecraft/world/level/block/entity/BlockEntity','''package net.minecraft.world.level.block.entity;public class BlockEntity {public boolean removed;public Object level=new Object();public Object getLevel(){return level;}public boolean isRemoved(){return removed;}public int frame=1;}''')
stub('net/minecraft/world/phys/Vec3','''package net.minecraft.world.phys;public record Vec3(double x,double y,double z){}''')
stub('net/minecraft/client/renderer/blockentity/state/BlockEntityRenderState','''package net.minecraft.client.renderer.blockentity.state;import net.minecraft.world.level.block.entity.BlockEntity;import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
public class BlockEntityRenderState {public int lightCoords;public static void extractBase(BlockEntity t,BlockEntityRenderState s,ModelFeatureRenderer.CrumblingOverlay c){s.lightCoords=0xF000A0;}}''')
stub('net/minecraft/client/renderer/blockentity/BlockEntityRenderer','''package net.minecraft.client.renderer.blockentity;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;import net.minecraft.world.level.block.entity.BlockEntity;import net.minecraft.client.renderer.*;import net.minecraft.client.renderer.state.CameraRenderState;import net.minecraft.client.renderer.feature.ModelFeatureRenderer;import net.minecraft.world.phys.Vec3;import com.mojang.blaze3d.vertex.PoseStack;
public interface BlockEntityRenderer<T extends BlockEntity,S extends BlockEntityRenderState>{default boolean shouldRenderOffScreen(){return false;}S createRenderState();void extractRenderState(T t,S s,float partial,Vec3 pos,ModelFeatureRenderer.CrumblingOverlay c);void submit(S s,PoseStack p,SubmitNodeCollector c,CameraRenderState cam);}''')


def run(java_root: Path, probes: dict[str, str]) -> str:
    if not shutil.which('javac') or not shutil.which('java'):
        raise RuntimeError('Java 21 javac/java required; probes were NOT run')
    with tempfile.TemporaryDirectory(prefix='bc-transfer-probes-') as temporary:
        root = Path(temporary)
        for relative in ACTUAL:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text((java_root / relative).read_text())
        for relative, source in STUBS.items() | probes.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
        argfile = root / 'sources.txt'
        argfile.write_text('\n'.join(str(p) for p in root.rglob('*.java')))
        classes = root / 'classes'
        compiled = subprocess.run(['javac', '--release', '21', '-d', str(classes), '@'+str(argfile)], text=True, capture_output=True)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        outputs = []
        for main in ['TransferProbe', 'buildcraft.builders.snapshot.BlueprintProbe', 'buildcraft.lib.client.render.compat.GeometryProbe']:
            ran = subprocess.run(['java', '-ea', '-cp', str(classes), main], text=True, capture_output=True)
            if ran.returncode:
                raise AssertionError(ran.stdout + ran.stderr)
            outputs.append(ran.stdout.strip())
        return '\n'.join(outputs)
