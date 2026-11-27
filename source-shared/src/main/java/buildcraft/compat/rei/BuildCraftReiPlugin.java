package buildcraft.compat.rei;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fuels.FuelProfile;
import buildcraft.api.v2.recipe.*;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.OilIndustry;
import buildcraft.energy.client.gui.GuiEngineStone_BC8;
import buildcraft.factory.BCFactoryItems;
import buildcraft.factory.client.gui.ScreenHeatExchange;
import buildcraft.factory.container.ContainerAutoCraftItems;
import buildcraft.factory.gui.GuiAutoCraftItems;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.FuelApiBridge;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.ledger.Ledger_Neptune;
import buildcraft.lib.internal.mj.MjFormatting;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.recipe.*;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.item.ItemRedstoneBoard;
import buildcraft.robotics.item.ItemRobot;
import buildcraft.robotics.recipes.RobotIntegrationRecipe;
import buildcraft.silicon.BCSiliconConfig;
import buildcraft.silicon.BCSiliconItems;
import buildcraft.silicon.BCSiliconRecipes;
import buildcraft.silicon.container.ContainerAdvancedCraftingTable;
import buildcraft.silicon.container.ContainerAssemblyTable;
import buildcraft.silicon.gui.*;
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import buildcraft.transport.recipe.PipeRecipe;
import dev.architectury.fluid.FluidStack;
import me.shedaniel.math.Point;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.entry.filtering.base.BasicFilteringRule;
import me.shedaniel.rei.api.client.gui.Renderer;
import me.shedaniel.rei.api.client.gui.widgets.Widget;
import me.shedaniel.rei.api.client.gui.widgets.Widgets;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.category.CategoryRegistry;
import me.shedaniel.rei.api.client.registry.display.DisplayCategory;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.registry.entry.EntryRegistry;
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.client.registry.transfer.simple.SimpleTransferHandler;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.display.basic.BasicDisplay;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.util.EntryIngredients;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.plugin.common.BuiltinPlugin;
import net.minecraft.client.Minecraft;
//? if <1.19.3 {
import net.minecraft.core.Registry;
//?} else {
import net.minecraft.core.registries.BuiltInRegistries;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
//? if >=1.21.11 {
import buildcraft.silicon.BCSiliconRecipeSync;
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Optional native REI client integration. No JEI classes are referenced. */
//? if forge || neoforge {
@me.shedaniel.rei.forge.REIPluginClient
//?}
public final class BuildCraftReiPlugin implements REIClientPlugin {
    private static final CategoryIdentifier<MachineDisplay> ASSEMBLY = id("silicon", "assembly");
    private static final CategoryIdentifier<MachineDisplay> PROGRAMMING = id("silicon", "programming");
    private static final CategoryIdentifier<MachineDisplay> INTEGRATION = id("silicon", "integration");
    private static final CategoryIdentifier<MachineDisplay> DISTILLATION = id("factory", "distillation");
    private static final CategoryIdentifier<MachineDisplay> HEAT_EXCHANGE = id("factory", "heat_exchange");
    private static final CategoryIdentifier<MachineDisplay> COMBUSTION_FUEL = id("energy", "combustion_fuel");

    private static CategoryIdentifier<MachineDisplay> id(String module, String path) {
        return CategoryIdentifier.of("buildcraft" + module, path);
    }

    private static final class MachineDisplay extends BasicDisplay {
        private final CategoryIdentifier<MachineDisplay> category;
        private final List<Component> details;

        MachineDisplay(CategoryIdentifier<MachineDisplay> category, List<EntryIngredient> inputs,
                List<EntryIngredient> outputs, Component... details) {
            super(List.copyOf(inputs), List.copyOf(outputs));
            this.category = category;
            this.details = List.of(details);
        }

        @Override public CategoryIdentifier<MachineDisplay> getCategoryIdentifier() { return category; }
        //? if >=1.21.11 {
        // Dynamic local displays are not sent over REI's recipe-sync channel.
        @Override public me.shedaniel.rei.api.common.display.DisplaySerializer<? extends me.shedaniel.rei.api.common.display.Display> getSerializer() { return null; }
        //?}
    }

    private static final class MachineCategory implements DisplayCategory<MachineDisplay> {
        private final CategoryIdentifier<MachineDisplay> id;
        private final String title;
        private final ItemStack machine;
        private final int height;

