# Spec reference

The BFF spec is a single YAML file (or [multiple merged files](#multiple-specs)) that
describes every GraphQL endpoint the engine will serve.

## Environment variables

Any string value can reference an environment variable:

```yaml
url: "${USER_SERVICE_URL}/api/v1/users/{userId}"            # required — throws at startup if unset
url: "${USER_SERVICE_URL:-http://localhost:8081}/api/v1/users/{userId}"  # with fallback
```

Substitution runs at load time. Missing variables with no default fail at startup.

## Top level

```yaml
forward_headers:       # optional — headers forwarded to every backend step
  - authorization      # defaults to [authorization, x-request-id, x-correlation-id]
  - x-request-id

scalars:               # optional — custom GraphQL scalar types
  - name: DateTime     # implementation supplied in the handler's :scalars config
    description: "ISO-8601 timestamp"

input_types:           # optional — shared input object types for mutation args
  - name: MyInput
    fields:
      field1: String!
      field2: Int

endpoints:             # list of query and mutation definitions
  - ...
```

Every name in the `scalars:` block must have a matching implementation
supplied to `bff.core/create-handler` under the `:scalars` config key (or via
`BffConfig.Builder#scalar` in Java). See
[extensions.md — Custom scalar](extensions.md#custom-scalar) for the interface.

## Endpoint

```yaml
- name: myEndpoint          # GraphQL query/mutation name
  type: query               # query | mutation
  description: "..."

  args:
    simpleArg: { type: String!, default: "val" }

    # args can include per-field validation rules:
    userId:
      type: String!
      validate:
        pattern: "^[a-f0-9-]{36}$"   # regex — strings only
        message: "userId must be a UUID"
    amount:
      type: Float!
      validate:
        min: 0                        # inclusive lower bound — numbers only
        max: 10000                    # inclusive upper bound — numbers only
        message: "amount must be between 0 and 10000"

  output_type:
    name: MyType
    fields:
      field1: String!
      field2: "[Int]"
      nested:
        name: NestedType
        fields:
          value: String

  backend_chain:
    - ...

  output_mapping:
    field1:
      source: step
      step_id: some_step
      jq: ".nested.value"

  # optional — custom validator (runs before backend_chain; short-circuits on failure)
  validator:
    ns: my.project.validators     # resolve a Clojure var
    fn: my-validator-fn
  # or:
  validator:
    key: my-validator             # look up in the handler's :validators config

  # optional — transformer (runs after output_mapping is applied)
  transformer:
    ns: my.project.transformers
    fn: my-fn
  # or:
  transformer:
    key: my-transform

  # optional — custom resolver (replaces backend_chain entirely)
  resolver:
    key: my-resolver
```

## Backend step

```yaml
- id: my_step
  url: "https://svc/path/{argName}"   # {placeholders} filled from args or prior step data
  method: GET                          # GET POST PUT PATCH DELETE
  deps: [other_step]                   # explicit dependency — determines execution wave
  critical: true                       # if true, failure aborts the entire chain
  condition:                           # skip this step if the value resolves to false
    source: args
    key: someBoolean
  extra_headers:                       # step-specific headers merged with forwarded headers
    x-service-key: "secret"

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

  # Response caching
  cache:
    key: "prefix:{argName}"  # cache key template — same placeholder syntax as url
    ttl: 60000               # TTL in milliseconds (default 60000)

  # Retry on selected failure codes
  retry:
    max: 2                             # max additional attempts (3 calls total)
    on_code: [unauthorized, timeout]   # semantic codes from http_client — see below
    before_retry:                      # optional — run a hook between attempts
      key: auth-token-refresh          # or {ns: my.ns, fn: my-hook}

  # Remap step failures to domain-specific error codes
  errors:
    400: DUPLICATE_CUSTOMER            # keyed by upstream HTTP status
    unauthorized: TOKEN_EXPIRED        # or by semantic code from http_client

  # Compensating action for cascading rollback
  compensation:
    url:    "{service_base}/portal/customers/{customer_id}"
    method: DELETE
    input_mapping:
      customer_id: {source: step, step_id: create_customer, jq: ".data.id"}
```

### Error code mapping

Each step failure carries both a raw HTTP status (when the failure came from an
HTTP response) and a semantic code (`:unauthorized`, `:timeout`, …). By default
the semantic code appears under `extensions.code` on the surfaced GraphQL
error. Declaring an `errors:` map on the step remaps that to a domain-specific
string of your choice:

```yaml
- id: create_customer
  url: "{service_base}/portal/customers"
  method: POST
  errors:
    400: DUPLICATE_CUSTOMER
    422: VALIDATION_FAILED
    unauthorized: TOKEN_EXPIRED
```

Lookup order: HTTP status → semantic code (keyword) → semantic code (string).
The mapping runs *after* the retry loop, so `retry.on_code` still matches
against the raw semantic codes.

### Compensation

Steps that mutate remote state can declare a `compensation:` block — a
mini-step (URL + method + input/body mappings) that runs iff the step
succeeded and a later critical step failed. Compensations run in reverse
completion order and are best-effort: their own failures are logged but
never mask the original chain failure.

```yaml
backend_chain:
  - id: create_customer
    url: "{service_base}/portal/customers"
    method: POST
    critical: true
    body_mapping:
      email: {source: args, key: email}
    compensation:
      url:    "{service_base}/portal/customers/{customer_id}"
      method: DELETE
      input_mapping:
        customer_id: {source: step, step_id: create_customer, jq: ".data.id"}

  - id: create_equipment
    url: "{service_base}/portal/equipment"
    method: POST
    critical: true
    deps: [create_customer]
    body_mapping:
      customerId: {source: step, step_id: create_customer, jq: ".data.id"}
```

If `create_equipment` fails, `create_customer`'s compensation fires — the
customer created moments earlier gets deleted before the failure surfaces
to the client. Compensations never run when the whole chain succeeds.

Failed steps have nothing to compensate — only successful steps' rollbacks
are recorded and run.

### Retryable error codes

The semantic codes come from `bff.http-client` and match one-to-one with the
kind of failure that occurred. Use these in `retry.on_code`:

| Code                | Origin                                    |
|---------------------|-------------------------------------------|
| `no-response`       | Underlying HTTP call returned no status   |
| `bad-request`       | Upstream returned 400                     |
| `unauthorized`      | Upstream returned 401                     |
| `forbidden`         | Upstream returned 403                     |
| `not-found`         | Upstream returned 404                     |
| `unprocessable`     | Upstream returned 422                     |
| `backend-error`     | Upstream returned 5xx                     |
| `unexpected-status` | Any other non-2xx status                  |
| `connection-refused`| TCP/connect failure                       |
| `timeout`           | Connect or socket read timeout            |
| `unexpected`        | Any other client-side exception           |

The `before_retry` hook — see [extensions.md](extensions.md#retry-hook) — is
called between attempts and can return a rewritten request-ctx (typically
with a refreshed `Authorization` header).

## Mapping sources

| source  | Description                                      |
|---------|--------------------------------------------------|
| `args`  | GraphQL input argument (`key: argName`)          |
| `step`  | Prior step's response data — use `jq` for paths  |
| `value` | Hardcoded literal (`value: "foo"`)               |
| `ctx`   | Request context — forwarded headers and remote-addr |

## jq expressions

All standard jq is supported via `jackson-jq`. Expressions are compiled at startup.

```yaml
jq: ".id"                                      # top-level field
jq: ".profile.display_name"                    # nested
jq: ".contact.emails[0].address"               # array index
jq: "[.items[].title]"                         # collect from array
jq: ".price * .quantity"                       # arithmetic
jq: "if .verified then \"Verified\" else null end"  # conditional
jq: ".name // \"Anonymous\""                   # default value
jq: "[.items[] | select(.active)] | length"    # filter + count
```

## Multiple specs

Multiple YAML files are merged into a single spec at startup. Endpoints from all
files are combined; `forward_headers` and `input_types` are deduplicated.

```clojure
(bff/create-handler ["base.yaml" "orders.yaml" "users.yaml"])
```
