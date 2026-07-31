# Extensions

Seven extension points let you plug custom logic into the request pipeline. All
extensions are supplied to `bff.core/create-handler` (or the Java
`Bff.createHandler` / `Bff.createServlet` overloads) via a single immutable
config — there is no global registry and no runtime registration.

Each extension follows the same pattern: a Clojure protocol with an `IFn`
extension (so plain functions work without wrapping), a Java interface under
`io.github.rthadani.bff.*` (so Java classes are first-class), and — for the
three request-scoped points — a convenience base class that hides the
boilerplate.

| Extension          | When it runs                             | Referenced in spec as                | Config key       | Java interface                                    |
|--------------------|------------------------------------------|--------------------------------------|------------------|---------------------------------------------------|
| context enricher   | First, once per request                  | (implicit, ordered chain)            | `:enrichers`     | `io.github.rthadani.bff.BffContextEnricher`       |
| `validator`        | After enrichment, before the chain       | `validator:`                         | `:validators`    | `io.github.rthadani.bff.BffValidator`             |
| `transformer`      | After output mapping                     | `transformer:`                       | `:transformers`  | `io.github.rthadani.bff.BffTransformer`           |
| `resolver`         | Instead of the backend chain             | `resolver:`                          | `:resolvers`     | `io.github.rthadani.bff.BffResolver`              |
| cache backend      | Around every cacheable step              | (single instance)                    | `:cache`         | `io.github.rthadani.bff.CacheStore`               |
| retry hook         | Between step attempts (when retry fires) | inside a step's `retry.before_retry` | `:retry-hooks`   | `io.github.rthadani.bff.BffRetryHook`             |
| custom scalar      | Every input coerce / output serialize    | top-level `scalars:` block           | `:scalars`       | `io.github.rthadani.bff.BffScalar`                |

Java authors have two options at every extension point that ships with a base
class:

1. **Implement the interface directly.** Args and ctx arrive as
   `Map<String, Object>` with String keys. Minimum ceremony; composes cleanly
   with Spring's `@Component`.
2. **Extend the convenience base class.** Gets you typed helpers
   (`ResolverResult`, `StepResult`) at the cost of one extra layer of
   inheritance.

Plain Clojure functions continue to work everywhere — the Java interfaces are
purely additive.

## The extensions map

Both `bff.core/create-handler` and Java's `Bff.createHandler` accept a single
extensions config. In Clojure it is a plain map:

```clojure
(bff/create-handler
  "bff-spec.yaml"
  {:enrichers    [customer-enricher tenant-enricher]        ; ordered seq
   :validators   {"check-order"       order-validator}      ; keyed
   :transformers {"attach-warnings"   warnings-transformer}
   :resolvers    {"user-profile"      user-profile-resolver}
   :retry-hooks  {"auth-token-refresh" token-refresh-hook}
   :cache         redis-cache-store})
```

In Java, use the `BffConfig.Builder`:

```java
BffConfig config = BffConfig.builder()
    .enricher(customerEnricher)
    .enricher(tenantEnricher)
    .validator("check-order",       orderValidator)
    .transformer("attach-warnings", warningsTransformer)
    .resolver("user-profile",       userProfileResolver)
    .retryHook("auth-token-refresh", tokenRefreshHook)
    .cache(redisCacheStore)
    .build();
```

All keys are optional. The handler closes over the config once at startup —
there is no way to add extensions after `create-handler` returns.

---

## Context enricher

Runs once at the top of every GraphQL operation, before validators and the
backend chain. Purpose: pre-compute values into `ctx` that every downstream
step or validator can read without repeating the lookup — the canonical case
is fetching a customer / equipment identifier from Redis using the JWT
subject.

Enrichers run in the order they appear in `:enrichers`; each sees the ctx
accumulated by earlier enrichers. An enricher's return value is merged into
ctx (return `nil` to leave ctx unchanged).

### Protocol

