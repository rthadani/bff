package io.github.rthadani.bff;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import jakarta.servlet.http.HttpServlet;

/**
 * Static facade for embedding BFF from a Java application without direct calls
 * into {@code clojure.java.api}.
 *
 * <p>Typical Spring Boot 3 wiring:
 *
 * <pre>{@code
 * @Bean
 * public ServletRegistrationBean<HttpServlet> bffServlet() {
 *     Bff.registerValidator("check-order",  new OrderValidator());
 *     Bff.registerResolver ("user-profile", new UserProfileResolver(userRepo));
 *     Bff.registerCache(new RedisCacheStore(redisTemplate));
 *
 *     ServletRegistrationBean<HttpServlet> reg =
 *         new ServletRegistrationBean<>(Bff.createServlet("bff-spec.yaml"), "/graphql");
 *     reg.setName("bff");
 *     return reg;
 * }
 * }</pre>
 *
 * <p>All registrations must happen before the first {@code createHandler} /
 * {@code createServlet} call, since the spec is compiled at that point.
 */
public final class Bff {

    private Bff() {}

    private static IFn resolveVar(String ns, String var) {
        Clojure.var("clojure.core", "require").invoke(Clojure.read(ns));
        return Clojure.var(ns, var);
    }

    /** Register a validator implementation under {@code key}. */
    public static void registerValidator(String key, BffValidator validator) {
        resolveVar("bff.validator", "register-validator!").invoke(key, validator);
    }

    /** Register a transformer implementation under {@code key}. */
    public static void registerTransformer(String key, BffTransformer transformer) {
        resolveVar("bff.executor", "register-transformer!").invoke(key, transformer);
    }

    /** Register a resolver implementation under {@code key}. A registered resolver
     *  bypasses the endpoint's {@code backend_chain} entirely. */
    public static void registerResolver(String key, BffResolver resolver) {
        resolveVar("bff.executor", "register-resolver!").invoke(key, resolver);
    }

    /** Register the process-wide cache backend. Only one is active at a time;
     *  the most recent registration wins. Pass {@code null} to disable caching. */
    public static void registerCache(CacheStore store) {
        resolveVar("bff.cache", "register-cache!").invoke(store);
    }

    /** Append a context enricher to the ordered chain that runs at the top of
     *  every GraphQL operation. Enrichers fire in registration order and each
     *  sees the ctx accumulated by earlier enrichers. */
    public static void registerContextEnricher(BffContextEnricher enricher) {
        resolveVar("bff.enricher", "register-enricher!").invoke(enricher);
    }

    /**
     * Load and compile the spec at {@code specPath} (classpath resource) and
     * return the underlying Ring handler as a Clojure {@link IFn}. Use this if
     * you already have a Ring-to-servlet bridge you prefer.
     */
    public static IFn createHandler(String specPath) {
        return (IFn) resolveVar("bff.core", "create-handler").invoke(specPath);
    }

    /**
     * Load and compile the spec at {@code specPath} and return an
     * {@link HttpServlet} ready to mount into any Jakarta Servlet 6 container —
     * including Spring Boot 3 via {@code ServletRegistrationBean}.
     */
    public static HttpServlet createServlet(String specPath) {
        return new BffServlet(createHandler(specPath));
    }
}
