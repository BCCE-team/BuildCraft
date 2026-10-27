package buildcraft.robotics.recipes;

import buildcraft.api.v2.energy.MjAmount;

import java.util.List;

import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.api.v2.recipe.CountedIngredient;
import buildcraft.api.v2.recipe.IntegrationRecipeDefinition;
import buildcraft.robotics.BCRobotics;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.BCRoboticsBoards.BoardEntry;
import buildcraft.robotics.BCRoboticsItems;
import buildcraft.robotics.item.ItemRobot;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/** Built-in robot-board Integration Table recipe registered through API 2. */
public class RobotIntegrationRecipe implements IntegrationRecipeDefinition {
    /** Declares a Java algorithm, never a hardcoded registered recipe. */
    public static final String TYPE = "buildcraftsilicon:robot_board_integration";
    public static final ResourceLocation ID = ResourceLocation.tryParse(
        "buildcraftrobotics:integration_table/robot_board_integration");
    private final long powerMicroMj;
    private static boolean registered;

    public RobotIntegrationRecipe(long powerMicroMj) {
        if (powerMicroMj <= 0) throw new IllegalArgumentException("Robot integration energy must be positive");
        this.powerMicroMj = powerMicroMj;
    }

    public static synchronized void register() {
        if (registered) return;
        buildcraft.lib.recipe.MachineRecipeDatapackReloadListener.registerHandler(TYPE, json -> {
            if (!json.has("energy") || !json.get("energy").isJsonObject()) {
                throw new IllegalArgumentException("Missing robot integration energy object");
            }
            com.google.gson.JsonObject energy = json.getAsJsonObject("energy");
            if (!energy.has("mj") || !energy.get("mj").isJsonPrimitive()
                || !energy.get("mj").getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("Robot integration energy must be an integer MJ amount");
            }
            long mj = energy.get("mj").getAsBigDecimal().longValueExact();
            if (mj <= 0) throw new IllegalArgumentException("Robot integration energy must be positive");
            return new RobotIntegrationRecipe(Math.multiplyExact(mj, MjAmount.MICRO_MJ_PER_MJ));
        });
        registered = true;
    }

    @Override
    public ItemStack output(ItemStack target, NonNullList<ItemStack> toIntegrate) {
        if (target.isEmpty() || !target.is(BCRoboticsItems.ROBOT.get())) {
            return ItemStack.EMPTY;
        }
        if (BCRoboticsBoards.getRobotBoard(target) != BCRoboticsBoards.EMPTY) {
            return ItemStack.EMPTY;
        }
        BoardEntry board = findBoard(toIntegrate);
        if (board == BCRoboticsBoards.EMPTY) {
            return ItemStack.EMPTY;
        }
        int energy = ItemRobot.getEnergy(target);
        if (energy <= 0) {
            energy = EntityRobotBase.SAFETY_ENERGY;
        }
        return ItemRobot.createRobotStack(board, energy);
    }

    @Override
    public List<CountedIngredient> requirements(ItemStack output) {
        return List.of(CountedIngredient.of(Ingredient.of(BCRoboticsItems.REDSTONE_BOARD.get()), 1));
    }

    @Override
    public long requiredMicroJoules(ItemStack output) {
        // BuildCraft 7.1.27 charged 50,000 legacy energy units here; BCCE preserves that balance at 10 legacy units = 1 MJ.
        return powerMicroMj;
    }

    @Override
    public CountedIngredient centerIngredient() {
        return CountedIngredient.of(Ingredient.of(BCRoboticsItems.ROBOT.get()), 1);
    }

    private BoardEntry findBoard(NonNullList<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack.isEmpty() || !stack.is(BCRoboticsItems.REDSTONE_BOARD.get())) {
                continue;
            }
            BoardEntry board = BCRoboticsBoards.getBoard(stack);
            if (board != BCRoboticsBoards.EMPTY) {
                return board;
            }
        }
        return BCRoboticsBoards.EMPTY;
    }
}
