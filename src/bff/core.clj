(ns bff.core
  (:require [bff.spec-loader :as loader]
            [bff.schema-builder :as schema-builder]
            [bff.sdl :as sdl]
            [bff.http-client :as http]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as lacinia-schema]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as resp]
            [taoensso.timbre :as log]))

(def ^:private default-forward-headers
  ["authorization" "x-request-id" "x-correlation-id"])

(defn- extract-request-ctx [request forward-headers]
  (-> (->> forward-headers
           (map (fn [h] [(keyword h) (get-in request [:headers h])]))
           (into {}))
      (assoc :remote-addr (:remote-addr request))))

(def ^:private graphiql-html
  "<!DOCTYPE html>
<html>
<head>
  <title>GraphiQL</title>
  <style>body { height: 100%; margin: 0; overflow: hidden; } #graphiql { height: 100vh; }</style>
  <script crossorigin src=\"https://unpkg.com/react@18.2.0/umd/react.production.min.js\"></script>
  <script crossorigin src=\"https://unpkg.com/react-dom@18.2.0/umd/react-dom.production.min.js\"></script>
  <link rel=\"stylesheet\" href=\"https://unpkg.com/graphiql@2.4.7/graphiql.min.css\" />
</head>
<body>
  <div id=\"graphiql\">Loading...</div>
  <script src=\"https://unpkg.com/graphiql@2.4.7/graphiql.min.js\"></script>
  <script type=\"module\">
    const { buildSchema, introspectionFromSchema } =
      await import('https://esm.sh/graphql@16.9.0');
    const fetcher = async (params, opts) => {
      const extra = (opts && opts.headers) || {};
      return fetch('/graphql', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, extra),
        body: JSON.stringify(params),
      }).then(r => r.json());
    };
    // GraphiQL bundles its own graphql-js so instanceof checks fail across
    // module copies. Feed it an introspection JSON instead — GraphiQL
    // accepts that shape whichever graphql built the schema.
    let schema = null;
    try {
      const r = await fetch('/schema.graphqls');
      if (r.ok) schema = introspectionFromSchema(buildSchema(await r.text()));
    } catch (e) { /* fall back to introspection */ }
    const props = schema ? { fetcher, schema } : { fetcher };
    ReactDOM.createRoot(document.getElementById('graphiql')).render(
      React.createElement(GraphiQL, props)
    );
  </script>
</body>
</html>")

(defn- graphql-handler [compiled-schema forward-headers sdl-text expose-schema?]
  (fn [request]
    (let [method (:request-method request)
          uri    (:uri request)]
      (cond
        (and (= :get method) (= uri "/schema.graphqls"))
        (if expose-schema?
          (-> (resp/response sdl-text)
              (resp/content-type "application/graphql"))
          (-> (resp/response "Not Found") (resp/status 404)))

        (and (= :get method) (= uri "/graphiql"))
        (-> (resp/response graphiql-html)
            (resp/content-type "text/html"))

        (= :options method)
        (-> (resp/response nil)
            (resp/status 204)
            (resp/header "Access-Control-Allow-Origin" "*")
            (resp/header "Access-Control-Allow-Methods" "GET, POST, OPTIONS")
            (resp/header "Access-Control-Allow-Headers" "Content-Type, Authorization"))

        :else
        (let [body    (:body request)
              query   (or (:query body) (get body "query"))
              vars    (or (:variables body) (get body "variables") {})
              op-name (or (:operationName body) (get body "operationName"))
              ctx     {:request (extract-request-ctx request forward-headers)}]

          (when-not query
            (throw (ex-info "Missing 'query' in request body" {})))

          (log/debugf "GraphQL request: op=%s" (or op-name "<anonymous>"))

          (let [result (lacinia/execute compiled-schema query vars ctx
                                        {:operation-name op-name})]
            (-> (resp/response result)
                (resp/status 200)
                (resp/header "Content-Type" "application/json")
                (resp/header "Access-Control-Allow-Origin" "*"))))))))

(defn- build-handler [compiled-schema forward-headers sdl-text expose-schema?]
  (-> (graphql-handler compiled-schema forward-headers sdl-text expose-schema?)
      (wrap-json-body {:keywords? true})
      wrap-json-response
      wrap-params))

(defn create-handler
  "Load spec-path and return a Ring handler ready to mount in any server.
   Use this when embedding BFF as a library in your own application.

   `extensions` is the immutable extension config for this handler. Keys
   (all optional):

     :enrichers    — ordered seq of context enrichers
     :validators   — {\"key\" impl} looked up by :validator {:key ...} refs
     :transformers — {\"key\" impl}
     :resolvers    — {\"key\" impl}
     :retry-hooks  — {\"key\" impl}
     :cache        — a bff.cache/CacheStore, or nil

   Example:
     (require '[bff.core :as bff])

     (def handler
       (bff/create-handler
         \"my-spec.yaml\"
         {:validators {\"check-order\" my-validator}
          :cache      my-cache-store}))

     ;; plug handler into your existing Ring/Jetty/http-kit server"
  ([spec-path] (create-handler spec-path {}))
  ([spec-path extensions]
   (log/infof "Loading spec: %s" spec-path)
   (let [extensions      (update extensions :http-client
                                 (fn [c] (or c (http/default-client (:hato-options extensions)))))
         spec            (loader/load-and-compile spec-path)
         schema-map      (schema-builder/build-schema-map spec extensions)
         schema          (lacinia-schema/compile schema-map)
         sdl-text        (sdl/emit-sdl schema-map)
         expose-schema?  (get spec :expose_schema true)
         fwd-headers     (let [h (:forward_headers spec)]
                           (if (seq h) h default-forward-headers))]
     (log/infof "Schema loaded: %d endpoints, forwarding headers: %s, /schema.graphqls %s"
                (count (:endpoints spec)) fwd-headers
                (if expose-schema? "exposed" "disabled"))
     (build-handler schema fwd-headers sdl-text expose-schema?))))

