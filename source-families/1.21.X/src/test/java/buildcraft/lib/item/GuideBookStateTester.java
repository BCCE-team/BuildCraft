package buildcraft.lib.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GuideBookStateTester {
    @Test
    void stateBelongsToTheIndividualGuideTag() {
        CompoundTag first = new CompoundTag();
        CompoundTag second = new CompoundTag();

        GuideBookStateCodec.write(first, new ItemGuide.GuideState(
            false, true, "MODULE", true, ResourceLocation.fromNamespaceAndPath("buildcraftbuilders", "block/quarry"), 3
        ));
        GuideBookStateCodec.write(second, new ItemGuide.GuideState(
            true, false, "ALPHABETICAL", false, null, 5
        ));

        ItemGuide.GuideState firstState = GuideBookStateCodec.read(first);
        ItemGuide.GuideState secondState = GuideBookStateCodec.read(second);

        Assertions.assertFalse(firstState.showLore);
        Assertions.assertTrue(firstState.showHints);
        Assertions.assertEquals("MODULE", firstState.sortMode);
        Assertions.assertTrue(firstState.document);
        Assertions.assertEquals(ResourceLocation.fromNamespaceAndPath("buildcraftbuilders", "block/quarry"), firstState.entry);
        Assertions.assertEquals(3, firstState.spread);

        Assertions.assertTrue(secondState.showLore);
        Assertions.assertFalse(secondState.showHints);
        Assertions.assertEquals("ALPHABETICAL", secondState.sortMode);
        Assertions.assertFalse(secondState.document);
        Assertions.assertNull(secondState.entry);
        Assertions.assertEquals(5, secondState.spread);
    }
}
