#!/usr/bin/env python3
"""26.3 vanilla cushion repainting: target isolation and runtime interaction contracts."""
from __future__ import annotations

from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from source_config import load_properties, target_layout
from source_layout import _materialize_text_file, resolve_effective_source

TARGETS = (
    "1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge",
    "1.21.11-neoforge", "26.1.2-neoforge", "26.2-neoforge", "26.3-neoforge",
)
REL = "src/main/java/buildcraft/core/item/CushionPaintEvents.java"


class CushionPaint263Test(unittest.TestCase):
    def test_native_only_target_and_synced_color_contract(self):
        props = load_properties()
        for target in TARGETS:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                source = resolve_effective_source(layout, props, REL)
                if target != "26.3-neoforge":
                    self.assertIsNone(source)
                    continue
                self.assertIsNotNone(source)
                with tempfile.TemporaryDirectory() as td:
                    output = Path(td) / "CushionPaintEvents.java"
                    _materialize_text_file(
                        source, output, logical_relative=REL, minecraft="26.3",
                        family=layout.family, platform=layout.platform, preprocess=True,
                    )
                    code = output.read_text(encoding="utf-8")
                    self.assertIn("@EventBusSubscriber(modid = BCCore.MODID)", code)
                    self.assertIn("PlayerInteractEvent.EntityInteract event", code)
                    self.assertIn("event.getTarget() instanceof Cushion cushion", code)
                    self.assertIn("cushion.setColor(paint)", code)
                    self.assertIn("level.isClientSide() || cushion.getColor() == paint", code)
                    self.assertIn("event.setCanceled(true)", code)
                    self.assertIn("!level.mayInteract(player, cushion.blockPosition())", code)
                    self.assertNotIn("cushion.discard()", code)
                    self.assertNotIn("new Cushion(", code)
                    self.assertNotIn("getSlotForHand", code)
                    self.assertIn("EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND", code)

    def test_brush_durability_slot_on_all_modern_targets(self):
        props = load_properties()
        logical = "src/main/java/buildcraft/core/item/ItemPaintbrush_BC8.java"
        for target in TARGETS[2:]:
            with self.subTest(target=target):
                layout = target_layout(target, props)
                source = resolve_effective_source(layout, props, logical)
                self.assertIsNotNone(source)
                with tempfile.TemporaryDirectory() as td:
                    out = Path(td) / "ItemPaintbrush_BC8.java"
                    _materialize_text_file(
                        source, out, logical_relative=logical,
                        minecraft=target.split("-")[0], family=layout.family,
                        platform=layout.platform, preprocess=True,
                    )
                    code = out.read_text(encoding="utf-8")
                    self.assertNotIn("getSlotForHand", code)
                    self.assertIn("EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND", code)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_compiles_and_runs_interaction_logic_against_minimal_api_stubs(self):
        props = load_properties()
        layout = target_layout("26.3-neoforge", props)
        source = resolve_effective_source(layout, props, REL)
        self.assertIsNotNone(source)

        stubs = {
            "buildcraft/core/BCCore.java": '''package buildcraft.core;
                public class BCCore { public static final String MODID="buildcraftcore"; }''',
            "buildcraft/core/BCCoreItems.java": '''package buildcraft.core;
                import net.minecraft.world.item.ItemStack;
                public class BCCoreItems {
                    public static final RegistryEntry PAINT_BRUSH = new RegistryEntry();
                    public static class RegistryEntry { public Object get() {return new Object();} }
                }''',
            "buildcraft/lib/misc/ItemStackUtil.java": '''package buildcraft.lib.misc;
                import net.minecraft.nbt.CompoundTag; import net.minecraft.world.item.ItemStack;
                public class ItemStackUtil {
                    public static CompoundTag getCustomDataOrNull(ItemStack s) {return s.tag;}
                    public static void setCustomData(ItemStack s, CompoundTag tag) {s.tag=tag;}
                }''',
            "buildcraft/lib/misc/SoundUtil.java": '''package buildcraft.lib.misc;
                import net.minecraft.world.item.DyeColor; import net.minecraft.world.level.Level;
                public class SoundUtil {
                    public static int played;
                    public static void playChangeColour(Level level, Object pos, DyeColor color) {played++;}
                }''',
            "net/minecraft/nbt/CompoundTag.java": 'package net.minecraft.nbt; public class CompoundTag {}',
            "net/minecraft/world/InteractionResult.java": '''package net.minecraft.world;
                public enum InteractionResult { SUCCESS, SUCCESS_SERVER, PASS }''',
            "net/minecraft/world/InteractionHand.java": '''package net.minecraft.world;
                public enum InteractionHand { MAIN_HAND, OFF_HAND }''',
            "net/minecraft/world/entity/EquipmentSlot.java": '''package net.minecraft.world.entity;
                public enum EquipmentSlot { MAINHAND, OFFHAND }''',
            "net/minecraft/world/entity/decoration/Cushion.java": '''package net.minecraft.world.entity.decoration;
                import net.minecraft.world.item.DyeColor;
                public class Cushion {
                    public DyeColor color = DyeColor.BLUE;
                    public int colorUpdates;
                    public DyeColor getColor() {return color;}
                    public void setColor(DyeColor color) {this.color=color; colorUpdates++;}
                    public Object blockPosition() {return new Object();}
                }''',
            "net/minecraft/world/entity/player/Player.java": '''package net.minecraft.world.entity.player;
                import net.minecraft.world.item.ItemStack;
                public class Player {
                    public boolean canBuild=true;
                    public ItemStack held;
                    public Menu inventoryMenu=new Menu();
                    public boolean mayBuild() {return canBuild;}
                    public void setItemInHand(Object hand, ItemStack stack) {held=stack;}
                    public static class Menu {public int broadcasts; public void broadcastChanges(){broadcasts++;}}
                }''',
            "net/minecraft/world/item/DyeColor.java": '''package net.minecraft.world.item;
                public enum DyeColor { RED, BLUE }''',
            "net/minecraft/world/item/ItemStack.java": '''package net.minecraft.world.item;
                import net.minecraft.nbt.CompoundTag;
                import net.minecraft.world.entity.player.Player;
                public class ItemStack {
                    public Object item; public int damage, maxDurability=64; public CompoundTag tag;
                    public net.minecraft.world.entity.EquipmentSlot lastSlot;
                    public ItemStack(Object item){this.item=item;}
                    public Object getItem(){return item;}
                    public void hurtAndBreak(int amount, Player player, net.minecraft.world.entity.EquipmentSlot slot){damage+=amount;lastSlot=slot;}
                    public boolean isEmpty(){return damage>=maxDurability;}
                }''',
            "net/minecraft/world/level/Level.java": '''package net.minecraft.world.level;
                import net.minecraft.world.entity.player.Player;
                public class Level {
                    public boolean client; public boolean accessible=true;
                    public boolean isClientSide(){return client;}
                    public boolean mayInteract(Player player,Object pos){return accessible;}
                }''',
            "net/neoforged/bus/api/SubscribeEvent.java": '''package net.neoforged.bus.api;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
                public @interface SubscribeEvent {}''',
            "net/neoforged/fml/common/EventBusSubscriber.java": '''package net.neoforged.fml.common;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                public @interface EventBusSubscriber { String modid(); }''',
            "net/neoforged/neoforge/event/entity/player/PlayerInteractEvent.java": '''package net.neoforged.neoforge.event.entity.player;
                import net.minecraft.world.InteractionResult;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.Level;
                public class PlayerInteractEvent {
                    public static class EntityInteract {
                        public Object target; public ItemStack stack; public Player player; public Level level;
                        public boolean canceled; public InteractionResult result;
                        public net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
                        public EntityInteract(Object target, ItemStack stack, Player player, Level level) {
                            this.target=target; this.stack=stack; this.player=player; this.level=level;
                        }
                        public Object getTarget(){return target;}
                        public ItemStack getItemStack(){return stack;}
                        public Player getEntity(){return player;}
                        public Level getLevel(){return level;}
                        public net.minecraft.world.InteractionHand getHand(){return hand;}
                        public void setCancellationResult(InteractionResult value){result=value;}
                        public void setCanceled(boolean cancel){canceled=cancel;}
                    }
                }''',
            "buildcraft/core/item/ItemPaintbrush_BC8.java": '''package buildcraft.core.item;
                import net.minecraft.world.item.DyeColor;
                public class ItemPaintbrush_BC8 {
                    private final DyeColor color;
                    public ItemPaintbrush_BC8(DyeColor color){this.color=color;}
                    public DyeColor getType(){return color;}
                }''',
            "buildcraft/core/item/CushionPaintFixture.java": '''package buildcraft.core.item;
                import buildcraft.lib.misc.SoundUtil;
                import net.minecraft.world.InteractionResult;
                import net.minecraft.world.InteractionHand;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.entity.decoration.Cushion;
                import net.minecraft.world.entity.player.Player;
                import net.minecraft.world.item.DyeColor;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.Level;
                import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
                public class CushionPaintFixture {
                    static void yes(boolean b, String reason){if (!b) throw new AssertionError(reason);}
                    static PlayerInteractEvent.EntityInteract event(Object target, DyeColor paint, Level level, Player player){
                        return new PlayerInteractEvent.EntityInteract(target,new ItemStack(new ItemPaintbrush_BC8(paint)),player,level);
                    }
                    public static void main(String[] args) {
                        var cushion=new Cushion(); var level=new Level(); var player=new Player();
                        var client=event(cushion,DyeColor.RED,level,player); level.client=true;
                        CushionPaintEvents.onInteract(client);
                        yes(client.canceled && client.result==InteractionResult.SUCCESS,"client consumes");
                        yes(cushion.getColor()==DyeColor.BLUE && client.stack.damage==0,"client no mutation");
                        level.client=false;
                        var server=event(cushion,DyeColor.RED,level,player);
                        CushionPaintEvents.onInteract(server);
                        yes(server.canceled && server.result==InteractionResult.SUCCESS_SERVER,"server consumes");
                        yes(cushion.getColor()==DyeColor.RED && cushion.colorUpdates==1,"recolors existing cushion");
                        yes(server.stack.damage==1 && SoundUtil.played==1,"cost exactly once");
                        yes(server.stack.lastSlot==EquipmentSlot.MAINHAND,"mainhand maps to equipment slot");
                        var same=event(cushion,DyeColor.RED,level,player);
                        CushionPaintEvents.onInteract(same);
                        yes(same.canceled && same.stack.damage==0 && SoundUtil.played==1,"same-color noop");
                        var clean=event(cushion,null,level,player);
                        CushionPaintEvents.onInteract(clean);
                        yes(!clean.canceled && clean.stack.damage==0,"clean brush ignored");
                        var other=event(new Object(),DyeColor.RED,level,player);
                        CushionPaintEvents.onInteract(other);
                        yes(!other.canceled,"unrelated entities untouched");
                        level.accessible=false;
                        var denied=event(cushion,DyeColor.BLUE,level,player);
                        CushionPaintEvents.onInteract(denied);
                        yes(!denied.canceled && cushion.getColor()==DyeColor.RED,"world protection respected");
                        level.accessible=true; player.canBuild=false;
                        var denied2=event(cushion,DyeColor.BLUE,level,player);
                        CushionPaintEvents.onInteract(denied2);
                        yes(!denied2.canceled && cushion.getColor()==DyeColor.RED,"player protection respected");
                        player.canBuild=true;
                        var last=event(cushion,DyeColor.BLUE,level,player);
                        last.stack.damage=63; last.stack.tag=new net.minecraft.nbt.CompoundTag();
                        CushionPaintEvents.onInteract(last);
                        yes(last.stack.isEmpty() && player.held!=null && player.held.tag==last.stack.tag,"clean brush replaces broken one, preserving tag");
                        yes(cushion.getColor()==DyeColor.BLUE && cushion.colorUpdates==2,"last use recolors");
                        var offhand=event(cushion,DyeColor.RED,level,player);
                        offhand.hand=InteractionHand.OFF_HAND;
                        CushionPaintEvents.onInteract(offhand);
                        yes(offhand.stack.damage==1 && offhand.stack.lastSlot==EquipmentSlot.OFFHAND,
                            "offhand maps to equipment slot without removed helper");
                        System.out.println("10 cushion brush scenarios passed");
                    }
                }''',
        }
        with tempfile.TemporaryDirectory(prefix="bc-cushion-java-") as td:
            root = Path(td)
            for rel, text in stubs.items():
                p = root / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text, encoding="utf-8")
            p = root / REL.removeprefix("src/main/java/")
            p.parent.mkdir(parents=True, exist_ok=True)
            _materialize_text_file(
                source, p, logical_relative=REL, minecraft="26.3",
                family=layout.family, platform=layout.platform, preprocess=True,
            )
            files = [str(path.relative_to(root)) for path in root.rglob("*.java")]
            comp = subprocess.run(
                ["javac", "-encoding", "UTF-8", *files], cwd=root,
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(comp.returncode, 0, comp.stderr)
            run = subprocess.run(
                ["java", "buildcraft.core.item.CushionPaintFixture"], cwd=root,
                capture_output=True, text=True, timeout=30,
            )
            self.assertEqual(run.returncode, 0, run.stderr)
            self.assertIn("10 cushion brush scenarios passed", run.stdout)


if __name__ == "__main__":
    unittest.main()
