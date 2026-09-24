package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.OperationMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OperationScopeTester {
    @Test
    void nestedScopesShareRecursionStateAndAttachments() {
        Object endpoint = new Object();
        Object attachmentKey = new Object();
        Object attachment = new Object();

        Assertions.assertTrue(OperationScope.current().isEmpty());
        try (OperationScope outer = OperationScope.open(OperationMode.EXECUTE)) {
            Assertions.assertSame(outer, OperationScope.current().orElseThrow());
            Assertions.assertSame(attachment, outer.sharedAttachment(attachmentKey, () -> attachment));
            try (OperationScope.Guard first = outer.enter(endpoint)) {
                Assertions.assertTrue(first.entered());
                try (OperationScope nested = OperationScope.open(OperationMode.EXECUTE)) {
                    Assertions.assertSame(attachment,
                        nested.sharedAttachment(attachmentKey, Object.class).orElseThrow());
                    try (OperationScope.Guard repeated = nested.enter(endpoint)) {
                        Assertions.assertFalse(repeated.entered());
                    }
                }
            }
            try (OperationScope.Guard afterRelease = outer.enter(endpoint)) {
                Assertions.assertTrue(afterRelease.entered());
            }
        }
        Assertions.assertTrue(OperationScope.current().isEmpty());
    }

    @Test
    void modeAndNestingAreExplicit() {
        try (OperationScope execute = OperationScope.open(OperationMode.EXECUTE)) {
            Assertions.assertFalse(execute.simulate());
            try (OperationScope simulate = OperationScope.open(OperationMode.SIMULATE)) {
                Assertions.assertTrue(simulate.simulate());
                Assertions.assertSame(execute, simulate.parent().orElseThrow());
            }
        }
    }
}
