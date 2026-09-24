/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.tile;

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

import buildcraft.lib.platform.registry.PlatformMenus;
import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.misc.AdvancementUtil;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.lib.misc.LocaleUtil;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.item.crafting.Recipe;
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

    private static final ResourceLocation ADVANCEMENT = new ResourceLocation("buildcraftsilicon:precision_crafting");
    
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
            for (AssemblyRecipeBasic recipe : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
                Set<ItemStack> outputs = recipe.getOutputs(inv);
                for (ItemStack out : outputs) {
                    if (out.isEmpty()) {
                        break;
                    }
                    boolean found = false;
                    for (AssemblyInstruction instruction : recipesStates.keySet()) {
                        if (instruction.recipe == recipe && out == instruction.output) {
                            found = true;
                            break;
                        }
                    }
                    AssemblyInstruction instruction = new AssemblyInstruction(recipe, out);
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
                } else if (state != EnumAssemblyRecipeState.SAVED) {
                    state = EnumAssemblyRecipeState.SAVED;
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
                if (entry.getValue() == EnumAssemblyRecipeState.SAVED_ENOUGH) {
                    entry.setValue(EnumAssemblyRecipeState.SAVED_ENOUGH_ACTIVE);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
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
        return Optional.ofNullable(getActiveRecipe()).map(instruction -> instruction.recipe.getRequiredMicroJoulesFor(instruction.output)).orElse(0L);
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
	public void saveAdditional(CompoundTag nbt) {
		super.saveAdditional(nbt);
	      ListTag recipesStatesTag = new ListTag();
	        recipesStates.forEach((instruction, state) -> {
	            CompoundTag entryTag = new CompoundTag();
	            entryTag.putString("recipe", instruction.recipe.getId().toString());
	            entryTag.put("output", instruction.output.serializeNBT());
	            entryTag.putInt("state", state.ordinal());
	            recipesStatesTag.add(entryTag);
	        });
	        nbt.put("recipes_states", recipesStatesTag);
	}
    
	@Override
	public void load(CompoundTag nbt) {
		super.load(nbt);
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
                        ItemStack.of(entryTag.getCompound("output")),
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
			AssemblyInstruction instruction = lookupRecipe(pending.recipeName(), pending.output());
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
                buffer.writeUtf(instruction.recipe.getId().toString(), MAX_RECIPE_ID_LENGTH);
                buffer.writeItem(instruction.output);
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
                ItemStack output = buffer.readItem();
                int stateIndex = NetworkSecurity.requireRange(
                    buffer.readInt(), 0, EnumAssemblyRecipeState.values().length - 1, "assembly recipe state"
                );
                AssemblyInstruction instruction = lookupRecipe(recipeName, output);
                if (instruction != null) {
                    recipesStates.put(instruction, EnumAssemblyRecipeState.values()[stateIndex]);
                }
            }
        }

        if (id == NET_RECIPE_STATE) {
            String recipeName = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
            ItemStack output = buffer.readItem();
            int stateIndex = NetworkSecurity.requireRange(
                buffer.readInt(), 0, EnumAssemblyRecipeState.values().length - 1, "assembly recipe state"
            );
            AssemblyInstruction recipe = lookupRecipe(recipeName, output);
            if (recipe != null && recipesStates.containsKey(recipe)) {
                recipesStates.put(recipe, EnumAssemblyRecipeState.values()[stateIndex]);
                if (side == BCNetworkSide.SERVER) {
                    sendNetworkGuiUpdate(NET_GUI_DATA);
                }
            }
        }
    }

    public void sendRecipeStateToServer(AssemblyInstruction instruction, EnumAssemblyRecipeState state) {
    	MessageUpdateTile message = createMessage(NET_RECIPE_STATE, (buffer) -> {
            buffer.writeUtf(instruction.recipe.getId().toString(), MAX_RECIPE_ID_LENGTH);
            buffer.writeItem(instruction.output);
            buffer.writeInt(state.ordinal());
        });
        MessageManager.sendToServer(message);
    }
    
	@Override
	public InteractionResult onActivated(Player player, InteractionHand hand, BlockHitResult hit) {
		//? if <1.20 {
		if(player instanceof ServerPlayer splayer&&!player.level.isClientSide) {
		//?} else {
		/*?
		if(player instanceof ServerPlayer splayer&&!player.level().isClientSide) {
		?*/
		//?}
			PlatformMenus.open(splayer, this, worldPosition);
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
    private AssemblyInstruction lookupRecipe(String name, ItemStack output) {
        Optional<? extends Recipe<?>> recipe = level.getRecipeManager().byKey(new ResourceLocation(name));
        return (recipe.isPresent() && recipe.get() instanceof AssemblyRecipeBasic assemblyRecipeBasic)
        		? new AssemblyInstruction(assemblyRecipeBasic, output) : null;
    }

    private record PendingRecipeState(String recipeName, ItemStack output, EnumAssemblyRecipeState state) {
    }

    public class AssemblyInstruction implements Comparable<AssemblyInstruction> {
        public final AssemblyRecipeBasic recipe;
        public final ItemStack output;

        private AssemblyInstruction(AssemblyRecipeBasic recipe, ItemStack output) {
            this.recipe = recipe;
            this.output = output;
        }

        @Override
        public int compareTo(AssemblyInstruction o) {
            int recipeOrder = recipe.getId().compareTo(o.recipe.getId());
            if (recipeOrder != 0) {
                return recipeOrder;
            }
            return output.serializeNBT().toString().compareTo(o.output.serializeNBT().toString());
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof AssemblyInstruction)) return false;
            AssemblyInstruction instruction = (AssemblyInstruction) obj;
            //? if <1.20 {
            return recipe.getId().equals(instruction.recipe.getId()) && ItemStack.isSame(output, instruction.output);
            //?} else {
            /*?
            return recipe.getId().equals(instruction.recipe.getId()) && ItemStack.isSameItem(output, instruction.output);
            ?*/
            //?}
        }
    }

}
