package bff.executor;

import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentVector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF resolvers in Java.
 *
 * Extend this class and implement {@link #resolve(Map, Map)}.
 * All Clojure data types are handled internally — your implementation
 * works with plain Java Maps and returns a {@link ResolverResult}.
 *
 * <pre>{@code
 * public class UserProfileResolver extends BaseResolver {
 *     @Override
 *     public ResolverResult resolve(Map<String, Object> args, Map<String, Object> ctx) {
 *         String userId = (String) args.get("userId");
 *         User user = userRepo.findById(userId);
 *         return ResolverResult.ok(Map.of(
 *             "fullName", user.getName(),
 *             "email",    user.getEmail()
 *         ));
 *     }
 * }
 * }</pre>
 *
 * Register the instance before {@code bff.core/create-handler} runs:
 * <pre>{@code
 * Clojure.var("bff.executor", "register-resolver!").invoke("user-profile", new UserProfileResolver());
 * }</pre>
 */
public abstract class BaseResolver implements BffResolver {

    @Override
    public final Object resolve_endpoint(Object args, Object ctx) {
        Map<String, Object> javaArgs = toJavaMap((IPersistentMap) args);
        Map<String, Object> javaCtx  = toJavaMap((IPersistentMap) ctx);
        return resolve(javaArgs, javaCtx).toClojure();
    }

    /**
     * Implement your resolver logic here.
     *
     * @param args GraphQL input arguments, keyed by argument name
     * @param ctx  Request context — forwarded headers and remote-addr
     * @return a {@link ResolverResult} built via {@code ResolverResult.ok(...)} or {@code ResolverResult.error(...)}
     */
    public abstract ResolverResult resolve(Map<String, Object> args, Map<String, Object> ctx);

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

    /**
     * The return type for {@link #resolve(Map, Map)}.
     *
     * Build one with the static factories:
     * <ul>
     *   <li>{@code ResolverResult.ok(data)} — success with output fields</li>
     *   <li>{@code ResolverResult.error(message)} — top-level error, empty data</li>
     *   <li>{@code result.withError(message)} — add an error to a partial-success result</li>
     * </ul>
     */
    public static class ResolverResult {

        private final Map<String, Object>       data;
        private final List<Map<String, Object>> errors;

        private ResolverResult(Map<String, Object> data, List<Map<String, Object>> errors) {
            this.data   = data;
            this.errors = errors;
        }

        /** Successful result. Keys in {@code data} must match the endpoint's {@code output_type} fields. */
        public static ResolverResult ok(Map<String, Object> data) {
            return new ResolverResult(new HashMap<>(data), new ArrayList<>());
        }

        /** Error result with empty data. */
        public static ResolverResult error(String message) {
            List<Map<String, Object>> errs = new ArrayList<>();
            errs.add(Map.of("message", message));
            return new ResolverResult(new HashMap<>(), errs);
        }

        /** Attach an additional error to a partial-success result. */
        public ResolverResult withError(String message) {
            List<Map<String, Object>> errs = new ArrayList<>(this.errors);
            errs.add(Map.of("message", message));
            return new ResolverResult(this.data, errs);
        }

        IPersistentMap toClojure() {
            Map<Object, Object> clojureData = new HashMap<>();
            for (Map.Entry<String, Object> e : data.entrySet()) {
                clojureData.put(Keyword.intern(e.getKey()), e.getValue());
            }

            Object[] errorMaps = errors.stream()
                .map(err -> {
                    Map<Object, Object> m = new HashMap<>();
                    err.forEach((k, v) -> m.put(Keyword.intern(k), v));
                    return PersistentHashMap.create(m);
                })
                .toArray();

            return PersistentHashMap.create(Map.of(
                Keyword.intern("data"),   PersistentHashMap.create(clojureData),
                Keyword.intern("errors"), PersistentVector.create(Arrays.asList(errorMaps))
            ));
        }
    }
}
