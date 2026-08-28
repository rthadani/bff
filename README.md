# BFF Engine

[![Clojars Project](https://img.shields.io/clojars/v/io.github.rthadani/bff.svg)](https://clojars.org/io.github.rthadani/bff)

A spec-driven GraphQL Backend-for-Frontend engine in Clojure.
Write a YAML spec and get a fully functional GraphQL API with no boilerplate per endpoint.

## How it works

Define your endpoints in a YAML spec. The engine generates a Lacinia GraphQL
schema, fans out HTTP calls to your backend services in parallel, maps the
responses to your output types with jq, and returns a well-formed GraphQL
response including partial data and structured errors when things go wrong.

```
bff-spec.yaml
     │
     ├── spec_loader      load YAML, resolve env vars, pre-compile jq
     │
     ├── schema_builder   generate Lacinia schema (objects, queries, mutations)
     │
     └── executor
          ├── validator    optional — short-circuit before any backend call
          ├── graph        dep graph → execution waves (topological sort)
          ├── [Wave 0]     missionary m/join → parallel HTTP calls
          ├── [Wave 1]     missionary m/join → parallel HTTP calls
          ├── jq_engine    apply compiled jq to map step results → output fields
          ├── transformer  optional — post-process mapped output
          └── resolver     optional — replace backend chain entirely
```

## Quick start

Add to `deps.edn`:

```clojure
io.github.rthadani/bff {:mvn/version "0.2.0"}
```

Create a spec file and start the handler:

```clojure
(require '[bff.core :as bff])

;; Simplest — no extensions
(def handler (bff/create-handler "bff-spec.yaml"))

;; With extensions
(def handler
  (bff/create-handler
    "bff-spec.yaml"
    {:validators   {"check-order" my-validator-fn}
     :transformers {"attach-warnings" my-transformer-fn}
     :resolvers    {"user-profile" my-resolver-fn}
     :cache        my-cache-store}))
;; handler is a standard Ring handler
```

Or run it standalone:

```bash
clj -M -m bff.main bff-spec.yaml
# GraphQL server on http://localhost:8080 (set PORT to change)
```

Send a query:

```bash
curl -X POST http://localhost:8080 \
  -H "Content-Type: application/json" \
  -d '{"query": "{ userDashboard(userId: \"u123\") { fullName email } }"}'
```

## Documentation

- [Spec reference](docs/spec.md) — YAML format, all fields, env var substitution, jq, caching
- [Execution model](docs/execution.md) — waves, critical steps, partial failure, error codes
- [Extensions](docs/extensions.md) — validators, transformers, resolvers, retry hooks, cache, HTTP client, in Clojure and Java
- [Spring Boot 3](docs/spring-boot.md) — Jakarta servlet bridge and extension registration
- [Clojure](docs/clojure.md) — Ring handler mounting and Buddy auth integration
- [Internals](docs/INTERNALS.md) — full request lifecycle, component interactions, extension points

## Alternatives

Several tools solve a similar problem:

| Tool | Runtime | Config |
|------|---------|--------|
| [GraphQL Mesh](https://the-guild.dev/graphql/mesh) | Node.js | YAML |
| [Tailcall](https://tailcall.run) | Rust | SDL |
| [Apollo Connectors](https://www.apollographql.com/docs/graphos/connectors) | Rust (Router) | SDL directives |
| [StepZen](https://stepzen.com) (IBM) | Cloud SaaS | YAML + SDL |
| [Hasura](https://hasura.io) | Go | Dashboard / metadata |

GraphQL Mesh is the closest in spirit. Key differences:

- Embeds as a Ring handler — no separate runtime or sidecar
- jq for all mappings: input, body, and output
- `critical` steps with `compensation` blocks for rollback
- REST only

## Publishing

Tag a release to deploy to Clojars:

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The publish workflow runs tests then deploys using `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` secrets.
