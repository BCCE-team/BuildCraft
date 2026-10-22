#!/usr/bin/env python3
"""Compile the public item transit contract with its production dispatcher and check ownership rules."""
from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = 'source-shared/src/main/java/'
SOURCES = [BASE + p for p in (
    'buildcraft/api/v2/pipe/ItemTransitPhase.java',
    'buildcraft/api/v2/pipe/ItemTransitStatus.java',
    'buildcraft/api/v2/pipe/ItemTransitTransfer.java',
    'buildcraft/api/v2/pipe/ItemTransitContext.java',
    'buildcraft/api/v2/pipe/ItemTransitComponent.java',
    'buildcraft/api/v2/pipe/ItemTransitData.java',
    'buildcraft/api/v2/pipe/ItemTransitPayload.java',
    'buildcraft/api/v2/item/ItemTransferResult.java',
    'buildcraft/transport/api2/PipeItemTransitRuntime.java',
    'buildcraft/transport/api2/PipeTransitHandoff.java',
)]
STUBS = {
    'net/minecraft/resources/ResourceLocation.java': '''package net.minecraft.resources;
public record ResourceLocation(String namespace, String path) {}''',
    'net/minecraft/core/Direction.java': '''package net.minecraft.core;
public enum Direction { UP, DOWN, NORTH, SOUTH, WEST, EAST }''',
    'net/minecraft/core/BlockPos.java': '''package net.minecraft.core;
public record BlockPos(int x,int y,int z) {}''',
    'net/minecraft/world/item/DyeColor.java': '''package net.minecraft.world.item;
public enum DyeColor { RED, BLUE }''',
    'net/minecraft/world/item/ItemStack.java': '''package net.minecraft.world.item;
public class ItemStack {
    public static final ItemStack EMPTY = new ItemStack("",0);
    private final String item;
    private int count;
    public ItemStack(String item, int count) { this.item=item; this.count=count; }
    public String getItem() { return item; }
    public boolean isEmpty() { return count<=0; }
    public int getCount() { return count; }
    public void setCount(int n) { count=n; }
    public void shrink(int n) { count=Math.max(0,count-n); }
    public ItemStack copy() { return new ItemStack(item,count); }
}''',
    'net/minecraft/world/level/block/entity/BlockEntity.java': '''package net.minecraft.world.level.block.entity;
public class BlockEntity {}''',
    'net/minecraft/server/MinecraftServer.java': '''package net.minecraft.server;
public class MinecraftServer { public boolean isSameThread() { return true; } }''',
    'net/minecraft/world/level/Level.java': '''package net.minecraft.world.level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
public class Level {
    public net.minecraft.server.MinecraftServer server;
    public boolean client,loaded=true;
    public int lookupCount;
    public BlockEntity tile;
    public Level(net.minecraft.server.MinecraftServer server) { this.server=server; }
    public boolean isClientSide() { return client; }
    public net.minecraft.server.MinecraftServer getServer() { return server; }
    public boolean hasChunkAt(BlockPos pos) { return loaded; }
    public BlockEntity getBlockEntity(BlockPos pos) { lookupCount++; return tile; }
}''',
    'buildcraft/api/v2/OperationMode.java': '''package buildcraft.api.v2;
public enum OperationMode { SIMULATE, EXECUTE }''',
    'buildcraft/api/v2/item/ItemPort.java': '''package buildcraft.api.v2.item;
import buildcraft.api.v2.OperationMode;
import net.minecraft.world.item.ItemStack;
public interface ItemPort { ItemTransferResult insert(ItemStack offered, OperationMode mode); }''',
    'buildcraft/api/v2/pipe/PipeView.java': '''package buildcraft.api.v2.pipe;
public interface PipeView {}''',
    'buildcraft/api/v2/pipe/PipeComponent.java': '''package buildcraft.api.v2.pipe;
import net.minecraft.resources.ResourceLocation;
public interface PipeComponent { ResourceLocation typeId(); }''',
    'buildcraft/lib/misc/StackUtil.java': '''package buildcraft.lib.misc;
import net.minecraft.world.item.ItemStack;
public class StackUtil {
    public static boolean canMerge(ItemStack a,ItemStack b) {
        return a.getItem().equals(b.getItem());
    }
}''',
    'buildcraft/lib/internal/debug/BCLog.java': '''package buildcraft.lib.internal.debug;
public class BCLog {
    public static final Log logger=new Log();
    public static class Log { public void warn(String msg,Object... args) {} }
}''',
    'buildcraft/transport/api2/ReadOnlyPipeView.java': '''package buildcraft.transport.api2;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.transport.pipe.Pipe;
public class ReadOnlyPipeView { public static PipeView of(Pipe pipe) { return pipe; } }''',
    'buildcraft/transport/pipe/flow/PipeFlowItems.java': '''package buildcraft.transport.pipe.flow;
import net.minecraft.core.Direction;
import net.minecraft.world.item.*;
import java.util.*;
public class PipeFlowItems {
    public int acceptedCap=64, persistedReceipts=0, insertions=0;
    public final Set<UUID> receipts=new HashSet<>();
    public ItemStack injectTransitStack(ItemStack stack, boolean add, Direction side,
                                         DyeColor color,double speed,UUID token) {
        if(token!=null && receipts.contains(token))return ItemStack.EMPTY;
        int accepted=Math.min(stack.getCount(),acceptedCap);
        if(add && accepted>0) { insertions++; if(token!=null)receipts.add(token); }
        ItemStack remainder=stack.copy();remainder.shrink(accepted);return remainder;
    }
}''',
    'buildcraft/transport/pipe/Pipe.java': '''package buildcraft.transport.pipe;
import java.util.*;
import buildcraft.api.v2.pipe.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
public class Pipe implements PipeView {
    public static final Pipe EMPTY=new Pipe(null);
    public Object flow;
    public final Holder holder;
    public final List<PipeComponent> registered=new ArrayList<>();
    public int dirtied;
    public Pipe(Level level) { this.holder=new Holder(level); }
    public List<PipeComponent> components() { return registered; }
    public Holder getHolder() { return holder; }
    public BlockPos position() { return new BlockPos(0,0,0); }
    public boolean isConnected(Direction side) { return side!=Direction.UP; }
    public void markChanged() { dirtied++; }
    public record Holder(Level world) { public Level getPipeWorld() { return world; } }
}''',
    'buildcraft/transport/tile/TilePipeHolder.java': '''package buildcraft.transport.tile;
import buildcraft.transport.pipe.Pipe;
import net.minecraft.world.level.block.entity.BlockEntity;
public class TilePipeHolder extends BlockEntity {
    private final Pipe pipe;
    public TilePipeHolder(Pipe pipe) { this.pipe=pipe; }
    public Pipe getPipe() { return pipe; }
}''',
}
FIXTURE = r'''package test;
import buildcraft.api.v2.*;
import buildcraft.api.v2.item.*;
import buildcraft.api.v2.pipe.*;
import buildcraft.transport.api2.PipeItemTransitRuntime;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.tile.TilePipeHolder;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;

public class Fixture {
    static void check(boolean test, String why) { if (!test) throw new AssertionError(why); }
    static final ItemTransitData DATA=new ItemTransitData(Optional.of(DyeColor.RED),0.06);
    static PipeItemTransitRuntime.Result invoke(Pipe p,ItemTransitPhase phase,int count,UUID token) {
        return PipeItemTransitRuntime.fire(p,phase,new ItemStack("iron",count),DATA,Direction.WEST,
            Direction.EAST,token);
    }
    static class Listener implements ItemTransitComponent {
        public ResourceLocation typeId(){return new ResourceLocation("addon","transit");}
        String action="";
        ItemTransitContext saved;
        Level target;
        ItemTransitTransfer observed;
        boolean throwAfter;
        public void onTransit(ItemTransitContext ctx){
            if(ctx.phase()!=ItemTransitPhase.CENTER) return;
            saved=ctx;
            switch(action){
                case "replace" -> check(ctx.replace(new ItemStack("gold",ctx.stack().getCount()),DATA),"replace");
                case "redirect" -> check(ctx.redirect(Direction.SOUTH),"redirect");
                case "delay" -> check(ctx.delay(8),"delay");
                case "reject" -> check(ctx.reject(),"reject");
                case "eject" -> check(ctx.eject(Direction.UP),"eject");
                case "claim" -> observed=ctx.claim((stack,mode) ->
                    ItemTransferResult.ofInsertion(stack, Math.min(2,stack.getCount())), OperationMode.EXECUTE);
                case "simulate-claim" -> observed=ctx.claim((stack,mode) ->
                    ItemTransferResult.ofInsertion(stack,1), OperationMode.SIMULATE);
                case "teleport" -> observed=ctx.transfer(target,new BlockPos(10,0,0),Direction.WEST,OperationMode.EXECUTE);
                case "simulate-teleport" -> observed=ctx.transfer(target,new BlockPos(10,0,0),Direction.WEST,OperationMode.SIMULATE);
                case "wrong-type" -> observed=ctx.claim((stack,mode) ->
                    ItemTransferResult.ofExtraction(stack.getCount(),new ItemStack("diamond",1)),OperationMode.EXECUTE);
                default -> { }
            }
            if(throwAfter) throw new IllegalStateException("addon exploded after transfer");
        }
    }
    public static void main(String[] args) {
        net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer();
        Level a=new Level(server), b=new Level(server);
        Pipe source=new Pipe(a), dest=new Pipe(b);
        PipeFlowItems receiver=new PipeFlowItems(); dest.flow=receiver;
        b.tile=new TilePipeHolder(dest);
        Listener listener=new Listener(); source.registered.add(listener); listener.target=b;

        listener.action="replace";
        var r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().getItem().equals("gold") && r.stack().getCount()==5,"replacement preserves amount");
        listener.action="redirect";
        check(invoke(source,ItemTransitPhase.CENTER,5,null).redirected()==Direction.SOUTH,"redirect");
        listener.action="delay";
        check(invoke(source,ItemTransitPhase.CENTER,5,null).delay()==8,"delay");
        listener.action="reject";
        check(invoke(source,ItemTransitPhase.CENTER,5,null).delay()==20,"reject");
        listener.action="eject";
        check(invoke(source,ItemTransitPhase.CENTER,5,null).eject()==Direction.UP,"eject");
        listener.action="simulate-claim";
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().getCount()==5 && listener.observed.accepted()==1 && source.dirtied==0,"simulate claim");
        listener.action="claim";
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().getCount()==3 && listener.observed.accepted()==2 && r.delay()==1,"claim remainder");
        listener.action="wrong-type";
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().getCount()==5 && listener.observed.status()==ItemTransitStatus.FAILED,"invalid claim result");

        listener.action="simulate-teleport";
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().getCount()==5 && receiver.insertions==0,"simulate teleport");
        listener.action="teleport";
        b.loaded=false;
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(listener.observed.status()==ItemTransitStatus.UNLOADED && r.stack().getCount()==5
            && r.delay()==20 && b.lookupCount==1 && receiver.insertions==0,"unloaded retry no lookup");
        b.loaded=true;
        receiver.acceptedCap=2;
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(listener.observed.status()==ItemTransitStatus.PARTIAL && r.stack().getCount()==3 &&
            r.delay()==1 && receiver.insertions==1 && r.id()!=null,"partial transfer");
        UUID delivered=receiver.receipts.iterator().next();
        receiver.acceptedCap=64;
        r=invoke(source,ItemTransitPhase.CENTER,5,delivered);
        check(r.stack().isEmpty() && receiver.insertions==1,"idempotent retry");
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().isEmpty() && receiver.insertions==2,"full transfer");
        listener.throwAfter=true;
        r=invoke(source,ItemTransitPhase.CENTER,5,null);
        check(r.stack().isEmpty() && receiver.insertions==3,"callback error cannot resurrect item");
        listener.throwAfter=false;
        boolean expired=false;
        try {listener.saved.stack();} catch(IllegalStateException ex){expired=true;}
        check(expired,"callback must not retain mutable handle");
        r=invoke(source,ItemTransitPhase.ENTER,5,null);
        check(r.stack().getCount()==5,"readonly phase");
        check(source.dirtied>=5,"source dirty after transfers");
        System.out.println("Item transit ownership checks passed");
    }
}
'''

