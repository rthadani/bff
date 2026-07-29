# Extensions

Three extension points let you plug custom logic into the request pipeline.
All three follow the same pattern: a Clojure protocol with an `IFn` extension
(so plain functions work without wrapping), a registry for pre-registered
instances, and a Java base class that hides Clojure types.

| Extension     | When it runs                        | Declared in spec as |
|---------------|-------------------------------------|---------------------|
| `validator`   | Before the backend chain            | `validator:`        |
| `transformer` | After output mapping                | `transformer:`      |
| `resolver`    | Instead of the backend chain        | `resolver:`         |

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

### Java

Extend `BaseValidator`. Return an empty list to pass, or a list of error messages
to fail. All Clojure types are handled internally.

```java
import bff.validator.BaseValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrderValidator extends BaseValidator {
    @Override
    public List<String> validate(Map<String, Object> args, Map<String, Object> ctx) {
        List<String> errors = new ArrayList<>();
        Double amount = (Double) args.get("amount");
        String currency = (String) args.get("currency");
        if (amount != null && amount > 10000 && "GBP".equals(currency)) {
            errors.add("GBP orders above 10000 require manual approval");
        }
        return errors;
    }
}

// Registration — call before bff.core/create-handler
Clojure.var("bff.validator", "register-validator!")
       .invoke("check-order", new OrderValidator());
```

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

### Java

Extend `BaseTransformer`. The `chainCtx` parameter gives you a `StepResult` per
step with `isOk()`, `isError()`, and `getData()`.

```java
import bff.executor.BaseTransformer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AttachWarningsTransformer extends BaseTransformer {
    @Override
    public Map<String, Object> transform(
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

// Registration — call before bff.core/create-handler
Clojure.var("bff.executor", "register-transformer!")
       .invoke("attach-warnings", new AttachWarningsTransformer());
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

### Java

Extend `BaseResolver`. Return a `ResolverResult` built with the static factories.
The `output_type` in the spec still defines the GraphQL schema; the resolver must
return keys that match those fields.

```java
import bff.executor.BaseResolver;
import java.util.Map;

public class UserProfileResolver extends BaseResolver {
    private final UserRepository repo;

    public UserProfileResolver(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public ResolverResult resolve(Map<String, Object> args, Map<String, Object> ctx) {
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

// Registration — call before bff.core/create-handler
Clojure.var("bff.executor", "register-resolver!")
       .invoke("user-profile", new UserProfileResolver(userRepo));
```

`ResolverResult.withError` lets you return partial data alongside errors:

```java
return ResolverResult.ok(Map.of("fullName", user.getName()))
                     .withError("email service unavailable");
```
