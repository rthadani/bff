# Extensions

Six extension points let you plug custom logic into the request pipeline.
Each follows the same pattern: a Clojure protocol with an `IFn` extension
(so plain functions work without wrapping), a Java interface under
`io.github.rthadani.bff.*` (so Java classes are first-class), and — for the
three request-scoped points — a convenience base class that hides the
boilerplate.

| Extension          | When it runs                             | Declared in spec as                  | Java interface                                    |
|--------------------|------------------------------------------|--------------------------------------|---------------------------------------------------|
| context enricher   | First, once per request                  | (registered once)                    | `io.github.rthadani.bff.BffContextEnricher`       |
| `validator`        | After enrichment, before the chain       | `validator:`                         | `io.github.rthadani.bff.BffValidator`             |
| `transformer`      | After output mapping                     | `transformer:`                       | `io.github.rthadani.bff.BffTransformer`           |
| `resolver`         | Instead of the backend chain             | `resolver:`                          | `io.github.rthadani.bff.BffResolver`              |
| cache backend      | Around every cacheable step              | (registered once)                    | `io.github.rthadani.bff.CacheStore`               |
| retry hook         | Between step attempts (when retry fires) | inside a step's `retry.before_retry` | *(Clojure only for now)*                          |

Java authors have two options at every extension point that ships with a Java
interface:

1. **Implement the interface directly.** Args and ctx arrive as
   `Map<String, Object>` with String keys; conversion to/from Clojure keywords
   is handled at the interop boundary. This is the minimum-ceremony route and
   composes cleanly with Spring's `@Component`.
2. **Extend the convenience base class.** Gets you typed helpers
   (`ResolverResult`, `StepResult`) at the cost of one extra layer of
   inheritance. Recommended when the interface's raw `Map` surface would be
   awkward.

Plain Clojure functions continue to work everywhere — the Java interfaces are
purely additive.

---

## Context enricher

Runs once at the top of every GraphQL operation, before validators and the
backend chain. Purpose: pre-compute values into `ctx` that every downstream
step or validator can read without repeating the lookup — the canonical case
is fetching a customer / equipment identifier from Redis using the JWT
subject.

Multiple enrichers can be registered; they run in registration order and each
sees the ctx accumulated by earlier enrichers. An enricher's return value is
merged into ctx (return `nil` to leave ctx alone).

### Protocol

```clojure
(defprotocol BffContextEnricher
  (enrich [this ctx]))
```

### Clojure — registration

```clojure
(require '[bff.enricher :as enricher])

(enricher/register-enricher!
  (fn [{:keys [authorization]}]
    (let [subject     (jwt/subject authorization)
          customer-id (redis/hget (str "user:" subject) "customerId")]
      {:customerId customer-id})))
```

### Java — interface

```java
import io.github.rthadani.bff.BffContextEnricher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

public class CustomerEnricher implements BffContextEnricher {
    private final StringRedisTemplate redis;

    public CustomerEnricher(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Map<String, Object> enrich(Map<String, Object> ctx) {
        String subject = JwtUtil.subject((String) ctx.get("authorization"));
        String cust    = redis.opsForHash().get("user:" + subject, "customerId").toString();
        return Map.of("customerId", cust);
    }
}
```

Register the instance once at application startup — see
[Spring Boot integration](spring-boot.md).

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

Runs before any backend call. Return errors to short-circuit; the chain is never
touched. Both built-in arg rules and a custom validator can be declared on the
same endpoint — built-in rules run first, then the custom validator.

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
  [args ctx]
  (when (and (= "GBP" (:currency args))
             (> (:amount args) 10000))
    [{:message "GBP orders above 10000 require manual approval"}]))
```

```yaml
validator:
  ns: my.project.validators
  fn: check-order
```

### Clojure — registered by key

```clojure
(require '[bff.validator :as validator])

(validator/register-validator! "check-order"
  (fn [args _ctx]
    (when (> (:amount args) 10000)
      [{:message "amount exceeds limit"}])))
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

Register the instance before `bff.core/create-handler` runs — see the
[Spring Boot integration](spring-boot.md) for the recommended pattern.

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
  [args chain-ctx output]
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

### Clojure — registered by key

```clojure
(require '[bff.executor :as executor])

(executor/register-transformer! "attach-warnings"
  (fn [args chain-ctx output]
    (assoc output :warnings [])))
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

(defn user-profile [args ctx]
  (let [user (db/find-user (:userId args))]
    {:data   {:fullName (:name user) :email (:email user)}
     :errors []}))
```

```yaml
resolver:
  ns: my.project.resolvers
  fn: user-profile
```

### Clojure — registered by key

```clojure
(require '[bff.executor :as executor])

(executor/register-resolver! "user-profile"
  (fn [args ctx]
    {:data {:fullName "Alice"} :errors []}))
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
            return ResolverResult.error("User not found: " + userId);
        }
        return ResolverResult.ok(Map.of(
            "fullName", user.getName(),
            "email",    user.getEmail()
        ));
    }
}
```

`ResolverResult.withError` lets you return partial data alongside errors:

```java
return ResolverResult.ok(Map.of("fullName", user.getName()))
                     .withError("email service unavailable");
```

---

## Cache backend

Registered once at startup. Every backend step that declares
`cache: {key: "..."}` in the spec routes reads and writes through the configured
implementation. Any exception thrown by the store is logged and swallowed —
cache failures never propagate to the GraphQL response.

### Protocol

```clojure
(defprotocol CacheStore
  (cache-get        [this key])
  (cache-put        [this key value ttl-ms])
  (cache-invalidate [this key]))
```

### Clojure — registration

```clojure
(require '[bff.cache :as cache])

(cache/register-cache!
  (reify cache/CacheStore
    (cache-get        [_ k]         (get @store k))
    (cache-put        [_ k v _ttl]  (swap! store assoc k v))
    (cache-invalidate [_ k]         (swap! store dissoc k))))
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

Register the instance once at application startup — see
[Spring Boot integration](spring-boot.md) for the recommended pattern.

---

## Retry hook

Runs between attempts of a backend step whose `retry:` config declared a
`before_retry` reference. Purpose: rewrite the request-ctx (typically to
inject a refreshed auth header) before the next call. If no hook is
declared, retries reuse the current ctx unchanged.

The step spec:

```yaml
- id: fetch_customer
  url: "{cmap_base}/portal/customers/{id}"
  method: GET
  retry:
    max: 2
    on_code: [unauthorized]
    before_retry:
      key: cmap-token-refresh
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

(defn cmap-token-refresh
  [{:keys [request-ctx]}]
  (let [fresh-token (cmap-client/refresh-service-token!)]
    (assoc request-ctx :authorization (str "Bearer " fresh-token))))
```

```yaml
retry:
  before_retry:
    ns: my.project.retry
    fn: cmap-token-refresh
```

### Clojure — registered by key

```clojure
(require '[bff.retry :as retry])

(retry/register-retry-hook! "cmap-token-refresh"
  (fn [{:keys [request-ctx]}]
    (assoc request-ctx :authorization
           (str "Bearer " (cmap-client/refresh-service-token!)))))
```

```yaml
retry:
  before_retry:
    key: cmap-token-refresh
```