```clojure
(defprotocol BffContextEnricher
  (enrich [this ctx]))
```

### Clojure

```clojure
(defn customer-enricher
  [{:keys [authorization]}]
  (let [subject     (jwt/subject authorization)
        customer-id (redis/hget (str "user:" subject) "customerId")]
    {:customerId customer-id}))
```

Register by adding it to `:enrichers` in your config:

```clojure
(bff/create-handler "spec.yaml" {:enrichers [customer-enricher]})
```

### Java — interface

```java
import io.github.rthadani.bff.BffContextEnricher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

public class CustomerEnricher implements BffContextEnricher {
    private final StringRedisTemplate redis;

    public CustomerEnricher(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Map<String, Object> enrich(Map<String, Object> ctx) {
        String subject = JwtUtil.subject((String) ctx.get("authorization"));
        Object cust    = redis.opsForHash().get("user:" + subject, "customerId");
        return cust == null ? null : Map.of("customerId", cust.toString());
    }
}
```

Register on the builder: `.enricher(new CustomerEnricher(redis))`.

Downstream, an endpoint or step can read the enriched value via a `ctx`
source mapping:

```yaml
input_mapping:
  customerId:
    source: ctx
    key:    customerId
```

---

## Validator

Runs before any backend call. Return errors to short-circuit; the chain is
never touched. Both built-in arg rules and a custom validator can be declared
on the same endpoint — built-in rules run first, then the custom validator.

### Protocol

```clojure
(defprotocol BffValidator
  (validate [this args ctx]))
```

Return `nil` or `[]` to pass. Return `[{:message "..."}]` to fail.

### Clojure — ns/fn

```clojure
(ns my.project.validators)

(defn check-order
  [args _ctx]
  (when (and (= "GBP" (:currency args))
             (> (:amount args) 10000))
    [{:message "GBP orders above 10000 require manual approval"}]))
```

```yaml
validator:
  ns: my.project.validators
  fn: check-order
```

### Clojure — by key

```clojure
(defn check-order [args _ctx] ...)

(bff/create-handler "spec.yaml"
  {:validators {"check-order" check-order}})
```

```yaml
validator:
  key: check-order
```

### Java — interface

```java
import io.github.rthadani.bff.BffValidator;
import java.util.List;
import java.util.Map;

public class OrderValidator implements BffValidator {
    @Override
    public List<Map<String, Object>> validate(Map<String, Object> args, Map<String, Object> ctx) {
        if ("GBP".equals(args.get("currency"))
                && ((Number) args.get("amount")).doubleValue() > 10000) {
            return List.of(Map.of("message", "GBP orders above 10000 require manual approval"));
        }
        return List.of();
    }
}
```

### Java — base class

Extend `BaseValidator` when you'd rather return a `List<String>` of messages
and let the base class wrap them into the expected error shape.

```java
import bff.validator.BaseValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrderValidator extends BaseValidator {
    @Override
    protected List<String> doValidate(Map<String, Object> args, Map<String, Object> ctx) {
        List<String> errors = new ArrayList<>();
        Double amount   = (Double) args.get("amount");
        String currency = (String) args.get("currency");
        if (amount != null && amount > 10000 && "GBP".equals(currency)) {
            errors.add("GBP orders above 10000 require manual approval");
        }
        return errors;
    }
}
```

Register on the builder: `.validator("check-order", new OrderValidator())`.

---

## Transformer

Runs after jq output mappings are applied. Receives the GraphQL args, the full
step result map, and the already-mapped output. Returns the final output map.

### Protocol

```clojure
(defprotocol BffTransformer
  (transform [this args chain-ctx mapped]))
```

### Clojure — ns/fn

```clojure
(ns my.project.transformers.orders)

(defn attach-warnings
  [_args chain-ctx output]
  (assoc output :warnings
         (cond-> []
           (= :error (get-in chain-ctx [:notify_user :status]))
           (conj "Notification could not be sent"))))
```

