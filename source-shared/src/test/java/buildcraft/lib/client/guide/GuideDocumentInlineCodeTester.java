package buildcraft.lib.client.guide;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GuideDocumentInlineCodeTester {
    @Test
    void inlineCodeTagsAreRenderedAsTextWithoutLiteralMarkup() {
        GuideDocument document = GuideDocument.parse(
            "Use <code>MJ_ONLY</code> and <code>power.mjPerFe</code>.",
            true, true, true
        );

        Assertions.assertEquals(1, document.blocks.size());
        GuideDocument.Block block = document.blocks.get(0);
        Assertions.assertEquals(GuideDocument.Kind.TEXT, block.kind);
        Assertions.assertNotNull(block.text);
        Assertions.assertEquals("Use MJ_ONLY and power.mjPerFe.", block.text.getString());
    }
}
