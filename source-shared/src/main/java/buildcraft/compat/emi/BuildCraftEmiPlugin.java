/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.compat.emi;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fuels.FuelProfile;
import buildcraft.api.v2.recipe.*;
import buildcraft.energy.BCEnergyBlocks;
import buildcraft.energy.OilIndustry;
import buildcraft.factory.BCFactoryItems;
import buildcraft.factory.container.ContainerAutoCraftItems;
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
import buildcraft.silicon.item.ItemPluggableGate;
import buildcraft.silicon.item.ItemPluggableLens;
import buildcraft.silicon.plug.FacadeStateManager;
import buildcraft.silicon.recipe.FacadeAssemblyRecipes;
import buildcraft.transport.recipe.PipeRecipe;
import buildcraft.factory.BCFactoryGuis;
import buildcraft.silicon.BCSiliconGuis;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.TextWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if <1.19.3 {
import net.minecraft.core.Registry;
//?} else {
import net.minecraft.core.registries.BuiltInRegistries;
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
//? if >=1.21.11 {
import buildcraft.silicon.BCSiliconRecipeSync;
//?}

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Optional native EMI integration; never loads JEI or REI classes. */
@EmiEntrypoint
public final class BuildCraftEmiPlugin implements EmiPlugin {
    // Lazily initialized during EMI registration, after Minecraft's item registries.
    private EmiRecipeCategory assembly, programming, integration, distillation, heatExchange, combustion;

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.tryParse(namespace + ":" + path);
    }

    private static EmiRecipeCategory category(EmiRegistry registry, String module, String path, String title, ItemStack machine) {
        EmiStack icon = EmiStack.of(machine);
        EmiRecipeCategory category = new EmiRecipeCategory(id("buildcraft" + module, path), icon) {
            @Override public Component getName() { return Component.translatable(title); }
        };
        registry.addCategory(category);
        registry.addWorkstation(category, icon);
        return category;
    }

    /** Keep IDs stable across reloads, language changes and the power-value privacy setting. */
    private static ResourceLocation synthetic(EmiRecipeCategory category, ResourceLocation source, String variant) {
        String key = UUID.nameUUIDFromBytes(variant.getBytes(StandardCharsets.UTF_8)).toString();
        return id(category.getId().getNamespace(), "/emi/" + category.getId().getPath() + "/"
            + source.getNamespace() + "/" + source.getPath() + "/" + key);
    }

    private static String outputKey(ItemStack stack) {
        //? if <1.21 {
        return itemId(stack) + ":" + stack.getCount() + ":" + stack.getTag();
        //?} else {
        return itemId(stack) + ":" + stack.getCount() + ":" + stack.getComponentsPatch();
        //?}
    }

    private static final class MachineRecipe implements EmiRecipe {
        private final EmiRecipeCategory category;
        private final ResourceLocation id;
        private final List<EmiIngredient> inputs;
        private final List<EmiStack> outputs;
        private final List<Component> details;
        private final boolean tree;

        MachineRecipe(EmiRecipeCategory category, ResourceLocation source, String variant, List<EmiIngredient> inputs,
                List<EmiStack> outputs, boolean tree, Component... details) {
            this.category = category;
            this.id = synthetic(category, source, variant);
            this.inputs = List.copyOf(inputs);
            this.outputs = List.copyOf(outputs);
            this.details = List.of(details);
            this.tree = tree;
        }
        @Override public EmiRecipeCategory getCategory() { return category; }
        @Override public ResourceLocation getId() { return id; }
        @Override public List<EmiIngredient> getInputs() { return inputs; }
        @Override public List<EmiStack> getOutputs() { return outputs; }
        @Override public boolean supportsRecipeTree() { return tree && !outputs.isEmpty(); }
        @Override public int getDisplayWidth() { return 158; }
        private int rows() { return Math.max(1, Math.max((inputs.size() + 3) / 4, (outputs.size() + 1) / 2)); }
        @Override public int getDisplayHeight() { return rows() * 18 + 5 + details.size() * 12; }
        @Override public void addWidgets(WidgetHolder widgets) {
            for (int i = 0; i < inputs.size(); i++) {
                widgets.addSlot(inputs.get(i), 2 + i % 4 * 18, i / 4 * 18);
            }
            widgets.addTexture(EmiTexture.EMPTY_ARROW, 82, 0);
            for (int i = 0; i < outputs.size(); i++) {
                widgets.addSlot(outputs.get(i), 119 + i % 2 * 18, i / 2 * 18).recipeContext(this);
            }
            int y = rows() * 18 + 4;
            for (Component detail : details) {
                // Full localized detail also remains available as a tooltip when a label is long.
                widgets.addText(detail, 79, y, 0x404040, false).horizontalAlign(TextWidget.Alignment.CENTER);
                widgets.addTooltipText(List.of(detail), 0, y, 158, 11);
                y += 12;
            }
        }
    }

    @Override public void register(EmiRegistry registry) {
        assembly = category(registry, "silicon", "assembly", "block.buildcraftsilicon.assembly_table", new ItemStack(BCSiliconItems.ASSEMBLY_TABLE_ITEM.get()));
        programming = category(registry, "silicon", "programming", "block.buildcraftsilicon.programming_table", new ItemStack(BCSiliconItems.PROGRAMMING_TABLE_ITEM.get()));
        integration = category(registry, "silicon", "integration", "block.buildcraftsilicon.integration_table", new ItemStack(BCSiliconItems.INTERGRATION_TABLE_ITEM.get()));
        distillation = category(registry, "factory", "distillation", "block.buildcraftfactory.distiller", new ItemStack(BCFactoryItems.DISTILLER_BLOCK_ITEM.get()));
        heatExchange = category(registry, "factory", "heat_exchange", "block.buildcraftfactory.heat_exchange", new ItemStack(BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get()));
        combustion = category(registry, "energy", "combustion_fuel", "jei.buildcraftenergy.combustion_fuels", new ItemStack(BCEnergyBlocks.ENGINE_IRON_ITEM.get()));
        registry.addWorkstation(VanillaEmiRecipeCategories.CRAFTING, EmiStack.of(BCFactoryItems.AUTO_BENCH_ITEM.get()));
        registry.addWorkstation(VanillaEmiRecipeCategories.CRAFTING, EmiStack.of(BCSiliconItems.ADVANCED_CRAFTING_TABLE_ITEM.get()));
        registry.addWorkstation(VanillaEmiRecipeCategories.FUEL, EmiStack.of(BCEnergyBlocks.ENGINE_STONE_ITEM.get()));
        registerComparisons(registry);
        registerTransfers(registry);
        registerExclusions(registry);
        registry.removeEmiStacks(BuildCraftEmiPlugin::hiddenOil);
        // Native catalogs are rebuilt when server recipe data or oil mode changes.
        ClientMachineRecipeCatalog.setEmiListener(BuildCraftEmiPlugin::refresh);
        registerPipeRecipes(registry);
        registry.addDeferredRecipes(add -> {
            Map<ResourceLocation, MachineRecipe> recipes = new LinkedHashMap<>();
            addAssemblyRecipes(recipes);
            addProgrammingRecipes(recipes);
            addIntegrationRecipes(recipes);
            addDistillationRecipes(recipes);
            addHeatExchangeRecipes(recipes);
            addFuels(recipes);
            recipes.values().forEach(add);
        });
    }

    private static void refresh() {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null) EmiReloadManager.reload();
    }

    private static void registerComparisons(EmiRegistry registry) {
        registry.setDefaultComparison(BCRoboticsItems.REDSTONE_BOARD.get(), Comparison.compareData(s -> BCRoboticsBoards.getBoard(s.getItemStack()).key()));
        registry.setDefaultComparison(BCRoboticsItems.ROBOT.get(), Comparison.compareData(s -> BCRoboticsBoards.getRobotBoard(s.getItemStack()).key()));
        registry.setDefaultComparison(BCSiliconItems.PLUG_GATE_ITEM.get(), Comparison.compareData(s -> ItemPluggableGate.getVariant(s.getItemStack()).material.tag));
        registry.setDefaultComparison(BCSiliconItems.PLUG_LENS_ITEM.get(), Comparison.compareData(s -> ItemPluggableLens.getData(s.getItemStack()).getItemDamage()));
        //? if <1.21 {
        registry.setDefaultComparison(BCSiliconItems.PLUG_FACADE_ITEM.get(), Comparison.compareNbt());
        //?} else {
        registry.setDefaultComparison(BCSiliconItems.PLUG_FACADE_ITEM.get(), Comparison.compareComponents());
        //?}
    }

    private static void put(Map<ResourceLocation, MachineRecipe> recipes, MachineRecipe recipe) {
        if (recipe.inputs.isEmpty() || recipe.inputs.stream().anyMatch(EmiIngredient::isEmpty)) return;
        if (recipe.outputs.isEmpty() && recipe.tree) return;
        recipes.putIfAbsent(recipe.getId(), recipe);
    }

    // EMI uses a fixed recipe index. Use the same authoritative BCCE definitions as JEI/REI,
    // retaining source IDs for bookmarks and distinguishing individual alternatives.
    private record Defined<T extends RecipeDefinition>(ResourceLocation id, T recipe) {}
    private static <T extends RecipeDefinition> List<Defined<T>> definitions(Class<T> type) {
        var snapshot = ClientMachineRecipeCatalog.snapshot();
        List<Defined<T>> result = new ArrayList<>();
        if (snapshot.authoritative()) {
            for (var entry : snapshot.recipes()) {
                if (type.isInstance(entry.definition()) && !snapshot.isDenied(entry.machine(), entry.id()))
                    result.add(new Defined<>(entry.id(), type.cast(entry.definition())));
            }
        } else {
            for (var match : BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).recipes(type))
                result.add(new Defined<>(match.id(), match.recipe()));
        }
        return result;
    }

    private static EmiIngredient items(Ingredient ingredient, int count) {
        if (ingredient == null || ingredient.isEmpty()) return EmiStack.EMPTY;
        return EmiIngredient.of(ingredient, Math.max(1, count));
    }
    private static Component energy(long microMj) {
        return BCLibConfig.hidePowerValues ? Component.translatable("buildcraft.value.hidden")
            : Component.literal(MjFormatting.formatMicroMj(Math.max(0, microMj)) + " MJ");
    }

    private void addAssemblyRecipes(Map<ResourceLocation, MachineRecipe> recipes) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        //? if <1.21 {
        for (AssemblyRecipeBasic recipe : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", recipe.getId())) addAssembly(recipes, recipe.getId(), recipe);
        }
        //?} else if <1.21.11 {
        for (var holder : level.getRecipeManager().getAllRecipesFor(BCSiliconRecipes.ASSEMBLY_TYPE.get())) {
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", holder.id())) addAssembly(recipes, holder.id(), holder.value());
        }
        //?} else {
        for (var holder : BCSiliconRecipeSync.Client.assemblyRecipes()) {
            var id = holder.id().identifier();
            if (!ClientMachineRecipeCatalog.isDenied("assembly_table", id)) addAssembly(recipes, id, holder.value());
        }
        //?}
        for (var entry : BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).assemblyRecipes()) {
            if (ClientMachineRecipeCatalog.isDenied("assembly_table", entry.id())) continue;
            var adapter = ApiAssemblyRecipeAdapter.fromService(entry.id());
            if (adapter != null) addAssembly(recipes, entry.id(), adapter);
        }
    }

    private void addAssembly(Map<ResourceLocation, MachineRecipe> recipes, ResourceLocation source, AssemblyRecipeBasic recipe) {
        if (recipe instanceof FacadeAssemblyRecipes) {
            if (!BCSiliconConfig.enableFacades) return;
            for (var info : FacadeStateManager.getIndexedStates()) {
                if (!info.isVisible || info.requiredStack.isEmpty()) continue;
                addAssemblyOutput(recipes, source, recipe, FacadeAssemblyRecipes.createFacadeStack(info, false));
                addAssemblyOutput(recipes, source, recipe, FacadeAssemblyRecipes.createFacadeStack(info, true));
            }
        } else {
            for (ItemStack output : assemblyOutputs(recipe)) addAssemblyOutput(recipes, source, recipe, output);
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

    private void addAssemblyOutput(Map<ResourceLocation, MachineRecipe> recipes, ResourceLocation source, AssemblyRecipeBasic recipe, ItemStack output) {
        if (output == null || output.isEmpty()) return;
        List<EmiIngredient> inputs = new ArrayList<>();
        for (var input : recipe.getInputsFor(output)) inputs.add(items(input.ingredient, input.count));
        put(recipes, new MachineRecipe(assembly, source, outputKey(output), inputs,
            List.of(EmiStack.of(output.copy())), true, energy(recipe.getRequiredMicroJoulesFor(output))));
    }

    private void addProgrammingRecipes(Map<ResourceLocation, MachineRecipe> recipes) {
        BCRoboticsBoards.init();
        for (var entry : definitions(ProgrammingRecipeDefinition.class)) {
            var definition = entry.recipe();
            var board = BCRoboticsBoards.getByKey(definition.boardKey());
            if (board == BCRoboticsBoards.EMPTY) continue;
            put(recipes, new MachineRecipe(programming, entry.id(), definition.boardKey(),
                List.of(EmiStack.of(BCRoboticsItems.REDSTONE_BOARD.get())),
                List.of(EmiStack.of(ItemRedstoneBoard.createStack(board))), true, energy(definition.requiredMicroJoules())));
        }
    }

    private void addIntegrationRecipes(Map<ResourceLocation, MachineRecipe> recipes) {
        BCRoboticsBoards.init();
        var robotId = id("buildcraftrobotics", "integration_table/robot_board_integration");
        if (!ClientMachineRecipeCatalog.isDenied("integration_table", robotId)) {
            var recipe = ClientMachineRecipeCatalog.visibleDefinition("integration_table", robotId);
            if (!(recipe instanceof IntegrationRecipeDefinition)) {
                recipe = BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).snapshot().resolved(robotId)
                    .map(entry -> entry.value()).orElse(null);
            }
            if (recipe instanceof RobotIntegrationRecipe robotRecipe) for (var board : BCRoboticsBoards.robotEntries()) {
                var output = ItemRobot.createRobotStack(board, EntityRobotBase.SAFETY_ENERGY);
                put(recipes, new MachineRecipe(integration, robotId, board.key(),
                    List.of(EmiStack.of(BCRoboticsItems.ROBOT.get()), EmiStack.of(ItemRedstoneBoard.createStack(board))),
                    List.of(EmiStack.of(output)), true, energy(robotRecipe.requiredMicroJoules(output))));
            }
        }
        for (var entry : definitions(StaticIntegrationRecipe.class)) {
            var recipe = entry.recipe();
            List<EmiIngredient> inputs = new ArrayList<>();
            var center = recipe.centerIngredient();
            inputs.add(items(center.ingredient(), center.count()));
            for (var input : recipe.requirements(recipe.result())) inputs.add(items(input.ingredient(), input.count()));
            put(recipes, new MachineRecipe(integration, entry.id(), outputKey(recipe.result()), inputs,
                List.of(EmiStack.of(recipe.result())), true, energy(recipe.requiredMicroJoules(recipe.result()))));
        }
    }

    //? if fabric {
    private static EmiIngredient fluids(buildcraft.lib.fluid.FluidStack stack) {
    //?} else if forge {
    private static EmiIngredient fluids(net.minecraftforge.fluids.FluidStack stack) {
    //?} else {
    private static EmiIngredient fluids(net.neoforged.neoforge.fluids.FluidStack stack) {
    //?}
        if (stack == null || stack.isEmpty()) return EmiStack.EMPTY;
        var variants = FluidCompatRegistry.getEquivalentStacks(stack, "buildcraftenergy");
        List<EmiStack> entries = new ArrayList<>();
        for (var variant : variants.isEmpty() ? List.of(stack) : variants) {
            entries.add(outputFluid(variant));
        }
        return EmiIngredient.of(entries, nativeFluidAmount(stack.getAmount()));
    }

    //? if fabric {
    private static EmiStack outputFluid(buildcraft.lib.fluid.FluidStack stack) {
    //?} else if forge {
    private static EmiStack outputFluid(net.minecraftforge.fluids.FluidStack stack) {
    //?} else {
    private static EmiStack outputFluid(net.neoforged.neoforge.fluids.FluidStack stack) {
    //?}
        if (stack == null || stack.isEmpty()) return EmiStack.EMPTY;
        // Equivalent inputs are alternatives, but the machine produces one exact fluid.
        // Listing equivalent output fluids as separate outputs would double-count the recipe tree.
        //? if <1.21 {
        return EmiStack.of(stack.getFluid(), stack.getTag(), nativeFluidAmount(stack.getAmount()));
        //?} else {
        return EmiStack.of(stack.getFluid(), stack.getComponentsPatch(), nativeFluidAmount(stack.getAmount()));
        //?}
    }

    private static long nativeFluidAmount(long mb) {
        //? if fabric {
        return mb * 81L;
        //?} else {
        return mb;
        //?}
    }

    private static List<EmiStack> nonEmptyOutputs(EmiStack... stacks) {
        return java.util.Arrays.stream(stacks).filter(s -> !s.isEmpty()).toList();
    }

    private void addDistillationRecipes(Map<ResourceLocation, MachineRecipe> recipes) {
        for (var entry : definitions(DistillationRecipeDefinition.class)) {
            var recipe = entry.recipe();
            if (!OilIndustry.recipeEnabled(entry.id().getNamespace(), entry.id().getPath())) continue;
            var outputs = nonEmptyOutputs(outputFluid(MachineRecipeApiBridge.outputStack(recipe.gasOutput())),
                outputFluid(MachineRecipeApiBridge.outputStack(recipe.liquidOutput())));
            if (outputs.isEmpty()) continue;
            put(recipes, new MachineRecipe(distillation, entry.id(), "", List.of(fluids(MachineRecipeApiBridge.representativeInput(recipe.input()))),
                outputs, true, energy(recipe.powerRequiredMicroMj())));
        }
    }

    private void addHeatExchangeRecipes(Map<ResourceLocation, MachineRecipe> recipes) {
        for (var entry : definitions(HeatExchangeRecipeDefinition.class)) {
            var recipe = entry.recipe();
            if (!OilIndustry.recipeEnabled(entry.id().getNamespace(), entry.id().getPath())) continue;
            if (recipe.output().isEmpty()) continue;
            var outputs = nonEmptyOutputs(outputFluid(MachineRecipeApiBridge.outputStack(recipe.output())));
            if (outputs.isEmpty()) continue;
            put(recipes, new MachineRecipe(heatExchange, entry.id(), "", List.of(fluids(MachineRecipeApiBridge.representativeInput(recipe.input()))), outputs,
                true, Component.translatable("buildcraft.jei.heat_exchange.range", Component.translatable(recipe.kind() == RecipeDefinition.Kind.HEATING
                    ? "buildcraft.jei.heat_exchange.mode.heat" : "buildcraft.jei.heat_exchange.mode.cool"), recipe.heatFrom(), recipe.heatTo())));
        }
    }

    private void addFuels(Map<ResourceLocation, MachineRecipe> recipes) {
        for (var match : BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS).fuels()) {
            if (!OilIndustry.fuelEnabled(match.id().getNamespace(), match.id().getPath())) continue;
            FuelProfile fuel = match.profile();
            List<EmiStack> variants = new ArrayList<>();
            if (fuel.representativeVariant().isPresent()) {
                variants.addAll(fluids(FuelApiBridge.stackOfVariant(fuel.representativeVariant().get(), 1000)).getEmiStacks());
            } else {
                for (Fluid fluid : fluidRegistry()) {
                    if (variants.size() >= 64) break;
                    if (fluid == Fluids.EMPTY) continue;
                    var variant = FluidVariant.of(fluidRegistry().getKey(fluid));
                    try { if (!fuel.matcher().matches(variant, FuelApiBridge.MATCH_CONTEXT)) continue; }
                    catch (RuntimeException invalidMatcher) { continue; }
                    variants.addAll(fluids(FuelApiBridge.stackOfVariant(variant, 1000)).getEmiStacks());
                }
            }
            var input = EmiIngredient.of(variants, nativeFluidAmount(1000));
            if (input.isEmpty()) continue;
            put(recipes, new MachineRecipe(combustion, match.id(), "", List.of(input),
                nonEmptyOutputs(outputFluid(FuelApiBridge.stackOf(fuel.residuePerBucket()))), false,
                Component.translatable("jei.buildcraftenergy.burn_time", fuel.burnTicksPerBucket() / 20),
                Component.translatable("jei.buildcraftenergy.power_output", BCLibConfig.hidePowerValues
                    ? Component.translatable("buildcraft.value.hidden") : LocaleUtil.localizeMjFlow(fuel.powerPerTickMicroMj())),
                Component.translatable("jei.buildcraftenergy.total_energy", energy(fuel.powerPerTickMicroMj() * (long) fuel.burnTicksPerBucket()))));
        }
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

    private static boolean hiddenOil(EmiStack stack) {
        var key = stack.getId();
        if (key == null) return false;
        if (stack.getKey() instanceof Fluid) return !OilIndustry.fluidEnabled(key.getNamespace(), key.getPath());
        return !stack.getItemStack().isEmpty() && !OilIndustry.bucketEnabled(key.getNamespace(), key.getPath());
    }

    private static void registerPipeRecipes(EmiRegistry registry) {
        // EMI does not recognize all custom pipe serializers as shaped/shapeless vanilla recipes.
        //? if <1.21 {
        for (var recipe : registry.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (recipe instanceof PipeRecipe pipe) addPipeRecipe(registry, pipe, pipe.getId());
        }
        //?} else if <1.21.11 {
        for (var holder : registry.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (holder.value() instanceof PipeRecipe pipe) addPipeRecipe(registry, pipe, holder.id());
        }
        //?} else if >=26.1 {
        var map = registry.getRecipeMap();
        if (map != null) for (var holder : map.byType(RecipeType.CRAFTING)) {
            if (holder.value() instanceof PipeRecipe pipe) addPipeRecipe(registry, pipe, holder.id().identifier());
        }
        //?}
    }

    private static void addPipeRecipe(EmiRegistry registry, PipeRecipe pipe, ResourceLocation recipeId) {
        List<EmiIngredient> inputs = pipe.getIngredients().stream().map(i -> items(i, 1)).toList();
        EmiStack output = EmiStack.of(pipe.getDisplayResult());
        if (inputs.isEmpty() || output.isEmpty()) return;
        EmiRecipe replacement = new EmiCraftingRecipe(inputs, output, recipeId, !pipe.hasShapedBasePattern());
        // EMI applies recipe removal predicates when it bakes the index, including
        // recipes added after this call. Keep the native replacement itself.
        registry.removeRecipes(existing -> existing != replacement && recipeId.equals(existing.getId()));
        registry.addRecipe(replacement);
    }

    private static void registerExclusions(EmiRegistry registry) {
        registry.addGenericExclusionArea((screen, add) -> {
            if (!(screen instanceof GuiBC8<?> gui)) return;
            for (Object element : gui.mainGui.shownElements) if (element instanceof Ledger_Neptune ledger) {
                int x = (int) Math.floor(ledger.getX()) - 1, y = (int) Math.floor(ledger.getY()) - 1;
                int width = (int) Math.ceil(ledger.getX() + ledger.getWidth()) + 1 - x;
                int height = (int) Math.ceil(ledger.getY() + ledger.getHeight()) + 1 - y;
                if (width > 0 && height > 0) add.accept(new Bounds(x, y, width, height));
            }
        });
    }

    private void registerTransfers(EmiRegistry registry) {
        registry.addRecipeHandler(BCSiliconGuis.MENU_ASSEMBLY_TABLE.get(), new StandardRecipeHandler<ContainerAssemblyTable>() {
            @Override public List<Slot> getInputSources(ContainerAssemblyTable menu) { return menu.slots.subList(0, 48); }
            @Override public List<Slot> getCraftingSlots(ContainerAssemblyTable menu) { return menu.slots.subList(36, 48); }
            @Override public boolean supportsRecipe(EmiRecipe recipe) {
                return recipe.getCategory() == assembly && recipe.getInputs().size() <= 12;
            }
        });
        registry.addRecipeHandler(BCFactoryGuis.MENU_AUTOWORK_BENCH_ITEM.get(), new PhantomHandler<ContainerAutoCraftItems>(
            (menu, grid) -> menu.sendSetPhantomSlots(menu.blueprintInv, grid)));
        registry.addRecipeHandler(BCSiliconGuis.MENU_AD_CRAFTING_TABLE.get(), new PhantomHandler<ContainerAdvancedCraftingTable>(
            (menu, grid) -> menu.sendSetPhantomSlots(menu.blueprintInv, grid)));
    }

    private static final class PhantomHandler<T extends AbstractContainerMenu> implements EmiRecipeHandler<T> {
        private final BiConsumer<T, List<ItemStack>> send;
        private PhantomHandler(BiConsumer<T, List<ItemStack>> send) { this.send = send; }
        @Override public EmiPlayerInventory getInventory(AbstractContainerScreen<T> screen) {
            List<Slot> slots = screen.getMenu().slots;
            return new EmiPlayerInventory(slots.subList(Math.max(0, slots.size() - 36), slots.size())
                .stream().map(Slot::getItem).map(EmiStack::of).toList());
        }
        @Override public boolean supportsRecipe(EmiRecipe recipe) {
            return recipe.getCategory() == VanillaEmiRecipeCategories.CRAFTING && phantomGrid(recipe) != null;
        }
        @Override public boolean canCraft(EmiRecipe recipe, EmiCraftContext<T> context) {
            return supportsRecipe(recipe);
        }
        @Override public boolean craft(EmiRecipe recipe, EmiCraftContext<T> context) {
            List<ItemStack> grid = phantomGrid(recipe);
            if (grid == null || context.getScreenHandler() == null) return false;
            buildcraft.lib.client.gui.ClientGuiModeNavigation.show(context.getScreen());
            send.accept(context.getScreenHandler(), grid);
            return true;
        }
    }

    private static List<ItemStack> phantomGrid(EmiRecipe recipe) {
        // EMI's crafting display is already normalized to a 3-wide layout, with
        // empty slots preserved. Only accept that known layout, not arbitrary synthetic categories.
        if (!(recipe instanceof EmiCraftingRecipe crafting) || !crafting.canFit(3, 3)) return null;
        if (crafting.getInputs().size() > 9) return null;
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < crafting.getInputs().size(); i++) {
            var ingredient = crafting.getInputs().get(i);
            if (ingredient.isEmpty()) continue;
            ItemStack item = ingredient.getEmiStacks().stream().map(EmiStack::getItemStack)
                .filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
            if (item.isEmpty()) return null;
            ItemStack copy = item.copy();
            copy.setCount(1);
            grid.set(i, copy);
        }
        return grid;
    }
}