```yaml
transformer:
  ns: my.project.transformers.orders
  fn: attach-warnings
```

### Clojure — by key

```clojure
(bff/create-handler "spec.yaml"
  {:transformers {"attach-warnings" attach-warnings}})
```

```yaml
transformer:
  key: attach-warnings
```

### Java — interface

`chainCtx` is a `Map<String, Object>` where each value is itself a
`Map<String, Object>` — the raw step result with a `"status"` key
(`"ok"` or `"error"`), a `"data"` key when successful, and an `"error"`
sub-map when failed.

```java
import io.github.rthadani.bff.BffTransformer;
import java.util.Map;

public class AttachWarningsTransformer implements BffTransformer {
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> transform(Map<String, Object> args,
                                         Map<String, Object> chainCtx,
                                         Map<String, Object> output) {
        Map<String, Object> notify = (Map<String, Object>) chainCtx.get("notify_user");
        if ("error".equals(notify.get("status"))) {
            output.put("warning", "Notification could not be sent");
        }
        return output;
    }
}
```

### Java — base class

`BaseTransformer` unpacks `chainCtx` into `Map<String, StepResult>` for you.
`StepResult` has `isOk()`, `isError()`, `getData()`, and `getMessage()`.

```java
import bff.executor.BaseTransformer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AttachWarningsTransformer extends BaseTransformer {
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
```

Register on the builder: `.transformer("attach-warnings", new AttachWarningsTransformer())`.

---

## Resolver

Replaces the backend chain entirely for endpoints that don't fit the HTTP fan-out
model — database calls, cache lookups, local computation. The resolver owns the
full `{:data :errors}` response; no output mapping or transformer runs after it.

### Protocol

```clojure
(defprotocol BffResolver
  (resolve-endpoint [this args ctx]))
```

Return `{:data {...} :errors [...]}`.

### Clojure — ns/fn

```clojure
(ns my.project.resolvers)

(defn user-profile [args _ctx]
  (let [user (db/find-user (:userId args))]
    {:data   {:fullName (:name user) :email (:email user)}
     :errors []}))
```

```yaml
resolver:
  ns: my.project.resolvers
  fn: user-profile
```

### Clojure — by key

```clojure
(bff/create-handler "spec.yaml"
  {:resolvers {"user-profile" user-profile}})
```

```yaml
resolver:
  key: user-profile
```

### Java — interface

Return a `Map<String, Object>` with `"data"` and `"errors"` keys. Keys inside
`data` become the endpoint's `output_type` fields.

```java
import io.github.rthadani.bff.BffResolver;
import java.util.List;
import java.util.Map;

public class UserProfileResolver implements BffResolver {
    private final UserRepository repo;

    public UserProfileResolver(UserRepository repo) { this.repo = repo; }

    @Override
    public Map<String, Object> resolve(Map<String, Object> args, Map<String, Object> ctx) {
        String userId = (String) args.get("userId");
        User user = repo.findById(userId);
        if (user == null) {
            return Map.of("data", Map.of(),
                          "errors", List.of(Map.of("message", "User not found: " + userId)));
        }
        return Map.of("data", Map.of("fullName", user.getName(), "email", user.getEmail()),
                      "errors", List.of());
    }
}
```

### Java — base class

`BaseResolver` builds the response for you via `ResolverResult`.

```java
import bff.executor.BaseResolver;
import java.util.Map;

public class UserProfileResolver extends BaseResolver {
    private final UserRepository repo;

    public UserProfileResolver(UserRepository repo) { this.repo = repo; }

    @Override
    protected ResolverResult doResolve(Map<String, Object> args, Map<String, Object> ctx) {
        String userId = (String) args.get("userId");
        User user = repo.findById(userId);
        if (user == null) {
            return ResolverResult.error("User not found: " + userId, "USER_NOT_FOUND");
        }
        return ResolverResult.ok(Map.of(
            "fullName", user.getName(),
            "email",    user.getEmail()
        ));
    }
}
```

