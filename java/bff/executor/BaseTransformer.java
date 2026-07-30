package bff.executor;

import io.github.rthadani.bff.BffTransformer;

import java.util.HashMap;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF transformers in Java.
 *
 * <p>Extend this class and implement {@link #doTransform(Map, Map, Map)}. Chain
 * context is exposed as {@code Map<String, StepResult>} so you can query the
 * status of each backend step directly instead of pattern-matching raw maps.
 *
 * <pre>{@code
 * public class AttachWarningsTransformer extends BaseTransformer {
 *     @Override
 *     protected Map<String, Object> doTransform(
 *             Map<String, Object>     args,
 *             Map<String, StepResult> chainCtx,
 *             Map<String, Object>     output) {
 *
 *         if (chainCtx.get("notify_user").isError()) {
 *             output.put("warning", "Notification could not be sent");
 *         }
 *         return output;
 *     }
 * }
 * }</pre>
 */
public abstract class BaseTransformer implements BffTransformer {

    @Override
    public final Map<String, Object> transform(Map<String, Object> args,
                                               Map<String, Object> chainCtx,
                                               Map<String, Object> output) {
        return doTransform(args, toChainCtx(chainCtx), output);
    }

    /**
     * Apply post-processing to the already jq-mapped output.
     *
     * @param args     GraphQL input arguments, keyed by argument name
     * @param chainCtx results of each backend step, keyed by step id
     * @param output   the jq-mapped output fields — modify and return this map
     * @return the final output map, matching the endpoint's {@code output_type} fields
     */
    protected abstract Map<String, Object> doTransform(Map<String, Object>     args,
                                                       Map<String, StepResult> chainCtx,
                                                       Map<String, Object>     output);

    /** The result of a single backend step, as seen from a transformer. */
    public static final class StepResult {
        private final String              status;
        private final Map<String, Object> data;
        private final String              message;

        private StepResult(String status, Map<String, Object> data, String message) {
            this.status  = status;
            this.data    = data;
            this.message = message;
        }

        public boolean isOk()    { return "ok".equals(status); }
        public boolean isError() { return "error".equals(status); }

        /** Response data from the step. Null when the step failed. */
        public Map<String, Object> getData() { return data; }

        /** Error message. Null when the step succeeded. */
        public String getMessage() { return message; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, StepResult> toChainCtx(Map<String, Object> raw) {
        Map<String, StepResult> out = new HashMap<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Map<String, Object> result = (Map<String, Object>) e.getValue();
            String              status = String.valueOf(result.get("status"));
            Object              data   = result.get("data");
            Map<String, Object> errMap = (Map<String, Object>) result.get("error");
            String              msg    = errMap != null ? String.valueOf(errMap.get("message")) : null;
            out.put(e.getKey(),
                    new StepResult(status,
                                   data instanceof Map ? (Map<String, Object>) data : null,
                                   msg));
        }
        return out;
    }
}
