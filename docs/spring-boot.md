# Spring Boot 3 integration

BFF ships with `io.github.rthadani.bff.Bff` — a static Java facade that
handles Clojure loading, extension registration, and Jakarta servlet wiring
for you. There is no Ring bridge to hand-roll.

## Dependencies (`pom.xml`)

```xml
<dependency>
    <groupId>io.github.rthadani</groupId>
    <artifactId>bff</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>org.clojure</groupId>
    <artifactId>clojure</artifactId>
    <version>1.12.0</version>
</dependency>
```

`jakarta.servlet-api` is declared as `provided` in the BFF POM — your Spring
Boot 3 starter already brings its own copy, so nothing more to add. Place your
spec file in `src/main/resources/`.

## Mounting the servlet

The simplest route is `Bff.createServlet(specPath)` behind a
`ServletRegistrationBean`. All extension registrations must happen before
the servlet is created (the spec is compiled at that point), so put them in
the same `@Bean` method or use `@DependsOn` to enforce ordering.

```java
import io.github.rthadani.bff.Bff;
import jakarta.servlet.http.HttpServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BffConfiguration {

    private final BffExtensions extensions;

    public BffConfiguration(BffExtensions extensions) {
        this.extensions = extensions;
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> bffServlet() {
        extensions.registerAll();
        ServletRegistrationBean<HttpServlet> reg =
            new ServletRegistrationBean<>(Bff.createServlet("bff-spec.yaml"), "/graphql");
        reg.setName("bff");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
```

For a controller-style mount instead of the servlet, use
`Bff.createHandler(specPath)` and call it yourself.

## Registering extensions

Extensions can implement the raw Java interfaces under
`io.github.rthadani.bff.*` or extend the convenience base classes — pick per
extension. See [extensions.md](extensions.md) for the full API.

```java
import bff.executor.BaseTransformer;
import bff.executor.BaseResolver;
import bff.validator.BaseValidator;
import io.github.rthadani.bff.Bff;
import io.github.rthadani.bff.BffContextEnricher;
import io.github.rthadani.bff.CacheStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BffExtensions {

    @Autowired StringRedisTemplate redis;

    public void registerAll() {
        Bff.registerContextEnricher(new CustomerEnricher(redis));
        Bff.registerTransformer("attach-warnings", new WarningsTransformer());
        Bff.registerValidator  ("check-order",     new OrderValidator());
        Bff.registerResolver   ("user-profile",    new UserProfileResolver());
        Bff.registerCache(new RedisCacheStore(redis));
    }

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

BFF has no built-in auth — it never sees JWTs and never mints tokens. Put
Spring Security's `SecurityFilterChain` in front of the servlet path:

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

See [extensions.md](extensions.md) for full documentation on each extension type.
