# BFF Engine

[![Clojars Project](https://img.shields.io/clojars/v/io.github.rthadani/bff.svg)](https://clojars.org/io.github.rthadani/bff)

A spec-driven GraphQL Backend-for-Frontend engine in Clojure.  
Write a YAML spec → get a fully functional GraphQL API. No boilerplate per endpoint.

---

## Quick start

```bash
clj -M -m my.bff.core resources/bff-spec.yaml
# → GraphQL server on http://localhost:8080
```

Send a query:

```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"query": "{ userDashboard(userId: \"u123\", includeActivity: true) { fullName email activityCount recentTitles } }"}'
```

---

## Architecture

```
bff-spec.yaml
     │
     ├── spec_loader      load YAML + pre-compile all jq expressions
     │
     ├── schema_builder   generate Lacinia schema (objects, queries, mutations)
     │        │
     │        └── make-resolver  per endpoint:
     │
     └── executor
          │
          ├── graph        dep graph → execution waves (topological sort)
          │
          ├── [Wave 0]     missionary m/join → parallel HTTP calls
          ├── [Wave 1]     missionary m/join → parallel HTTP calls
          │    ...
          │
          ├── jq_engine    apply compiled jq expressions to map I/O
          ├── error        tag results, collect partial failures
          └── transformer  optional BffTransformer (Clojure fn or Java class)
```

---

## Spec reference

### Environment variables

Any string value in the spec can reference an environment variable:

```yaml
url: "${USER_SERVICE_URL}/api/v1/users/{userId}"   # required — throws at startup if unset
url: "${USER_SERVICE_URL:-http://localhost:8081}/api/v1/users/{userId}"  # with fallback
```

Substitution runs at load time. Missing variables with no default fail at startup.

### Top level

```yaml
input_types:           # optional — input object types for mutation args
  - name: MyInput
    fields:
      field1: String!
      field2: Int

endpoints:             # list of query or mutation definitions
  - ...
```

### Endpoint

```yaml
- name: myEndpoint          # → GraphQL query/mutation name
  type: query               # query | mutation
  description: "..."

  args:                     # GraphQL input arguments
    argName: { type: String!, default: "val" }

  output_type:              # GraphQL return type (auto-generated)
    name: MyType
    fields:
      field1: String!
      field2: "[Int]"

  backend_chain:            # ordered list of backend HTTP calls
    - ...

  output_mapping:           # map step results → output fields
    field1:
      source: step
      step_id: some_step
      jq: ".nested.value"

  # resolve a Clojure var at startup:
  transformer:
    ns: my.project.transformers
    fn: my-fn

  # or look up a pre-registered instance by key:
  transformer:
    key: my-transform
```

### Backend step

```yaml
- id: my_step               # unique identifier within the chain
  url: "https://svc/path/{argName}"   # {placeholders} from args or prior steps
  method: GET               # GET POST PUT PATCH DELETE
  deps: [other_step]        # explicit dependency declarations
  critical: true            # if true, failure aborts entire chain
  condition:                # optional — skip step if resolves to false
    source: args
    key: someBoolean

  # Query params (GET/DELETE)
  input_mapping:
    param_name:
      source: args | step | value | ctx
      key: argName           # for args/ctx sources
      step_id: step_name     # for step source
      jq: ".nested.field"    # jq path (step source only)
      value: "literal"       # for value source

  # Request body (POST/PUT/PATCH)
  body_mapping:
    body_field:
      source: step
      step_id: prev_step
      jq: ".id"
```

### Mapping sources

| source  | Description                                      |
|---------|--------------------------------------------------|
| `args`  | GraphQL input argument (`key: argName`)          |
| `step`  | Prior step's response data — use `jq` for paths  |
| `value` | Hardcoded literal (`value: "foo"`)               |
| `ctx`   | Request context — forwarded headers/auth claims  |

### jq expressions

All standard jq is supported via `jackson-jq`:

```yaml
jq: ".id"                                    # top-level field
jq: ".profile.display_name"                  # nested
jq: ".contact.emails[0].address"             # array index
jq: "[.items[].title]"                       # collect from array
jq: ".price * .quantity"                     # arithmetic
jq: "if .verified then \"✓\" else null end"  # conditional
jq: ".name // \"Anonymous\""                 # default value
jq: "[.items[] | select(.active)] | length"  # filter + count
```

---

## Execution model

Steps are grouped into **waves** by topological sort of their `deps`:

```
Wave 0: steps with no unmet deps   → run in parallel (missionary m/join)
Wave 1: steps whose deps are done  → run in parallel
...
```

Within a wave, all steps fire simultaneously via `(m/join ...)`.  
Waves execute sequentially — wave N+1 only starts after wave N completes.

### Critical vs non-critical steps

- `critical: true` — if the step fails, the entire chain aborts and the GraphQL
  response contains only errors (no partial data).
- `critical: false` (default) — failure is recorded, sibling/downstream steps
  still run (with `nil` data from this step), and errors appear in the GraphQL
  `errors` array alongside whatever data was successfully resolved.

---

## Error handling

### Partial failure response

When non-critical steps fail, the response looks like:

```json
{
  "data": {
    "createOrder": {
      "orderId": "ord_123",
      "status": "confirmed",
      "totalAmount": 49.99,
      "notificationSent": false,
      "inventoryReserved": false,
      "warnings": [
        "Order confirmed but notification could not be sent.",
        "Order confirmed but inventory reservation failed — ops team notified."
      ]
    }
  },
  "errors": [
    {
      "message": "Request to https://notification-service/... timed out",
      "extensions": { "code": "timeout", "step": "notify_user" }
    },
    {
      "message": "Backend returned 503",
      "extensions": { "code": "backend-error", "step": "update_inventory" }
    }
  ]
}
```

### Error codes

| Code                | Cause                                    |
|---------------------|------------------------------------------|
| `bad-request`       | Backend returned 400                     |
| `unauthorized`      | Backend returned 401                     |
| `forbidden`         | Backend returned 403                     |
| `not-found`         | Backend returned 404                     |
| `unprocessable`     | Backend returned 422                     |
| `backend-error`     | Backend returned 5xx                     |
| `timeout`           | Connection or read timeout               |
| `connection-refused`| Could not connect to backend             |
| `execution-error`   | Critical step failure or spec error      |
| `internal-error`    | Unexpected exception in resolver         |

---

## Transformer functions

Transformers run after jq output mappings are applied and return the final
output map. They receive the GraphQL args, the full step result map, and the
already-mapped output.

### Protocol

```clojure
(defprotocol BffTransformer
  (transform [this args chain-ctx mapped]))
```

Plain Clojure fns work without wrapping via `IFn` extension.

### Clojure — ns/fn

```clojure
(ns my.project.transformers.orders)

(defn attach-warnings
  [args        ; GraphQL input args map
   chain-ctx   ; {:step_id {:status :ok/:error :data {...}}}
   output]     ; already jq-mapped output fields
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

`bff.executor.BffTransformer` is generated as a Java interface by the protocol.
Register an instance before `create-handler` runs:

```java
import bff.executor.BffTransformer;
import clojure.java.api.Clojure;
import clojure.lang.IPersistentMap;

public class AttachWarningsTransformer implements BffTransformer {
    @Override
    public Object transform(Object args, Object chainCtx, Object mapped) {
        IPersistentMap output = (IPersistentMap) mapped;
        return output.assoc(clojure.lang.Keyword.intern("warnings"),
                            clojure.lang.PersistentVector.EMPTY);
    }
}

// Registration — call before bff.core/create-handler
clojure.lang.IFn register = Clojure.var("bff.executor", "register-transformer!");
register.invoke("attach-warnings", new AttachWarningsTransformer());
```

Then reference it in the spec with `key: attach-warnings`.

---

## Spring Boot 3 integration

Spring Boot 3 uses `jakarta.servlet.*`, which is incompatible with
`ring.util.servlet`. The bridge below maps between `HttpServletRequest`/
`HttpServletResponse` and Ring's request/response maps directly.

### Dependencies (`pom.xml`)

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

Spec file goes in `src/main/resources/`.

### Controller

```java
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IMapEntry;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Controller
public class BffController {

    private IFn handler;

    @PostConstruct
    public void init() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("bff.core"));
        handler = (IFn) Clojure.var("bff.core", "create-handler")
                                .invoke("bff-spec.yaml");
    }

    @RequestMapping({"/graphql", "/graphiql"})
    public void handle(HttpServletRequest req, HttpServletResponse res) throws IOException {
        IPersistentMap ringResp = (IPersistentMap) handler.invoke(toRingRequest(req));
        writeResponse(ringResp, res);
    }

    private static IPersistentMap toRingRequest(HttpServletRequest req) throws IOException {
        Map<Object, Object> headers = new HashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(), req.getHeader(name));
        }

        Map<Object, Object> m = new HashMap<>();
        m.put(Keyword.intern("server-port"),    req.getServerPort());
        m.put(Keyword.intern("server-name"),    req.getServerName());
        m.put(Keyword.intern("remote-addr"),    req.getRemoteAddr());
        m.put(Keyword.intern("uri"),            req.getRequestURI());
        m.put(Keyword.intern("query-string"),   req.getQueryString());
        m.put(Keyword.intern("scheme"),         Keyword.intern(req.getScheme()));
        m.put(Keyword.intern("request-method"), Keyword.intern(req.getMethod().toLowerCase()));
        m.put(Keyword.intern("protocol"),       req.getProtocol());
        m.put(Keyword.intern("headers"),        PersistentHashMap.create(headers));
        m.put(Keyword.intern("body"),           req.getInputStream());
        return PersistentHashMap.create(m);
    }

    private static void writeResponse(IPersistentMap resp, HttpServletResponse res) throws IOException {
        res.setStatus((Integer) resp.valAt(Keyword.intern("status")));

        IPersistentMap headers = (IPersistentMap) resp.valAt(Keyword.intern("headers"));
        if (headers != null) {
            for (Object entry : headers) {
                IMapEntry e = (IMapEntry) entry;
                res.setHeader((String) e.key(), (String) e.val());
            }
        }

        Object body = resp.valAt(Keyword.intern("body"));
        if (body instanceof String s) {
            res.getWriter().write(s);
        } else if (body instanceof InputStream is) {
            is.transferTo(res.getOutputStream());
        }
    }
}
```

### Transformer registration

Use a separate `@Component` so transformers are registered before the
controller initialises. Add `@DependsOn` if you need to enforce ordering:

```java
import bff.executor.BffTransformer;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class BffTransformers {

    @PostConstruct
    public void register() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("bff.executor"));

        Clojure.var("bff.executor", "register-transformer!")
               .invoke("my-transform", new MyTransformer());
    }

    static class MyTransformer implements BffTransformer {
        @Override
        public Object transform(Object args, Object chainCtx, Object mapped) {
            return mapped;
        }
    }
}
```

---
