package bff.validator;

import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF validators in Java.
 *
 * Extend this class and implement {@link #validate(Map, Map)}.
 * Return an empty list when the args are valid, or a list of error messages
 * to fail validation. All Clojure data types are handled internally.
 *
 * <pre>{@code
 * public class OrderValidator extends BaseValidator {
 *     @Override
 *     public List<String> validate(Map<String, Object> args, Map<String, Object> ctx) {
 *         List<String> errors = new ArrayList<>();
 *         Double amount = (Double) args.get("amount");
 *         String currency = (String) args.get("currency");
 *         if (amount != null && amount > 10000 && "GBP".equals(currency)) {
 *             errors.add("GBP orders above 10000 require manual approval");
 *         }
 *         return errors;
 *     }
 * }
 * }</pre>
 *
 * Register the instance before {@code bff.core/create-handler} runs:
 * <pre>{@code
 * Clojure.var("bff.validator", "register-validator!").invoke("order-validator", new OrderValidator());
 * }</pre>
 */
public abstract class BaseValidator implements BffValidator {

    @Override
    public final Object validate(Object args, Object ctx) {
        Map<String, Object> javaArgs = toJavaMap((IPersistentMap) args);
        Map<String, Object> javaCtx  = toJavaMap((IPersistentMap) ctx);
        List<String> messages = validate(javaArgs, javaCtx);
        return toClojureErrors(messages);
    }

    /**
     * Validate the request arguments.
     *
     * @param args GraphQL input arguments, keyed by argument name
     * @param ctx  Request context — forwarded headers and remote-addr
     * @return empty list if valid; a list of error messages if invalid
     */
    public abstract List<String> validate(Map<String, Object> args, Map<String, Object> ctx);

    private static Map<String, Object> toJavaMap(IPersistentMap m) {
        Map<String, Object> out = new HashMap<>();
        if (m == null) return out;
        for (Object o : m) {
            IMapEntry e   = (IMapEntry) o;
            Object    k   = e.key();
            String    key = (k instanceof Keyword) ? ((Keyword) k).getName() : k.toString();
            out.put(key, e.val());
        }
        return out;
    }

    private static Object toClojureErrors(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return PersistentVector.EMPTY;
        }
        Object[] maps = messages.stream()
            .map(msg -> PersistentArrayMap.createAsIfByAssoc(
                    new Object[]{Keyword.intern("message"), msg}))
            .toArray();
        return PersistentVector.create(java.util.Arrays.asList(maps));
    }
}
