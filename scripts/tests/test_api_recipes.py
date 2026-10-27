#!/usr/bin/env python3
"""Assembly recipes, machine recipe discovery and refresh contracts."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

import os
import shutil
import subprocess
import tempfile
import unittest
import zipfile

RECIPES_SHARED = ROOT / 'source-shared/src/main/java'
RECIPES_PUBLIC = [
    'buildcraft/api/v2/recipe/RecipeDefinition.java',
    'buildcraft/api/v2/recipe/CountedIngredient.java',
    'buildcraft/api/v2/recipe/AssemblyIngredients.java',
    'buildcraft/api/v2/recipe/AssemblyRecipeDefinition.java',
    'buildcraft/api/v2/recipe/SimpleAssemblyRecipe.java',
    'buildcraft/api/v2/recipe/StaticIntegrationRecipe.java',
    'buildcraft/api/v2/recipe/ProgrammingRecipeDefinition.java',
    'buildcraft/api/v2/recipe/IntegrationRecipeDefinition.java',
    'buildcraft/api/v2/recipe/IntegrationRecipeProvider.java',
    'buildcraft/api/v2/recipe/RecipeMatch.java',
    'buildcraft/api/v2/recipe/MachineRecipeService.java',
    *('buildcraft/api/v2/reload/' + name + '.java' for name in (
        'DefinitionEntry', 'DefinitionProvenance', 'DefinitionSnapshot',
        'ReloadDiagnostic', 'ReloadGeneration', 'ResolvedDefinition')),
]
RECIPES_BACKEND = [
    'buildcraft/lib/recipe/RecipeBlacklist.java',
    'buildcraft/lib/internal/api/v2/MachineRecipeRegistryImpl.java',
    *('buildcraft/lib/internal/api/v2/reload/' + name + '.java' for name in (
        'ReloadableDefinitionRegistry', 'ReloadTransaction', 'ReloadPhase', 'DefinitionValidator')),
    *('buildcraft/lib/internal/api/v2/recipe/' + name + '.java' for name in (
        'MachineRecipeRegistration', 'MachineRecipeReloadResult')),
]
RECIPES_STUBS = {
    'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public class ResourceLocation {
 private final String id;
 protected ResourceLocation(String id){this.id=id;}
 public static ResourceLocation tryParse(String s){return s == null || !s.contains(":")?null:new ResourceLocation(s);}
 public String toString(){return id;}
 public int hashCode(){return id.hashCode();}
 public boolean equals(Object value){return value instanceof ResourceLocation that && id.equals(that.id);}
}''',
    'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources;
public final class Identifier extends ResourceLocation {
 private Identifier(String name){super(name);}
 public static Identifier tryParse(String name){return new Identifier(name);}
}''',
    'net/minecraft/core/HolderSet.java': '''package net.minecraft.core;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
public class HolderSet<T> {
 public final TagKey<T> tag;
 public HolderSet(TagKey<T> tag){this.tag=tag;}
 public static HolderSet<Item> emptyNamed(net.minecraft.core.registries.BuiltInRegistries.ItemRegistry registry, TagKey<Item> tag){return new HolderSet<>(tag);}
}''',
    'net/minecraft/core/registries/BuiltInRegistries.java': '''package net.minecraft.core.registries;
import java.util.*;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.Item;
import net.minecraft.tags.TagKey;
public final class BuiltInRegistries {
 public static final ItemRegistry ITEM=new ItemRegistry();
 public static class ItemRegistry {
   public Optional<HolderSet<Item>> get(TagKey<Item> tag){return Optional.of(new HolderSet<>(tag));}
 }
}''',
    'net/minecraft/core/NonNullList.java': '''package net.minecraft.core;
public class NonNullList<T> extends java.util.ArrayList<T> {
 public static <T> NonNullList<T> create(){return new NonNullList<>();}
}''',
    'net/minecraft/tags/TagKey.java': '''package net.minecraft.tags;
import java.util.*;
public class TagKey<T> {
 private final Set<T> members=new HashSet<>();
 @SafeVarargs public TagKey(T... items){members.addAll(Arrays.asList(items));}
 public boolean has(T item){return members.contains(item);}
}''',
    'net/minecraft/world/level/ItemLike.java': '''package net.minecraft.world.level;
import net.minecraft.world.item.Item;
public interface ItemLike {Item asItem();}''',
    'net/minecraft/world/item/Item.java': '''package net.minecraft.world.item;
public class Item implements net.minecraft.world.level.ItemLike {
 public final String id;
 public Item(String id){this.id=id;}
 public Item asItem(){return this;}
 public String toString(){return id;}
}''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
import net.minecraft.tags.TagKey;
public class ItemStack {
 public static final ItemStack EMPTY=new ItemStack(null,0,"");
 private final Item item; private int count; private final String data;
 public ItemStack(Item item){this(item,1,"");}
 public ItemStack(Item item,int count){this(item,count,"");}
 public ItemStack(Item item,int count,String data){this.item=item;this.count=count;this.data=data;}
 public ItemStack copy(){return new ItemStack(item,count,data);}
 public boolean isEmpty(){return item==null || count<=0;}
 public Item getItem(){return item;}
 public int getCount(){return count;}
 public void shrink(int n){count-=n;if(count<0)throw new IllegalArgumentException("negative stack");}
 public boolean is(TagKey<Item> tag){return tag.has(item);}
 public static boolean isSameItemSameComponents(ItemStack a,ItemStack b){return a.item==b.item && a.data.equals(b.data);}
 public static boolean isSameItemSameTags(ItemStack a,ItemStack b){return isSameItemSameComponents(a,b);}
 public String toString(){return item+":"+count+"/"+data;}
}''',
    'net/minecraft/world/item/crafting/Ingredient.java': '''package net.minecraft.world.item.crafting;
import java.util.function.Predicate;
import net.minecraft.core.HolderSet;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ItemLike;
public class Ingredient {
 private final Predicate<ItemStack> matcher;
 private Ingredient(Predicate<ItemStack> matcher){this.matcher=matcher;}
 public static Ingredient of(ItemLike item){return new Ingredient(stack->!stack.isEmpty() && stack.getItem()==item.asItem());}
 public static Ingredient of(ItemStack item){return of(item.getItem());}
 public static Ingredient of(TagKey<Item> tag){return new Ingredient(stack->!stack.isEmpty() && stack.is(tag));}
 public static Ingredient of(HolderSet<Item> items){return of(items.tag);}
 public boolean test(ItemStack stack){return stack!=null && matcher.test(stack);}
}''',
    'buildcraft/api/v2/BuildCraftServices.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.recipe.MachineRecipeService;
public final class BuildCraftServices {
 public record Key<T>() {}
 public static final Key<MachineRecipeService> MACHINE_RECIPES=new Key<>();
}''',
    'buildcraft/api/v2/BuildCraftApi.java': '''package buildcraft.api.v2;
import buildcraft.api.v2.recipe.MachineRecipeService;
public final class BuildCraftApi {
 public static MachineRecipeService machine;
 @SuppressWarnings("unchecked") public static <T> T service(BuildCraftServices.Key<T> key) {return (T)machine;}
}''',
    'buildcraft/api/v2/fluid/FluidVariant.java': '''package buildcraft.api.v2.fluid;
public record FluidVariant(String id) {}''',
    'buildcraft/api/v2/fluid/FluidMatchContext.java': '''package buildcraft.api.v2.fluid;
public record FluidMatchContext() {}''',
    'buildcraft/api/v2/recipe/FluidIngredient.java': '''package buildcraft.api.v2.recipe;
import buildcraft.api.v2.fluid.*;
public record FluidIngredient(String accepted) {
 public boolean matches(FluidVariant input,FluidMatchContext ctx){return accepted.equals(input.id());}
}''',
    'buildcraft/api/v2/recipe/DistillationRecipeDefinition.java': '''package buildcraft.api.v2.recipe;
public record DistillationRecipeDefinition(FluidIngredient input) implements RecipeDefinition {
 public Kind kind(){return Kind.DISTILLATION;}
}''',
    'buildcraft/api/v2/recipe/HeatExchangeRecipeDefinition.java': '''package buildcraft.api.v2.recipe;
public record HeatExchangeRecipeDefinition(Kind kind,FluidIngredient input) implements RecipeDefinition {}''',
    'com/google/common/collect/ImmutableSet.java': '''package com.google.common.collect;
public class ImmutableSet<T> extends java.util.LinkedHashSet<T> {
 public static <T> ImmutableSet<T> of(){return new ImmutableSet<>();}
}''',
    'buildcraft/lib/internal/recipes/IngredientStack.java': '''package buildcraft.lib.internal.recipes;
import net.minecraft.world.item.crafting.Ingredient;
public class IngredientStack {
 public final Ingredient ingredient;public final int count;
 public IngredientStack(Ingredient ingredient,int count){this.ingredient=ingredient;this.count=count;}
}''',
    'net/minecraft/world/item/Items.java': '''package net.minecraft.world.item;
public final class Items {public static final Item PAPER=new Item("paper");}''',
    'net/minecraft/core/HolderLookup.java': '''package net.minecraft.core;
public interface HolderLookup {interface Provider {}}''',
    'buildcraft/lib/recipe/AssemblyRecipe.java': '''package buildcraft.lib.recipe;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import buildcraft.lib.internal.recipes.IngredientStack;
import com.google.common.collect.ImmutableSet;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
public class AssemblyRecipe {
 public AssemblyRecipe(Identifier id,long mj,ImmutableSet<IngredientStack> ingredients,ItemStack output,String group){}
 public Set<ItemStack> getOutputs(IItemHandlerModifiable inv){return Set.of();}
 public Set<ItemStack> getOutputPreviews(){return Set.of();}
 public Set<IngredientStack> getInputsFor(ItemStack output){return Set.of();}
 public long getRequiredMicroJoulesFor(ItemStack output){return 0;}
 public ItemStack getResultItem(HolderLookup.Provider registries){return ItemStack.EMPTY;}
}''',
    'net/neoforged/neoforge/items/IItemHandlerModifiable.java': '''package net.neoforged.neoforge.items;
import net.minecraft.world.item.ItemStack;
public interface IItemHandlerModifiable {
 int getSlots();ItemStack getStackInSlot(int slot);void setStackInSlot(int slot,ItemStack stack);
}''',
}
RECIPES_ADDON = r'''package addon;
import java.util.*;
import buildcraft.api.v2.recipe.*;
import buildcraft.api.v2.reload.*;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
public final class IndependentRecipeAddon {
 public static final Item COPPER=new Item("copper"),TIN=new Item("tin"),WRENCH=new Item("wrench"),MODULE=new Item("module");
 public static final TagKey<Item> METAL=new TagKey<>(COPPER,TIN);
 public static final ItemStack OUTPUT=new ItemStack(MODULE,2,"recipe-result");
 public static final SimpleAssemblyRecipe ASSEMBLY=new SimpleAssemblyRecipe(List.of(
   CountedIngredient.of(METAL,2),CountedIngredient.of(new ItemStack(COPPER,1,"charged"),1)),OUTPUT,12345);
 public static final DefinitionProvenance ADDON=new DefinitionProvenance("test-addon","addon-code",12);
 public static ResourceLocation id(String path){return ResourceLocation.tryParse("test:"+path);}
 public static NonNullList<ItemStack> slots(ItemStack... stacks){NonNullList<ItemStack> slots=NonNullList.create();slots.addAll(Arrays.asList(stacks));return slots;}
 public static void ok(boolean yes,String label){if(!yes)throw new AssertionError(label);}
 public static void attach(MachineRecipeService service){service.register(id("assembly"),ASSEMBLY,ADDON);}
 public static void exercise(MachineRecipeService service){
  var good=slots(new ItemStack(COPPER,1,"charged"),new ItemStack(TIN,2));
  ok(ASSEMBLY.canCraft(good),"tag/exact overlap allocation");
  ok(ASSEMBLY.requiredMicroJoules(OUTPUT.copy())==12345,"power cost not retained");
  ok(ASSEMBLY.outputPreviews().iterator().next().getCount()==2,"display count");
  var remainder=AssemblyIngredients.remaining(ASSEMBLY.requirements(OUTPUT),good).orElseThrow();
  ok(remainder.get(0).isEmpty() && remainder.get(1).isEmpty(),"ingredient allocation incorrect");
  ok(good.get(0).getCount()==1 && good.get(1).getCount()==2,"simulation mutated inputs");
  ok(!ASSEMBLY.canCraft(slots(new ItemStack(COPPER,1,"uncharged"),new ItemStack(TIN,2))),"component mismatch accepted");
  ok(!ASSEMBLY.canCraft(slots(new ItemStack(COPPER,2,"charged"))),"single slot double-counted");
  ok(!ASSEMBLY.canCraft(slots(new ItemStack(TIN,2))),"missing exact ingredient accepted");
  ok(service.findAssembly(good).size()==1,"Assembly registry did not resolve addon recipe");
  ok(service.assemblyById(id("assembly")).orElseThrow().recipe()==ASSEMBLY,"id lookup did not return instance");
  ok(service.findAssembly(slots(new ItemStack(TIN,2))).isEmpty(),"incomplete inputs returned a recipe");
 }
 public static IntegrationRecipeProvider dynamic(){return (target,slots)->
   target.getItem()==WRENCH && !slots.isEmpty() && slots.get(0).getItem()==TIN
     ? new IntegrationRecipeDefinition() {
         public ItemStack output(ItemStack t,NonNullList<ItemStack> parts){return new ItemStack(MODULE,1,"integrated");}
         public List<CountedIngredient> requirements(ItemStack result){return List.of(CountedIngredient.of(TIN,1));}
         public long requiredMicroJoules(ItemStack result){return 90;}
         public CountedIngredient centerIngredient(){return CountedIngredient.of(WRENCH,1);}
       }:null;}
}'''
RECIPES_HOST = r'''package fixture;
import java.util.*;
import addon.IndependentRecipeAddon;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.recipe.*;
import buildcraft.api.v2.reload.*;
import buildcraft.lib.internal.api.v2.MachineRecipeRegistryImpl;
import buildcraft.lib.internal.api.v2.recipe.MachineRecipeRegistration;
import buildcraft.lib.recipe.ApiAssemblyRecipeAdapter;
import net.minecraft.world.item.*;
import net.minecraft.core.*;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
public class Host {
 static void ok(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
 static final class Slots implements IItemHandlerModifiable {
  final ItemStack[] stacks;
  Slots(ItemStack... stacks){this.stacks=stacks;}
  public int getSlots(){return stacks.length;}
  public ItemStack getStackInSlot(int i){return stacks[i];}
  public void setStackInSlot(int i,ItemStack stack){stacks[i]=stack;}
 }
 public static void main(String[] args){
  MachineRecipeRegistryImpl service=new MachineRecipeRegistryImpl();BuildCraftApi.machine=service;
  IndependentRecipeAddon.attach(service);IndependentRecipeAddon.exercise(service);
  var base=service.snapshot();long baseline=base.generation().id();
  ok(!service.reloadData(List.of(
     new MachineRecipeRegistration(IndependentRecipeAddon.id("assembly"),IndependentRecipeAddon.ASSEMBLY,
         new DefinitionProvenance("test-addon","datapack",12)))).published(),"conflict accepted");
  ok(service.snapshot()==base,"failed reload published partial state");
  ok(service.reloadData(List.of(new MachineRecipeRegistration(IndependentRecipeAddon.id("assembly"),
     new SimpleAssemblyRecipe(List.of(CountedIngredient.of(IndependentRecipeAddon.TIN,1)),
       new ItemStack(IndependentRecipeAddon.MODULE,1,"override"),150),
     new DefinitionProvenance("datapack","high",14)))).published(),"priority override failed");
  ok(service.snapshot().generation().id()>baseline,"generation not updated");
  ok(service.assemblyById(IndependentRecipeAddon.id("assembly")).orElseThrow().provenance().priority()==14,
    "provenance priority lost");
  ok(base.get(IndependentRecipeAddon.id("assembly")).orElseThrow()==IndependentRecipeAddon.ASSEMBLY,
    "previous snapshot mutated by reload");
  ok(service.reloadData(List.of()).published(),"datapack removal failed");
  ok(service.assemblyById(IndependentRecipeAddon.id("assembly")).orElseThrow().recipe()==IndependentRecipeAddon.ASSEMBLY,
    "code-owned recipe lost on reload");
  service.register(IndependentRecipeAddon.id("dynamic"),IndependentRecipeAddon.dynamic(),
    new DefinitionProvenance("test-addon","code",15));
  var dynamic=service.findIntegration(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.TIN)));
  ok(dynamic.isPresent() && dynamic.orElseThrow().recipe().output(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.TIN))).getItem()==IndependentRecipeAddon.MODULE,
    "dynamic Integration Table recipe missing");
  ok(service.findIntegration(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.COPPER))).isEmpty(),
    "dynamic integration accepted wrong ingredient");
  var fixed = new StaticIntegrationRecipe(CountedIngredient.of(IndependentRecipeAddon.WRENCH,1),
    List.of(CountedIngredient.of(IndependentRecipeAddon.METAL, 2),
            CountedIngredient.of(IndependentRecipeAddon.COPPER, 1)),
    new ItemStack(IndependentRecipeAddon.MODULE,2), 2500);
  ok(!fixed.output(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.COPPER,1),
      new ItemStack(IndependentRecipeAddon.TIN,2))).isEmpty(), "static integration overlap allocation failed");
  ok(fixed.output(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.COPPER,1),
      new ItemStack(IndependentRecipeAddon.TIN,1))).isEmpty(), "static integration double-counted input");
  ok(fixed.output(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.COPPER,2),
      new ItemStack(IndependentRecipeAddon.TIN,2))).isEmpty(), "static integration accepted extra items");
  ok(fixed.requiredMicroJoules(new ItemStack(IndependentRecipeAddon.MODULE,2))==2500,
    "static integration MJ mismatch");
  ok(new ProgrammingRecipeDefinition("picker", 800_000_000L).kind() == RecipeDefinition.Kind.PROGRAMMING,
    "programming definition not typed");
  // One malformed third-party definition must not take the entire Silicon machine offline.
  service.register(IndependentRecipeAddon.id("bad_assembly"),new AssemblyRecipeDefinition() {
     public Set<ItemStack> outputs(NonNullList<ItemStack> slots){throw new IllegalStateException("broken addon");}
     public Set<ItemStack> outputPreviews(){return Set.of();}
     public List<CountedIngredient> requirements(ItemStack result){return List.of();}
     public long requiredMicroJoules(ItemStack result){return 100;}
   },new DefinitionProvenance("broken","code",100));
  ok(service.findAssembly(IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.COPPER,1,"charged"),
      new ItemStack(IndependentRecipeAddon.TIN,2))).size()==1,"bad addon corrupted assembly discovery");
  service.register(IndependentRecipeAddon.id("bad_integration"),
    (IntegrationRecipeProvider)(target,slots)->{throw new IllegalStateException("broken provider");},
    new DefinitionProvenance("broken","code",101));
  ok(service.findIntegration(new ItemStack(IndependentRecipeAddon.WRENCH),
    IndependentRecipeAddon.slots(new ItemStack(IndependentRecipeAddon.TIN))).isPresent(),
    "bad addon blocked valid integration provider");
  var id=IndependentRecipeAddon.id("assembly");
  ApiAssemblyRecipeAdapter adapter=ApiAssemblyRecipeAdapter.fromService(net.minecraft.resources.Identifier.tryParse(id.toString()));
  ok(adapter!=null && adapter.registered(),"runtime adapter unavailable");
  ok(adapter.getOutputPreviews().iterator().next().getCount()==2,"JEI preview missing");
  ok(adapter.getResultItem(null).getItem()==IndependentRecipeAddon.MODULE,"JEI result is placeholder");
  Slots items=new Slots(new ItemStack(IndependentRecipeAddon.COPPER,1,"charged"),
    new ItemStack(IndependentRecipeAddon.TIN,2));
  ok(adapter.consume(items,IndependentRecipeAddon.OUTPUT,true),"simulate rejected valid recipe");
  ok(items.getStackInSlot(0).getCount()==1,"simulation mutated slot");
  ok(adapter.consume(items,IndependentRecipeAddon.OUTPUT,false),"consume rejected valid recipe");
  ok(items.getStackInSlot(0).isEmpty() && items.getStackInSlot(1).isEmpty(),"items not deducted atomically");
  Slots broken=new Slots(new ItemStack(IndependentRecipeAddon.COPPER,1,"incorrect"),
    new ItemStack(IndependentRecipeAddon.TIN,2));
  ok(!adapter.consume(broken,IndependentRecipeAddon.OUTPUT,false),"invalid components consumed");
  ok(broken.getStackInSlot(0).getCount()==1 && broken.getStackInSlot(1).getCount()==2,
    "failed craft mutated slots");
  ok(service.removeCode(id),"code removal failed");
  ok(!adapter.registered() && !adapter.consume(items,IndependentRecipeAddon.OUTPUT,true),"stale recipe usable after removal");
  System.out.println("API assembly contracts PASS independent addon, exact input, lifecycle, reload, JEI preview and table consumption");
 }
}'''


def recipes_run(*args: str, timeout=110) -> str:
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise AssertionError(' '.join(map(str, args[:6])) + '\n' + result.stdout + '\n' + result.stderr[-14000:])
    return result.stdout


def recipes_write(root: Path, name: str, data: str) -> Path:
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding='utf-8')
    return path


class AssemblyRecipeConformanceTest(unittest.TestCase):
    def test_public_jar_and_production_recipe_registry(self):
        if not all(shutil.which(tool) for tool in ('javac','java','jar')):
            self.skipTest('JDK needed to test real JVM behavior')
        with tempfile.TemporaryDirectory(prefix='bcce-recipes-') as td:
            temp = Path(td)
            common_stubs = [recipes_write(temp / 'stub', name, body) for name,body in RECIPES_STUBS.items()]
            for family, counted, adapter in (
                ('old',RECIPES_SHARED/'buildcraft/api/v2/recipe/CountedIngredient.java',
                    ROOT/'source-platforms/forge/src/main/java/buildcraft/lib/recipe/ApiAssemblyRecipeAdapter.java'),
                ('1.21.1',RECIPES_SHARED/'buildcraft/api/v2/recipe/CountedIngredient.java',
                    ROOT/'source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/lib/recipe/ApiAssemblyRecipeAdapter.java'),
                ('1.21.11',ROOT/'version-src/1.21.11-neoforge/src/main/java/buildcraft/api/v2/recipe/CountedIngredient.java',
                    ROOT/'source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/recipe/ApiAssemblyRecipeAdapter.java'),
                ('26.x',ROOT/'source-families/26.X/src/main/java/buildcraft/api/v2/recipe/CountedIngredient.java',
                    ROOT/'source-family-platforms/26.X/neoforge/src/main/java/buildcraft/lib/recipe/ApiAssemblyRecipeAdapter.java'),
            ):
                # Optional developer-only smoke run; the default still checks every family.
                selected_family = os.environ.get("BCCE_TEST_RECIPE_FAMILY")
                if selected_family and family != selected_family:
                    continue
                with self.subTest(family=family):
                    work = temp/family
                    api_dir=work/'public'; api_dir.mkdir(parents=True)
                    # For this test, the Forge IItemHandlerModifiable is an ABI-compatible alias in stubs.
                    selected = [RECIPES_SHARED/f for f in RECIPES_PUBLIC if not f.endswith('/CountedIngredient.java')]
                    sources = common_stubs.copy()
                    if family=='1.21.1':
                        neo_stub=RECIPES_STUBS['buildcraft/lib/recipe/AssemblyRecipe.java'].replace(
                            'AssemblyRecipe(Identifier id','AssemblyRecipe(ResourceLocation id')
                        sources=[s for s in sources if s != temp/'stub/buildcraft/lib/recipe/AssemblyRecipe.java']
                        sources.append(recipes_write(work/'stub','buildcraft/lib/recipe/AssemblyRecipe.java',neo_stub))
                    if family=='old':
                        forge_interface = recipes_write(work/'stub','net/minecraftforge/items/IItemHandlerModifiable.java',
                            'package net.minecraftforge.items; public interface IItemHandlerModifiable extends net.neoforged.neoforge.items.IItemHandlerModifiable {}')
                        sources.append(forge_interface)
                        old_stub = RECIPES_STUBS['buildcraft/lib/recipe/AssemblyRecipe.java'].replace(
                            'import net.neoforged.neoforge.items.IItemHandlerModifiable;',
                            'import net.minecraftforge.items.IItemHandlerModifiable;').replace('AssemblyRecipe(Identifier id',
                            'AssemblyRecipe(ResourceLocation id')
                        sources=[s for s in sources if s != temp/'stub/buildcraft/lib/recipe/AssemblyRecipe.java']
                        sources.append(recipes_write(work/'stub','buildcraft/lib/recipe/AssemblyRecipe.java',old_stub))
                    recipes_run('javac','--release','17','-encoding','UTF-8','-sourcepath','',
                        '-d',str(api_dir),*map(str,sources),*map(str,selected),str(counted))
                    api_jar=work/'api.jar'
                    recipes_run('jar','--create','--file',str(api_jar),'-C',str(api_dir),'.')
                    addon=recipes_write(work,'addon-src/addon/IndependentRecipeAddon.java',RECIPES_ADDON)
                    addon_dir=work/'addon-classes';addon_dir.mkdir()
                    recipes_run('javac','--release','17','-encoding','UTF-8','-sourcepath','',
                        '-cp',str(api_jar),'-d',str(addon_dir),str(addon))
                    addon_jar=work/'addon.jar'
                    recipes_run('jar','--create','--file',str(addon_jar),'-C',str(addon_dir),'.')
                    with zipfile.ZipFile(addon_jar) as z:
                        self.assertEqual([f for f in z.namelist() if f.endswith('.class')],
                                         [f for f in z.namelist() if f.startswith('addon/') and f.endswith('.class')])
                    host_src=RECIPES_HOST
                    if family=='1.21.1':
                        host_src=host_src.replace(
                            'ApiAssemblyRecipeAdapter.fromService(net.minecraft.resources.Identifier.tryParse(id.toString()))',
                            'ApiAssemblyRecipeAdapter.fromService(id)')
                    if family=='old':
                        host_src=host_src.replace('import net.neoforged.neoforge.items.IItemHandlerModifiable;',
                            'import net.minecraftforge.items.IItemHandlerModifiable;').replace(
                            'ApiAssemblyRecipeAdapter.fromService(net.minecraft.resources.Identifier.tryParse(id.toString()))',
                            'ApiAssemblyRecipeAdapter.fromService(id)').replace(
                            'ok(adapter.getResultItem(null).getItem()==IndependentRecipeAddon.MODULE,"JEI result is placeholder");','')
                    host=recipes_write(work,'host-src/fixture/Host.java',host_src)
                    compiled=work/'host-classes';compiled.mkdir()
                    recipes_run('javac','--release','17','-encoding','UTF-8','-sourcepath','',
                        '-cp',os.pathsep.join((str(api_jar),str(addon_jar))),'-d',str(compiled),
                        str(host),str(adapter),*(str(RECIPES_SHARED/f) for f in RECIPES_BACKEND))
                    result=recipes_run('java','-cp',os.pathsep.join((str(api_jar),str(addon_jar),str(compiled))),
                        'fixture.Host')
                    self.assertIn('API assembly contracts PASS',result)

    def test_all_target_assembly_wiring(self):
        base = ROOT/'scripts'
        targets = [
          '1.19.2-forge','1.20.1-forge','1.21.1-neoforge','1.21.11-neoforge',
          '26.1.2-neoforge','26.2-neoforge','26.3-neoforge',
        ]
        import sys
        sys.path.insert(0,str(base))
        from source_layout import effective_source_files, load_properties, target_layout
        props = load_properties()
        for target in targets:
            with self.subTest(target=target):
                effective = effective_source_files(target_layout(target, props), props)
                for suffix in (
                    'buildcraft/lib/recipe/ApiAssemblyRecipeAdapter.java',
                    'buildcraft/silicon/tile/TileAssemblyTable.java',
                    'buildcraft/compat/jei/BuildCraftJeiPlugin.java',
                    'buildcraft/api/v2/recipe/CountedIngredient.java',
                ):
                    files = [file for name,file in effective.items() if name.endswith(suffix)]
                    self.assertEqual(len(files),1,(target,suffix,files))
                    source = Path(files[0]).read_text(encoding='utf-8')
                    if suffix.endswith('TileAssemblyTable.java'):
                        self.assertIn('ApiAssemblyRecipeAdapter.fromService',source)
                        self.assertIn('machineRecipes().findAssembly',source)
                        self.assertIn('adapter.consume(inv',source)
                    if suffix.endswith('BuildCraftJeiPlugin.java'):
                        self.assertIn('ApiAssemblyRecipeAdapter.fromService',source)
                    if suffix.endswith('CountedIngredient.java'):
                        self.assertIn('matchesItem(',source)
                        self.assertIn('sameItemAndData(',source)

if __name__ == "__main__":
    unittest.main()
