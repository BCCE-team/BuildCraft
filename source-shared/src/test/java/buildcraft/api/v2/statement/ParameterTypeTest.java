package buildcraft.api.v2.statement;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.context.ExtensionContext;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** A required parameter must not depend on a list of visible suggestions to initialize. */
public class ParameterTypeTest {
    private static final ResourceLocation ID = ResourceLocation.tryParse("buildcraft:test_parameter");
    private static final ApiCodec<String, OpaqueData> CODEC = new ApiCodec<>() {
        public CodecResult<String> decode(OpaqueData payload) { return CodecResult.failure("not needed"); }
        public CodecResult<OpaqueData> encode(String value) { return CodecResult.failure("not needed"); }
    };
    private static final StatementContext CONTEXT = new StatementContext(
        ExtensionContext.empty(), AutomationActor.unknown(), OperationMode.SIMULATE, null);

    @Test
    void noSuggestionsCanStillProvideAnInitialValue() {
        ParameterType<String> type = new ParameterType<>(ID, CODEC, ctx -> List.of(), ctx -> "ready");
        Assertions.assertTrue(type.suggestions(CONTEXT).isEmpty());
        Assertions.assertEquals("ready", type.initialValue(CONTEXT).orElseThrow());
    }

    @Test
    void existingConstructorsNeverInventValues() {
        ParameterType<String> type = new ParameterType<>(ID, CODEC);
        Assertions.assertTrue(type.initialValue(CONTEXT).isEmpty());
    }
}
