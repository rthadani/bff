package io.github.rthadani.bff;

import java.util.Map;

/**
 * Replaces the entire backend_chain for an endpoint. Owns the full response.
 *
 * <p>Must return a map with two keys:
 * <ul>
 *   <li>{@code "data"} — a {@code Map<String,Object>} with the endpoint's output fields (or null)</li>
 *   <li>{@code "errors"} — a {@code List<Map<String,Object>>} of error maps (may be empty)</li>
 * </ul>
 *
 * <p>Both {@code args} and {@code ctx} arrive with {@code String} keys; return-value
 * keys are converted back to Clojure keywords at the interop boundary.
 */
@FunctionalInterface
public interface BffResolver {
    Map<String, Object> resolve(Map<String, Object> args, Map<String, Object> ctx);
}
