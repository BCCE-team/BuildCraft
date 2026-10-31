#!/usr/bin/env python3
"""Compile the complete production Guide cache and recipe-book implementations.

The fixtures stub Minecraft APIs only; the BuildCraft classes are copied directly
from the root-relative replacement files, without running an installation script.
"""
from __future__ import annotations
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
STUBS={
'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
  public final class BCLog {public static final Logger logger=new Logger();
    public static class Logger {public void warn(String s,Object... args) {}}
  }''',
'net/minecraft/resources/Identifier.java': '''package net.minecraft.resources;
  public record Identifier(String value) {
    public static Identifier fromNamespaceAndPath(String ns,String path){return new Identifier(ns+":"+path);}
  }''',
'net/minecraft/world/item/crafting/display/SlotDisplay.java': '''package net.minecraft.world.item.crafting.display;
  public interface SlotDisplay {}''',
'net/minecraft/world/item/crafting/display/RecipeDisplay.java': '''package net.minecraft.world.item.crafting.display;
  public interface RecipeDisplay {}''',
'net/minecraft/world/item/crafting/display/ShapedCraftingRecipeDisplay.java': '''package net.minecraft.world.item.crafting.display;
  import java.util.List;
  public class ShapedCraftingRecipeDisplay implements RecipeDisplay {
    public List<SlotDisplay> ingredients(){return List.of();}
  }''',
'net/minecraft/world/item/crafting/display/ShapelessCraftingRecipeDisplay.java': '''package net.minecraft.world.item.crafting.display;
  import java.util.List;
  public class ShapelessCraftingRecipeDisplay implements RecipeDisplay {
    public List<SlotDisplay> ingredients(){return List.of();}
  }''',
'net/minecraft/world/item/crafting/display/RecipeDisplayEntry.java': '''package net.minecraft.world.item.crafting.display;
  public class RecipeDisplayEntry {
    public record Id(int index) {}
    private final int index;
    private final RecipeDisplay display;
    private final int fault;
    public RecipeDisplayEntry(int index,RecipeDisplay display,int fault){
        this.index=index;this.display=display;this.fault=fault;
    }
    public Id id(){if(fault==1) throw new IllegalStateException("broken id");return new Id(index);}
    public RecipeDisplay display(){if(fault==2) throw new NoClassDefFoundError("missing addon display class");return display;}
  }''',
'net/minecraft/client/gui/screens/recipebook/RecipeCollection.java': '''package net.minecraft.client.gui.screens.recipebook;
  import java.util.List;
  import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
  public class RecipeCollection {
    private final List<RecipeDisplayEntry> recipes;
    private final boolean broken;
    public RecipeCollection(List<RecipeDisplayEntry> list,boolean broken){recipes=list;this.broken=broken;}
    public List<RecipeDisplayEntry> getRecipes(){if(broken) throw new IllegalStateException("broken collection");return recipes;}
  }''',
'net/minecraft/client/ClientRecipeBook.java': '''package net.minecraft.client;
  import java.util.List;
  import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
  public class ClientRecipeBook {
    private final List<RecipeCollection> collections;
    public ClientRecipeBook(List<RecipeCollection> collections){this.collections=collections;}
    public List<RecipeCollection> getCollections(){return collections;}
  }''',
'test/ClientGuideSafetyRuntime.java': '''package test;
  import java.util.List;
  import buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays;
  import buildcraft.lib.net.GuideRecipeDisplayCache;
  import net.minecraft.client.ClientRecipeBook;
  import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
  import net.minecraft.resources.Identifier;
  import net.minecraft.world.item.crafting.display.*;
  public class ClientGuideSafetyRuntime {
    private static void check(boolean ok,String msg){if(!ok) throw new AssertionError(msg);}
    public static void main(String[] args) {
      RecipeDisplayEntry a=new RecipeDisplayEntry(1,new ShapedCraftingRecipeDisplay(),0);
      RecipeDisplayEntry b=new RecipeDisplayEntry(2,new ShapedCraftingRecipeDisplay(),1);
      RecipeDisplayEntry c=new RecipeDisplayEntry(3,new ShapelessCraftingRecipeDisplay(),0);
      RecipeDisplayEntry d=new RecipeDisplayEntry(4,new ShapelessCraftingRecipeDisplay(),2);
      ClientRecipeBook book=new ClientRecipeBook(List.of(
        new RecipeCollection(List.of(a,b,c,d),false),
        new RecipeCollection(List.of(a),true),
        new RecipeCollection(List.of(c),false)));
      List<RecipeDisplayEntry> unlocked=BCRecipeDisplays.unlockedCrafting(book);
      check(unlocked.size()==2,"invalid entries/collections not filtered");
      List<GuideRecipeDisplayCache.Entry> before=GuideRecipeDisplayCache.entriesOr(List.of(a,b,c,d));
      check(before.size()==2,"fallback cache should isolate each broken recipe");
      GuideRecipeDisplayCache.replace(List.of(
        new GuideRecipeDisplayCache.Entry(new Identifier("valid:a"),a),
        new GuideRecipeDisplayCache.Entry(new Identifier("bad:id"),b),
        new GuideRecipeDisplayCache.Entry(new Identifier("bad:display"),d),
        new GuideRecipeDisplayCache.Entry(new Identifier("valid:c"),c)));
      check(GuideRecipeDisplayCache.entriesOr(List.of()).size()==2,
        "broken entries should not replace valid cached recipes");
      System.out.println("PASS: client fallback, broken collections and cached display types isolated");
    }
  }'''
}

class ClientGuideRuntimeTest(unittest.TestCase):
    def test_client_preview_and_cache_isolation(self):
        with tempfile.TemporaryDirectory(prefix='bcce-guide-client-') as dirname:
            root=Path(dirname)
            for path,src in STUBS.items():
                target=root/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(src,encoding='utf-8')
            for relative,java_path in (
                ('source-families/1.21.X/src/main/java/buildcraft/lib/net/GuideRecipeDisplayCache.java',
                 'buildcraft/lib/net/GuideRecipeDisplayCache.java'),
                ('source-families/1.21.X/src/main/java/buildcraft/lib/compat/minecraft/recipe/BCRecipeDisplays.java',
                 'buildcraft/lib/compat/minecraft/recipe/BCRecipeDisplays.java'),
            ):
                target=root/java_path
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes((ROOT/relative).read_bytes())
            isolated=root/'buildcraft/lib/guide/GuideFaultIsolation.java'
            isolated.parent.mkdir(parents=True,exist_ok=True)
            isolated.write_bytes((ROOT/'source-shared/src/main/java/buildcraft/lib/guide/GuideFaultIsolation.java').read_bytes())
            java=[str(p) for p in root.rglob('*.java')]
            result=subprocess.run(['javac','-d',str(root/'classes'),*java],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)
            result=subprocess.run(['java','-cp',str(root/'classes'),'test.ClientGuideSafetyRuntime'],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stdout+'\n'+result.stderr)
            self.assertIn('PASS',result.stdout)
            print(result.stdout.strip())

if __name__=='__main__':
    unittest.main(verbosity=2)
