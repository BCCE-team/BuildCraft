package buildcraft.factory;

import java.util.function.Consumer;

import buildcraft.core.BCCoreItems;
import net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.FinishedRecipe;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;

public class BCFactoryRecipesProvider extends RecipeProvider {
    public BCFactoryRecipesProvider(PackOutput output) {
        super(output);
    }

    @Override
    protected void buildRecipes(Consumer<FinishedRecipe> writer) {
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.TANK_BLOCK_ITEM.get())
            .pattern("ggg")
            .pattern("g g")
            .pattern("ggg")
            .define('g', Items.GLASS)
            .unlockedBy("has_" + Items.GLASS.getDescriptionId(), TriggerInstance.hasItems(Items.GLASS))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.MINING_WELL_BLOCK_ITEM.get())
            .pattern("iri")
            .pattern("igi")
            .pattern("iai")
            .define('i', Items.IRON_INGOT)
            .define('r', Items.REDSTONE)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('a', Items.IRON_PICKAXE)
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.PUMP_BLOCK_ITEM.get())
            .pattern("iri")
            .pattern("igi")
            .pattern("tbt")
            .define('i', Items.IRON_INGOT)
            .define('r', Items.REDSTONE)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('b', Items.BUCKET)
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.FLOOD_GATE_BLOCK_ITEM.get())
            .pattern("igi")
            .pattern("ftf")
            .pattern("ifi")
            .define('i', Items.IRON_INGOT)
            .define('f', Items.IRON_BARS)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get())
            .pattern("iei")
            .pattern("ggg")
            .pattern("iei")
            .define('g', Items.GLASS)
            .define('i', Items.IRON_INGOT)
            .define('e', BCCoreItems.GEAR_IRON.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_IRON.get()))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.DISTILLER_BLOCK_ITEM.get())
            .pattern("rtr")
            .pattern("ter")
            .pattern("   ")
            .define('r', Items.REDSTONE_TORCH)
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .define('e', BCCoreItems.GEAR_DIAMOND.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_DIAMOND.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_DIAMOND.get()))
            .save(writer);

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.AUTO_BENCH_ITEM.get())
            .pattern(" s ")
            .pattern(" c ")
            .pattern(" s ")
            .define('c', Items.CRAFTING_TABLE)
            .define('s', BCCoreItems.GEAR_STONE.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_STONE.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_STONE.get()))
            .save(writer, new ResourceLocation(BCFactory.MODID, "autowork_bench_1"));

        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BCFactoryItems.AUTO_BENCH_ITEM.get())
            .pattern("   ")
            .pattern("scs")
            .pattern("   ")
            .define('c', Items.CRAFTING_TABLE)
            .define('s', BCCoreItems.GEAR_STONE.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_STONE.getId().getPath(),
                TriggerInstance.hasItems(BCCoreItems.GEAR_STONE.get()))
            .save(writer, new ResourceLocation(BCFactory.MODID, "autowork_bench_2"));
    }
}
