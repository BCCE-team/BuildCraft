package buildcraft.compat.jei;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.lib.internal.mj.MjFormatting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.gui.GuiGraphics;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.fuels.FuelProfile;
import buildcraft.api.v2.recipe.DistillationRecipeDefinition;
import buildcraft.api.v2.recipe.HeatExchangeRecipeDefinition;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.recipe.RecipeDefinition;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.recipe.MachineRecipeApiBridge;
import buildcraft.lib.internal.recipes.IngredientStack;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.factory.BCFactoryItems;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.ledger.Ledger_Neptune;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.misc.ItemStackKey;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.recipe.AssemblyRecipeBasic;
import buildcraft.lib.recipe.ChangingItemStack;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.robotics.item.ItemRedstoneBoard;
import buildcraft.robotics.item.ItemRobot;
import buildcraft.silicon.BCSiliconGuis;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.container.ContainerAssemblyTable;
import buildcraft.silicon.gate.EnumGateMaterial;
import buildcraft.silicon.gate.EnumGateModifier;
import buildcraft.silicon.gate.GateVariant;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import buildcraft.silicon.tile.TileProgrammingTable_Neptune;
import buildcraft.silicon.plug.FacadeBlockStateInfo;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.transport.BCTransportItems;
import buildcraft.transport.recipe.PipeRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableAnimated;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import mezz.jei.api.registration.IVanillaCategoryExtensionRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.registries.ForgeRegistries;

