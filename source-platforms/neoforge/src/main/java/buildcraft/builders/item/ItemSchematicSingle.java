/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.SchematicBlockDefault;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.lib.inventory.InventoryWrapper;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.SoundUtil;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.5 {
import net.minecraft.world.item.component.TooltipDisplay;
//?}
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public class ItemSchematicSingle extends Item {
    public static final int DAMAGE_CLEAN = 0;
    public static final int DAMAGE_USED = 1;
    public static final String NBT_KEY = "schematic";

    public ItemSchematicSingle(Item.Properties prop) {
        super(prop);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = StackUtil.asNonNull(player.getItemInHand(hand));
        if (world.isClientSide) {
            return new InteractionResultHolder<>(InteractionResult.PASS, stack);
        }
        if (player.isCrouching()) {
            clear(stack);
            return new InteractionResultHolder<>(InteractionResult.SUCCESS, stack);
        }
        return new InteractionResultHolder<>(InteractionResult.PASS, stack);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level world = context.getLevel();
        Player player = context.getPlayer();
        if (world.isClientSide) {
            return InteractionResult.PASS;
        }
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (player.isCrouching()) {
            clear(stack);
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        BlockState state = world.getBlockState(pos);
        if (!isUsed(stack)) {
            ISchematicBlock schematicBlock = SchematicBlockManager.getSchematicBlock(new SchematicBlockContext(
                world,
                pos,
                pos,
                state,
                state.getBlock()
            ));
            if (schematicBlock.isAir()) {
                return InteractionResult.FAIL;
            }
            return recordSingleSchematic(stack, player, schematicBlock);
        }

        Direction side = context.getClickedFace();
        BlockPos placePos = pos;
        boolean replaceable = state.canBeReplaced(new BlockPlaceContext(context));
        if (!replaceable) {
            placePos = placePos.relative(side);
        }
        if (!world.getWorldBorder().isWithinBounds(placePos)) {
            return InteractionResult.FAIL;
        }

        try {
            ISchematicBlock schematicBlock = getSchematic(stack);
            if (schematicBlock != null) {
                // A replaceable target must remain untouched until every resource check has passed. The actor-aware
                // build path below replaces it atomically and loader place hooks can roll the snapshot back on cancel.
                boolean canAttemptBuild = schematicBlock.canBuild(world, placePos);
                if (!canAttemptBuild && replaceable && !world.isEmptyBlock(placePos)
                    && schematicBlock instanceof SchematicBlockDefault) {
                    // Default schematics reject any occupied target before checking survival. For an explicitly
                    // replaceable clicked block, defer that final survival check to build(..., player). Do not bypass
                    // addon/API2 canPlace() decisions, which may encode additional placement policy.
                    canAttemptBuild = true;
                }
                if (!schematicBlock.isBuilt(world, placePos) && canAttemptBuild) {
                    // Only the block itself is mandatory. Saved inventory contents are restored after
                    // placement with as many matching items as the player currently has available.
                    List<ItemStack> placementItems = schematicBlock.computeRequiredItemsForPlacement(world);
                    List<FluidStack> requiredFluids = new ArrayList<>();
                    placementItems.stream()
                        .map(FluidUtil::getFluidHandler)
                        .flatMap(java.util.Optional::stream)
                        .map(fluidHandler -> fluidHandler.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE))
                        .filter(fluidStack -> !fluidStack.isEmpty())
                        .forEach(requiredFluids::add);
                    requiredFluids.addAll(schematicBlock.computeRequiredFluids(world));

                    if (requiredFluids.isEmpty()) {
                        List<ItemStack> mergedPlacementItems = StackUtil.mergeSameItems(placementItems);
                        InventoryWrapper itemTransactor = new InventoryWrapper(player.getInventory());
                        boolean hasPlacementItems = player.isCreative() || mergedPlacementItems.stream().noneMatch(s ->
                            itemTransactor.extract(
                                extracted -> StackUtil.canMerge(s, extracted),
                                s.getCount(),
                                s.getCount(),
                                true
                            ).isEmpty()
                        );
                        if (hasPlacementItems) {
                            if (schematicBlock.build(world, placePos, player)) {
                                if (!player.isCreative()) {
                                    mergedPlacementItems.forEach(s ->
                                        itemTransactor.extract(
                                            extracted -> StackUtil.canMerge(s, extracted),
                                            s.getCount(),
                                            s.getCount(),
                                            false
                                        )
                                    );
                                }
                                fillDeferredInventory(schematicBlock, world, placePos, itemTransactor, player);
                                SoundUtil.playBlockPlace(world, placePos);
                                player.swing(context.getHand());
                                return InteractionResult.SUCCESS;
                            }
                        } else {
                            player.displayClientMessage(
                                Component.translatable(
                                    "item.buildcraftbuilders.schematic_single.not_enough_items",
                                    formatItemList(mergedPlacementItems)
                                ),
                                true
                            );
                        }
                    } else {
                        player.displayClientMessage(
                            Component.translatable("item.buildcraftbuilders.schematic_single.requires_fluids"),
                            true
                        );
                    }
                }
            }
        } catch (InvalidInputDataException e) {
            player.displayClientMessage(
                Component.translatable("item.buildcraftbuilders.schematic_single.invalid_with_reason", e.getMessage()),
                true
            );
            BCLog.logger.warn("Invalid single block schematic", e);
        }
        return InteractionResult.FAIL;
    }

    private static void fillDeferredInventory(
        ISchematicBlock schematicBlock,
        Level world,
        BlockPos blockPos,
        InventoryWrapper playerInventory,
        Player player
    ) {
        boolean creative = player.isCreative();
        List<ItemStack> missingItems = schematicBlock.computeMissingDeferredRequiredItems(world, blockPos);
        for (ItemStack missing : missingItems) {
            ItemStack stillNeeded = missing.copy();
            while (!stillNeeded.isEmpty()) {
                ItemStack wanted = stillNeeded.copy();
                wanted.setCount(Math.min(wanted.getCount(), wanted.getMaxStackSize()));

                ItemStack simulatedRemainder = schematicBlock.insertDeferredItem(world, blockPos, wanted, true);
                int insertCapacity = wanted.getCount() - simulatedRemainder.getCount();
                if (insertCapacity <= 0) {
                    break;
                }

                ItemStack supplied;
                if (creative) {
                    supplied = wanted.copy();
                    supplied.setCount(insertCapacity);
                } else {
                    supplied = playerInventory.extract(
                        extracted -> StackUtil.canMerge(wanted, extracted),
                        1,
                        insertCapacity,
                        false
                    );
                    if (supplied.isEmpty()) {
                        break;
                    }
                }

                ItemStack overflow = schematicBlock.insertDeferredItem(world, blockPos, supplied, false);
                int inserted = supplied.getCount() - overflow.getCount();
                if (!overflow.isEmpty() && !creative) {
                    ItemStack refundRemainder = playerInventory.insert(overflow, false, false);
                    if (!refundRemainder.isEmpty()) {
                        // A nonstandard handler may accept less than it advertised during simulation. If the
                        // player's inventory filled in the meantime, drop the exact remainder instead of deleting it.
                        player.drop(refundRemainder, false);
                    }
                }
                if (inserted <= 0) {
                    break;
                }
                stillNeeded.shrink(inserted);
            }
        }
    }

    private static InteractionResult recordSingleSchematic(ItemStack stack, Player player, ISchematicBlock schematicBlock) {
        if (stack.getCount() <= 1) {
            writeSchematic(stack, schematicBlock);
            return InteractionResult.SUCCESS;
        }

        ItemStack recorded = stack.copy();
        recorded.setCount(1);
        writeSchematic(recorded, schematicBlock);
        stack.shrink(1);

        if (!player.getInventory().add(recorded)) {
            player.drop(recorded, false);
        }
        return InteractionResult.SUCCESS;
    }

    private static void writeSchematic(ItemStack stack, ISchematicBlock schematicBlock) {
        CompoundTag itemData = ItemStackUtil.getCustomData(stack);
        itemData.put(NBT_KEY, SchematicBlockManager.writeToNBT(schematicBlock));
        ItemStackUtil.setCustomData(stack, itemData);
        stack.setDamageValue(DAMAGE_USED);
        stack.set(DataComponents.MAX_STACK_SIZE, 1);
        stack.setCount(1);
    }

    @Override
    //? if >=1.21.5 {
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
    //?} else {
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipLines, TooltipFlag flag) {
        Consumer<Component> tooltip = tooltipLines::add;
    //?}
        Level world = context.level();
        if (!isUsed(stack)) {
            tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.blank").withStyle(ChatFormatting.GRAY));
            tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.blank_hint").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        ISchematicBlock schematic = getSchematicSafe(stack);
        if (schematic == null) {
            tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.invalid").withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.used").withStyle(ChatFormatting.GRAY));
        if (world != null) {
            try {
                List<ItemStack> items = StackUtil.mergeSameItems(schematic.computeRequiredItems(world));
                if (!items.isEmpty()) {
                    tooltip.accept(Component.translatable(
                        "item.buildcraftbuilders.schematic_single.contains",
                        formatItemList(items)
                    ).withStyle(ChatFormatting.DARK_GRAY));
                }
            } catch (RuntimeException ignored) {
                tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.invalid").withStyle(ChatFormatting.RED));
            }
        }
        tooltip.accept(Component.translatable("item.buildcraftbuilders.schematic_single.clear_hint").withStyle(ChatFormatting.DARK_GRAY));
    }

    public static boolean isUsed(@Nonnull ItemStack stack) {
        return stack.getItem() instanceof ItemSchematicSingle
            && (stack.getDamageValue() == DAMAGE_USED || hasSchematicData(stack));
    }

    public static boolean isValidUsed(@Nonnull ItemStack stack) {
        return isUsed(stack) && getSchematicSilently(stack) != null;
    }

    private static boolean hasSchematicData(@Nonnull ItemStack stack) {
        CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
        return tag != null && tag.contains(NBT_KEY, Tag.TAG_COMPOUND);
    }

    public static void clear(@Nonnull ItemStack stack) {
        CompoundTag itemData = ItemStackUtil.getCustomDataOrNull(stack);
        if (itemData != null) {
            itemData.remove(NBT_KEY);
            ItemStackUtil.setCustomData(stack, itemData);
        }
        stack.setDamageValue(DAMAGE_CLEAN);
        stack.set(DataComponents.MAX_STACK_SIZE, 16);
    }

    public static ISchematicBlock getSchematic(@Nonnull ItemStack stack) throws InvalidInputDataException {
        if (stack.getItem() instanceof ItemSchematicSingle) {
            CompoundTag tag = ItemStackUtil.getCustomDataOrNull(stack);
            if (tag == null || !tag.contains(NBT_KEY, Tag.TAG_COMPOUND)) {
                return null;
            }
            return SchematicBlockManager.readFromNBT(tag.getCompound(NBT_KEY));
        }
        return null;
    }

    public static ISchematicBlock getSchematicSafe(@Nonnull ItemStack stack) {
        return getSchematicSilently(stack);
    }

    private static ISchematicBlock getSchematicSilently(@Nonnull ItemStack stack) {
        try {
            return getSchematic(stack);
        } catch (InvalidInputDataException e) {
            return null;
        }
    }

    private static String formatItemList(List<ItemStack> items) {
        return items.stream()
            .filter(item -> !item.isEmpty())
            .map(item -> item.getHoverName().getString() + " x " + item.getCount())
            .collect(Collectors.joining(", "));
    }
}
