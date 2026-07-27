package bff.executor;

import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;

import java.util.HashMap;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF transformers in Java.
 *
 * Extend this class and implement {@link #transform(Map, Map, Map)}.
 * All Clojure data types are handled internally — your implementation
 * works with plain Java Maps.
 *
 * <pre>{@code
 * public class AttachWarningsTransformer extends BaseTransformer {
 *     @Override
 *     public Map<String, Object> transform(
 *             Map<String, Object>            args,
 *             Map<String, StepResult>        chainCtx,
 *             Map<String, Object>            output) {
 *
 *         if (chainCtx.get("notify_user").isError()) {
 *             output.put("warning", "Notification could not be sent");
 *         }
 *         return output;
 *     }
 * }
 * }</pre>
 *
 * Register the instance before {@code bff.core/create-handler} runs:
 * <pre>{@code
 * Clojure.var("bff.executor", "register-transformer!").invoke("attach-warnings", new AttachWarningsTransformer());
 * }</pre>
 */
public abstract class BaseTransformer implements BffTransformer {

    @Override
    public final Object transform(Object args, Object chainCtx, Object mapped) {
        Map<String, Object>  javaArgs     = toJavaMap((IPersistentMap) args);
        Map<String, StepResult> javaCtx   = toChainCtx((IPersistentMap) chainCtx);
        Map<String, Object>  javaOutput   = toJavaMap((IPersistentMap) mapped);
        return toClojureMap(transform(javaArgs, javaCtx, javaOutput));
    }

    /**
     * Apply post-processing to the already jq-mapped output.
     *
     * @param args     GraphQL input arguments, keyed by argument name
     * @param chainCtx results of each backend step, keyed by step id
     * @param output   the jq-mapped output fields — modify and return this map
     * @return the final output map, which must match the endpoint's {@code output_type} fields
     */
    public abstract Map<String, Object> transform(
            Map<String, Object>     args,
            Map<String, StepResult> chainCtx,
            Map<String, Object>     output);

    /**
     * The result of a single backend step, as seen from a transformer.
     */
    public static class StepResult {
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

    private static Map<String, StepResult> toChainCtx(IPersistentMap m) {
        Map<String, StepResult> out = new HashMap<>();
        if (m == null) return out;
        for (Object o : m) {
            IMapEntry      e      = (IMapEntry) o;
            String         stepId = keyName(e.key());
            IPersistentMap result = (IPersistentMap) e.val();
            String         status = keyName(result.valAt(Keyword.intern("status")));
            Object         raw    = result.valAt(Keyword.intern("data"));
            Map<String, Object> data    = (raw instanceof IPersistentMap)
                                          ? toJavaMap((IPersistentMap) raw) : null;
            Object         msgRaw = result.valAt(Keyword.intern("message"));
            String         msg    = msgRaw != null ? msgRaw.toString() : null;
            out.put(stepId, new StepResult(status, data, msg));
        }
        return out;
    }

    private static Map<String, Object> toJavaMap(IPersistentMap m) {
        Map<String, Object> out = new HashMap<>();
        if (m == null) return out;
        for (Object o : m) {
            IMapEntry e = (IMapEntry) o;
            out.put(keyName(e.key()), e.val());
        }
        return out;
    }

    private static IPersistentMap toClojureMap(Map<String, Object> m) {
        Map<Object, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            out.put(Keyword.intern(e.getKey()), e.getValue());
        }
        return PersistentHashMap.create(out);
    }

    private static String keyName(Object k) {
        return (k instanceof Keyword) ? ((Keyword) k).getName() : k.toString();
    }
}