        MachineCategory(CategoryIdentifier<MachineDisplay> id, String title, ItemStack machine, int height) {
            this.id = id;
            this.title = title;
            this.machine = machine;
            this.height = height;
        }
        @Override public CategoryIdentifier<MachineDisplay> getCategoryIdentifier() { return id; }
        @Override public Component getTitle() { return Component.translatable(title); }
        @Override public Renderer getIcon() { return EntryStacks.of(machine); }
        @Override public int getDisplayWidth(MachineDisplay display) { return 158; }
        @Override public int getDisplayHeight() { return height; }

        @Override public List<Widget> setupDisplay(MachineDisplay display, Rectangle bounds) {
            List<Widget> widgets = new ArrayList<>();
            widgets.add(Widgets.createRecipeBase(bounds));
            // Assembly has up to twelve inputs. The other machines use the same
            // slot renderer for items and real fluid stacks, with exact amounts.
            int columns = id.equals(ASSEMBLY) || id.equals(INTEGRATION) ? 4 : 3;
            int rows = Math.max(1, (display.getInputEntries().size() + columns - 1) / columns);
            for (int i = 0; i < display.getInputEntries().size(); i++) {
                widgets.add(Widgets.createSlot(new Point(bounds.x + 7 + (i % columns) * 18,
                    bounds.y + 7 + (i / columns) * 18)).entries(display.getInputEntries().get(i)).markInput());
            }
            widgets.add(Widgets.createArrow(new Point(bounds.x + 86, bounds.y + 7)));
            for (int i = 0; i < display.getOutputEntries().size(); i++) {
                widgets.add(Widgets.createSlot(new Point(bounds.x + 120 + (i % 2) * 18,
                    bounds.y + 7 + (i / 2) * 18)).entries(display.getOutputEntries().get(i)).markOutput());
            }
            int y = bounds.y + 11 + Math.max(rows, (display.getOutputEntries().size() + 1) / 2) * 18;
            for (Component detail : display.details) {
                widgets.add(Widgets.createLabel(new Point(bounds.x + bounds.width / 2, y), detail));
                y += 12;
            }
            return widgets;
        }
    }

    //? if <1.21.11 {
    @Override public void registerItemComparators(me.shedaniel.rei.api.common.entry.comparison.ItemComparatorRegistry registry) {
        ReiItemComparators.register(registry);
    }
    //?}

    @Override public void registerCategories(CategoryRegistry registry) {
        category(registry, ASSEMBLY, "block.buildcraftsilicon.assembly_table", new ItemStack(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()), 88);
        category(registry, PROGRAMMING, "block.buildcraftsilicon.programming_table", new ItemStack(BCSiliconItems.PROGRAMMING_TABLE_ITEM.get()), 56);
        category(registry, INTEGRATION, "block.buildcraftsilicon.integration_table", new ItemStack(BCSiliconItems.INTERGRATION_TABLE_ITEM.get()), 88);
        category(registry, DISTILLATION, "block.buildcraftfactory.distiller", new ItemStack(BCFactoryItems.DISTILLER_BLOCK_ITEM.get()), 56);
        category(registry, HEAT_EXCHANGE, "block.buildcraftfactory.heat_exchange", new ItemStack(BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get()), 56);
        category(registry, COMBUSTION_FUEL, "jei.buildcraftenergy.combustion_fuels", new ItemStack(BCEnergyBlocks.ENGINE_IRON_ITEM.get()), 76);
        registry.addWorkstations(BuiltinPlugin.CRAFTING, EntryStacks.of(BCFactoryItems.AUTO_BENCH_ITEM.get()),
            EntryStacks.of(BCSiliconItems.ADVANCED_CRAFTING_TABLE_ITEM.get()));
        registry.addWorkstations(BuiltinPlugin.FUEL, EntryStacks.of(BCEnergyBlocks.ENGINE_STONE_ITEM.get()));
    }

    private static void category(CategoryRegistry registry, CategoryIdentifier<MachineDisplay> id, String title,
            ItemStack machine, int height) {
        registry.add(new MachineCategory(id, title, machine, height));
        registry.addWorkstations(id, EntryStacks.of(machine));
    }

