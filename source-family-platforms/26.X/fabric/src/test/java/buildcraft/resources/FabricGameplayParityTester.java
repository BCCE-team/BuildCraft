/* Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Standalone resource/source guards for confirmed 26.3 parity regressions.
 * These deliberately avoid bootstrapping Minecraft and Fabric's registries in
 * a plain JVM; gameplay still needs integration tests with both loaders.
 */
public class FabricGameplayParityTester {
    private static JsonObject recipe(String path) throws IOException {
        return JsonParser.parseString(Files.readString(Path.of("src/main/resources", path)))
            .getAsJsonObject();
    }

    @Test
    void lensIngredientsAndGroupMatchMainBuild() throws IOException {
        JsonObject filter = recipe("data/buildcraftsilicon/recipe/assembly/lens/lens_filter.json");
        JsonObject regular = recipe("data/buildcraftsilicon/recipe/assembly/lens/lens_regular.json");
        Assertions.assertEquals("buildcraftsilicon:lens_filters", filter.get("group").getAsString());
        Assertions.assertEquals("minecraft:glass", filter.getAsJsonArray("ingredients").get(0).getAsString());
        Assertions.assertEquals("minecraft:glass", regular.getAsJsonArray("ingredients").get(0).getAsString());
    }

    @Test
    void brickGateIngredientsMatchMainBuild() throws IOException {
        JsonObject clay = recipe("data/buildcraftsilicon/recipe/plug_gate_create/clay_brick_no_modifier.json");
        JsonObject nether = recipe("data/buildcraftsilicon/recipe/plug_gate_create/nether_brick_no_modifier.json");
        Assertions.assertEquals("minecraft:brick", clay.getAsJsonObject("key").get("m").getAsString());
        Assertions.assertEquals("minecraft:nether_brick", nether.getAsJsonObject("key").get("m").getAsString());
    }

    @Test
    void assemblyOutputIsDeferredAndPreservesNonDamageableVariants() throws IOException {
        String java = Files.readString(Path.of("src/main/java/buildcraft/lib/recipe/AssemblyRecipe.java"));
        Assertions.assertTrue(java.contains("private final LegacyResult deferredResult;"));
        Assertions.assertTrue(java.contains("private ItemStackTemplate resultTemplate()"));
        Assertions.assertTrue(java.contains("new ItemStack(item).isDamageableItem()"));
        Assertions.assertTrue(java.contains("return ImmutableSet.of(resultTemplate().create());"));
    }

    @Test
    void permissionsAndCompressedNbtLimitsRemainEnforced() throws IOException {
        String interaction = Files.readString(Path.of(
            "src/main/java/buildcraft/lib/internal/api/v2/BlockInteractionServiceImpl.java"));
        String squish = Files.readString(Path.of(
            "src/main/java/buildcraft/lib/nbt/NbtSquishMapReader.java"));
        Assertions.assertTrue(interaction.contains("BuildCraftServices.PERMISSIONS"));
        Assertions.assertTrue(interaction.contains("WorldOperationKind.BLOCK_INTERACT"));
        Assertions.assertTrue(squish.contains("MAX_EXPANDED_BUDGET"));
        Assertions.assertTrue(squish.contains("chargeCopy(value, 0);"));
    }
}
