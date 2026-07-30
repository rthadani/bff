package io.github.rthadani.bff;

import java.util.Map;

/**
 * Per-request context enricher. Runs once per GraphQL operation, before
 * validators and the backend chain. Return a {@code Map<String, Object>}
 * of new values to merge into ctx, or {@code null} for no change.
 *
 * <p>Multiple enrichers can be registered; they run in registration order
 * and each sees the ctx accumulated by earlier enrichers.
 *
 * <p>Typical Spring Boot use case: look up customer / equipment identifiers
 * in Redis using the JWT subject, so downstream steps can read them from
 * {@code ctx} instead of repeating the lookup per endpoint.
 */
@FunctionalInterface
public interface BffContextEnricher {
    Map<String, Object> enrich(Map<String, Object> ctx);
}