    @Override public void registerDisplays(DisplayRegistry registry) {
        //? if <1.21.11 {
        // Legacy Minecraft has no SlotDisplay representation of the custom pipe serializer.
        registry.registerRecipeFiller(PipeRecipe.class, net.minecraft.world.item.crafting.RecipeType.CRAFTING, holder -> {
            //? if <1.21 {
            PipeRecipe recipe = holder;
            //?} else {
            PipeRecipe recipe = holder.value();
            //?}
            var inputs = EntryIngredients.ofIngredients(recipe.getIngredients());
            var outputs = List.of(EntryIngredients.of(recipe.getDisplayResult()));
            return recipe.hasShapedBasePattern()
                ? new me.shedaniel.rei.plugin.common.displays.crafting.DefaultCustomShapedDisplay(holder, inputs, outputs, 3, 1)
                : new me.shedaniel.rei.plugin.common.displays.crafting.DefaultCustomShapelessDisplay(holder, inputs, outputs);
        });
        //?}
        // Newer versions expose native shaped/shapeless recipe displays in PipeRecipe.display().
        dynamic(registry, ASSEMBLY, BuildCraftReiPlugin::assembly);
        dynamic(registry, PROGRAMMING, BuildCraftReiPlugin::programming);
        dynamic(registry, INTEGRATION, BuildCraftReiPlugin::integration);
        dynamic(registry, DISTILLATION, BuildCraftReiPlugin::distillation);
        dynamic(registry, HEAT_EXCHANGE, BuildCraftReiPlugin::heatExchange);
        dynamic(registry, COMBUSTION_FUEL, BuildCraftReiPlugin::fuels);
    }

    // Query the live catalog instead of capturing recipes during REI startup:
    // remote recipes, deny rules and oil mode can arrive after plugin registration.
    private static void dynamic(DisplayRegistry registry, CategoryIdentifier<MachineDisplay> id,
            Supplier<List<MachineDisplay>> source) {
        registry.registerDisplayGenerator(id, new DynamicDisplayGenerator<MachineDisplay>() {
            @Override public Optional<List<MachineDisplay>> getRecipeFor(EntryStack<?> entry) {
                return Optional.of(source.get().stream().filter(d -> matches(d.getOutputEntries(), entry)).toList());
            }
            @Override public Optional<List<MachineDisplay>> getUsageFor(EntryStack<?> entry) {
                return Optional.of(source.get().stream().filter(d -> matches(d.getInputEntries(), entry)).toList());
            }
            @Override public Optional<List<MachineDisplay>> generate(ViewSearchBuilder builder) {
                if (!builder.getCategories().contains(id)) return Optional.empty();
                if (!builder.getRecipesFor().isEmpty() || !builder.getUsagesFor().isEmpty()) return Optional.empty();
                return Optional.of(source.get());
            }
        });
    }

    private static boolean matches(List<EntryIngredient> entries, EntryStack<?> focus) {
        for (EntryIngredient ingredient : entries) for (EntryStack<?> candidate : ingredient) {
            Object a = candidate.getValue(), b = focus.getValue();
            if (a instanceof ItemStack first && b instanceof ItemStack second) {
                if (first.getItem() != second.getItem()) continue;
                if (first.getItem() == BCRoboticsItems.REDSTONE_BOARD.get()) {
                    if (BCRoboticsBoards.getBoard(first).key().equals(BCRoboticsBoards.getBoard(second).key())) return true;
                } else if (first.getItem() == BCRoboticsItems.ROBOT.get()) {
                    if (BCRoboticsBoards.getRobotBoard(first).key().equals(BCRoboticsBoards.getRobotBoard(second).key())) return true;
                } else if (first.getItem() == BCSiliconItems.PLUG_GATE_ITEM.get()) {
                    if (ItemPluggableGate.getVariant(first).material == ItemPluggableGate.getVariant(second).material) return true;
                } else if (first.getItem() == BCSiliconItems.PLUG_LENS_ITEM.get()) {
                    if (ItemPluggableLens.getData(first).getItemDamage() == ItemPluggableLens.getData(second).getItemDamage()) return true;
                } else if (first.getItem() == BCSiliconItems.PLUG_FACADE_ITEM.get()) {
                    if (CountedIngredient.sameItemAndData(first, second)) return true;
                } else if (EntryStacks.equalsFuzzy(candidate, focus)) return true;
            } else if (a instanceof FluidStack fluid && b instanceof ItemStack item) {
                if (fluid.getFluid().getBucket() == item.getItem()) return true;
            } else if (EntryStacks.equalsFuzzy(candidate, focus)) return true;
        }
        return false;
    }

