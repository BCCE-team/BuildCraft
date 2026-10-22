//? source if >=26.3
package buildcraft.factory;


import buildcraft.core.BCCoreItems;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.world.item.Items;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;

public class BCFactoryRecipesProvider extends RecipeProvider {
    public BCFactoryRecipesProvider(BootstrapContext<Recipe<?>> recipes, BootstrapContext<Advancement> advancements) {
        super(recipes, advancements);
    }

    @Override
    protected void buildRecipes() {
        shaped(RecipeCategory.MISC, BCFactoryItems.TANK_BLOCK_ITEM.get())
            .pattern("ggg")
            .pattern("g g")
            .pattern("ggg")
            .define('g', Items.GLASS)
            .unlockedBy("has_" + Items.GLASS.getDescriptionId(), has(Items.GLASS))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.MINING_WELL_BLOCK_ITEM.get())
            .pattern("iri")
            .pattern("igi")
            .pattern("iai")
            .define('i', Items.IRON_INGOT)
            .define('r', Items.REDSTONE)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('a', Items.IRON_PICKAXE)
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                has(BCCoreItems.GEAR_IRON.get()))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.PUMP_BLOCK_ITEM.get())
            .pattern("iri")
            .pattern("igi")
            .pattern("tbt")
            .define('i', Items.IRON_INGOT)
            .define('r', Items.REDSTONE)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('b', Items.BUCKET)
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                has(BCCoreItems.GEAR_IRON.get()))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.FLOOD_GATE_BLOCK_ITEM.get())
            .pattern("igi")
            .pattern("ftf")
            .pattern("ifi")
            .define('i', Items.IRON_INGOT)
            .define('f', Items.IRON_BARS)
            .define('g', BCCoreItems.GEAR_IRON.get())
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                has(BCCoreItems.GEAR_IRON.get()))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.HEAT_EXCHANGE_BLOCK_ITEM.get())
            .pattern("iei")
            .pattern("ggg")
            .pattern("iei")
            .define('g', Items.GLASS)
            .define('i', Items.IRON_INGOT)
            .define('e', BCCoreItems.GEAR_IRON.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_IRON.getId().getPath(),
                has(BCCoreItems.GEAR_IRON.get()))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.DISTILLER_BLOCK_ITEM.get())
            .pattern("rtr")
            .pattern("ter")
            .pattern("   ")
            .define('r', Items.REDSTONE_TORCH)
            .define('t', BCFactoryItems.TANK_BLOCK_ITEM.get())
            .define('e', BCCoreItems.GEAR_DIAMOND.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_DIAMOND.getId().getPath(),
                has(BCCoreItems.GEAR_DIAMOND.get()))
            .save(output);

        shaped(RecipeCategory.MISC, BCFactoryItems.AUTO_BENCH_ITEM.get())
            .pattern(" s ")
            .pattern(" c ")
            .pattern(" s ")
            .define('c', Items.CRAFTING_TABLE)
            .define('s', BCCoreItems.GEAR_STONE.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_STONE.getId().getPath(),
                has(BCCoreItems.GEAR_STONE.get()))
            .save(output, ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath(BCFactory.MODID, "autowork_bench_1")));

        shaped(RecipeCategory.MISC, BCFactoryItems.AUTO_BENCH_ITEM.get())
            .pattern("   ")
            .pattern("scs")
            .pattern("   ")
            .define('c', Items.CRAFTING_TABLE)
            .define('s', BCCoreItems.GEAR_STONE.get())
            .unlockedBy("has_" + BCCoreItems.GEAR_STONE.getId().getPath(),
                has(BCCoreItems.GEAR_STONE.get()))
            .save(output, ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath(BCFactory.MODID, "autowork_bench_2")));
    }
}
