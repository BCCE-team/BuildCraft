"""Compile and execute the real, materialized ItemAddon interaction lifecycle against Java stubs.

Asserts that an air click or useOn attaches exactly once, never opens a phantom GUI,
consumes one item in survival and remains server-authoritative.
"""
from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path


def execute(real_source: Path, *, modern_api: bool, level_method: bool) -> str:
    level_decl = 'public boolean isClientSide(){return client;}' if level_method else 'public boolean isClientSide;'
    level_constructor = 'this.client=client;' if level_method else 'this.isClientSide=client;'
    level_property = 'public final boolean client;' if level_method else ''
    item_use = ('public InteractionResult use(Level l,Player p,InteractionHand h){return InteractionResult.PASS;}'
                if modern_api else
                'public InteractionResultHolder<ItemStack> use(Level l,Player p,InteractionHand h){return new InteractionResultHolder<>(InteractionResult.PASS,p.getItemInHand(h));}')
    sources = {
        'org/apache/commons/lang3/tuple/Pair': '''package org.apache.commons.lang3.tuple;
public record Pair<A,B>(A left,B right){public A getLeft(){return left;}public B getRight(){return right;}
public static <A,B> Pair<A,B> of(A a,B b){return new Pair<>(a,b);}}''',
        'net/minecraft/world/InteractionHand': '''package net.minecraft.world; public enum InteractionHand{MAIN_HAND}''',
        'net/minecraft/world/InteractionResult': '''package net.minecraft.world; public enum InteractionResult{SUCCESS,PASS,FAIL}''',
        'net/minecraft/world/InteractionResultHolder': '''package net.minecraft.world;
public record InteractionResultHolder<T>(InteractionResult result,T value) {}''',
        'net/minecraft/world/level/Level': f'''package net.minecraft.world.level;
public class Level {{{level_property} {level_decl} public Level(boolean client){{{level_constructor}}}}}''',
        'net/minecraft/world/item/ItemStack': '''package net.minecraft.world.item;
public class ItemStack{public int count=3;public void shrink(int n){count-=n;}}''',
        'net/minecraft/world/item/Item': f'''package net.minecraft.world.item;
import net.minecraft.world.*;import net.minecraft.world.entity.player.Player;import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.UseOnContext;
public class Item{{public static class Properties{{}}public Item(Properties p){{}}
public InteractionResult useOn(UseOnContext c){{return InteractionResult.PASS;}} {item_use}}}''',
        'net/minecraft/world/entity/player/Player': '''package net.minecraft.world.entity.player;
import net.minecraft.world.*;import net.minecraft.world.item.*;
public class Player{public static class Abilities{public boolean instabuild;}
public final Abilities abilities=new Abilities();public final ItemStack held=new ItemStack();
public Abilities getAbilities(){return abilities;}
public ItemStack getItemInHand(InteractionHand h){return held;}}''',
        'net/minecraft/world/item/context/UseOnContext': '''package net.minecraft.world.item.context;
import net.minecraft.world.*;import net.minecraft.world.level.Level;import net.minecraft.world.entity.player.Player;
public record UseOnContext(Level level,Player player,InteractionHand hand){
public Level getLevel(){return level;}public Player getPlayer(){return player;}public InteractionHand getHand(){return hand;}}''',
        'buildcraft/core/marker/volume/Lock': '''package buildcraft.core.marker.volume;
public class Lock{public abstract static class Target{
public static class TargetResize extends Target{} public static class TargetAddon extends Target{
public EnumAddonSlot slot;public TargetAddon(EnumAddonSlot slot){this.slot=slot;}}}}''',
        'buildcraft/core/marker/volume/Addon': '''package buildcraft.core.marker.volume;
public class Addon{public VolumeBox volumeBox;public int added;public static int openCalls;
public void onAdded(){added++;}public boolean canBePlaceInto(VolumeBox box){return true;}
public void onPlayerRightClick(net.minecraft.world.entity.player.Player p){openCalls++;}}''',
        'buildcraft/core/marker/volume/VolumeBox': '''package buildcraft.core.marker.volume;
import java.util.*;import java.util.stream.*;
public class VolumeBox{public boolean editing;
public boolean isEditing(){return editing;}
public final Map<EnumAddonSlot,Addon> addons=new HashMap<>();
public final List<Lock.Target> locks=new ArrayList<>();
public Stream<Lock.Target> getLockTargetsStream(){return locks.stream();}}''',
        'buildcraft/core/marker/volume/WorldSavedDataVolumeBoxes': '''package buildcraft.core.marker.volume;
import java.util.*;import net.minecraft.world.level.Level;
public class WorldSavedDataVolumeBoxes{
public static final WorldSavedDataVolumeBoxes INSTANCE=new WorldSavedDataVolumeBoxes();
public final List<VolumeBox> volumeBoxes=new ArrayList<>();public int dirty;
public static WorldSavedDataVolumeBoxes get(Level level){return INSTANCE;}
public void setDirty(){dirty++;}}''',
        'buildcraft/core/marker/volume/ClientVolumeBoxes': '''package buildcraft.core.marker.volume;
import java.util.*;public enum ClientVolumeBoxes{INSTANCE;
public final List<VolumeBox> volumeBoxes=new ArrayList<>();}''',
        'buildcraft/core/marker/volume/EnumAddonSlot': '''package buildcraft.core.marker.volume;
import java.util.*;import org.apache.commons.lang3.tuple.Pair;
import net.minecraft.world.entity.player.Player;
public enum EnumAddonSlot{CORNER;
public static VolumeBox candidate;
public static Pair<VolumeBox,EnumAddonSlot> getSelectingVolumeBoxAndSlot(Player p,List<VolumeBox> boxes){
return candidate!=null&&boxes.contains(candidate)?Pair.of(candidate,CORNER):Pair.of(null,null);}}''',
        'buildcraft/core/marker/volume/Check': '''package buildcraft.core.marker.volume;
import net.minecraft.world.*;import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.UseOnContext;
public class Check{
static int n;static void ok(boolean x){n++;if(!x)throw new AssertionError("check "+n);}
static final class Planner extends ItemAddon{
 Planner(){super(new Item.Properties());}public Addon createAddon(){return new Addon();}}
public static void main(String[] args){
var item=new Planner();var p=new Player();var srv=new Level(false);var client=new Level(true);
var data=WorldSavedDataVolumeBoxes.INSTANCE;var box=new VolumeBox();data.volumeBoxes.add(box);
EnumAddonSlot.candidate=box;var hand=InteractionHand.MAIN_HAND;
// Placing via useOn must attach, not open an editor.
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.SUCCESS);
ok(box.addons.size()==1);ok(data.dirty==1);ok(p.held.count==2);ok(Addon.openCalls==0);
// Already installed: item is a pure placement tool. MarkerConnector owns GUI opening.
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.PASS);
ok(box.addons.size()==1);ok(data.dirty==1);ok(Addon.openCalls==0);
// Installing a second addon after removing the first is possible only when unlocked.
box.addons.clear();box.editing=true;
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.PASS);
box.editing=false;box.locks.add(new Lock.Target.TargetResize());
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.PASS);
box.locks.clear();box.locks.add(new Lock.Target.TargetAddon(EnumAddonSlot.CORNER));
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.PASS);
box.locks.clear();
// Prediction must not mutate the server's authoritative addon data.
ClientVolumeBoxes.INSTANCE.volumeBoxes.add(box);
ok(item.useOn(new UseOnContext(client,p,hand))==InteractionResult.SUCCESS);
ok(box.addons.size()==0);ok(data.dirty==1);ok(p.held.count==2);
// Creative keeps its item; placement still persists.
p.getAbilities().instabuild=true;
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.SUCCESS);
ok(p.held.count==2);ok(data.dirty==2);ok(Addon.openCalls==0);
// Missed ray doesn't create a phantom addon and doesn't open anything.
EnumAddonSlot.candidate=null;int count=box.addons.size();
ok(item.useOn(new UseOnContext(srv,p,hand))==InteractionResult.PASS);
ok(box.addons.size()==count);ok(Addon.openCalls==0);
System.out.println(n+" assertions PASS");
}}'''
    }
    with tempfile.TemporaryDirectory(prefix='bc-planner-placement-java-') as temp:
        root=Path(temp)
        paths=[]
        for name, body in sources.items():
            path=root/'src'/(name+'.java')
            path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(body)
            paths.append(str(path))
        real=root/'src/buildcraft/core/marker/volume/ItemAddon.java'
        real.write_bytes(real_source.read_bytes())
        paths.append(str(real))
        p=subprocess.run(['javac','--release','21','-encoding','UTF-8','-d',str(root/'classes'),*paths],
            capture_output=True,text=True,timeout=70)
        if p.returncode:raise AssertionError('ItemAddon Java compilation failed:\n'+p.stdout+'\n'+p.stderr)
        p=subprocess.run(['java','-cp',str(root/'classes'),'buildcraft.core.marker.volume.Check'],
            capture_output=True,text=True,timeout=30)
        if p.returncode:raise AssertionError('ItemAddon lifecycle failed:\n'+p.stdout+'\n'+p.stderr)
        return p.stdout.strip()
