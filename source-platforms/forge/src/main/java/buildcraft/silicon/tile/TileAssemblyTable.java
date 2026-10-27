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
import buildcraft.lib.recipe.ApiAssemblyRecipeAdapter;
import buildcraft.lib.recipe.RecipeBlacklist;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.recipe.MachineRecipeService;
import net.minecraft.core.NonNullList;
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
import net.minecraftforge.network.NetworkHooks;

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
    /** Client-side only; the server continues validating recipe changes by instruction identity. */
    private int displayPage;
    private static final int DISPLAY_PAGE_SIZE = 12;

    public int getDisplayPage() {
        displayPage = Math.max(0, Math.min(displayPage, getDisplayPageCount() - 1));
        return displayPage;
    }

    public int getDisplayPageCount() {
        return Math.max(1, (recipesStates.size() + DISPLAY_PAGE_SIZE - 1) / DISPLAY_PAGE_SIZE);
    }

    public void setDisplayPage(int page) {
        displayPage = Math.max(0, Math.min(page, getDisplayPageCount() - 1));
    }

    public final ItemProvider display = new ItemProvider((index) -> {
        int offset = getDisplayPage() * DISPLAY_PAGE_SIZE + index;
        return index >= 0 && index < DISPLAY_PAGE_SIZE && offset < recipesStates.size()
                ? new ArrayList<>(recipesStates.keySet()).get(offset).output : ItemStack.EMPTY;
    }, DISPLAY_PAGE_SIZE);

    private final List<PendingRecipeState> pendingRecipeStates = new ArrayList<>();

    private static final ResourceLocation ADVANCEMENT = new ResourceLocation("buildcraftsilicon:precision_crafting");
    
    protected boolean isDirty = true;
    private long lastApiRecipeGeneration = -1;
    private long lastBlacklistGeneration = -1;
    /** Last advertised work target; progress packets are rate-limited while the recipe is unchanged. */
    private long lastGuiTarget = Long.MIN_VALUE;
    private Object lastVanillaRecipeManager;
    private Object lastVanillaRecipeMap;

    private static MachineRecipeService machineRecipes() {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES);
    }

    private boolean canConsume(AssemblyInstruction instruction, boolean simulate) {
        if (instruction.recipe instanceof ApiAssemblyRecipeAdapter adapter) {
            return adapter.consume(inv, instruction.output, simulate);
        }
        return extract(inv, instruction.recipe.getInputsFor(instruction.output), simulate, false);
    }


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
        // RecipeManager is replaced during /reload; client/server caches must discard obsolete instructions.
        Object manager = level.getRecipeManager();
        Object recipeMap = manager;
        // On 1.21.11+ the recipeAccess recipe-map instance is the reload generation.
        // The family-local source processor selects the applicable branch.
        //? if >=1.21.11 {
        recipeMap = ((net.minecraft.server.level.ServerLevel) level).recipeAccess().recipeMap();
        //?}
        if (lastVanillaRecipeManager != manager || lastVanillaRecipeMap != recipeMap) {
            lastVanillaRecipeManager = manager;
            lastVanillaRecipeMap = recipeMap;
            changed |= recipesStates.entrySet().removeIf(entry ->
                !(entry.getKey().recipe instanceof ApiAssemblyRecipeAdapter));
            isDirty = true;
        }
        long generation = machineRecipes().snapshot().generation().id();
        if (lastApiRecipeGeneration != generation) {
            lastApiRecipeGeneration = generation;
            isDirty = true;
            // On datapack reload, stale cached instructions must not outlive their recipe.
            changed |= recipesStates.entrySet().removeIf(entry ->
                entry.getKey().recipe instanceof ApiAssemblyRecipeAdapter adapter && !adapter.registered());
        }
        long blacklistGeneration = RecipeBlacklist.generation();
        if (lastBlacklistGeneration != blacklistGeneration) {
            lastBlacklistGeneration = blacklistGeneration;
            isDirty = true;
            changed |= recipesStates.entrySet().removeIf(entry ->
                RecipeBlacklist.isDenied("assembly_table", entry.getKey().recipe.getId()));
        }
        if (isDirty) {
            int previousSize = recipesStates.size();
            for (AssemblyRecipeBasic recipe : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
                if (RecipeBlacklist.isDenied("assembly_table", recipe.getId())) continue;
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
            // API v2 recipes are not Minecraft recipe-manager entries. Feed their outputs
            // through the same instruction, energy, persistence and GUI pipeline.
            NonNullList<ItemStack> apiInputs = NonNullList.create();
            for (int slot = 0; slot < inv.getSlots(); slot++) apiInputs.add(inv.getStackInSlot(slot).copy());
            for (var match : machineRecipes().findAssembly(apiInputs)) {
                ApiAssemblyRecipeAdapter recipe = ApiAssemblyRecipeAdapter.fromService(match.id());
                if (recipe == null) continue;
                for (ItemStack output : recipe.getOutputs(inv)) {
                    if (output == null || output.isEmpty()) continue;
                    AssemblyInstruction instruction = new AssemblyInstruction(recipe, output.copy());
                    if (!recipesStates.containsKey(instruction)) {
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
            boolean enough = canConsume(instruction, true);
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
        boolean completed = false;
        if (target > 0) {
            AdvancementUtil.unlockAdvancement(getOwner().getId(), ADVANCEMENT);
            if (power >= target) {
                AssemblyInstruction instruction = getActiveRecipe();
                if (instruction != null && canConsume(instruction, false)) {
                    InventoryUtil.addToBestAcceptor(getLevel(), getBlockPos(), null, instruction.output.copy());
                    power -= target;
                    activateNextRecipe();
                    completed = true;
                } else {
                    isDirty = true;
                }
            }

        }
        // Recipe discovery already sends an immediate packet on a change. During steady laser work only
        // publish progress five times a second; always publish completed crafts and a changed/cleared target.
        if (completed || target != lastGuiTarget || (target > 0 && level.getGameTime() % 4 == 0)) {
            sendNetworkGuiUpdate(NET_GUI_DATA);
            lastGuiTarget = target;
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
            // Full GUI state is server-authoritative. A client may only send the
            // narrowly validated NET_RECIPE_STATE command.
            if (side == BCNetworkSide.SERVER) {
                throw new IOException("Client cannot replace Assembly Table recipe state");
            }
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
            // Comparing the actual server-created output prevents a client from
            // substituting a different ItemStack/count under a legitimate recipe ID.
            boolean knownOutput = recipe != null && recipesStates.keySet().stream().anyMatch(known ->
                known.recipe.getId().equals(recipe.recipe.getId()) && ItemStack.matches(known.output, output));
            if (knownOutput && recipesStates.containsKey(recipe)) {
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
			NetworkHooks.openScreen(splayer, this, worldPosition);
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
        if (RecipeBlacklist.isDenied("assembly_table", ResourceLocation.tryParse(name))) return null;
        Optional<? extends Recipe<?>> recipe = level.getRecipeManager().byKey(new ResourceLocation(name));
        if (recipe.isPresent() && recipe.get() instanceof AssemblyRecipeBasic original) {
            return new AssemblyInstruction(original, output.copy());
        }
        ApiAssemblyRecipeAdapter addon = ApiAssemblyRecipeAdapter.fromService(new ResourceLocation(name));
        return addon == null ? null : new AssemblyInstruction(addon, output.copy());
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
