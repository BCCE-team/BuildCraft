/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

import buildcraft.lib.compat.minecraft.persistence.BCValueOutput;
import buildcraft.lib.compat.minecraft.persistence.BCValueInput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.ItemStackUtil;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.net.NetworkSecurity;
import buildcraft.lib.net.MessageUpdateTile;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.EnumAssemblyRecipeState;
import buildcraft.silicon.container.ContainerAssemblyTable;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class TileAssemblyTable extends TileLaserTableBase implements MenuProvider{
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("assembly_table");
    public static final int NET_RECIPE_STATE = IDS.allocId("RECIPE_STATE");
    private static final int MAX_RECIPE_ID_LENGTH = 256;
    private static final int MAX_RECIPE_STATES = 4096;

    public final ItemHandlerSimple inv = itemManager.addInvHandler(
        "inv",
        3 * 4,
        ItemHandlerManager.EnumAccess.BOTH,
        EnumPipePart.VALUES
    );
    public SortedMap<AssemblyInstruction, EnumAssemblyRecipeState> recipesStates = new TreeMap<>();
    public final ItemProvider display = new ItemProvider((i) -> {
        return i < recipesStates.size() ? new ArrayList<>(recipesStates.keySet()).get(i).output : ItemStack.EMPTY;}, 3 * 4);

    private final List<PendingRecipeState> pendingRecipeStates = new ArrayList<>();

    private static final ResourceLocation ADVANCEMENT = ResourceLocation.parse("buildcraftsilicon:precision_crafting");

    protected boolean isDirty = true;

    public TileAssemblyTable(BlockPos pos, BlockState state) {
        super(BCSiliconBlocks.ASSEMBLY_TABLE_TILE.get(), pos, state);
        inv.setCallback((a,b,c,d) -> isDirty = true);
    }

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    private void updateRecipes() {
        boolean changed = false;
        if (isDirty) {
            int previousSize = recipesStates.size();
            for (RecipeHolder<AssemblyRecipeBasic> holder : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
                ResourceLocation recipeId = holder.id();
                AssemblyRecipeBasic recipe = holder.value();
                Set<ItemStack> outputs = recipe.getOutputs(inv);
                for (ItemStack out : outputs) {
                    if (out.isEmpty()) {
                        continue;
                    }
                    boolean found = false;
                    for (AssemblyInstruction instruction : recipesStates.keySet()) {
                        if (instruction.recipeId.equals(recipeId) && ItemStack.matches(out, instruction.output)) {
                            found = true;
                            break;
                        }
                    }
                    AssemblyInstruction instruction = new AssemblyInstruction(recipeId, recipe, out.copy());
                    if (!found && !recipesStates.containsKey(instruction)) {
                        recipesStates.put(instruction, EnumAssemblyRecipeState.POSSIBLE);
                    }
                }
            }
            changed |= previousSize != recipesStates.size();
            isDirty = false;
        }

        boolean findActive = false;
        for (Iterator<Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState>> iterator = recipesStates.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState> entry = iterator.next();
            AssemblyInstruction instruction = entry.getKey();
            EnumAssemblyRecipeState previousState = entry.getValue();
            EnumAssemblyRecipeState state = previousState;
            if (instruction.recipe == null) {
                // Client-only synced instructions never enter the server update loop. If an invalid
                // persisted entry somehow does, discard it instead of crashing recipe discovery.
                iterator.remove();
                changed = true;
                continue;
            }
            boolean enough = extract(inv, instruction.recipe.getInputsFor(instruction.output), true, false);
            if (state == EnumAssemblyRecipeState.POSSIBLE) {
                if (!enough) {
                    iterator.remove();
                    changed = true;
                    continue;
                }
            } else {
                if (enough) {
                    if (state == EnumAssemblyRecipeState.SAVED) {
                        state = EnumAssemblyRecipeState.SAVED_ENOUGH;
                    }
                } else {
                    if (state != EnumAssemblyRecipeState.SAVED) {
                        state = EnumAssemblyRecipeState.SAVED;
                    }
                }
            }
            if (state == EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE) {
                findActive = true;
            }
            if (state != previousState) {
                entry.setValue(state);
                changed = true;
            }
        }
        if (!findActive) {
            for (Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState> entry : recipesStates.entrySet()) {
                EnumAssemblyRecipeState state = entry.getValue();
                if (state == EnumAssemblyRecipeState.SAVED_ENOUGH) {
                    state = EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE;
                    entry.setValue(state);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            // State-only transitions (SAVED <-> SAVED_ENOUGH/ACTIVE) are visible in the GUI too.
            // Sync them even when the number of discovered recipes did not change.
            sendNetworkGuiUpdate(NET_GUI_DATA);
        }
    }

    private AssemblyInstruction getActiveRecipe() {
        return recipesStates.entrySet().stream().filter(entry -> entry.getValue() == EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE).map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private void activateNextRecipe() {
        List<Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState>> available = new ArrayList<>();
        for (Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState> entry : recipesStates.entrySet()) {
            if (entry.getValue() == EnumAssemblyRecipeState.SAVED_ENOUGH
                || entry.getValue() == EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE) {
                available.add(entry);
            }
        }
        if (available.size() <= 1) {
            return;
        }
        int activeIndex = -1;
        for (int i = 0; i < available.size(); i++) {
            Map.Entry<AssemblyInstruction, EnumAssemblyRecipeState> entry = available.get(i);
            if (entry.getValue() == EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE) {
                activeIndex = i;
                entry.setValue(EnumAssemblyRecipeState.SAVED_ENOUGH);
                break;
            }
        }
        if (activeIndex >= 0) {
            available.get((activeIndex + 1) % available.size()).setValue(EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE);
        }
    }

    @Override
    public long getTarget() {
        AssemblyInstruction instruction = getActiveRecipe();
        return instruction == null || instruction.recipe == null
            ? 0L
            : instruction.recipe.getRequiredMicroJoulesFor(instruction.output);
    }

    @Override
    public void update() {
        super.update();

        if (level.isClientSide) {

            return;
        }

 //        if(isDirty)
        updateRecipes();


        long target = getTarget();
        if (target > 0) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT);
            if (power >= target) {
                AssemblyInstruction instruction = getActiveRecipe();
                if (instruction != null && extract(inv, instruction.recipe.getInputsFor(instruction.output), false, false)) {
                    InventoryUtil.addToBestAcceptor(getLevel(), getBlockPos(), null, instruction.output.copy());
                    power -= target;
                    activateNextRecipe();
                } else {
                    isDirty = true;
                }
            }
            sendNetworkGuiUpdate(NET_GUI_DATA);
        }
    }

    @Override
    protected void writeData(BCValueOutput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.writeData(bcData);
        ListTag recipesStatesTag = new ListTag();
        recipesStates.forEach((instruction, state) -> {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString("recipe", instruction.recipeId.toString());
            entryTag.put("output", ItemStackUtil.saveOptional(instruction.output, registries));
            entryTag.putInt("state", state.ordinal());
            recipesStatesTag.add(entryTag);
        });
        nbt.put("recipes_states", recipesStatesTag);
    }

    @Override
    protected void readData(BCValueInput bcData) {
        CompoundTag nbt = bcData.tag();
        HolderLookup.Provider registries = bcData.registries();
        super.readData(bcData);
        recipesStates.clear();
        pendingRecipeStates.clear();
        ListTag recipesStatesTag = nbt.getList("recipes_states", Tag.TAG_COMPOUND);
        for (int i = 0; i < recipesStatesTag.size(); i++) {
            CompoundTag entryTag = recipesStatesTag.getCompound(i);
            String name = entryTag.getString("recipe");
            if (entryTag.contains("output")) {
                int stateIndex = entryTag.getInt("state");
                if (stateIndex >= 0 && stateIndex < EnumAssemblyRecipeState.values().length) {
                    pendingRecipeStates.add(new PendingRecipeState(
                        name,
                        ItemStackUtil.parseOptional(registries, entryTag.getCompound("output")),
                        EnumAssemblyRecipeState.values()[stateIndex]
                    ));
                }
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        for (PendingRecipeState pending : pendingRecipeStates) {
            AssemblyInstruction instruction = level != null && level.isClientSide
                ? createSyncedInstruction(pending.recipeName(), pending.output())
                : lookupServerRecipe(pending.recipeName(), pending.output());
            if (instruction != null) {
                recipesStates.put(instruction, pending.state());
            }
        }
        pendingRecipeStates.clear();
    }

    @Override
    public void writePayload(int id, FriendlyByteBuf buffer, BCNetworkSide side) {
        super.writePayload(id, buffer, side);

        if (id == NET_GUI_DATA) {
            buffer.writeInt(recipesStates.size());
            recipesStates.forEach((instruction, state) -> {
                buffer.writeUtf(instruction.recipeId.toString(), MAX_RECIPE_ID_LENGTH);
                ItemStackUtil.writeOptional(buffer, instruction.output);
                buffer.writeInt(state.ordinal());
            });
        }
    }

    @Override
    public void readPayload(int id, FriendlyByteBuf buffer, BCNetworkSide side, BCPacketContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);

        if (id == NET_GUI_DATA) {
            recipesStates.clear();
            int count = NetworkSecurity.requireCount(buffer.readInt(), MAX_RECIPE_STATES, "assembly recipe states");
            for (int i = 0; i < count; i++) {
                String recipeName = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
                ItemStack output = ItemStackUtil.readOptional(buffer);
                int stateIndex = NetworkSecurity.requireRange(
                    buffer.readInt(), 0, EnumAssemblyRecipeState.values().length - 1, "assembly recipe state"
                );
                AssemblyInstruction instruction = side == BCNetworkSide.CLIENT
                    ? createSyncedInstruction(recipeName, output)
                    : lookupServerRecipe(recipeName, output);
                if (instruction != null && stateIndex >= 0 && stateIndex < EnumAssemblyRecipeState.values().length) {
                    recipesStates.put(instruction, EnumAssemblyRecipeState.values()[stateIndex]);
                }
            }
        }

        if (id == NET_RECIPE_STATE) {
            String recipeName = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
            ItemStack output = ItemStackUtil.readOptional(buffer);
            int stateIndex = NetworkSecurity.requireRange(
                buffer.readInt(), 0, EnumAssemblyRecipeState.values().length - 1, "assembly recipe state"
            );
            AssemblyInstruction recipe = side == BCNetworkSide.SERVER
                ? lookupServerRecipe(recipeName, output)
                : null;
            if (recipe != null && stateIndex >= 0 && stateIndex < EnumAssemblyRecipeState.values().length
                && recipesStates.containsKey(recipe)) {
                recipesStates.put(recipe, EnumAssemblyRecipeState.values()[stateIndex]);
                // Echo the authoritative state immediately instead of waiting for a later laser-table tick.
                sendNetworkGuiUpdate(NET_GUI_DATA);
            }
        }
    }

    public void sendRecipeStateToServer(AssemblyInstruction instruction, EnumAssemblyRecipeState state) {
        MessageUpdateTile message = createMessage(NET_RECIPE_STATE, (buffer) -> {
            buffer.writeUtf(instruction.recipeId.toString(), MAX_RECIPE_ID_LENGTH);
            ItemStackUtil.writeOptional(buffer, instruction.output);
            buffer.writeInt(state.ordinal());
        });
        MessageManager.sendToServer(message);
    }

    @Override
    public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
        if(player instanceof ServerPlayer splayer&&!player.level().isClientSide) {
            splayer.openMenu(this, buffer -> buffer.writeBlockPos(worldPosition));
        }
        return super.onActivated(player, hand, hit);
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player p_39956_) {
        return new ContainerAssemblyTable(id, inventory, inv, display, ContainerLevelAccess.create(level, worldPosition));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(this.getBlockState().getBlock().getDescriptionId());
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, Direction side) {
        super.getDebugInfo(left, right, side);
        left.add("recipes - " + recipesStates.size());
        left.add("target - " + LocaleUtil.localizeMj(getTarget()));
    }

    @Nullable
    private AssemblyInstruction createSyncedInstruction(String name, ItemStack output) {
        ResourceLocation recipeId = normalizeRecipeId(name);
        return recipeId == null ? null : new AssemblyInstruction(recipeId, null, output.copy());
    }

    @Nullable
    private AssemblyInstruction lookupServerRecipe(String name, ItemStack output) {
        ResourceLocation recipeId = normalizeRecipeId(name);
        if (recipeId == null || level == null || level.isClientSide) {
            return null;
        }
        Optional<? extends RecipeHolder<?>> recipe = level.getRecipeManager().byKey(recipeId);
        return (recipe.isPresent() && recipe.get().value() instanceof AssemblyRecipeBasic assemblyRecipeBasic)
            ? new AssemblyInstruction(recipeId, assemblyRecipeBasic, output.copy()) : null;
    }

    @Nullable
    private static ResourceLocation normalizeRecipeId(String name) {
        ResourceLocation recipeId = ResourceLocation.tryParse(name);
        if (FacadeAssemblyRecipes.LEGACY_ID.equals(recipeId)) {
            return FacadeAssemblyRecipes.ID;
        }
        return recipeId;
    }

    private record PendingRecipeState(String recipeName, ItemStack output, EnumAssemblyRecipeState state) {
    }

    public class AssemblyInstruction implements Comparable<AssemblyInstruction> {
        public final ResourceLocation recipeId;
        /** Null only for client-side GUI state reconstructed from NET_GUI_DATA. */
        @Nullable
        public final AssemblyRecipeBasic recipe;
        public final ItemStack output;

        private AssemblyInstruction(ResourceLocation recipeId, AssemblyRecipeBasic recipe, ItemStack output) {
            this.recipeId = recipeId;
            this.recipe = recipe;
            this.output = output;
        }

        @Override
        public int compareTo(AssemblyInstruction o) {
            int recipeOrder = recipeId.compareTo(o.recipeId);
            if (recipeOrder != 0) {
                return recipeOrder;
            }
            if (ItemStack.matches(output, o.output)) {
                return 0;
            }
            int itemOrder = BuiltInRegistries.ITEM.getKey(output.getItem())
                .compareTo(BuiltInRegistries.ITEM.getKey(o.output.getItem()));
            if (itemOrder != 0) {
                return itemOrder;
            }
            int countOrder = Integer.compare(output.getCount(), o.output.getCount());
            if (countOrder != 0) {
                return countOrder;
            }
            int componentOrder = Integer.compare(
                ItemStack.hashItemAndComponents(output),
                ItemStack.hashItemAndComponents(o.output)
            );
            if (componentOrder != 0) {
                return componentOrder;
            }
            // Deterministic collision fallback without serializing through a registry codec.
            return output.getComponentsPatch().toString().compareTo(o.output.getComponentsPatch().toString());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AssemblyInstruction)) return false;
            AssemblyInstruction instruction = (AssemblyInstruction) obj;
            return recipeId.equals(instruction.recipeId) && ItemStack.matches(output, instruction.output);
        }

        @Override
        public int hashCode() {
            return 31 * recipeId.hashCode() + 31 * ItemStack.hashItemAndComponents(output) + output.getCount();
        }
    }

}
