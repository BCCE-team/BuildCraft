/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.lib.recipe.RecipeBlacklist;
import buildcraft.lib.recipe.ProgrammingRecipeAccess;
import buildcraft.api.v2.recipe.ProgrammingRecipeDefinition;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;

import net.minecraft.core.HolderLookup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.StackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.robotics.item.ItemRedstoneBoard;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.container.ContainerProgrammingTable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileProgrammingTable_Neptune extends TileLaserTableBase implements MenuProvider {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("programming_table");
    public static final int NET_SELECTED_OPTION = IDS.allocId("SELECTED_OPTION");

    public static final int WIDTH = 6;
    public static final int HEIGHT = 4;
    public static final int OPTION_COUNT = WIDTH * HEIGHT;

    public final ItemHandlerSimple invInput = itemManager.addInvHandler(
            "input",
            1,
            (slot, stack) -> stack.isEmpty() || stack.is(BCRoboticsItems.REDSTONE_BOARD.get()),
            ItemHandlerManager.EnumAccess.INSERT,
            EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invOutput = itemManager.addInvHandler(
            "output",
            1,
            (slot, stack) -> stack.isEmpty(),
            ItemHandlerManager.EnumAccess.EXTRACT,
            EnumPipePart.VALUES
    );

    public final ItemProvider optionDisplay = new ItemProvider(this::getOptionStack, OPTION_COUNT);
    private List<BoardEntry> options = List.of();
    /** The same board may occur multiple times at different processing costs. */
    private List<ProgrammingRecipeDefinition> optionDefinitions = List.of();
    private List<ProgrammingRecipeDefinition> cachedRecipeCatalog = List.of();
    private long lastBlacklistGeneration = -1;
    private long lastRecipeGeneration = -1;
    /** Server-authoritative GUI snapshot: a list, not a board-key-to-cost map. */
    private List<ProgrammingRecipeDefinition> syncedProgrammingOptions;
    public int selectedOption = -1;
    public int selectedPage;

    public TileProgrammingTable_Neptune(BlockPos pos, BlockState state) {
        super(BCSiliconBlocks.PROGRAMMING_TABLE_TILE.get(), pos, state);
        invInput.setCallback((handler, slot, before, after) -> {
            selectedOption = -1;
            refreshOptions();
            setChanged();
            sendNetworkGuiUpdate(NET_GUI_DATA);
        });
        refreshOptions();
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    public List<BoardEntry> getOptions() {
        refreshOptions();
        return options;
    }

    public ItemStack getOptionStack(int index) {
        List<BoardEntry> opts = getOptions();
        if (index < 0 || index >= OPTION_COUNT) {
            return ItemStack.EMPTY;
        }
        int absolute = selectedPage * OPTION_COUNT + index;
        return absolute >= 0 && absolute < opts.size() ? ItemRedstoneBoard.createStack(opts.get(absolute)) : ItemStack.EMPTY;
    }

    public BoardEntry getSelectedBoard() {
        List<BoardEntry> opts = getOptions();
        int absolute = selectedPage * OPTION_COUNT + selectedOption;
        if (selectedOption < 0 || absolute < 0 || absolute >= opts.size()) {
            return BCRoboticsBoards.EMPTY;
        }
        return opts.get(absolute);
    }

    public boolean canProgramInput() {
        ItemStack input = invInput.getStackInSlot(0);
        return !input.isEmpty() && input.is(BCRoboticsItems.REDSTONE_BOARD.get());
    }

    public boolean canPutOutput(ItemStack stack) {
        ItemStack output = invOutput.getStackInSlot(0);
        return output.isEmpty() || (StackUtil.canMerge(stack, output) && output.getCount() + stack.getCount() <= output.getMaxStackSize());
    }

    public int pageCount() {
        return Math.max(1, (getOptions().size() + OPTION_COUNT - 1) / OPTION_COUNT);
    }

    public void selectOption(int option) {
        List<BoardEntry> opts = getOptions();
        if (option <= -2) {
            long requested = -((long) option) - 2L;
            if (requested < 0 || requested >= pageCount()) return;
            selectedPage = (int) requested;
            selectedOption = -1;
        } else if (option < 0 || option >= OPTION_COUNT || selectedPage * OPTION_COUNT + option >= opts.size()) {
            selectedOption = -1;
        } else {
            selectedOption = option;
        }
        power = Math.min(power, getTarget());
        sendNetworkGuiUpdate(NET_GUI_DATA);
        setChanged();
    }

    // A board can have multiple JSON recipes with different IDs. Eligibility is
    // based on the surviving active recipes, not on a guessed canonical ID.

    private void refreshOptions() {
        BCRoboticsBoards.init();
        if (!canProgramInput()) {
            options = List.of();
            return;
        }
        boolean client = level != null && level.isClientSide;
        long blacklistGeneration = RecipeBlacklist.generation();
        long recipeGeneration = BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES)
            .snapshot().generation().id();
        if (level != null && !client && (lastBlacklistGeneration != blacklistGeneration
            || lastRecipeGeneration != recipeGeneration)) {
            lastBlacklistGeneration = blacklistGeneration;
            lastRecipeGeneration = recipeGeneration;
            // Never silently switch to a different board after a recipe reload.
            selectedOption = -1;
            cachedRecipeCatalog = List.of();
            sendNetworkGuiUpdate(NET_GUI_DATA);
            setChanged();
        }
        List<ProgrammingRecipeDefinition> catalog = client && syncedProgrammingOptions != null
            ? syncedProgrammingOptions : ProgrammingRecipeAccess.options(client);
        // A datapack can provide several valid recipes for the same registered board.
        List<ProgrammingRecipeDefinition> active = catalog.stream()
            .filter(recipe -> BCRoboticsBoards.getByKey(recipe.boardKey()) != BCRoboticsBoards.EMPTY)
            .distinct()
            .sorted(java.util.Comparator.comparingLong(ProgrammingRecipeDefinition::requiredMicroJoules)
                .thenComparing(ProgrammingRecipeDefinition::boardKey))
            .toList();
        if (!active.equals(cachedRecipeCatalog) || options.isEmpty()) {
            cachedRecipeCatalog = active;
            optionDefinitions = active;
            options = active.stream().map(recipe -> BCRoboticsBoards.getByKey(recipe.boardKey())).toList();
        }
        int pageCount = Math.max(1, (options.size() + OPTION_COUNT - 1) / OPTION_COUNT);
        if (selectedPage < 0 || selectedPage >= pageCount) selectedPage = 0;
        if (selectedOption >= OPTION_COUNT || selectedPage * OPTION_COUNT + selectedOption >= options.size()) {
            selectedOption = -1;
        }
    }

    @Override
    public long getTarget() {
        BoardEntry selected = getSelectedBoard();
        if (selected == BCRoboticsBoards.EMPTY || !canProgramInput()) {
            return 0;
        }
        ItemStack programmed = ItemRedstoneBoard.createStack(selected);
        int absolute = selectedPage * OPTION_COUNT + selectedOption;
        if (absolute < 0 || absolute >= optionDefinitions.size()) return 0;
        // The selected *recipe variant*, not a guessed board-key winner, owns the energy cost.
        return canPutOutput(programmed) ? optionDefinitions.get(absolute).requiredMicroJoules() : 0L;
    }

    @Override
    public void update() {
        super.update();
        if (level.isClientSide) {
            return;
        }
        refreshOptions();
        long target = getTarget();
        if (target > 0 && power >= target) {
            BoardEntry selected = getSelectedBoard();
            ItemStack programmed = ItemRedstoneBoard.createStack(selected);
            invInput.extractItem(0, 1, false);
            ItemStack output = invOutput.getStackInSlot(0);
            if (output.isEmpty()) {
                invOutput.setStackInSlot(0, programmed);
            } else {
                output.grow(programmed.getCount());
                invOutput.setStackInSlot(0, output);
            }
            power -= target;
            selectedOption = -1;
            refreshOptions();
            setChanged();
            sendNetworkGuiUpdate(NET_GUI_DATA);
            setChanged();
        }
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && !player.level().isClientSide) {
            serverPlayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return super.onActivated(player, hand, hit);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ContainerProgrammingTable(id, inventory, invInput, invOutput, optionDisplay,
                ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }

    @Override
    protected void writeData(BCValueOutput bcData) {
        super.writeData(bcData);
        bcData.writeInt("selectedOption", selectedOption);
        bcData.writeInt("selectedPage", selectedPage);
    }

    @Override
    protected void readData(BCValueInput bcData) {
        super.readData(bcData);
        selectedOption = bcData.readInt("selectedOption");
        selectedPage = bcData.readInt("selectedPage");
        refreshOptions();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);
        if (id == NET_GUI_DATA || id == NET_SELECTED_OPTION) {
            buffer.writeVarInt(selectedOption);
            buffer.writeVarInt(selectedPage);
            if (id == NET_GUI_DATA) {
                List<ProgrammingRecipeDefinition> recipes = ProgrammingRecipeAccess.options(false);
                buffer.writeVarInt(recipes.size());
                for (ProgrammingRecipeDefinition recipe : recipes) {
                    buffer.writeUtf(recipe.boardKey(), 256);
                    buffer.writeLong(recipe.requiredMicroJoules());
                }
            }
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (id == NET_GUI_DATA || id == NET_SELECTED_OPTION) {
            int incomingOption = buffer.readVarInt();
            int incomingPage = buffer.readVarInt();
            if (id == NET_GUI_DATA) {
                int count = buffer.readVarInt();
                if (count < 0 || count > 512) throw new IOException("Invalid programming recipe count " + count);
                List<ProgrammingRecipeDefinition> recipes = new ArrayList<>(count);
                java.util.Set<ProgrammingRecipeDefinition> seen = new java.util.HashSet<>();
                for (int i = 0; i < count; i++) {
                    String key = buffer.readUtf(256);
                    long energy = buffer.readLong();
                    ProgrammingRecipeDefinition recipe;
                    try {
                        recipe = new ProgrammingRecipeDefinition(key, energy);
                    } catch (IllegalArgumentException invalid) {
                        throw new IOException("Invalid programming option for " + key, invalid);
                    }
                    if (!seen.add(recipe)) throw new IOException("Duplicate programming option " + key + " at " + energy);
                    recipes.add(recipe);
                }
                if (side == BCNetworkSide.CLIENT) {
                    syncedProgrammingOptions = List.copyOf(recipes);
                    cachedRecipeCatalog = List.of();
                }
            }
            // This packet is a server-owned GUI snapshot. Players select a board
            // through the validated menu action, not by writing arbitrary tile state.
            if (side == BCNetworkSide.CLIENT) {
                selectedOption = incomingOption;
                selectedPage = incomingPage;
                refreshOptions();
            }
        }
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("selected - " + selectedOption);
        left.add("page - " + selectedPage);
        left.add("options - " + getOptions().size());
        left.add("target - " + LocaleUtil.localizeMj(getTarget()));
    }
}