class ItemTransitTest(unittest.TestCase):
    def test_production_dispatcher(self) -> None:
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('JDK unavailable')
        with tempfile.TemporaryDirectory() as tmp:
            temp = Path(tmp)
            java_sources = [str(ROOT / source) for source in SOURCES]
            for relative, content in STUBS.items():
                dest = temp / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_text(content, encoding='utf-8')
                java_sources.append(str(dest))
            fixture = temp / 'test/Fixture.java'
            fixture.parent.mkdir(parents=True, exist_ok=True)
            fixture.write_text(FIXTURE, encoding='utf-8')
            java_sources.append(str(fixture))
            classes = temp / 'classes'
            classes.mkdir()
            compile_result = subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *java_sources],
                capture_output=True, text=True, timeout=80)
            self.assertEqual(0, compile_result.returncode, compile_result.stdout + compile_result.stderr)
            test_result = subprocess.run(['java', '-cp', str(classes), 'test.Fixture'],
                capture_output=True, text=True, timeout=30)
            self.assertEqual(0, test_result.returncode, test_result.stdout + test_result.stderr)

    def test_materialized_hooks(self) -> None:
        from sys import path as sys_path
        sys_path.insert(0, str(ROOT / 'scripts'))
        from source_layout import load_properties, target_ids, target_layout, resolve_effective_source
        props = load_properties()
        for target in target_ids(props):
            src = resolve_effective_source(target_layout(target, props), props,
                Path('src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java'))
            self.assertIsNotNone(src, target)
            text = src.read_text(encoding='utf-8')
            for fragment in ('ItemTransitPhase.CENTER','ItemTransitPhase.ROUTING','ItemTransitPhase.EXIT',
                             'injectTransitStack(', 'apiTransferReceipts', 'finishControlledEvent('):
                self.assertIn(fragment,text,f'{target}: {fragment}')


    def test_transient_pipe_handoff_retries_once_without_reversing(self) -> None:
        from sys import path as sys_path
        sys_path.insert(0, str(ROOT / 'scripts'))
        from source_layout import load_properties, target_ids, target_layout, resolve_effective_source
        props = load_properties()
        for target in target_ids(props):
            with self.subTest(target=target):
                path = resolve_effective_source(target_layout(target, props), props,
                    Path('src/main/java/buildcraft/transport/pipe/flow/PipeFlowItems.java'))
                self.assertIsNotNone(path)
                source = path.read_text(encoding='utf-8')
                retry = source.index('if (pipe.isConnected(item.side) && pipe.getConnectedType(item.side) == ConnectedType.PIPE')
                fallback = source.index('item.toCenter = true;', retry)
                self.assertLess(retry, fallback)
                block = source[retry:fallback]
                self.assertIn('!item.tried.contains(item.side)', block)
                self.assertIn('item.tried.add(item.side);', block)
                self.assertIn('item.tickFinished = now + 1;', block)
                self.assertIn('scheduleTravellingItem(item);', block)
                self.assertIn('holder.getPipeTile().setChanged();', block)
                self.assertNotIn('sendItemDataToClient(item);', block)
                self.assertIn('return;', block)
                self.assertIn('item.tried.add(item.side);', source[fallback - 120:fallback])


if __name__ == '__main__':
    unittest.main()
