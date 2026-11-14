package buildcraft.gametest;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Production server boot, Fabric entrypoints, and world-backed GameTest smoke. */
public final class FabricInfrastructureGameTests implements CustomTestMethodInvoker {
    @GameTest
    public void fabricRuntimeHasBuildCraftAndWorld(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("buildcraftlib")) {
            throw new AssertionError("BuildCraft Fabric entrypoint did not load");
        }
        helper.assertBlockPresent(Blocks.AIR, 0, 0, 0);
        helper.succeed();
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        helper.setBlock(0, 0, 0, Blocks.AIR);
        method.invoke(this, helper);
    }
}