`ResolverResult.withError` lets you return partial data alongside errors, and
either factory takes an optional machine-readable code that surfaces under
`extensions.code`:

```java
return ResolverResult.ok(Map.of("fullName", user.getName()))
                     .withError("email service unavailable", "EMAIL_UNAVAILABLE");
```

Clojure resolvers surface the same shape by returning `{:message "..." :code "..."}`
in `:errors` — the engine lifts a top-level `:code` into `:extensions.code`
automatically.

Register on the builder: `.resolver("user-profile", new UserProfileResolver(repo))`.

---

## Cache backend

A single instance registered on the handler. Every backend step that declares
`cache: {key: "..."}` in the spec routes reads and writes through the
configured implementation. Any exception thrown by the store is logged and
swallowed — cache failures never propagate to the GraphQL response.

### Protocol

```clojure
(defprotocol CacheStore
  (cache-get        [this key])
  (cache-put        [this key value ttl-ms])
  (cache-invalidate [this key]))
```

### Clojure

```clojure
(def store
  (reify bff.cache/CacheStore
    (cache-get        [_ k]         (get @cache-atom k))
    (cache-put        [_ k v _ttl]  (swap! cache-atom assoc k v))
    (cache-invalidate [_ k]         (swap! cache-atom dissoc k))))

(bff/create-handler "spec.yaml" {:cache store})
```

### Java — interface

```java
import io.github.rthadani.bff.CacheStore;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisCacheStore implements CacheStore {
    private final StringRedisTemplate redis;

    public RedisCacheStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Object get(String key) {
        return redis.opsForValue().get(key);
    }

    @Override
    public void put(String key, Object value, long ttlMs) {
        redis.opsForValue().set(key, value.toString(), java.time.Duration.ofMillis(ttlMs));
    }

    @Override
    public void invalidate(String key) {
        redis.delete(key);
    }
}
```

Register on the builder: `.cache(new RedisCacheStore(redis))`.

---

## Retry hook

Runs between attempts of a backend step whose `retry:` config declared a
`before_retry` reference. Purpose: rewrite the request-ctx (typically to
inject a refreshed auth header) before the next call. If no hook is
declared, retries reuse the current ctx unchanged.

The step spec:

```yaml
- id: fetch_customer
  url: "{service_base}/portal/customers/{id}"
  method: GET
  retry:
    max: 2
    on_code: [unauthorized]
    before_retry:
      key: auth-token-refresh
```

