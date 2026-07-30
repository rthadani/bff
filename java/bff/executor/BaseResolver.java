package bff.executor;

import io.github.rthadani.bff.BffResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Convenience base class for implementing custom BFF resolvers in Java.
 *
 * <p>Extend this class and implement {@link #doResolve(Map, Map)}. The base class
 * translates your {@link ResolverResult} into the {@code {"data", "errors"}} map
 * the engine expects.
 *
 * <pre>{@code
 * public class UserProfileResolver extends BaseResolver {
 *     @Override
 *     protected ResolverResult doResolve(Map<String, Object> args, Map<String, Object> ctx) {
 *         String userId = (String) args.get("userId");
 *         User user = userRepo.findById(userId);
 *         return ResolverResult.ok(Map.of(
 *             "fullName", user.getName(),
 *             "email",    user.getEmail()
 *         ));
 *     }
 * }
 * }</pre>
 */
public abstract class BaseResolver implements BffResolver {

    @Override
    public final Map<String, Object> resolve(Map<String, Object> args, Map<String, Object> ctx) {
        ResolverResult r = doResolve(args, ctx);
        Map<String, Object> out = new HashMap<>();
        out.put("data",   r.data);
        out.put("errors", r.errors);
        return out;
    }

    /**
     * Implement your resolver logic here.
     *
     * @return a {@link ResolverResult} built via {@code ResolverResult.ok(...)} or {@code ResolverResult.error(...)}
     */
    protected abstract ResolverResult doResolve(Map<String, Object> args, Map<String, Object> ctx);

    /**
     * Return type for {@link #doResolve(Map, Map)}. Build one via the static
     * factories {@code ok(data)} or {@code error(message)}, and add extra errors
     * to a partial-success result with {@code withError(message)}.
     */
    public static final class ResolverResult {

        private final Map<String, Object>       data;
        private final List<Map<String, Object>> errors;

        private ResolverResult(Map<String, Object> data, List<Map<String, Object>> errors) {
            this.data   = data;
            this.errors = errors;
        }

        /** Success. Keys in {@code data} must match the endpoint's {@code output_type} fields. */
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
    }
}
