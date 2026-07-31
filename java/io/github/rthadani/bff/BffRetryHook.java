package io.github.rthadani.bff;

import java.util.Map;

/**
 * Called before each retry attempt of a backend step whose {@code retry:} config
 * declared a {@code before_retry} reference. Purpose: rewrite the request-ctx
 * used for the next attempt — typically to inject a refreshed auth token.
 *
 * <p>The {@code failureContext} map contains:
 * <ul>
 *   <li>{@code "step-id"} — id of the step that just failed</li>
 *   <li>{@code "attempt"} — 1-indexed retry number about to happen</li>
 *   <li>{@code "args"} — GraphQL input arguments</li>
 *   <li>{@code "chain-ctx"} — results of steps completed so far</li>
 *   <li>{@code "request-ctx"} — current request context (headers, remote-addr)</li>
 *   <li>{@code "error"} — the {@code {"code", "message", "detail"}} error map</li>
 * </ul>
 *
 * <p>Return a new request-ctx map for the retry, or {@code null} to reuse the
 * current one unchanged.
 */
@FunctionalInterface
public interface BffRetryHook {
    Map<String, Object> beforeRetry(Map<String, Object> failureContext);
}
