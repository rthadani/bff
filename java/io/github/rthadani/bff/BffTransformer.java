package io.github.rthadani.bff;

import java.util.Map;

/**
 * Post-processor that runs after output_mapping. Receives the endpoint args,
 * the full chain context (step id → step result), and the already-mapped
 * output map. Return the final output map.
 *
 * <p>All maps use {@code String} keys from the Java caller's perspective;
 * conversion to/from Clojure keywords is handled at the interop boundary.
 */
@FunctionalInterface
public interface BffTransformer {
    Map<String, Object> transform(Map<String, Object> args,
                                  Map<String, Object> chainCtx,
                                  Map<String, Object> output);
}
