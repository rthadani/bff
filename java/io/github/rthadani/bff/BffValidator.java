package io.github.rthadani.bff;

import java.util.List;
import java.util.Map;

/**
 * Custom validator for a BFF endpoint.
 *
 * <p>Return a list of error maps (each with at least a {@code "message"} key),
 * or {@code null} / an empty list when the args are valid.
 *
 * <p>Both {@code args} and {@code ctx} have {@code String} keys. Keyword-typed
 * Clojure keys are stringified before this method is called, and any string
 * keys returned in error maps are converted back to keywords for the caller.
 */
@FunctionalInterface
public interface BffValidator {
    List<Map<String, Object>> validate(Map<String, Object> args, Map<String, Object> ctx);
}
