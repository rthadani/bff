(ns bff.core
  (:require [bff.spec-loader :as loader]
            [bff.schema-builder :as schema-builder]
            [com.walmartlabs.lacinia :as lacinia]
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
  <script>
    const fetcher = async (params) =>
      fetch('/graphql', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params),
      }).then(r => r.json());
    ReactDOM.createRoot(document.getElementById('graphiql')).render(
      React.createElement(GraphiQL, { fetcher })
    );
  </script>
</body>
</html>")

(defn- graphql-handler [compiled-schema forward-headers]
  (fn [request]
    (let [method (:request-method request)
          uri    (:uri request)]
      (cond
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

(defn- build-handler [compiled-schema forward-headers]
  (-> (graphql-handler compiled-schema forward-headers)
      (wrap-json-body {:keywords? true})
      wrap-json-response
      wrap-params))

(defn create-handler
  "Load spec-path and return a Ring handler ready to mount in any server.
   Use this when embedding BFF as a library in your own application.

   Example:
     (require '[bff.core :as bff]
              '[bff.executor :as executor])

     (executor/register-transformer! \"my-transform\" my-transform-fn)

     (def handler (bff/create-handler \"my-spec.yaml\"))

     ;; plug handler into your existing Ring/Jetty/http-kit server"
  [spec-path]
  (log/infof "Loading spec: %s" spec-path)
  (let [spec         (loader/load-and-compile spec-path)
        schema       (schema-builder/build-schema spec)
        fwd-headers  (let [h (:forward_headers spec)]
                       (if (seq h) h default-forward-headers))]
    (log/infof "Schema loaded: %d endpoints, forwarding headers: %s"
               (count (:endpoints spec)) fwd-headers)
    (build-handler schema fwd-headers)))