See [spec.md](spec.md#retryable-error-codes) for the list of codes accepted
by `on_code`.

### Protocol

```clojure
(defprotocol BffRetryHook
  (before-retry [this failure-context]))
```

The `failure-context` map contains:

| Key            | Value                                             |
|----------------|---------------------------------------------------|
| `:step-id`     | Keyword id of the step that just failed           |
| `:attempt`     | 1-indexed retry number about to happen            |
| `:args`        | GraphQL input arguments                           |
| `:chain-ctx`   | Results of steps completed so far                 |
| `:request-ctx` | Current request context (headers, remote-addr)   |
| `:error`       | The `{:code :message :detail}` error map          |

Return a new request-ctx map to use for the retry, or `nil` to reuse the
current one.

### Clojure — ns/fn

```clojure
(ns my.project.retry)

(defn auth-token-refresh
  [{:keys [request-ctx]}]
  (let [fresh-token (auth-client/refresh-service-token!)]
    (assoc request-ctx :authorization (str "Bearer " fresh-token))))
```

```yaml
retry:
  before_retry:
    ns: my.project.retry
    fn: auth-token-refresh
```

### Clojure — by key

```clojure
(bff/create-handler "spec.yaml"
  {:retry-hooks {"auth-token-refresh" auth-token-refresh}})
```

```yaml
retry:
  before_retry:
    key: auth-token-refresh
```

### Java — interface

```java
import io.github.rthadani.bff.BffRetryHook;
import java.util.HashMap;
import java.util.Map;

public class TokenRefreshHook implements BffRetryHook {
    private final AuthTokenService tokens;

    public TokenRefreshHook(AuthTokenService tokens) { this.tokens = tokens; }

    @Override
    public Map<String, Object> beforeRetry(Map<String, Object> failureContext) {
        @SuppressWarnings("unchecked")
        Map<String, Object> requestCtx = (Map<String, Object>) failureContext.get("request-ctx");
        Map<String, Object> next = new HashMap<>(requestCtx);
        next.put("authorization", "Bearer " + tokens.refresh());
        return next;
    }
}
```

Register on the builder: `.retryHook("auth-token-refresh", new TokenRefreshHook(tokens))`.

---

## Custom scalar

Declare a scalar type in the spec's top-level `scalars:` block, then supply a
`{parse, serialize}` implementation in the handler config under `:scalars`.
The engine wires them into Lacinia's `:scalars` schema section at build time
— they are indistinguishable from Lacinia's built-in `String`/`Int`/`Float`
scalars from a client's perspective.

- **parse** — client input (usually a string from JSON) → internal value.
  Throw on invalid input; Lacinia surfaces the exception as a coercion error.
- **serialize** — internal value → JSON primitive
  (`String` / `Number` / `Boolean` / `nil`).

```yaml
scalars:
  - name: DateTime
    description: "ISO-8601 timestamp"
  - name: Mac
    description: "MAC address in colon-hex form"
```

If a spec declares a scalar with no matching entry in `:scalars`, the handler
fails fast at `create-handler` time with `ex-info` naming the missing scalar.

### Protocol

```clojure
(defprotocol BffScalar
  (parse     [this value])
  (serialize [this value]))
```

### Clojure — map form

A plain map with `:parse` and `:serialize` keys satisfies the protocol:

```clojure
(def mac-scalar
  {:parse     (fn [v]
                (let [s (str/lower-case (str v))]
                  (or (re-matches #"^([0-9a-f]{2}:){5}[0-9a-f]{2}$" s)
                      (throw (ex-info (str "Not a MAC address: " v) {:value v})))
                  s))
   :serialize identity})

(bff/create-handler "spec.yaml" {:scalars {"Mac" mac-scalar}})
```

### Built-in scalars

`bff.scalar` ships four convenience scalars — drop them straight into the
config:

| Var                          | GraphQL type name (by convention) | Backed by                   |
|------------------------------|-----------------------------------|-----------------------------|
| `bff.scalar/date-time`       | `DateTime`                        | `java.time.Instant`         |
| `bff.scalar/date`            | `Date`                            | `java.time.LocalDate`       |
| `bff.scalar/local-date-time` | `LocalDateTime`                   | `java.time.LocalDateTime`   |
| `bff.scalar/uuid`            | `Uuid`                            | `java.util.UUID`            |

```clojure
(require '[bff.scalar :as scalar])

(bff/create-handler "spec.yaml"
  {:scalars {"DateTime" scalar/date-time
             "Date"     scalar/date
             "Uuid"     scalar/uuid}})
```

### Java — interface

```java
import io.github.rthadani.bff.BffScalar;

public class MacScalar implements BffScalar {
    private static final java.util.regex.Pattern MAC =
        java.util.regex.Pattern.compile("^([0-9a-f]{2}:){5}[0-9a-f]{2}$",
                                        java.util.regex.Pattern.CASE_INSENSITIVE);

    @Override public Object parse(Object value) {
        String s = value.toString().toLowerCase();
        if (!MAC.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a MAC address: " + value);
        }
        return s;
    }

    @Override public Object serialize(Object value) {
        return value.toString();
    }
}
```

Register on the builder: `.scalar("Mac", new MacScalar())`.