    private static EntryIngredient items(Ingredient ingredient, int count) {
        var entries = EntryIngredient.builder();
        for (EntryStack<?> entry : EntryIngredients.ofIngredient(ingredient)) {
            if (!(entry.getValue() instanceof ItemStack stack) || stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            copy.setCount(Math.min(Math.max(1, count), copy.getMaxStackSize()));
            entries.add(EntryStacks.of(copy));
        }
        return entries.build();
    }

    private static Component energy(long microMj) {
        return BCLibConfig.hidePowerValues ? Component.translatable("buildcraft.value.hidden")
            : Component.literal(MjFormatting.formatMicroMj(Math.max(0, microMj)) + " MJ");
    }

    private static List<MachineDisplay> assembly() {
        var result = new ArrayList<MachineDisplay>();
        var level = Minecraft.getInstance().level;
        if (level == null) return result;
        //? if <1.21 {
        for (AssemblyRecipeBasic recipe : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", recipe.getId())) addAssembly(result, recipe);
        }
        //?} else if <1.21.11 {
        for (var holder : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", holder.id())) addAssembly(result, holder.value());
        }
        //?} else {
        for (var holder : BCSiliconRecipeSync.Client.assemblyRecipes()) {
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", holder.id().identifier())) addAssembly(result, holder.value());
        }
        //?}
        for (var entry : BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).assemblyRecipes()) {
            if (ClientMachineRecipeCatalog.isDenied("assembly_table", entry.id())) continue;
            var adapter = ApiAssemblyRecipeAdapter.fromService(entry.id());
            if (adapter != null) addAssembly(result, adapter);
        }
        return result;
    }

    private static List<buildcraft.silicon.plug.FacadeBlockStateInfo> cachedFacadeInfos = List.of();
    private static List<MachineDisplay> cachedFacadeDisplays = List.of();
    private static AssemblyRecipeBasic cachedFacadeRecipe;
    private static boolean cachedHidePower;

    private static void addAssembly(List<MachineDisplay> result, AssemblyRecipeBasic recipe) {
        if (recipe instanceof FacadeAssemblyRecipes) {
            if (!BCSiliconConfig.enableFacades) return;
            // Keep each facade's material linked to its own output. Independent cycling
            // of input and output slots would otherwise advertise impossible combinations.
            var infos = FacadeStateManager.getIndexedStates();
            if (cachedFacadeRecipe != recipe || !cachedFacadeInfos.equals(infos) || cachedHidePower != BCLibConfig.hidePowerValues) {
                List<MachineDisplay> built = new ArrayList<>();
                for (var info : infos) {
                    if (!info.isVisible || info.requiredStack.isEmpty()) continue;
                    addAssemblyOutput(built, recipe, FacadeAssemblyRecipes.createFacadeStack(info, false));
                    addAssemblyOutput(built, recipe, FacadeAssemblyRecipes.createFacadeStack(info, true));
                }
                cachedFacadeInfos = List.copyOf(infos);
                cachedFacadeDisplays = List.copyOf(built);
                cachedFacadeRecipe = recipe;
                cachedHidePower = BCLibConfig.hidePowerValues;
            }
            result.addAll(cachedFacadeDisplays);
        } else {
            for (ItemStack output : assemblyOutputs(recipe)) addAssemblyOutput(result, recipe, output);
        }
    }

    /** The legacy fixed-output recipe exposes its result through Recipe#getResultItem.
     * Its optional multi-output preview set can legitimately be empty. */
    private static List<ItemStack> assemblyOutputs(AssemblyRecipeBasic recipe) {
        List<ItemStack> outputs = recipe.getOutputPreviews().stream()
            .filter(stack -> stack != null && !stack.isEmpty()).toList();
        //? if <1.21.11 {
        if (outputs.isEmpty()) {
            //? if <1.20 {
            ItemStack result = recipe.getResultItem();
            //?} else {
            var level = Minecraft.getInstance().level;
            if (level == null) return outputs;
            ItemStack result = recipe.getResultItem(level.registryAccess());
            //?}
            if (result != null && !result.isEmpty()) return List.of(result);
        }
        //?}
        return outputs;
    }

