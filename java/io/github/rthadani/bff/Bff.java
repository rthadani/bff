package io.github.rthadani.bff;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;

import jakarta.servlet.http.HttpServlet;

/**
 * Static facade for embedding BFF from a Java application without direct calls
 * into {@code clojure.java.api}.
 *
 * <p>The recommended flow is: build a {@link BffConfig} once at application
 * startup, then hand it to {@link #createServlet(String, BffConfig)}. The
 * config is immutable — no global state, no runtime registration.
 *
 * <pre>{@code
 * BffConfig config = BffConfig.builder()
 *     .enricher(new CustomerEnricher(redis))
 *     .validator("check-order",  new OrderValidator())
 *     .resolver ("user-profile", new UserProfileResolver(userRepo))
 *     .cache(new RedisCacheStore(redisTemplate))
 *     .build();
 *
 * @Bean
 * ServletRegistrationBean<HttpServlet> bffServlet(BffConfig config) {
 *     return new ServletRegistrationBean<>(
 *         Bff.createServlet("bff-spec.yaml", config), "/graphql");
 * }
 * }</pre>
 */
public final class Bff {

    private Bff() {}

    private static IFn resolveVar(String ns, String var) {
        Clojure.var("clojure.core", "require").invoke(Clojure.read(ns));
        return Clojure.var(ns, var);
    }

    private static Object toExtensionsMap(BffConfig config) {
        IFn hashMap = Clojure.var("clojure.core", "hash-map");
        return hashMap.invoke(
            Keyword.intern("enrichers"),    config.enrichers(),
            Keyword.intern("validators"),   config.validators(),
            Keyword.intern("transformers"), config.transformers(),
            Keyword.intern("resolvers"),    config.resolvers(),
            Keyword.intern("retry-hooks"),  config.retryHooks(),
            Keyword.intern("cache"),        config.cache(),
            Keyword.intern("scalars"),      config.scalars(),
            Keyword.intern("http-client"),  config.httpClient());
    }

    /**
     * Load and compile the spec at {@code specPath} with no extensions. Handy
     * for smoke tests and specs that reference only ns/fn-form validators or
     * transformers.
     */
    public static IFn createHandler(String specPath) {
        return createHandler(specPath, BffConfig.EMPTY);
    }

    /**
     * Load and compile the spec at {@code specPath} with the given extensions.
     * The returned {@link IFn} is a Ring handler — invoke it with a Ring
     * request map, or wrap it via {@link #createServlet(String, BffConfig)}.
     */
    public static IFn createHandler(String specPath, BffConfig config) {
        return (IFn) resolveVar("bff.core", "create-handler")
                        .invoke(specPath, toExtensionsMap(config));
    }

    /** Load a spec with no extensions and return a ready-to-mount HttpServlet. */
    public static HttpServlet createServlet(String specPath) {
        return createServlet(specPath, BffConfig.EMPTY);
    }

    /**
     * Load a spec with the given extensions and return an {@link HttpServlet}
     * ready to mount into any Jakarta Servlet 6 container — including Spring
     * Boot 3 via {@code ServletRegistrationBean}.
     */
    public static HttpServlet createServlet(String specPath, BffConfig config) {
        return new BffServlet(createHandler(specPath, config));
    }
}
