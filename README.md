# BFF Engine

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
          └── transformer  optional Clojure fn for business logic
```

---

## Spec reference

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

  transformer:              # optional post-processing fn
    ns: my.project.transformers
    fn: my-fn
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

Transformers receive three arguments and return the final output map:

```clojure
(ns bff.examples.transformers.orders
  (:require [my.bff.error :as error]))

(defn attach-warnings
  [args        ; GraphQL input args map
   chain-ctx   ; {:step_id {:status :ok/:error :data {...}}}
   output]     ; already jq-mapped output fields
  ;; Inspect step results, add derived fields, reshape as needed
  (assoc output :warnings
         (when (error/error? (get chain-ctx :notify_user))
           ["Notification could not be sent"])))
```

---