    private static void addAssemblyOutput(List<MachineDisplay> result, AssemblyRecipeBasic recipe, ItemStack output) {
        if (output == null || output.isEmpty()) return;
        List<EntryIngredient> inputs = new ArrayList<>();
        for (var input : recipe.getInputsFor(output)) inputs.add(items(input.ingredient, input.count));
        if (!inputs.isEmpty() && inputs.stream().noneMatch(List::isEmpty)) {
            result.add(new MachineDisplay(ASSEMBLY, inputs, List.of(EntryIngredients.of(output.copy())),
                energy(recipe.getRequiredMicroJoulesFor(output))));
        }
    }

    private static List<MachineDisplay> programming() {
        BCRoboticsBoards.init();
        List<MachineDisplay> result = new ArrayList<>();
        for (var definition : ProgrammingRecipeAccess.options(true)) {
            var board = BCRoboticsBoards.getByKey(definition.boardKey());
            if (board == BCRoboticsBoards.EMPTY) continue;
            result.add(new MachineDisplay(PROGRAMMING, List.of(EntryIngredients.of(BCRoboticsItems.REDSTONE_BOARD.get())),
                List.of(EntryIngredients.of(ItemRedstoneBoard.createStack(board))), energy(definition.requiredMicroJoules())));
        }
        return result;
    }

