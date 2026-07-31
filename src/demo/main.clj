(ns demo.main
  (:require [bff.core :as bff]
            [bff.cache :as cache]
            [bff.examples.validators.orders :as order-validator]
            [demo.mock-services :as mock]
            [ring.adapter.jetty :refer [run-jetty]]
            [taoensso.timbre :as log])
  (:gen-class))

(defrecord MemoryCache [store]
  cache/CacheStore
  (cache-get        [_ k]       (log/infof "Cache get: %s" k) (get @store k))
  (cache-put        [_ k v _]   (log/infof "Cache put: %s = %s" k v) (swap! store assoc k v))
  (cache-invalidate [_ k]       (swap! store dissoc k)))

(defn -main [& _]
  (log/info "Starting mock upstream services on port 3001")
  (run-jetty mock/handler {:port 3001 :join? false})

  (log/info "Starting BFF on port 8080")
  (let [config  {:cache      (->MemoryCache (atom {}))
                 :validators {"order-validator" order-validator/check-order}}
        handler (bff/create-handler "bff-spec.yaml" config)]
    (run-jetty handler {:port 8080 :join? true})))
