package io.github.rthadani.bff;

/**
 * Pluggable cache backend for step-level caching.
 *
 * <p>Implementations are registered once at startup via
 * {@code bff.cache/register-cache!} (Clojure) or the {@code Bff.registerCache}
 * facade (Java). Any thrown exception is swallowed by the caller and logged;
 * cache failures never propagate to the GraphQL response.
 */
public interface CacheStore {
    /** Return the cached value for {@code key}, or {@code null} if absent/expired. */
    Object get(String key);

    /** Cache {@code value} under {@code key} with the given time-to-live in milliseconds. */
    void put(String key, Object value, long ttlMs);

    /** Remove {@code key} from the cache. No-op if the key is absent. */
    void invalidate(String key);
}
