# Clojure integration

BFF exposes `bff.core/create-handler`, which returns a standard Ring handler you can mount in any Clojure HTTP server (Jetty, http-kit, Aleph, etc.).

## Dependencies (`deps.edn`)

```clojure
io.github.rthadani/bff {:mvn/version "0.2.0"}
```

Place your spec file anywhere on the classpath (e.g. `resources/bff-spec.yaml`).

## Mounting the handler

```clojure
(require '[bff.core :as bff]
         '[ring.adapter.jetty :refer [run-jetty]])

(def handler
  (bff/create-handler
    "bff-spec.yaml"
    {:validators   {"check-order"      my-validator-fn}
     :transformers {"attach-warnings"  my-transformer-fn}
     :resolvers    {"user-profile"     my-resolver-fn}
     :enrichers    [my-enricher-fn]
     :cache        my-cache-store}))

(run-jetty handler {:port 8080 :join? false})
```

`create-handler` accepts a second map of extensions — all keys are optional. See [extensions.md](extensions.md) for the full API.

## Reitit integration

If you're using [Reitit](https://github.com/metosin/reitit) for routing, mount the BFF handler as a catch-all route under `/graphql`:

```clojure
;; deps.edn
metosin/reitit {:mvn/version "0.7.2"}
```

```clojure
(require '[bff.core :as bff]
         '[reitit.ring :as ring]
         '[ring.adapter.jetty :refer [run-jetty]])

(def bff-handler (bff/create-handler "bff-spec.yaml" {...}))

(def app
  (ring/ring-handler
    (ring/router
      [["/graphql"  {:handler bff-handler}]
       ["/graphiql" {:handler bff-handler}]
       ;; your other routes
       ["/api/health" {:get (fn [_] {:status 200 :body "ok"})}]])
    (ring/create-default-handler)))

(run-jetty app {:port 8080 :join? false})
```

Reitit's middleware chain sits outside the BFF handler, so you can apply Buddy auth (or any other middleware) at the router level using `:middleware`:

```clojure
(require '[buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
         '[buddy.auth.backends.token :refer [jws-backend]])

(def backend (jws-backend {:secret (System/getenv "JWT_SECRET") :options {:alg :hs256}}))

(def app
  (ring/ring-handler
    (ring/router
      [["/graphiql" {:handler bff-handler}]   ; no auth middleware here
       ["/graphql"  {:handler    bff-handler
                     :middleware [[wrap-authentication backend]
                                  [wrap-authorization  backend]]}]])))
```

## Security

BFF has no built-in auth. Use [Buddy](https://github.com/funcool/buddy-auth) middleware in front of the handler:

```clojure
;; deps.edn
buddy/buddy-auth {:mvn/version "3.0.323"}
buddy/buddy-sign {:mvn/version "3.4.333"}
```

```clojure
(require '[buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
         '[buddy.auth.backends.token :refer [jws-backend]]
         '[buddy.auth :refer [authenticated?]]
         '[ring.util.response :as resp])

(def backend
  (jws-backend {:secret  (System/getenv "JWT_SECRET")
                :options {:alg :hs256}}))

(defn- require-auth [handler]
  (fn [request]
    (if (authenticated? request)
      (handler request)
      (-> (resp/response {:errors [{:message "Unauthorized"}]})
          (resp/status 401)
          (resp/content-type "application/json")))))

(def app
  (-> handler
      require-auth
      (wrap-authentication backend)
      (wrap-authorization  backend)))
```

`/graphiql` is typically excluded from auth — split the routes before wrapping if you want to keep it open:

```clojure
(defn- route [handler]
  (fn [request]
    (if (= "/graphiql" (:uri request))
      (handler request)           ; pass through unauthenticated
      ((require-auth handler) request))))

(def app
  (-> handler
      route
      (wrap-authentication backend)
      (wrap-authorization  backend)))
```

The `authorization` header (and `x-request-id`, `x-correlation-id`) are forwarded to every downstream HTTP step automatically. See `forward_headers` in the [spec reference](spec.md) if you need to widen or narrow that list.
