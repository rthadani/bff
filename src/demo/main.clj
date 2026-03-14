(ns demo.main
  (:require [bff.core :as bff]
            [bff.cache :as cache]
            [demo.mock-services :as mock]
            [ring.adapter.jetty :refer [run-jetty]]
            [taoensso.timbre :as log])
  (:gen-class))

(defn -main [& _]
  (log/info "Starting mock upstream services on port 3001")
  (run-jetty mock/handler {:port 3001 :join? false})

  (log/info "Starting BFF on port 8080")
  (defrecord MemoryCache [store]
    cache/CacheStore
    (cache-get       [_ k]       (log/info "Fetching " k " from cache") (get @store k))
    (cache-put       [_ k v ttl] (log/info "Adding " k " to cache with value " v) (swap! store assoc k v))
    (cache-invalidate [_ k]      (swap! store dissoc k)))     
  (cache/register-cache! (->MemoryCache (atom {})))
  (run-jetty (bff/create-handler "bff-spec.yaml") {:port 8080 :join? true}))
