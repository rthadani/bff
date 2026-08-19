# Spring Boot 3 integration

BFF ships with `io.github.rthadani.bff.Bff`, a static Java facade that handles Clojure loading and Jakarta servlet wiring. Pass a `BffConfig` at startup with all your extensions.

## Dependencies (`pom.xml`)

```xml
<dependency>
    <groupId>io.github.rthadani</groupId>
    <artifactId>bff</artifactId>
    <version>0.2.0</version>
</dependency>
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.0</version>
</dependency>
```

`jakarta.servlet-api` is declared as `provided` in the BFF POM. Your Spring Boot 3 starter already brings its own copy. Place your spec file in `src/main/resources/`.

## Mounting the servlet

Build a `BffConfig` from your Spring beans, then pass it to `Bff.createServlet` in a `ServletRegistrationBean`.

```java
import io.github.rthadani.bff.Bff;
import io.github.rthadani.bff.BffConfig;
import jakarta.servlet.http.HttpServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BffConfiguration {

    @Bean
    public BffConfig bffConfig(BffExtensions ext) {
        return BffConfig.builder()
            .enricher   (ext.customerEnricher())
            .validator  ("check-order",       ext.orderValidator())
            .transformer("attach-warnings",   ext.warningsTransformer())
            .resolver   ("user-profile",      ext.userProfileResolver())
            .retryHook  ("auth-token-refresh", ext.tokenRefreshHook())
            .cache(ext.redisCacheStore())
            .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> bffServlet(BffConfig config) {
        ServletRegistrationBean<HttpServlet> reg =
            new ServletRegistrationBean<>(Bff.createServlet("bff-spec.yaml", config), "/graphql");
        reg.setName("bff");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
```

For a controller-style mount instead of the servlet, use `Bff.createHandler(specPath, config)`.

## Extension implementations

Extensions can implement the interfaces under `io.github.rthadani.bff.*` or extend the convenience base classes. See [extensions.md](extensions.md) for the full API.

```java
import bff.executor.BaseTransformer;
import bff.executor.BaseResolver;
import bff.validator.BaseValidator;
import io.github.rthadani.bff.BffContextEnricher;
import io.github.rthadani.bff.BffRetryHook;
import io.github.rthadani.bff.CacheStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class BffExtensions {

    @Autowired StringRedisTemplate redis;
    @Autowired AuthTokenService    tokens;

    public BffContextEnricher customerEnricher()   { return new CustomerEnricher(redis); }
    public BaseValidator      orderValidator()     { return new OrderValidator(); }
    public BaseTransformer    warningsTransformer(){ return new WarningsTransformer(); }
    public BaseResolver       userProfileResolver(){ return new UserProfileResolver(); }
    public BffRetryHook       tokenRefreshHook()   { return new TokenRefreshHook(tokens); }
    public CacheStore         redisCacheStore()    { return new RedisCacheStore(redis); }

    static class WarningsTransformer extends BaseTransformer {
        @Override
        protected Map<String, Object> doTransform(
                Map<String, Object>     args,
                Map<String, StepResult> chainCtx,
                Map<String, Object>     output) {
            List<String> warnings = new ArrayList<>();
            if (chainCtx.get("notify_user").isError()) {
                warnings.add("Notification could not be sent");
            }
            output.put("warnings", warnings);
            return output;
        }
    }

    static class OrderValidator extends BaseValidator {
        @Override
        protected List<String> doValidate(Map<String, Object> args, Map<String, Object> ctx) {
            List<String> errors = new ArrayList<>();
            Double amount = (Double) args.get("amount");
            if (amount != null && amount > 10000) {
                errors.add("Orders above 10000 require manual approval");
            }
            return errors;
        }
    }

    static class UserProfileResolver extends BaseResolver {
        @Override
        protected ResolverResult doResolve(Map<String, Object> args, Map<String, Object> ctx) {
            return ResolverResult.ok(Map.of("fullName", "Alice", "email", "alice@example.com"));
        }
    }

    static class CustomerEnricher implements BffContextEnricher {
        private final StringRedisTemplate redis;
        CustomerEnricher(StringRedisTemplate redis) { this.redis = redis; }

        @Override public Map<String, Object> enrich(Map<String, Object> ctx) {
            String subject = JwtUtil.subject((String) ctx.get("authorization"));
            Object cust    = redis.opsForHash().get("user:" + subject, "customerId");
            return cust == null ? null : Map.of("customerId", cust.toString());
        }
    }

    static class TokenRefreshHook implements BffRetryHook {
        private final AuthTokenService tokens;
        TokenRefreshHook(AuthTokenService tokens) { this.tokens = tokens; }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> beforeRetry(Map<String, Object> failureContext) {
            Map<String, Object> requestCtx = (Map<String, Object>) failureContext.get("request-ctx");
            Map<String, Object> next = new HashMap<>(requestCtx);
            next.put("authorization", "Bearer " + tokens.refresh());
            return next;
        }
    }

    static class RedisCacheStore implements CacheStore {
        private final StringRedisTemplate redis;
        RedisCacheStore(StringRedisTemplate redis) { this.redis = redis; }

        @Override public Object get(String key) {
            return redis.opsForValue().get(key);
        }
        @Override public void put(String key, Object value, long ttlMs) {
            redis.opsForValue().set(key, value.toString(), Duration.ofMillis(ttlMs));
        }
        @Override public void invalidate(String key) {
            redis.delete(key);
        }
    }
}
```

## Security

BFF has no built-in auth. Put Spring Security's `SecurityFilterChain` in front of the servlet path:

```java
@Bean
public SecurityFilterChain bffSecurity(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/graphql", "/graphiql")
        .oauth2ResourceServer(o -> o.jwt())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/graphiql").permitAll()
            .anyRequest().authenticated())
        .build();
}
```

The `authorization` header (and `x-request-id`, `x-correlation-id`) are
forwarded to every downstream HTTP step automatically. See
`forward_headers` in the [spec reference](spec.md) if you need to widen or
narrow that list.

## Migrating from the register-*! API

Before 0.2.0 extensions were registered globally via `Bff.registerValidator`,
`Bff.registerCache`, etc. Those methods are gone. Replace each call with the
corresponding builder method on `BffConfig.builder()` and pass the built
config to `Bff.createHandler(spec, config)` or
`Bff.createServlet(spec, config)`. The `bff.*/register-*!` Clojure functions
and the process-wide atoms are also gone.

See [extensions.md](extensions.md) for full documentation on each extension type.
