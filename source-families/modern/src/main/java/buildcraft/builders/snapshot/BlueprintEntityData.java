//? source if >=1.21.11
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.snapshot;

import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.storage.TagValueOutput;

/** Native entity serialization with the stable, material-accounted BCCE equipment schema. */
final class BlueprintEntityData {
    private static final EquipmentSlot[] HANDS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    private BlueprintEntityData() {}

    static CompoundTag save(Entity entity) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, entity.registryAccess());
        if (!entity.save(output)) return new CompoundTag();
        CompoundTag nbt = output.buildResult();
        if (entity instanceof ArmorStand stand) {
            // 1.21.11 moved gear into "equipment". Keep the existing six-slot blueprint
            // rule paths so ALL copied gear is charged exactly once, including legacy blueprints.
            nbt.remove("equipment");
            nbt.put("HandItems", equipment(stand, HANDS));
            nbt.put("ArmorItems", equipment(stand, ARMOR));
        }
        return nbt;
    }

    private static ListTag equipment(ArmorStand stand, EquipmentSlot[] slots) {
        ListTag result = new ListTag();
        for (EquipmentSlot slot : slots) result.add(ItemStackUtil.saveOptional(stand.getItemBySlot(slot), stand.registryAccess()));
        return result;
    }

    static void prepareLoad(CompoundTag nbt) {
        if ("minecraft:armor_stand".equals(NbtCompat.getString(nbt, "id"))) {
            // Never load a second, unaccounted copy of armor from a different serialization path.
            nbt.remove("equipment");
        }
    }

    static void restoreEquipment(Entity entity, CompoundTag nbt) {
        if (entity instanceof ArmorStand stand) {
            restore(stand, nbt, "HandItems", HANDS);
            restore(stand, nbt, "ArmorItems", ARMOR);
        }
    }

    private static void restore(ArmorStand stand, CompoundTag nbt, String key, EquipmentSlot[] slots) {
        ListTag items = NbtCompat.getList(nbt, key);
        for (int i = 0; i < slots.length; i++) {
            CompoundTag item = i < items.size() ? NbtCompat.getCompound(items, i) : new CompoundTag();
            stand.setItemSlot(slots[i], ItemStackUtil.parseOptional(stand.registryAccess(), item));
        }
    }
}
