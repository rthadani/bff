package io.github.rthadani.bff;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a BFF handler. Holds every extension the engine
 * knows about — enrichers, validators, transformers, resolvers, retry hooks,
 * the cache backend, and custom scalars — and is passed once to
 * {@link Bff#createHandler(String, BffConfig)} or
 * {@link Bff#createServlet(String, BffConfig)} at startup.
 *
 * <p>Build via the fluent {@link #builder()}:
 * <pre>{@code
 * BffConfig config = BffConfig.builder()
 *     .enricher(new CustomerEnricher(redis))
 *     .validator("check-order",  new OrderValidator())
 *     .transformer("attach-warnings", new WarningsTransformer())
 *     .resolver("user-profile", new UserProfileResolver(userRepo))
 *     .retryHook("cmap-token-refresh", new TokenRefreshHook())
 *     .cache(new RedisCacheStore(redisTemplate))
 *     .scalar("Mac", new MacScalar())
 *     .build();
 *
 * HttpServlet servlet = Bff.createServlet("bff-spec.yaml", config);
 * }</pre>
 *
 * <p>Enrichers are ordered by insertion. Validators, transformers, resolvers,
 * retry hooks, and scalars are keyed — the string key matches {@code {key: "..."}}
 * references (or scalar names) in the YAML spec.
 */
public final class BffConfig {

    static final BffConfig EMPTY = new BffConfig(
        List.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), null);

    private final List<BffContextEnricher>    enrichers;
    private final Map<String, BffValidator>   validators;
    private final Map<String, BffTransformer> transformers;
    private final Map<String, BffResolver>    resolvers;
    private final Map<String, BffRetryHook>   retryHooks;
    private final CacheStore                  cache;
    private final Map<String, BffScalar>      scalars;
    private final BffHttpClient               httpClient;

    private BffConfig(List<BffContextEnricher>    enrichers,
                      Map<String, BffValidator>   validators,
                      Map<String, BffTransformer> transformers,
                      Map<String, BffResolver>    resolvers,
                      Map<String, BffRetryHook>   retryHooks,
                      CacheStore                  cache,
                      Map<String, BffScalar>      scalars,
                      BffHttpClient               httpClient) {
        this.enrichers    = enrichers;
        this.validators   = validators;
        this.transformers = transformers;
        this.resolvers    = resolvers;
        this.retryHooks   = retryHooks;
        this.cache        = cache;
        this.scalars      = scalars;
        this.httpClient   = httpClient;
    }

    public List<BffContextEnricher>    enrichers()    { return enrichers; }
    public Map<String, BffValidator>   validators()   { return validators; }
    public Map<String, BffTransformer> transformers() { return transformers; }
    public Map<String, BffResolver>    resolvers()    { return resolvers; }
    public Map<String, BffRetryHook>   retryHooks()   { return retryHooks; }
    public CacheStore                  cache()        { return cache; }
    public Map<String, BffScalar>      scalars()      { return scalars; }
    public BffHttpClient               httpClient()   { return httpClient; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<BffContextEnricher>    enrichers    = new ArrayList<>();
        private final Map<String, BffValidator>   validators   = new LinkedHashMap<>();
        private final Map<String, BffTransformer> transformers = new LinkedHashMap<>();
        private final Map<String, BffResolver>    resolvers    = new LinkedHashMap<>();
        private final Map<String, BffRetryHook>   retryHooks   = new LinkedHashMap<>();
        private final Map<String, BffScalar>      scalars      = new LinkedHashMap<>();
        private CacheStore                        cache;
        private BffHttpClient                     httpClient;

        private Builder() {}

        /** Append a context enricher. Enrichers run in registration order. */
        public Builder enricher(BffContextEnricher e) {
            enrichers.add(e);
            return this;
        }

        /** Register a validator under {@code key}, matching {@code validator: {key: "..."}} in the spec. */
        public Builder validator(String key, BffValidator v) {
            validators.put(key, v);
            return this;
        }

        /** Register a transformer under {@code key}, matching {@code transformer: {key: "..."}}. */
        public Builder transformer(String key, BffTransformer t) {
            transformers.put(key, t);
            return this;
        }

        /** Register a resolver under {@code key}, matching {@code resolver: {key: "..."}}. */
        public Builder resolver(String key, BffResolver r) {
            resolvers.put(key, r);
            return this;
        }

        /** Register a retry hook under {@code key}, matching {@code retry.before_retry: {key: "..."}}. */
        public Builder retryHook(String key, BffRetryHook hook) {
            retryHooks.put(key, hook);
            return this;
        }

        /** Set the process-wide cache backend. Pass {@code null} to disable caching. */
        public Builder cache(CacheStore store) {
            this.cache = store;
            return this;
        }

        /** Register a custom scalar by GraphQL type name (matches the entry in the
         *  spec's top-level {@code scalars:} list). */
        public Builder scalar(String name, BffScalar scalar) {
            scalars.put(name, scalar);
            return this;
        }

        /** Route outbound HTTP for spec {@code url:} steps through this client
         *  instead of the built-in hato-based one. Pass {@code null} to revert
         *  to the default. */
        public Builder httpClient(BffHttpClient client) {
            this.httpClient = client;
            return this;
        }

        public BffConfig build() {
            return new BffConfig(
                List.copyOf(enrichers),
                unmodifiable(validators),
                unmodifiable(transformers),
                unmodifiable(resolvers),
                unmodifiable(retryHooks),
                cache,
                unmodifiable(scalars),
                httpClient);
        }

        private static <K, V> Map<K, V> unmodifiable(Map<K, V> m) {
            return m.isEmpty() ? Map.of() : Collections.unmodifiableMap(new HashMap<>(m));
        }
    }
}
