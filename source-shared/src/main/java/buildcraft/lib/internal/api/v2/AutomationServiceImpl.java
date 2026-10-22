package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.automation.AutomationActionType;
import buildcraft.api.v2.automation.AutomationRequest;
import buildcraft.api.v2.automation.AutomationResult;
import buildcraft.api.v2.automation.AutomationService;

/** Registry-dispatch implementation of the loader-neutral automation service. */
final class AutomationServiceImpl implements AutomationService {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public AutomationResult execute(AutomationRequest request) {
        if (request == null) return new AutomationResult(AutomationResult.Status.FAILED, 0, "null_request");
        AutomationActionType type = BuildCraftApi.registry(BuildCraftRegistries.AUTOMATION_ACTION_TYPES).get(request.kind());
        if (type == null) return AutomationResult.pass();
        if (!type.requestType().isInstance(request)) {
            return new AutomationResult(AutomationResult.Status.FAILED, 0, "wrong_request_type:" + request.kind());
        }
        try {
            AutomationResult result = type.handler().execute(request);
            // Broken third-party callbacks must never crash robot AI or turn into PASS.
            return result == null
                ? new AutomationResult(AutomationResult.Status.FAILED, 0, "null_handler_result")
                : result;
        } catch (RuntimeException ex) {
            buildcraft.lib.internal.debug.BCLog.logger.warn("Automation handler {} failed", request.kind(), ex);
            return new AutomationResult(AutomationResult.Status.FAILED, 0, "handler_exception");
        }
    }
}