@JeiPlugin
public class BuildCraftJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = new ResourceLocation("buildcraftlib", "jei_plugin");

    private static RegistryAccess registryAccess() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            throw new IllegalStateException("JEI recipe rendering requires an active client level");
        }
        return level.registryAccess();
    }

    public static final RecipeType<AssemblyRecipeBasic> ASSEMBLY = RecipeType.create("buildcraftsilicon", "assembly", AssemblyRecipeBasic.class);
    public static final RecipeType<ProgrammingRecipeView> PROGRAMMING = RecipeType.create("buildcraftsilicon", "programming", ProgrammingRecipeView.class);
    public static final RecipeType<IntegrationRecipeView> INTEGRATION = RecipeType.create("buildcraftsilicon", "integration", IntegrationRecipeView.class);
    public static final RecipeType<DistillationRecipeDefinition> DISTILLATION = RecipeType.create("buildcraftfactory", "distillation", DistillationRecipeDefinition.class);
    public static final RecipeType<HeatExchangeRecipeView> HEAT_EXCHANGE = RecipeType.create("buildcraftfactory", "heat_exchange", HeatExchangeRecipeView.class);
    public static final RecipeType<CombustionFuelRecipeView> COMBUSTION_FUEL = RecipeType.create("buildcraftenergy", "combustion_fuel", CombustionFuelRecipeView.class);

    private static final Map<ResourceLocation, GroupedAssemblyRecipe> GROUPED_ASSEMBLY_RECIPES = new LinkedHashMap<>();

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGenericGuiContainerHandler(GuiBC8.class, new IGuiContainerHandler<GuiBC8<?>>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(GuiBC8<?> screen) {
                List<Rect2i> areas = new ArrayList<>();
                for (IGuiElement element : screen.mainGui.shownElements) {
                    if (!(element instanceof Ledger_Neptune ledger)) {
                        continue;
                    }

                    int x = Mth.floor(ledger.getX()) - 1;
                    int y = Mth.floor(ledger.getY()) - 1;
                    int endX = Mth.ceil(ledger.getX() + ledger.getWidth()) + 1;
                    int endY = Mth.ceil(ledger.getY() + ledger.getHeight()) + 1;
                    int width = endX - x;
                    int height = endY - y;
                    if (width > 0 && height > 0) {
                        areas.add(new Rect2i(x, y, width, height));
                    }
                }
                return areas;
            }
        });
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(BCSiliconItems.PLUG_FACADE_ITEM.get(),
                (stack, context) -> getFacadeSubtype(stack));

        // Gates share one item id. Grouping the subtype by material keeps JEI's use lookup useful:
        // pressing U on a gold gate shows only the gold base/modifier pages instead of every gate recipe.
        registration.registerSubtypeInterpreter(BCSiliconItems.PLUG_GATE_ITEM.get(),
                (stack, context) -> ItemPluggableGate.getVariant(stack).material.tag);

        // Lens colour/filter state is stored in Damage NBT on a single item. Expose it to JEI so focused
        // recipe lookups can select the matching variant inside the grouped lens pages.
        registration.registerSubtypeInterpreter(BCSiliconItems.PLUG_LENS_ITEM.get(),
                (stack, context) -> Integer.toString(stack.getDamageValue()));
    }

    @Override
    public void registerVanillaCategoryExtensions(IVanillaCategoryExtensionRegistration registration) {
        registration.getCraftingCategory().addCategoryExtension(PipeRecipe.class, PipeCraftingCategoryExtension::new);
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper guiHelper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new AssemblyCategory(guiHelper),
                new ProgrammingCategory(guiHelper),
                new IntegrationCategory(guiHelper),
                new DistillationCategory(guiHelper),
                new HeatExchangeCategory(guiHelper),
                new CombustionFuelCategory(guiHelper)
        );
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            List<AssemblyRecipeBasic> assemblyRecipes = new ArrayList<>(level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get()));
            boolean hadFacadeRecipe = assemblyRecipes.removeIf(FacadeAssemblyRecipes.class::isInstance);
            if (hadFacadeRecipe && BCSiliconConfig.enableFacades) {
                assemblyRecipes.add(FacadeAssemblyJeiRecipe.create());
            }
            assemblyRecipes = groupAssemblyRecipes(assemblyRecipes);
            registration.addRecipes(ASSEMBLY, assemblyRecipes);
        }

        BCRoboticsBoards.init();
        List<ProgrammingRecipeView> programming = new ArrayList<>();
        List<IntegrationRecipeView> integration = new ArrayList<>();
        for (BoardEntry board : getSortedProgrammingBoards()) {
            programming.add(new ProgrammingRecipeView(board));
            integration.add(new IntegrationRecipeView(board));
        }
        registration.addRecipes(PROGRAMMING, programming);
        registration.addRecipes(INTEGRATION, integration);

        MachineRecipeService machineRecipes = BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES);
        registration.addRecipes(DISTILLATION, machineRecipes.recipes(DistillationRecipeDefinition.class).stream()
                .map(match -> match.recipe())
                .toList());

        List<HeatExchangeRecipeView> heatExchange = machineRecipes.recipes(HeatExchangeRecipeDefinition.class).stream()
                .map(match -> match.recipe())
                .filter(recipe -> !recipe.output().isEmpty())
                .map(HeatExchangeRecipeView::new)
                .toList();
        registration.addRecipes(HEAT_EXCHANGE, heatExchange);

        EnergyFluidService energyFluids = BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS);
        List<CombustionFuelRecipeView> fuels = energyFluids.fuels().stream()
                .map(match -> match.profile())
                .filter(fuel -> fuel.representativeVariant().isPresent())
                .map(CombustionFuelRecipeView::new)
                .toList();
        registration.addRecipes(COMBUSTION_FUEL, fuels);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()), ASSEMBLY);
        registration.addRecipeCatalyst(new ItemStack(BCSiliconItems.PROGRAMMING_TABLE_ITEM.get()), PROGRAMMING);
        registration.addRecipeCatalyst(new ItemStack(BCSiliconItems.INTERGRATION_TABLE_ITEM.get()), INTEGRATION);
        registration.addRecipeCatalyst(new ItemStack(BCFactoryItems.DISTILLER_BLOCK_ITEM.get()), DISTILLATION);
        registration.addRecipeCatalyst(new ItemStack(BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get()), HEAT_EXCHANGE);
        registration.addRecipeCatalyst(new ItemStack(BCEnergyBlocks.ENGINE_IRON_ITEM.get()), COMBUSTION_FUEL);

        // Original BuildCraftCompat behaviour: both automated crafting tables
        // expose vanilla crafting recipes, and the steam engine is a fuel catalyst.
        registration.addRecipeCatalyst(new ItemStack(BCFactoryItems.AUTO_BENCH_ITEM.get()), RecipeTypes.CRAFTING);
        registration.addRecipeCatalyst(new ItemStack(BCSiliconItems.ADVANCED_CRAFTING_TABLE_ITEM.get()), RecipeTypes.CRAFTING);
        registration.addRecipeCatalyst(new ItemStack(BCEnergyBlocks.ENGINE_STONE_ITEM.get()), RecipeTypes.FUELING);
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new AutoWorkbenchRecipeTransferHandler(), RecipeTypes.CRAFTING);
        registration.addRecipeTransferHandler(new AdvancedCraftingRecipeTransferHandler(), RecipeTypes.CRAFTING);
        registration.addRecipeTransferHandler(
                ContainerAssemblyTable.class,
                BCSiliconGuis.MENU_ASSEMBLY_TABLE.get(),
                ASSEMBLY,
                36, 12,
                0, 36
        );
    }

    private static List<AssemblyRecipeBasic> groupAssemblyRecipes(List<AssemblyRecipeBasic> recipes) {
        GROUPED_ASSEMBLY_RECIPES.clear();
        Map<GroupedAssemblyKey, List<AssemblyRecipeBasic>> grouped = new LinkedHashMap<>();
        List<AssemblyRecipeBasic> result = new ArrayList<>();

        for (AssemblyRecipeBasic recipe : recipes) {
            GroupedAssemblyKey key = getGroupedAssemblyKey(recipe);
            if (key == null) {
                result.add(recipe);
            } else {
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(recipe);
            }
        }

        for (Map.Entry<GroupedAssemblyKey, List<AssemblyRecipeBasic>> entry : grouped.entrySet()) {
            addGroupedAssemblyRecipe(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static GroupedAssemblyKey getGroupedAssemblyKey(AssemblyRecipeBasic recipe) {
        ItemStack output = recipe.getResultItem(registryAccess());
        if (output.getItem() == BCSiliconItems.PLUG_LENS_ITEM.get()) {
            boolean filter = recipe.getInputsFor(output).stream()
                    .anyMatch(input -> input.ingredient.test(new ItemStack(Items.IRON_BARS)));
            return new GroupedAssemblyKey(filter ? GroupedAssemblyKind.LENS_FILTER : GroupedAssemblyKind.LENS, null);
        }

        ResourceLocation outputId = ForgeRegistries.ITEMS.getKey(output.getItem());
        if (outputId != null
                && outputId.getNamespace().equals("buildcrafttransport")
                && outputId.getPath().startsWith("wire/")) {
            return new GroupedAssemblyKey(GroupedAssemblyKind.WIRE, null);
        }

        if (output.getItem() == BCSiliconItems.PLUG_GATE_ITEM.get()) {
            GateVariant variant = ItemPluggableGate.getVariant(output);
            if (variant.material.canBeModified) {
                GroupedAssemblyKind kind = variant.modifier == EnumGateModifier.NO_MODIFIER
                        ? GroupedAssemblyKind.GATE_BASE
                        : GroupedAssemblyKind.GATE_MODIFIER;
                return new GroupedAssemblyKey(kind, variant.material);
            }
        }
        return null;
    }

    private static void addGroupedAssemblyRecipe(List<AssemblyRecipeBasic> target, GroupedAssemblyKey key,
            List<AssemblyRecipeBasic> variants) {
        if (variants.isEmpty()) {
            return;
        }
        variants.sort(groupedAssemblyComparator(key));
        AssemblyRecipeBasic representative = variants.get(0);
        GROUPED_ASSEMBLY_RECIPES.put(
                representative.getId(),
                new GroupedAssemblyRecipe(key, List.copyOf(variants))
        );
        target.add(representative);
    }

    private static Comparator<AssemblyRecipeBasic> groupedAssemblyComparator(GroupedAssemblyKey key) {
        return switch (key.kind()) {
            case LENS, LENS_FILTER -> Comparator.comparingInt(recipe -> recipe.getResultItem(registryAccess()).getDamageValue());
            case WIRE -> Comparator.comparing(recipe -> String.valueOf(ForgeRegistries.ITEMS.getKey(recipe.getResultItem(registryAccess()).getItem())));
            case GATE_BASE, GATE_MODIFIER -> Comparator
                    .comparingInt((AssemblyRecipeBasic recipe) -> ItemPluggableGate.getVariant(recipe.getResultItem(registryAccess())).logic.ordinal())
                    .thenComparingInt(recipe -> ItemPluggableGate.getVariant(recipe.getResultItem(registryAccess())).modifier.ordinal());
        };
    }

    private enum GroupedAssemblyKind {
        LENS,
        LENS_FILTER,
        WIRE,
        GATE_BASE,
        GATE_MODIFIER
    }

    private record GroupedAssemblyKey(GroupedAssemblyKind kind, EnumGateMaterial gateMaterial) {
    }

    private record GroupedAssemblyRecipe(GroupedAssemblyKey key, List<AssemblyRecipeBasic> variants) {
    }

    private static List<AssemblyRecipeBasic> getFocusedAssemblyVariants(List<AssemblyRecipeBasic> variants, IFocusGroup focuses) {
        List<ItemStack> focused = new ArrayList<>();
        focuses.getFocuses(RecipeIngredientRole.INPUT)
                .map(BuildCraftJeiPlugin::getFocusedItemStack)
                .filter(stack -> !stack.isEmpty())
                .forEach(focused::add);
        focuses.getFocuses(RecipeIngredientRole.OUTPUT)
                .map(BuildCraftJeiPlugin::getFocusedItemStack)
                .filter(stack -> !stack.isEmpty())
                .forEach(focused::add);
        if (focused.isEmpty()) {
            return variants;
        }
        List<AssemblyRecipeBasic> matches = variants.stream().filter(recipe -> {
            ItemStack output = recipe.getResultItem(registryAccess());
            for (ItemStack stack : focused) {
                if (ItemStack.isSameItemSameTags(output, stack)) {
                    return true;
                }
                for (IngredientStack input : recipe.getInputsFor(output)) {
                    if (input.ingredient.test(stack)) {
                        return true;
                    }
                }
            }
            return false;
        }).toList();
        return matches.isEmpty() ? variants : matches;
    }

    private static List<ItemStack> expandIngredient(IngredientStack ingredientStack) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : ingredientStack.ingredient.getItems()) {
            ItemStack copy = stack.copy();
            copy.setCount(Math.max(1, ingredientStack.count));
            stacks.add(copy);
        }
        return stacks;
    }

    private static List<ItemStack> expandIngredient(Ingredient ingredient, int count) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            ItemStack copy = stack.copy();
            copy.setCount(Math.max(1, count));
            stacks.add(copy);
        }
        return stacks;
    }

    private static List<ItemStack> expandChangingStack(ChangingItemStack changing) {
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStackKey key : changing.getOptions()) {
            ItemStack copy = key.baseStack.copy();
            if (!copy.isEmpty()) {
                stacks.add(copy);
            }
        }
        return stacks;
    }

    private static List<BoardEntry> getSortedProgrammingBoards() {
        BCRoboticsBoards.init();
        List<BoardEntry> boards = new ArrayList<>(BCRoboticsBoards.robotEntries());
        boards.sort(Comparator
                .comparingInt(BoardEntry::energyCost)
                .thenComparing(BoardEntry::id));
        return boards;
    }

    private static String formatMj(long microJoules) {
        if (BCLibConfig.hidePowerValues) {
            return sanitizeJeiText(LocaleUtil.localize("buildcraft.value.hidden"));
        }
        return sanitizeJeiText(MjFormatting.formatMicroMj(Math.max(0L, microJoules)) + " MJ");
    }

    private static String sanitizeJeiText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2009', ' ')
                .replace('\u2007', ' ')
                .replace("\u00B5", "u")
                .replace("\u03BC", "u")
                .replace("\u2192", "->")
                .replace("\u2013", "-")
                .replace("\u2014", "-");
    }

    private static Component sanitizeJeiComponent(Component component) {
        String original = component.getString();
        String sanitized = sanitizeJeiText(original);
        return original.equals(sanitized) ? component : Component.literal(sanitized).withStyle(component.getStyle());
    }

    private static void sanitizeFluidTooltip(List<Component> tooltip) {
        if (BCLibConfig.hideFluidValues) {
            Component fluidName = tooltip.isEmpty() ? null : tooltip.get(0);
            tooltip.clear();
            if (fluidName != null) {
                tooltip.add(sanitizeJeiComponent(fluidName));
            }
            tooltip.add(Component.translatable("buildcraft.value.hidden"));
            return;
        }
        for (int i = 0; i < tooltip.size(); i++) {
            tooltip.set(i, sanitizeJeiComponent(tooltip.get(i)));
        }
    }

    private static ItemStack createFilledBucketStack(FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        FluidStack bucketFluid = fluidStack.copy();
        bucketFluid.setAmount(FluidType.BUCKET_VOLUME);
        ItemStack bucket = FluidUtil.getFilledBucket(bucketFluid);
        if (bucket.isEmpty() && bucketFluid.getFluid().getBucket() != Items.AIR) {
            bucket = new ItemStack(bucketFluid.getFluid().getBucket());
        }
        if (!bucket.isEmpty()) {
            bucket.setCount(1);
        }
        return bucket;
    }

    private static void addFilledBucketFocus(IRecipeLayoutBuilder builder, RecipeIngredientRole role,
            FluidStack fluidStack) {
        ItemStack bucket = createFilledBucketStack(fluidStack);
        if (!bucket.isEmpty()) {
            builder.addInvisibleIngredients(role).addItemStack(bucket);
        }
    }

    private static IRecipeSlotBuilder addFluidSlot(IRecipeLayoutBuilder builder, RecipeIngredientRole role, int x, int y,
            FluidStack fluidStack, IDrawable slotBackground) {
        FluidStack shownFluid = fluidStack.copy();
        List<FluidStack> equivalentFluids = FluidCompatRegistry.getEquivalentStacks(shownFluid, "buildcraftenergy");
        if (equivalentFluids.isEmpty()) {
            equivalentFluids = List.of(shownFluid);
        }

        IRecipeSlotBuilder slot = builder.addSlot(role, x, y);
        if (slotBackground != null) {
            slot.setBackground(slotBackground, -1, -1);
        }
        slot.setFluidRenderer(Math.max(1, shownFluid.getAmount()), false, 16, 16)
                .addIngredients(ForgeTypes.FLUID_STACK, equivalentFluids)
                .addTooltipCallback((recipeSlotView, tooltip) -> sanitizeFluidTooltip(tooltip));

        for (FluidStack equivalentFluid : equivalentFluids) {
            addFilledBucketFocus(builder, role, equivalentFluid);
        }
        return slot;
    }

    public record ProgrammingRecipeView(BoardEntry board) {
        public ItemStack input() {
            return new ItemStack(BCRoboticsItems.REDSTONE_BOARD.get());
        }

        public ItemStack output() {
            return ItemRedstoneBoard.createStack(board);
        }

        public long requiredMicroJoules() {
            return board.energyCost() * MjAmount.MICRO_MJ_PER_MJ;
        }
    }

    public record IntegrationRecipeView(BoardEntry board) {
        public ItemStack robotInput() {
            return new ItemStack(BCRoboticsItems.ROBOT.get());
        }

        public ItemStack boardInput() {
            return ItemRedstoneBoard.createStack(board);
        }

        public ItemStack output() {
            return ItemRobot.createRobotStack(board, EntityRobotBase.SAFETY_ENERGY);
        }

        public long requiredMicroJoules() {
            return 10_000L * MjAmount.MICRO_MJ_PER_MJ;
        }
    }

    public record HeatExchangeRecipeView(HeatExchangeRecipeDefinition recipe) {
        public boolean heating() {
            return recipe.kind() == RecipeDefinition.Kind.HEATING;
        }
    }

    public record CombustionFuelRecipeView(FuelProfile fuel) {
        public FluidStack input() {
            return fuel.representativeVariant()
                    .map(variant -> FuelApiBridge.stackOfVariant(variant, FluidType.BUCKET_VOLUME))
                    .orElse(FluidStack.EMPTY);
        }

        public FluidStack residue() {
            return FuelApiBridge.stackOf(fuel.residuePerBucket());
        }
    }

    private static ItemStack createFacadeBaseRequirementStack() {
        if (!BCTransportItems.PIPE_STRUCTURE.isPresent()) {
            return new ItemStack(Blocks.COBBLESTONE_WALL);
        }
        return new ItemStack(BCTransportItems.PIPE_STRUCTURE.get(), 3);
    }

    private static String getFacadeSubtype(ItemStack stack) {
        // Keep the wrapped block state in JEI's subtype identity. Collapsing every solid/hollow facade
        // into four coarse keys breaks R/U lookup because JEI can no longer distinguish facade materials.
        // Read the serialized facade envelope directly: this avoids resolving BlockStates/FacadeInstance here.
        var data = NBTUtilBC.getItemData(stack);
        if (!data.contains("facade")) {
            return IIngredientSubtypeInterpreter.NONE;
        }
        return data.getCompound("facade").toString();
    }

    private static ItemStack getFocusedItemStack(IFocus<?> focus) {
        return focus.getTypedValue().getIngredient(VanillaTypes.ITEM_STACK).orElse(ItemStack.EMPTY);
    }

    /**
     * Builds the facade recipe index once, deduplicated by the source ItemStack rather than by BlockState.
     * A furnace, stairs, etc. can expose many facade states for one inventory item; JEI only needs one
     * representative per unique input stack for U lookup. The sorted validFacadeStates map gives stable order.
     */
    private static List<FacadeBlockStateInfo> collectJeiFacadeInfos() {
        LinkedHashMap<ItemStackKey, FacadeBlockStateInfo> unique = new LinkedHashMap<>();
        for (FacadeBlockStateInfo info : FacadeStateManager.validFacadeStates.values()) {
            if (!info.isVisible || info.requiredStack.isEmpty()) {
                continue;
            }
            ItemStack required = info.requiredStack.copy();
            required.setCount(1);
            unique.putIfAbsent(new ItemStackKey(required), info);
        }
        return List.copyOf(unique.values());
    }

    private static List<ItemStack> createFacadeRequirementStacks(List<FacadeBlockStateInfo> infos) {
        List<ItemStack> stacks = new ArrayList<>(infos.size());
        for (FacadeBlockStateInfo info : infos) {
            stacks.add(info.requiredStack.copy());
        }
        return List.copyOf(stacks);
    }

    private static List<ItemStack> createFacadeOutputStacks(List<FacadeBlockStateInfo> infos, boolean hollow) {
        List<ItemStack> stacks = new ArrayList<>(infos.size());
        for (FacadeBlockStateInfo info : infos) {
            stacks.add(FacadeAssemblyRecipes.createFacadeStack(info, hollow));
        }
        return List.copyOf(stacks);
    }

    private static NonNullList<ItemStack> asNonNullList(List<ItemStack> stacks) {
        NonNullList<ItemStack> copy = NonNullList.create();
        copy.addAll(stacks);
        return copy;
    }

    private static class FacadeAssemblyJeiRecipe extends FacadeAssemblyRecipes {
        private static final ResourceLocation ID = new ResourceLocation("buildcraftsilicon", "jei/facades");

        private final List<ItemStack> inputStacks;
        private final List<ItemStack> solidOutputs;
        private final List<ItemStack> hollowOutputs;

        private FacadeAssemblyJeiRecipe() {
            super(ID);
            List<FacadeBlockStateInfo> infos = collectJeiFacadeInfos();
            inputStacks = createFacadeRequirementStacks(infos);
            solidOutputs = createFacadeOutputStacks(infos, false);
            hollowOutputs = createFacadeOutputStacks(infos, true);
        }

        static FacadeAssemblyJeiRecipe create() {
            return new FacadeAssemblyJeiRecipe();
        }

        List<ItemStack> inputStacks() {
            return inputStacks;
        }

        List<ItemStack> solidOutputs() {
            return solidOutputs;
        }

        List<ItemStack> hollowOutputs() {
            return hollowOutputs;
        }

        @Override
        public ChangingItemStack[] getRecipeInputs() {
            ChangingItemStack[] inputs = new ChangingItemStack[2];
            inputs[0] = new ChangingItemStack(createFacadeBaseRequirementStack());
            inputs[1] = inputStacks.isEmpty()
                    ? new ChangingItemStack(ItemStack.EMPTY)
                    : new ChangingItemStack(asNonNullList(inputStacks));
            return inputs;
        }

        @Override
        public ChangingItemStack getRecipeOutputs() {
            if (solidOutputs.isEmpty()) {
                return new ChangingItemStack(ItemStack.EMPTY);
            }
            NonNullList<ItemStack> outputs = NonNullList.create();
            for (int i = 0; i < solidOutputs.size(); i++) {
                outputs.add(solidOutputs.get(i));
                outputs.add(hollowOutputs.get(i));
            }
            ChangingItemStack changing = new ChangingItemStack(outputs);
            changing.setTimeGap(500);
            return changing;
        }

        @Override
        public ItemStack getResultItem(RegistryAccess registryAccess) {
            if (solidOutputs.isEmpty()) {
                return super.getResultItem(registryAccess);
            }
            return solidOutputs.get(0);
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return BCSiliconRecipes.FACADE_SERIALIZER.get();
        }
    }

    private static class AssemblyCategory implements IRecipeCategory<AssemblyRecipeBasic> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftsilicon", "textures/gui/assembly_table.png");
        private static final ResourceLocation JEI_BACKGROUND = new ResourceLocation(
                "buildcraftsilicon", "textures/gui/jei/assembly_table_bc8.png");
        private final IGuiHelper guiHelper;
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawableStatic progressDrawable;
        private final Map<Integer, IDrawableAnimated> progressBars = new HashMap<>();

        AssemblyCategory(IGuiHelper guiHelper) {
            this.guiHelper = guiHelper;
            // BuildCraft 8's JEI category had 10 transparent pixels above the machine crop for the MJ label.
            // Baking that padding into a dedicated texture keeps the modern JEI layout pixel-identical.
            background = guiHelper.createDrawable(JEI_BACKGROUND, 0, 0, 166, 86);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()));
            progressDrawable = guiHelper.createDrawable(TEXTURE, 176, 48, 4, 70);
        }

        @Override
        public RecipeType<AssemblyRecipeBasic> getRecipeType() {
            return ASSEMBLY;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("block.buildcraftsilicon.assembly_table");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, AssemblyRecipeBasic recipe, IFocusGroup focuses) {
            GroupedAssemblyRecipe grouped = GROUPED_ASSEMBLY_RECIPES.get(recipe.getId());
            if (grouped != null) {
                setGroupedAssemblyRecipe(builder, grouped, focuses);
                return;
            }
            if (recipe instanceof FacadeAssemblyJeiRecipe facadeRecipe) {
                setFacadeRecipe(builder, facadeRecipe);
                return;
            }

            ItemStack result = recipe.getResultItem(registryAccess());
            Set<IngredientStack> inputs = recipe.getInputsFor(result);
            int index = 0;
            for (IngredientStack input : inputs) {
                int x = 3 + (index % 3) * 18;
                int y = 12 + (index / 3) * 18;
                builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                        .addItemStacks(expandIngredient(input));
                index++;
            }
            if (inputs.isEmpty()) {
                for (Ingredient input : recipe.getIngredients()) {
                    int x = 3 + (index % 3) * 18;
                    int y = 12 + (index / 3) * 18;
                    builder.addSlot(RecipeIngredientRole.INPUT, x, y)
                            .addItemStacks(expandIngredient(input, 1));
                    index++;
                }
            }
            builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStack(result);
        }

        private void setGroupedAssemblyRecipe(IRecipeLayoutBuilder builder, GroupedAssemblyRecipe grouped,
                IFocusGroup focuses) {
            List<AssemblyRecipeBasic> variants = getFocusedAssemblyVariants(grouped.variants(), focuses);
            switch (grouped.key().kind()) {
                case LENS, LENS_FILTER -> setGroupedLensRecipe(builder, variants, grouped.key().kind() == GroupedAssemblyKind.LENS_FILTER);
                case WIRE -> setGroupedWireRecipe(builder, variants);
                case GATE_BASE -> setGroupedGateBaseRecipe(builder, variants);
                case GATE_MODIFIER -> setGroupedGateModifierRecipe(builder, variants);
            }
        }

        private void setGroupedLensRecipe(IRecipeLayoutBuilder builder, List<AssemblyRecipeBasic> variants, boolean filter) {
            List<ItemStack> glassInputs = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();
            for (AssemblyRecipeBasic variant : variants) {
                ItemStack output = variant.getResultItem(registryAccess());
                outputs.add(output);
                for (IngredientStack input : variant.getInputsFor(output)) {
                    if (!input.ingredient.test(new ItemStack(Items.IRON_BARS))) {
                        List<ItemStack> expanded = expandIngredient(input);
                        if (!expanded.isEmpty()) {
                            glassInputs.add(expanded.get(0));
                            builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStacks(expanded);
                        }
                    }
                }
            }

            IRecipeSlotBuilder glassSlot = builder.addSlot(RecipeIngredientRole.INPUT, 3, 12)
                    .addItemStacks(glassInputs);
            if (filter) {
                builder.addSlot(RecipeIngredientRole.INPUT, 21, 12)
                        .addItemStack(new ItemStack(Items.IRON_BARS));
            }
            IRecipeSlotBuilder outputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStacks(outputs);
            builder.createFocusLink(glassSlot, outputSlot);
        }

        private void setGroupedWireRecipe(IRecipeLayoutBuilder builder, List<AssemblyRecipeBasic> variants) {
            List<ItemStack> dyes = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();
            for (AssemblyRecipeBasic variant : variants) {
                ItemStack output = variant.getResultItem(registryAccess());
                outputs.add(output);
                for (IngredientStack input : variant.getInputsFor(output)) {
                    List<ItemStack> expanded = expandIngredient(input);
                    if (input.ingredient.test(new ItemStack(Items.REDSTONE))) {
                        continue;
                    }
                    if (!expanded.isEmpty()) {
                        dyes.add(expanded.get(0));
                        builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStacks(expanded);
                    }
                }
            }

            builder.addSlot(RecipeIngredientRole.INPUT, 3, 12)
                    .addItemStack(new ItemStack(Items.REDSTONE));
            IRecipeSlotBuilder dyeSlot = builder.addSlot(RecipeIngredientRole.INPUT, 21, 12)
                    .addItemStacks(dyes);
            IRecipeSlotBuilder outputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStacks(outputs);
            builder.createFocusLink(dyeSlot, outputSlot);
        }

        private void setGroupedGateBaseRecipe(IRecipeLayoutBuilder builder, List<AssemblyRecipeBasic> variants) {
            List<List<ItemStack>> inputsBySlot = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();

            for (AssemblyRecipeBasic variant : variants) {
                ItemStack output = variant.getResultItem(registryAccess());
                outputs.add(output);

                List<IngredientStack> orderedInputs = new ArrayList<>(variant.getInputsFor(output));
                orderedInputs.sort(Comparator.comparing(AssemblyCategory::getGateBaseInputSortKey));
                for (int inputIndex = 0; inputIndex < orderedInputs.size(); inputIndex++) {
                    while (inputsBySlot.size() <= inputIndex) {
                        inputsBySlot.add(new ArrayList<>());
                    }

                    List<ItemStack> expanded = expandIngredient(orderedInputs.get(inputIndex));
                    if (!expanded.isEmpty()) {
                        // One visible stack per recipe variant keeps JEI's focus-link columns aligned.
                        // All accepted alternatives remain searchable through the invisible ingredient list.
                        inputsBySlot.get(inputIndex).add(expanded.get(0));
                        builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStacks(expanded);
                    }
                }
            }

            List<IRecipeSlotBuilder> linkedSlots = new ArrayList<>();
            for (int inputIndex = 0; inputIndex < inputsBySlot.size(); inputIndex++) {
                List<ItemStack> inputStacks = inputsBySlot.get(inputIndex);
                IRecipeSlotBuilder inputSlot = builder.addSlot(RecipeIngredientRole.INPUT, 3 + inputIndex * 18, 12)
                        .addItemStacks(inputStacks);
                if (inputStacks.size() == outputs.size()) {
                    linkedSlots.add(inputSlot);
                }
            }

            IRecipeSlotBuilder outputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStacks(outputs);
            if (!outputs.isEmpty() && !inputsBySlot.isEmpty() && linkedSlots.size() == inputsBySlot.size()) {
                linkedSlots.add(outputSlot);
                builder.createFocusLink(linkedSlots.toArray(IRecipeSlotBuilder[]::new));
            }
        }

        private static String getGateBaseInputSortKey(IngredientStack input) {
            ItemStack[] stacks = input.ingredient.getItems();
            if (stacks.length == 0) {
                return "";
            }
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stacks[0].getItem());
            String id = itemId == null ? stacks[0].getDescriptionId() : itemId.toString();
            return id + "#" + input.count;
        }

        private void setGroupedGateModifierRecipe(IRecipeLayoutBuilder builder, List<AssemblyRecipeBasic> variants) {
            List<ItemStack> gates = new ArrayList<>();
            List<ItemStack> modifiers = new ArrayList<>();
            List<ItemStack> outputs = new ArrayList<>();
            for (AssemblyRecipeBasic variant : variants) {
                ItemStack output = variant.getResultItem(registryAccess());
                outputs.add(output);
                for (IngredientStack input : variant.getInputsFor(output)) {
                    List<ItemStack> expanded = expandIngredient(input);
                    if (expanded.isEmpty()) {
                        continue;
                    }
                    ItemStack visible = expanded.get(0);
                    if (visible.getItem() == BCSiliconItems.PLUG_GATE_ITEM.get()) {
                        gates.add(visible);
                    } else {
                        modifiers.add(visible);
                    }
                    builder.addInvisibleIngredients(RecipeIngredientRole.INPUT).addItemStacks(expanded);
                }
            }
            IRecipeSlotBuilder gateSlot = builder.addSlot(RecipeIngredientRole.INPUT, 3, 12)
                    .addItemStacks(gates);
            IRecipeSlotBuilder modifierSlot = builder.addSlot(RecipeIngredientRole.INPUT, 21, 12)
                    .addItemStacks(modifiers);
            IRecipeSlotBuilder outputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStacks(outputs);
            builder.createFocusLink(gateSlot, modifierSlot, outputSlot);
        }

        private void setFacadeRecipe(IRecipeLayoutBuilder builder, FacadeAssemblyJeiRecipe recipe) {
            if (recipe.inputStacks().isEmpty()) {
                return;
            }

            builder.addSlot(RecipeIngredientRole.INPUT, 3, 12)
                    .addItemStack(createFacadeBaseRequirementStack());

            IRecipeSlotBuilder facadeInputSlot = builder.addSlot(RecipeIngredientRole.INPUT, 21, 12)
                    .addItemStacks(recipe.inputStacks());
            IRecipeSlotBuilder solidOutputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 111, 12)
                    .addItemStacks(recipe.solidOutputs());
            IRecipeSlotBuilder hollowOutputSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 129, 12)
                    .addItemStacks(recipe.hollowOutputs());

            // All three cycling lists have exactly the same length/order. JEI can therefore pin the
            // source block and both facade outputs together for R/U focus without thousands of recipes.
            builder.createFocusLink(facadeInputSlot, solidOutputSlot, hollowOutputSlot);
        }

        @Override
        public void draw(AssemblyRecipeBasic recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            GroupedAssemblyRecipe grouped = GROUPED_ASSEMBLY_RECIPES.get(recipe.getId());
            String energyText;
            long animationEnergy;
            if (grouped == null) {
                ItemStack output = recipe.getResultItem(registryAccess());
                animationEnergy = recipe.getRequiredMicroJoulesFor(output);
                energyText = formatMj(animationEnergy);
            } else {
                long min = Long.MAX_VALUE;
                long max = Long.MIN_VALUE;
                for (AssemblyRecipeBasic variant : grouped.variants()) {
                    ItemStack output = variant.getResultItem(registryAccess());
                    long value = variant.getRequiredMicroJoulesFor(output);
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                animationEnergy = max;
                energyText = min == max ? formatMj(min) : formatMj(min) + " - " + formatMj(max);
            }
            getProgressBar(animationEnergy).draw(guiGraphics, 81, 12);
            guiGraphics.drawString(Minecraft.getInstance().font, energyText, 4, 0, 0xFF707070, false);
        }

        private IDrawableAnimated getProgressBar(long microJoules) {
            int ticks = getProgressTicks(microJoules);
            return progressBars.computeIfAbsent(ticks, value -> guiHelper.createAnimatedDrawable(
                    progressDrawable, value, IDrawableAnimated.StartDirection.BOTTOM, false));
        }
    }

    private static class ProgrammingCategory implements IRecipeCategory<ProgrammingRecipeView> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftsilicon", "textures/gui/programming_table.png");
        private static final ResourceLocation JEI_BACKGROUND = new ResourceLocation(
                "buildcraftsilicon", "textures/gui/jei/programming_table_bc7.png");
        private final IGuiHelper guiHelper;
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawableStatic progressDrawable;
        private final IDrawable selectedDrawable;
        private final Map<Integer, IDrawableAnimated> progressBars = new HashMap<>();
        private final List<BoardEntry> optionBoards;

        ProgrammingCategory(IGuiHelper guiHelper) {
            this.guiHelper = guiHelper;
            // Classic programming-table work area. The first ten pixels are intentionally transparent,
            // matching the original recipe-view layout and leaving room for the MJ label in the grid.
            background = guiHelper.createDrawable(JEI_BACKGROUND, 0, 0, 176, 100);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCSiliconItems.PROGRAMMING_TABLE_ITEM.get()));
            progressDrawable = guiHelper.createDrawable(TEXTURE, 176, 18, 4, 70);
            selectedDrawable = guiHelper.createDrawable(TEXTURE, 196, 1, 16, 16);
            optionBoards = List.copyOf(getSortedProgrammingBoards());
        }

        @Override
        public RecipeType<ProgrammingRecipeView> getRecipeType() {
            return PROGRAMMING;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("block.buildcraftsilicon.programming_table");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, ProgrammingRecipeView recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, 8, 28)
                    .addItemStack(recipe.input());
            builder.addSlot(RecipeIngredientRole.OUTPUT, 8, 82)
                    .addItemStack(recipe.output());

            int count = Math.min(optionBoards.size(), TileProgrammingTable_Neptune.OPTION_COUNT);
            for (int index = 0; index < count; index++) {
                int x = 43 + (index % TileProgrammingTable_Neptune.WIDTH) * 18;
                int y = 28 + (index / TileProgrammingTable_Neptune.WIDTH) * 18;
                builder.addSlot(RecipeIngredientRole.RENDER_ONLY, x, y)
                        .addItemStack(ItemRedstoneBoard.createStack(optionBoards.get(index)));
            }
        }

        @Override
        public void draw(ProgrammingRecipeView recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            int selected = optionBoards.indexOf(recipe.board());
            if (selected >= 0 && selected < TileProgrammingTable_Neptune.OPTION_COUNT) {
                int x = 43 + (selected % TileProgrammingTable_Neptune.WIDTH) * 18;
                int y = 28 + (selected / TileProgrammingTable_Neptune.WIDTH) * 18;
                selectedDrawable.draw(guiGraphics, x, y);
            }
            getProgressBar(recipe.requiredMicroJoules()).draw(guiGraphics, 164, 28);
            guiGraphics.drawString(Minecraft.getInstance().font, formatMj(recipe.requiredMicroJoules()), 10, 17, 0xFF707070, false);
        }

        private IDrawableAnimated getProgressBar(long microJoules) {
            int ticks = getProgressTicks(microJoules);
            return progressBars.computeIfAbsent(ticks, value -> guiHelper.createAnimatedDrawable(
                    progressDrawable, value, IDrawableAnimated.StartDirection.BOTTOM, false));
        }
    }

    private static class IntegrationCategory implements IRecipeCategory<IntegrationRecipeView> {
        private static final ResourceLocation TEXTURE = new ResourceLocation(
                "buildcraftsilicon", "textures/gui/jei/integration_table_bc7.png");
        private final IGuiHelper guiHelper;
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawableStatic progressDrawable;
        private final Map<Integer, IDrawableAnimated> progressBars = new HashMap<>();

        IntegrationCategory(IGuiHelper guiHelper) {
            this.guiHelper = guiHelper;
            // Use the exact BuildCraft 7 integration-table JEI crop for the classic layout.
            background = guiHelper.createDrawable(TEXTURE, 17, 22, 153, 71);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCSiliconItems.INTERGRATION_TABLE_ITEM.get()));
            progressDrawable = guiHelper.createDrawable(TEXTURE, 176, 17, 4, 69);
        }

        @Override
        public RecipeType<IntegrationRecipeView> getRecipeType() {
            return INTEGRATION;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("block.buildcraftsilicon.integration_table");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, IntegrationRecipeView recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, 27, 27)
                    .addItemStack(recipe.robotInput());
            builder.addSlot(RecipeIngredientRole.INPUT, 52, 27)
                    .addItemStack(recipe.boardInput());
            builder.addSlot(RecipeIngredientRole.OUTPUT, 121, 27)
                    .addItemStack(recipe.output());
        }

        @Override
        public void draw(IntegrationRecipeView recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            getProgressBar(recipe.requiredMicroJoules()).draw(guiGraphics, 147, 1);
            guiGraphics.drawString(Minecraft.getInstance().font, formatMj(recipe.requiredMicroJoules()), 80, 52, 0xFF707070, false);
        }

        private IDrawableAnimated getProgressBar(long microJoules) {
            int ticks = getProgressTicks(microJoules);
            return progressBars.computeIfAbsent(ticks, value -> guiHelper.createAnimatedDrawable(
                    progressDrawable, value, IDrawableAnimated.StartDirection.BOTTOM, false));
        }
    }

    private static class DistillationCategory implements IRecipeCategory<DistillationRecipeDefinition> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftfactory", "textures/gui/distiller.png");
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawable machineBody;
        private final IDrawableAnimated processAnimation;
        private final IDrawable slot;

        DistillationCategory(IGuiHelper guiHelper) {
            background = guiHelper.createBlankDrawable(118, 65);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCFactoryItems.DISTILLER_BLOCK_ITEM.get()));
            machineBody = guiHelper.createDrawable(TEXTURE, 61, 12, 36, 57);
            IDrawableStatic processOverlay = guiHelper.createDrawable(TEXTURE, 212, 0, 36, 57);
            processAnimation = guiHelper.createAnimatedDrawable(
                    processOverlay, 40, IDrawableAnimated.StartDirection.LEFT, false);
            slot = guiHelper.createDrawable(TEXTURE, 7, 34, 18, 18);
        }

        @Override
        public RecipeType<DistillationRecipeDefinition> getRecipeType() {
            return DISTILLATION;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("block.buildcraftfactory.distiller");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, DistillationRecipeDefinition recipe, IFocusGroup focuses) {
            addFluidSlot(builder, RecipeIngredientRole.INPUT, 1, 26, MachineRecipeApiBridge.representativeInput(recipe.input()), null);
            addFluidSlot(builder, RecipeIngredientRole.OUTPUT, 57, 1, MachineRecipeApiBridge.outputStack(recipe.gasOutput()), null);
            addFluidSlot(builder, RecipeIngredientRole.OUTPUT, 57, 46, MachineRecipeApiBridge.outputStack(recipe.liquidOutput()), null);
        }

        @Override
        public void draw(DistillationRecipeDefinition recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            machineBody.draw(guiGraphics, 20, 4);
            processAnimation.draw(guiGraphics, 20, 4);
            slot.draw(guiGraphics, 0, 25);
            slot.draw(guiGraphics, 56, 0);
            slot.draw(guiGraphics, 56, 45);
            guiGraphics.drawString(Minecraft.getInstance().font, formatMj(recipe.powerRequiredMicroMj()), 78, 28, 0xFF55FFFF, false);
        }
    }

    private static class HeatExchangeCategory implements IRecipeCategory<HeatExchangeRecipeView> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("buildcraftfactory", "textures/gui/heat_exchanger.png");
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawable exchanger;
        private final IDrawable slot;

        HeatExchangeCategory(IGuiHelper guiHelper) {
            background = guiHelper.createBlankDrawable(90, 32);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get()));
            exchanger = guiHelper.createDrawable(TEXTURE, 61, 38, 54, 17);
            slot = guiHelper.createDrawable(TEXTURE, 7, 22, 18, 18);
        }

        @Override
        public RecipeType<HeatExchangeRecipeView> getRecipeType() {
            return HEAT_EXCHANGE;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("block.buildcraftfactory.heat_exchange");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, HeatExchangeRecipeView view, IFocusGroup focuses) {
            addFluidSlot(builder, RecipeIngredientRole.INPUT, 1, 1, MachineRecipeApiBridge.representativeInput(view.recipe().input()), null);
            FluidStack out = MachineRecipeApiBridge.outputStack(view.recipe().output());
            if (out != null && !out.isEmpty()) {
                addFluidSlot(builder, RecipeIngredientRole.OUTPUT, 73, 1, out.copy(), null);
            }
        }

        @Override
        public void draw(HeatExchangeRecipeView recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            slot.draw(guiGraphics, 0, 0);
            exchanger.draw(guiGraphics, 18, 0);
            slot.draw(guiGraphics, 72, 0);
            Component mode = Component.translatable(recipe.heating()
                    ? "buildcraft.jei.heat_exchange.mode.heat"
                    : "buildcraft.jei.heat_exchange.mode.cool");
            guiGraphics.drawString(Minecraft.getInstance().font,
                    Component.translatable("buildcraft.jei.heat_exchange.range", mode,
                            recipe.recipe().heatFrom(), recipe.recipe().heatTo()),
                    1, 21, 0xFF707070, false);
        }
    }

    private static class CombustionFuelCategory implements IRecipeCategory<CombustionFuelRecipeView> {
        private static final ResourceLocation FURNACE_TEXTURE = new ResourceLocation("minecraft", "textures/gui/container/furnace.png");
        private final IDrawable background;
        private final IDrawable icon;
        private final IDrawable furnace;

        CombustionFuelCategory(IGuiHelper guiHelper) {
            background = guiHelper.createBlankDrawable(116, 76);
            icon = guiHelper.createDrawableItemStack(new ItemStack(BCEnergyBlocks.ENGINE_IRON_ITEM.get()));
            furnace = guiHelper.createDrawable(FURNACE_TEXTURE, 55, 38, 18, 32);
        }

        @Override
        public RecipeType<CombustionFuelRecipeView> getRecipeType() {
            return COMBUSTION_FUEL;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("jei.buildcraftenergy.combustion_fuels");
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, CombustionFuelRecipeView recipe, IFocusGroup focuses) {
            addFluidSlot(builder, RecipeIngredientRole.INPUT, 1, 15, recipe.input(), null);
            FluidStack residue = recipe.residue();
            if (!residue.isEmpty()) {
                addFluidSlot(builder, RecipeIngredientRole.OUTPUT, 95, 15, residue, null);
            }
        }

        @Override
        public void draw(CombustionFuelRecipeView recipe, IRecipeSlotsView recipeSlotsView, GuiGraphics guiGraphics, double mouseX, double mouseY) {
            furnace.draw(guiGraphics, 0, 0);
            FuelProfile fuel = recipe.fuel();
            long total = fuel.powerPerTickMicroMj() * (long) fuel.burnTicksPerBucket();
            int seconds = fuel.burnTicksPerBucket() / 20;
            drawSanitizedComponent(guiGraphics, Component.translatable("jei.buildcraftenergy.burn_time", seconds), 24, 8, 0xFF404040);
            drawSanitizedComponent(guiGraphics, Component.translatable("jei.buildcraftenergy.power_per_tick", formatMj(fuel.powerPerTickMicroMj())), 24, 20, 0xFF404040);
            drawSanitizedComponent(guiGraphics, Component.translatable("jei.buildcraftenergy.total_energy", formatMj(total)), 24, 32, 0xFF707070);
        }
    }

    private static int getProgressTicks(long microJoules) {
        long ticks = Math.max(10L, microJoules / MjAmount.MICRO_MJ_PER_MJ / 50L);
        return (int) Math.min(Integer.MAX_VALUE, ticks);
    }

    private static void drawSanitizedComponent(GuiGraphics guiGraphics, Component component, int x, int y, int colour) {
        guiGraphics.drawString(Minecraft.getInstance().font, sanitizeJeiComponent(component), x, y, colour, false);
    }

}