    private static List<MachineDisplay> integration() {
        BCRoboticsBoards.init();
        List<MachineDisplay> result = new ArrayList<>();
        var robotId = ResourceLocation.tryParse("buildcraftrobotics:integration_table/robot_board_integration");
        if (!ClientMachineRecipeCatalog.isDenied("integration_table", robotId)) {
            var recipe = ClientMachineRecipeCatalog.visibleDefinition("integration_table", robotId);
            if (!(recipe instanceof IntegrationRecipeDefinition)) {
                recipe = BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).snapshot().resolved(robotId)
                    .map(entry -> entry.value()).orElse(null);
            }
            if (recipe instanceof RobotIntegrationRecipe robotRecipe) for (var board : BCRoboticsBoards.robotEntries()) {
                var output = ItemRobot.createRobotStack(board, EntityRobotBase.SAFETY_ENERGY);
                result.add(new MachineDisplay(INTEGRATION, List.of(EntryIngredients.of(BCRoboticsItems.ROBOT.get()),
                    EntryIngredients.of(ItemRedstoneBoard.createStack(board))), List.of(EntryIngredients.of(output)),
                    energy(robotRecipe.requiredMicroJoules(output))));
            }
        }
        for (StaticIntegrationRecipe recipe : ClientMachineRecipeCatalog.visibleRecipes(StaticIntegrationRecipe.class)) {
            List<EntryIngredient> inputs = new ArrayList<>();
            var center = recipe.centerIngredient();
            inputs.add(items(center.ingredient(), center.count()));
            for (var input : recipe.requirements(recipe.result())) inputs.add(items(input.ingredient(), input.count()));
            result.add(new MachineDisplay(INTEGRATION, inputs, List.of(EntryIngredients.of(recipe.result())),
                energy(recipe.requiredMicroJoules(recipe.result()))));
        }
        return result;
    }

    //? if fabric {
    private static EntryIngredient fluids(buildcraft.lib.fluid.FluidStack stack) {
    //?} else if forge {
    private static EntryIngredient fluids(net.minecraftforge.fluids.FluidStack stack) {
    //?} else {
    private static EntryIngredient fluids(net.neoforged.neoforge.fluids.FluidStack stack) {
    //?}
        if (stack == null || stack.isEmpty()) return EntryIngredient.empty();
        var variants = FluidCompatRegistry.getEquivalentStacks(stack, "buildcraftenergy");
        var entries = EntryIngredient.builder();
        for (var variant : variants.isEmpty() ? List.of(stack) : variants) {
            entries.add(EntryStacks.of(variant.getFluid(), (long) variant.getAmount() * FluidStack.bucketAmount() / 1000));
        }
        return entries.build();
    }

    private static List<EntryIngredient> nonEmpty(EntryIngredient... entries) {
        return java.util.Arrays.stream(entries).filter(e -> !e.isEmpty()).toList();
    }

    private static List<MachineDisplay> distillation() {
        return ClientMachineRecipeCatalog.visibleRecipes(DistillationRecipeDefinition.class).stream().map(recipe ->
            new MachineDisplay(DISTILLATION, nonEmpty(fluids(MachineRecipeApiBridge.representativeInput(recipe.input()))),
                nonEmpty(fluids(MachineRecipeApiBridge.outputStack(recipe.gasOutput())),
                    fluids(MachineRecipeApiBridge.outputStack(recipe.liquidOutput()))), energy(recipe.powerRequiredMicroMj())))
            .filter(d -> !d.getInputEntries().isEmpty() && !d.getOutputEntries().isEmpty()).toList();
    }

    private static List<MachineDisplay> heatExchange() {
        return ClientMachineRecipeCatalog.visibleRecipes(HeatExchangeRecipeDefinition.class).stream()
            .filter(recipe -> !recipe.output().isEmpty()).map(recipe -> new MachineDisplay(HEAT_EXCHANGE,
                nonEmpty(fluids(MachineRecipeApiBridge.representativeInput(recipe.input()))),
                nonEmpty(fluids(MachineRecipeApiBridge.outputStack(recipe.output()))),
                Component.translatable("buildcraft.jei.heat_exchange.range", Component.translatable(recipe.kind() == RecipeDefinition.Kind.HEATING
                    ? "buildcraft.jei.heat_exchange.mode.heat" : "buildcraft.jei.heat_exchange.mode.cool"), recipe.heatFrom(), recipe.heatTo())))
            .filter(d -> !d.getInputEntries().isEmpty() && !d.getOutputEntries().isEmpty()).toList();
    }

    private static List<MachineDisplay> fuels() {
        List<MachineDisplay> result = new ArrayList<>();
        for (var match : BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS).fuels()) {
            FuelProfile fuel = match.profile();
            var variants = EntryIngredient.builder();
            if (fuel.representativeVariant().isPresent()) {
                variants.addAll(fluids(FuelApiBridge.stackOfVariant(fuel.representativeVariant().get(), 1000)));
            } else {
                int matched = 0;
                for (Fluid fluid : fluidRegistry()) {
                    if (matched >= 64) break;
                    if (fluid == Fluids.EMPTY) continue;
                    var variant = FluidVariant.of(fluidRegistry().getKey(fluid));
                    try {
                        if (!fuel.matcher().matches(variant, FuelApiBridge.MATCH_CONTEXT)) continue;
                    } catch (RuntimeException invalidMatcher) { continue; }
                    variants.addAll(fluids(FuelApiBridge.stackOfVariant(variant, 1000)));
                    matched++;
                }
            }
            var input = variants.build();
            if (input.isEmpty()) continue;
            result.add(new MachineDisplay(COMBUSTION_FUEL, List.of(input), nonEmpty(fluids(FuelApiBridge.stackOf(fuel.residuePerBucket()))),
                Component.translatable("jei.buildcraftenergy.burn_time", fuel.burnTicksPerBucket() / 20),
                Component.translatable("jei.buildcraftenergy.power_output", BCLibConfig.hidePowerValues
                    ? Component.translatable("buildcraft.value.hidden") : LocaleUtil.localizeMjFlow(fuel.powerPerTickMicroMj())),
                Component.translatable("jei.buildcraftenergy.total_energy", energy(fuel.powerPerTickMicroMj() * (long) fuel.burnTicksPerBucket()))));
        }
        return result;
    }

    private static net.minecraft.core.Registry<Fluid> fluidRegistry() {
        //? if <1.19.3 {
        return Registry.FLUID;
        //?} else {
        return BuiltInRegistries.FLUID;
        //?}
    }

    private static ResourceLocation itemId(ItemStack stack) {
        //? if <1.19.3 {
        return Registry.ITEM.getKey(stack.getItem());
        //?} else {
        return BuiltInRegistries.ITEM.getKey(stack.getItem());
        //?}
    }

    @Override public void registerBasicEntryFiltering(BasicFilteringRule<?> rule) {
        var dirty = rule.hide(() -> EntryRegistry.getInstance().getPreFilteredList().stream().filter(entry -> {
            Object value = entry.getValue();
            if (value instanceof ItemStack item) {
                var id = itemId(item);
                return id != null && !OilIndustry.bucketEnabled(id.getNamespace(), id.getPath());
            }
            if (value instanceof FluidStack fluid) {
                var id = fluidRegistry().getKey(fluid.getFluid());
                return id != null && !OilIndustry.fluidEnabled(id.getNamespace(), id.getPath());
            }
            return false;
        }).collect(Collectors.toList()));
        ClientMachineRecipeCatalog.setReiListener(() -> {
            cachedFacadeRecipe = null;
            cachedFacadeInfos = List.of();
            cachedFacadeDisplays = List.of();
            dirty.markDirty();
        });
    }

    @Override public void registerScreens(ScreenRegistry registry) {
        registry.registerContainerClickArea(new Rectangle(83, 35, 10, 72), GuiAssemblyTable.class, ASSEMBLY);
        registry.registerContainerClickArea(new Rectangle(160, 35, 12, 72), GuiProgrammingTable.class, PROGRAMMING);
        registry.registerContainerClickArea(new Rectangle(160, 21, 12, 72), GuiIntegrationTable.class, INTEGRATION);
        registry.registerContainerClickArea(new Rectangle(93, 32, 23, 16), GuiAdvancedCraftingTable.class, BuiltinPlugin.CRAFTING);
        registry.registerContainerClickArea(new Rectangle(90, 47, 23, 10), GuiAutoCraftItems.class, BuiltinPlugin.CRAFTING);
        registry.registerContainerClickArea(new Rectangle(73, 36, 30, 21), ScreenHeatExchange.class, HEAT_EXCHANGE);
        registry.registerContainerClickArea(new Rectangle(81, 25, 14, 14), GuiEngineStone_BC8.class, BuiltinPlugin.FUEL);
    }

    @Override public void registerExclusionZones(ExclusionZones zones) {
        zones.register(GuiBC8.class, screen -> {
            List<Rectangle> result = new ArrayList<>();
            for (Object element : screen.mainGui.shownElements) if (element instanceof Ledger_Neptune ledger) {
                int x = (int) Math.floor(ledger.getX()) - 1, y = (int) Math.floor(ledger.getY()) - 1;
                int width = (int) Math.ceil(ledger.getX() + ledger.getWidth()) + 1 - x;
                int height = (int) Math.ceil(ledger.getY() + ledger.getHeight()) + 1 - y;
                if (width > 0 && height > 0) result.add(new Rectangle(x, y, width, height));
            }
            return result;
        });
    }

    @Override public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(SimpleTransferHandler.create(ContainerAssemblyTable.class, ASSEMBLY,
            new SimpleTransferHandler.IntRange(36, 48)));
        registry.register(context -> {
            if (!BuiltinPlugin.CRAFTING.equals(context.getDisplay().getCategoryIdentifier())) return TransferHandler.Result.createNotApplicable();
            if (!(context.getMenu() instanceof ContainerAutoCraftItems) && !(context.getMenu() instanceof ContainerAdvancedCraftingTable)) {
                return TransferHandler.Result.createNotApplicable();
            }
            List<ItemStack> grid = phantomGrid(context.getDisplay());
            if (grid == null) return TransferHandler.Result.createFailed(Component.translatable("buildcraft.rei.transfer.unsupported_grid"));
            if (context.isActuallyCrafting()) {
                if (context.getMenu() instanceof ContainerAutoCraftItems menu) menu.sendSetPhantomSlots(menu.blueprintInv, grid);
                if (context.getMenu() instanceof ContainerAdvancedCraftingTable menu) menu.sendSetPhantomSlots(menu.blueprintInv, grid);
            }
            return TransferHandler.Result.createSuccessful();
        });
    }

    private static List<ItemStack> phantomGrid(me.shedaniel.rei.api.common.display.Display display) {
        // The default crafting display supplies the real recipe dimensions, including
        // empty shaped slots. Never flatten a shaped recipe into a different grid.
        int width = 3;
        int height = 3;
        if (display instanceof me.shedaniel.rei.api.common.display.SimpleGridMenuDisplay shaped) {
            width = shaped.getInputWidth(3, 3);
            height = shaped.getInputHeight(3, 3);
        } else return null;
        if (width < 1 || width > 3 || height < 1 || height > 3 || display.getInputEntries().size() > width * height) return null;
        List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < display.getInputEntries().size(); i++) {
            EntryIngredient ingredient = display.getInputEntries().get(i);
            if (ingredient.isEmpty()) continue;
            ItemStack item = ingredient.stream().map(EntryStack::getValue).filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast).filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            if (item.isEmpty()) return null;
            ItemStack copy = item.copy();
            copy.setCount(1);
            grid.set((i / width) * 3 + i % width, copy);
        }
        return grid;
    }
}
